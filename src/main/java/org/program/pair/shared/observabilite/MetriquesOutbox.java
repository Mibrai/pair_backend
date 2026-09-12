package org.program.pair.shared.observabilite;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Les mesures de l'outbox, et le seul endroit du code qui les nomme.
 *
 * <p><b>Pourquoi une classe plutôt que des appels dispersés.</b> Un nom de
 * métrique est un contrat : une règle d'alerte, un tableau de bord et un relevé
 * après livraison s'y accrochent. Écrits à la main dans le module d'outbox, ces
 * noms s'y seraient retrouvés deux fois, un jour avec un point de moins, et
 * l'alerte aurait cessé de voir quoi que ce soit sans que rien ne casse. Les
 * noms vivent donc ici ; le module d'outbox appelle des méthodes.
 *
 * <p><b>Quatre compteurs, parce que trois ne suffisaient pas.</b> La fiche
 * P-BA-21 n'en nommait que deux, {@code outbox.sent} et {@code outbox.failed}.
 * Le module d'outbox en a demandé deux de plus, et ils règlent un vrai angle
 * mort :
 * <ul>
 *   <li>{@code outbox.failed} ne compte que l'abandon définitif — dix essais
 *       épuisés, soit près de deux heures après le premier refus. Une panne de
 *       fournisseur <b>en cours</b> ne le fait pas bouger.</li>
 *   <li>{@code outbox.refused} compte chaque refus qui laisse le message en
 *       {@code PENDING}, donc à réessayer. C'est lui qui monte tout de suite, et
 *       c'est lui qu'on regarde pendant l'incident.</li>
 *   <li>{@code outbox.claimed} compte ce que la réclamation a retenu : son
 *       immobilité dit « plus aucun balayage ne tourne », ce qu'aucun compteur
 *       d'échec ne dirait.</li>
 * </ul>
 *
 * <p><b>Trois jauges, une seule requête.</b> Une jauge est évaluée par le
 * registre au moment de la collecte, hors requête HTTP et hors transaction ; et
 * le registre composite l'évalue une fois par registre enfant. Trois jauges
 * naïves feraient donc six requêtes par collecte. Les trois valeurs sortent
 * d'un seul agrégat, mis en cache le temps d'une collecte.
 *
 * <p>{@code outbox.sending.stale} est celle qu'il faut connaître : elle compte
 * les lignes {@code SENDING} dont le verrou {@code locked_until} est dépassé
 * depuis plus de cinq minutes. {@code SENDING} n'est pas un état où l'on
 * s'installe — il vaut « quelqu'un a la main dessus, pour deux minutes au
 * plus ». Une ligne qui y reste au-delà n'existe que si plus aucun balayage ne
 * tourne. Elle doit valoir zéro, et c'est exactement la requête de vérification
 * après livraison que la fiche P-BA-03 prescrit.
 *
 * <p><b>Lecture en SQL, et non par le dépôt JPA.</b> Deux raisons, et la
 * seconde est la vraie : une jauge évaluée hors transaction ne peut pas
 * s'appuyer sur une session que rien n'ouvre ; et {@code OutboxMessageRepository}
 * appartient à un autre chantier — y ajouter une requête pour une métrique
 * aurait été un conflit de fichier pour rien.
 *
 * <p><b>Aucun type du domaine n'est importé ici, délibérément.</b> Le canal et
 * l'objet arrivent en {@code String} ({@code OutboxChannel.name()},
 * {@code OutboxPurpose.name()}) : {@code shared} n'a pas à dépendre de
 * {@code domain}, et une étiquette de métrique est de toute façon une chaîne.
 *
 * <p><b>Aucune donnée personnelle</b> : ni destinataire, ni sujet, ni corps, et
 * aucune étiquette dérivée du contenu d'un message (P-BS-19). Le canal et
 * l'objet sont des ensembles fermés de quelques valeurs, donc sans risque de
 * cardinalité non bornée.
 */
@Component
public class MetriquesOutbox {

    private static final Logger log = LoggerFactory.getLogger(MetriquesOutbox.class);

    /** Messages remis au fournisseur. */
    public static final String COMPTEUR_PARTIS = "outbox.sent";

    /** Messages abandonnés : tentatives épuisées, état terminal. */
    public static final String COMPTEUR_ECHECS = "outbox.failed";

    /** Refus d'un essai, message laissé à réessayer : le signal d'une panne en cours. */
    public static final String COMPTEUR_REFUS = "outbox.refused";

    /** Messages retenus par une réclamation. Immobile = plus aucun balayage. */
    public static final String COMPTEUR_RECLAMES = "outbox.claimed";

    /** Âge, en secondes, du plus vieux message qui attend encore son tour. */
    public static final String JAUGE_ARRIERE = "outbox.pending.oldest.age.seconds";

    /** Lignes {@code SENDING} dont le verrou est dépassé depuis plus de 5 min. */
    public static final String JAUGE_BLOQUES = "outbox.sending.stale";

    public static final String ETIQUETTE_CANAL = "channel";
    public static final String ETIQUETTE_OBJET = "purpose";
    public static final String ETIQUETTE_PRIORITE = "priority";

    /**
     * Durée de validité de l'agrégat. Plus courte que l'intervalle de collecte
     * usuel — la valeur ne périme donc jamais entre deux collectes — mais assez
     * longue pour que les évaluations successives d'une même collecte, une par
     * registre enfant du composite, n'émettent qu'une requête.
     */
    private static final Duration FRAICHEUR = Duration.ofSeconds(5);

    /**
     * Les trois valeurs d'un coup.
     *
     * <p>{@code COALESCE(next_attempt_at, created_at)} et non {@code created_at}
     * seul : un message qui vient d'être refusé est reprogrammé plus tard, et son
     * âge de création ne dit plus rien de son retard. C'est la date à laquelle il
     * <i>devait</i> partir qui mesure l'arriéré.
     *
     * <p>{@code MIN(…) FILTER (WHERE priority = 0)} donne la même mesure pour les
     * seuls messages urgents — les alertes de veille. Cinq minutes de retard n'y
     * ont pas le même sens que sur un récapitulatif hebdomadaire, et une seule
     * série mélangée aurait noyé les premiers dans les seconds.
     *
     * <p>Le {@code WHERE} borne le balayage aux deux états qui attendent : les
     * messages partis restent en table jusqu'à leur purge, sept jours plus tard,
     * et n'ont rien à faire dans ce calcul.
     */
    private static final String SQL_ETAT_DE_LA_FILE = """
        SELECT COALESCE(EXTRACT(EPOCH FROM
                   (now() - MIN(COALESCE(next_attempt_at, created_at)))), 0) AS age_attente,
               COALESCE(EXTRACT(EPOCH FROM
                   (now() - MIN(COALESCE(next_attempt_at, created_at))
                            FILTER (WHERE priority = 0))), 0) AS age_attente_urgent,
               COUNT(*) FILTER (WHERE status = 'SENDING'
                                  AND locked_until < now() - interval '5 minutes') AS bloques
          FROM outbox_messages
         WHERE status IN ('PENDING', 'SENDING')
        """;

    private final MeterRegistry registre;
    private final JdbcTemplate jdbc;
    private final Counter reclames;

    private final AtomicLong derniereLecture = new AtomicLong(Long.MIN_VALUE);
    private volatile Map<String, Object> etatEnCache = Map.of();

    public MetriquesOutbox(MeterRegistry registre, JdbcTemplate jdbc) {
        this.registre = registre;
        this.jdbc = jdbc;

        // Sans étiquette, donc enregistrable tout de suite — et il doit l'être :
        // c'est son immobilité qu'on surveille, et une série absente ne se
        // distingue pas d'une série à zéro.
        this.reclames = Counter.builder(COMPTEUR_RECLAMES)
            .description("Messages d'outbox retenus par une réclamation")
            .register(registre);

        jauge(JAUGE_ARRIERE, "age_attente", "all",
            "Âge du plus vieux message d'outbox qui attend encore son tour");
        jauge(JAUGE_ARRIERE, "age_attente_urgent", "0",
            "Âge du plus vieux message d'outbox urgent qui attend encore son tour");

        Gauge.builder(JAUGE_BLOQUES, this, m -> m.valeur("bloques"))
            .description("Lignes SENDING dont le verrou est dépassé depuis plus de 5 min "
                + "— doit valoir 0, sinon plus aucun balayage ne tourne")
            .strongReference(true)
            .register(registre);
    }

    /** Le fournisseur a pris le message. */
    public void messageParti(String canal, String objet) {
        registre.counter(COMPTEUR_PARTIS, etiquettes(canal, objet)).increment();
    }

    /** Tentatives épuisées : le message ne repartira plus seul. */
    public void messageEnEchec(String canal, String objet) {
        registre.counter(COMPTEUR_ECHECS, etiquettes(canal, objet)).increment();
    }

    /** Un essai refusé, le message reste à réessayer. */
    public void messageRefuse(String canal, String objet) {
        registre.counter(COMPTEUR_REFUS, etiquettes(canal, objet)).increment();
    }

    /**
     * Ce qu'une réclamation a retenu. Peut être appelée avec {@code 0} — un
     * balayage qui trouve la file vide — sans conséquence : le compteur est
     * enregistré dès le démarrage, sa série existe donc avant le premier
     * message, et c'est ce qui permet de lire « le balayage tourne et la file
     * est vide » là où une série absente ne dirait rien du tout.
     */
    public void messagesReclames(int nombre) {
        if (nombre > 0) {
            reclames.increment(nombre);
        }
    }

    // ------------------------------------------------------------------ interne

    private void jauge(String nom, String colonne, String priorite, String description) {
        Gauge.builder(nom, this, m -> m.valeur(colonne))
            .tag(ETIQUETTE_PRIORITE, priorite)
            .description(description)
            .baseUnit("seconds")
            .strongReference(true)
            .register(registre);
    }

    private static String[] etiquettes(String canal, String objet) {
        return new String[] {
            ETIQUETTE_CANAL, canal == null ? "inconnu" : canal,
            ETIQUETTE_OBJET, objet == null ? "inconnu" : objet
        };
    }

    /**
     * Une colonne de l'agrégat, relue au plus une fois par {@link #FRAICHEUR}.
     *
     * <p>Rend {@code NaN} — et non zéro — si la lecture échoue : zéro
     * signifierait « aucun arriéré, aucun blocage », soit exactement le
     * contraire de ce qu'on sait. Une valeur absente se voit dans un graphique,
     * une valeur fausse ne se voit pas.
     */
    double valeur(String colonne) {
        Object brute = etatDeLaFile().get(colonne);
        return brute instanceof Number nombre ? nombre.doubleValue() : Double.NaN;
    }

    private Map<String, Object> etatDeLaFile() {
        long maintenant = System.nanoTime();
        long precedente = derniereLecture.get();
        if (precedente != Long.MIN_VALUE && maintenant - precedente < FRAICHEUR.toNanos()) {
            return etatEnCache;
        }

        try {
            etatEnCache = jdbc.queryForMap(SQL_ETAT_DE_LA_FILE);
        } catch (RuntimeException echec) {
            // Une collecte ne doit pas faire de bruit dans les journaux à chaque
            // passage : debug, pas warn. L'absence des séries suffit à le
            // signaler là où ça se regarde.
            log.debug("Lecture de l'état de la file d'outbox impossible", echec);
            etatEnCache = Map.of();
        }
        derniereLecture.set(maintenant);
        return etatEnCache;
    }
}
