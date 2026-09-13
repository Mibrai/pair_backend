package org.program.pair.integration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.gdpr.GdprService;
import org.program.pair.domain.gdpr.MetriquesPurgeRgpd;
import org.program.pair.domain.guardian.PhoneNumber;
import org.program.pair.domain.notification.DevicePlatform;
import org.program.pair.domain.notification.DeviceTokenService;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La purge RGPD des comptes supprimés, de bout en bout (P-BL-03, couvre P-BS-05).
 *
 * <h2>Ce que ces tests couvrent, et que rien ne couvrait</h2>
 *
 * <p>La purge n'avait aucun test. Elle était pourtant cassée de trois façons qui
 * se cumulaient : les deux seules clés étrangères vers {@code users} sans
 * {@code ON DELETE} (participations et présences) empêchaient la suppression ;
 * l'effacement était auto-invoqué depuis une boucle {@code @Transactional}, si
 * bien qu'un seul compte bloqué annulait <b>toute la nuit</b> et que l'exception
 * était attribuée au compte suivant ; et la sélection lisait
 * {@code last_active_at}, une date qui ne dit pas quand la suppression a été
 * demandée.
 *
 * <h2>Pourquoi cette classe est la plus dangereuse du dépôt, et ce qui la tient</h2>
 *
 * <p>Ces tests <b>suppriment des comptes</b>, dans une base que les 190 classes
 * de la suite partagent. Quatre règles, tenues sans exception :
 *
 * <ol>
 *   <li><b>Chaque méthode crée ses propres comptes jetables</b> par la route
 *       d'inscription, avec {@code uniqueEmail}. Aucune adresse en dur, aucun
 *       compte de démonstration, aucun compte de test documenté n'est touché.</li>
 *   <li><b>Aucun {@code DELETE} ni {@code UPDATE} sans {@code WHERE id = ?}.</b>
 *       Les antidatages visent une ligne nommément et <b>vérifient qu'une seule a
 *       bougé</b> : un antidatage qui déborderait rendrait purgeables les comptes
 *       des autres classes, et la suite tomberait ailleurs, plus tard, sans
 *       rapport visible.</li>
 *   <li><b>Le nettoyage est inconditionnel</b> ({@link #nettoyerLesComptesJetables()}) :
 *       ce que la purge n'a pas effacé, cette classe l'efface, pour ne pas laisser
 *       derrière elle des comptes désactivés et antidatés que la <i>prochaine</i>
 *       exécution de cette même classe compterait comme les siens.</li>
 *   <li><b>Et surtout, le garde-fou de production sert de garde-fou de test.</b>
 *       La purge ignore toute ligne sans {@code deactivated_at}, et seule cette
 *       classe antidate cette colonne. Un compte désactivé par une autre classe
 *       porte donc une date du jour : il n'est jamais candidat. C'est la même
 *       propriété qui protège les comptes de démonstration en production et les
 *       autres classes ici.</li>
 * </ol>
 */
class GdprPurgeIntegrationTest extends AbstractIntegrationTest {

    private static final String MOT_DE_PASSE = "MotDePasse123!";

    /**
     * Le schéma d'un seul test, celui qui rend un compte impurgeable.
     *
     * <p><b>Hors de {@code public}, délibérément.</b> Il porte une clé étrangère
     * vers {@code users} en {@code ON DELETE RESTRICT} — une contrainte qui n'a
     * évidemment rien à faire dans le schéma de l'application. Or deux tests
     * balaient le catalogue de {@code public} et exigeraient d'elle un index de
     * tête ({@code db.ForeignKeysIndexedIntegrationTest}) et un {@code ON DELETE}
     * non bloquant ({@link #chaqueCleEtrangereVersUsers_doitDeclarerUnOnDelete()},
     * juste en dessous). Dans son propre schéma, elle est invisible des deux, et
     * les deux gardes-fous restent intacts.
     */
    private static final String SCHEMA_DE_BLOCAGE = "test_purge_rgpd";

    @Autowired private UserRepository userRepository;
    @Autowired private GdprService gdprService;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry registreDeMesures;

    /** Les seules lignes {@code users} que cette classe a le droit de toucher. */
    private final List<UUID> comptesJetables = new ArrayList<>();

    @AfterEach
    void nettoyerLesComptesJetables() {
        // Le blocage d'abord : sa clé en RESTRICT empêcherait la suppression du
        // compte qu'elle retient, et le laisserait désactivé et antidaté dans la
        // base pour toute la suite.
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA_DE_BLOCAGE + " CASCADE");

        for (UUID id : comptesJetables) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        comptesJetables.clear();
    }

    // ------------------------------------------------------------------
    // Le constat : ce qui empêchait la purge
    // ------------------------------------------------------------------

    /**
     * Le défaut lui-même : avoir rejoint un créneau rendait un compte
     * indéfiniment inaffaçable.
     *
     * <p>Les deux clés étrangères de V40 et V41 étaient en {@code NO ACTION}, si
     * bien que la suppression de la ligne {@code users} échouait — et avec elle
     * les contacts d'urgence, les veilles et les autres lignes en
     * {@code ON DELETE CASCADE}, qui ne partent qu'avec leur parent et n'étaient
     * donc jamais parties non plus (apport de P-BS-05). L'échec ne se lisait que
     * dans les journaux.
     *
     * <p>Le créneau et son programme appartiennent à un <b>autre</b> compte
     * jetable : c'est le cas réel — on rejoint le créneau de quelqu'un d'autre —
     * et il vérifie au passage que la cascade n'emporte que ce qui désigne la
     * personne effacée, pas le créneau de l'hôte.
     */
    @Test
    void unePersonneQuiARejointUnCreneau_doitEtrePurgeeApresTrenteJours() {
        Compte hote = inscrire("purge-hote");
        Compte membre = inscrire("purge-membre");
        UUID creneau = creerUnCreneauDe(hote.id());

        rejoindre(creneau, membre.id());
        marquerPresent(creneau, membre.id());
        armerUneVeille(creneau, membre.id());
        designerUnContactDurgence(membre.id());

        demanderLaSuppression(membre);
        antidaterLaDemande(membre.id(), 40);

        gdprService.purgeInactiveAccounts();

        assertThat(userRepository.findById(membre.id()))
            .as("la ligne users du compte purgé n'existe plus")
            .isEmpty();
        assertThat(lignesDe("slot_participations", membre.id())).isZero();
        assertThat(lignesDe("attendances", membre.id())).isZero();
        assertThat(lignesDe("watches", membre.id())).isZero();
        assertThat(lignesDe("guardians", "owner_id", membre.id())).isZero();

        assertThat(userRepository.findById(hote.id()))
            .as("la cascade n'emporte que ce qui désigne la personne effacée")
            .isPresent();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM schedules WHERE id = ?", Integer.class, creneau))
            .as("le créneau de l'hôte reste, il n'appartenait pas au compte purgé")
            .isEqualTo(1);
    }

    /**
     * Un échec sur un compte ne doit plus annuler la nuit entière.
     *
     * <p>C'est le cœur du correctif : la boucle n'ouvre plus de transaction et
     * l'effacement passe par un autre bean, donc par le proxy. Avant, le
     * {@code rollback-only} posé par le compte bloqué faisait lever
     * {@code UnexpectedRollbackException} au commit final et <b>aucun</b> compte
     * n'était effacé.
     *
     * <p>Le blocage est une clé étrangère de test en {@code ON DELETE RESTRICT},
     * dans son propre schéma (voir {@link #SCHEMA_DE_BLOCAGE}), et non le défaut
     * du jour sur {@code messages.sender_id} : un test qui s'appuierait sur un
     * autre défaut deviendrait rouge le jour où celui-ci est réparé, en donnant à
     * croire que c'est cette propriété-ci qui a cassé.
     */
    @Test
    void unCompteEnEchec_neDoitPasEmpecherLaPurgeDesAutres() {
        Compte avant = inscrire("purge-ordre-avant");
        Compte bloque = inscrire("purge-ordre-bloque");
        Compte apres = inscrire("purge-ordre-apres");

        // L'ordre de sélection est celui de la demande : le compte bloquant est au
        // milieu, pour que son échec ait un compte avant lui et un après lui.
        demanderLaSuppression(avant);
        antidaterLaDemande(avant.id(), 60);
        demanderLaSuppression(bloque);
        antidaterLaDemande(bloque.id(), 50);
        demanderLaSuppression(apres);
        antidaterLaDemande(apres.id(), 40);

        rendreImpurgeable(bloque.id());
        double echecsAvant = compteur(MetriquesPurgeRgpd.COMPTEUR_ECHECS);

        GdprService.ResultatDePurge resultat = gdprService.purgeInactiveAccounts();

        assertThat(userRepository.findById(avant.id()))
            .as("le compte sélectionné avant le compte bloquant est effacé")
            .isEmpty();
        assertThat(userRepository.findById(apres.id()))
            .as("celui sélectionné après aussi : c'est ce que l'ancienne "
                + "transaction unique rendait impossible")
            .isEmpty();
        assertThat(userRepository.findById(bloque.id()))
            .as("le compte bloqué reste, et lui seul")
            .isPresent();

        assertThat(resultat.echecs())
            .as("un échec, attribué au bon compte")
            .isEqualTo(1);
        assertThat(resultat.effaces())
            .as("au moins les deux comptes de cette méthode ; la base est "
                + "partagée, on ne fige pas un total")
            .isGreaterThanOrEqualTo(2);
        assertThat(compteur(MetriquesPurgeRgpd.COMPTEUR_ECHECS))
            .as("l'échec est compté, pas seulement journalisé : c'est la seule "
                + "chose sur quoi une alerte pourra se brancher")
            .isEqualTo(echecsAvant + 1);
    }

    // ------------------------------------------------------------------
    // L'horloge de la purge : la demande, et rien d'autre
    // ------------------------------------------------------------------

    /**
     * {@code last_active_at} nul ne doit plus mettre un compte hors de portée.
     *
     * <p>L'ancienne sélection lisait {@code lastActiveAt < :cutoff}, et une
     * comparaison sur {@code NULL} n'est jamais vraie : un compte sans dernière
     * activité n'était <b>jamais</b> purgé, quoi qu'il arrive. La date de la
     * demande n'a pas ce défaut, puisque c'est elle qui déclenche le délai.
     */
    @Test
    void unCompteSansDerniereActivite_doitEtrePurge() {
        Compte compte = inscrire("purge-sans-activite");

        demanderLaSuppression(compte);
        antidaterLaDemande(compte.id(), 45);
        effacerLaDerniereActivite(compte.id());

        gdprService.purgeInactiveAccounts();

        assertThat(userRepository.findById(compte.id())).isEmpty();
    }

    /**
     * Le délai est un délai, et il court depuis la demande.
     *
     * <p>Le compte est inactif depuis quatre-vingt-dix jours et sa suppression a
     * été demandée hier. L'ancienne sélection l'aurait effacé <b>la nuit même</b>,
     * ce qui est précisément le risque qu'a créé la réparation de la route de
     * suppression de l'application (P-BL-01) : les comptes restés ouverts par
     * jetons de rafraîchissement portent tous un {@code last_active_at} périmé.
     */
    @Test
    void uneDemandeDeLaVeille_neDoitPasEtrePurgee() {
        Compte compte = inscrire("purge-demande-hier");

        demanderLaSuppression(compte);
        antidater(compte.id(), Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().minus(90, ChronoUnit.DAYS));

        gdprService.purgeInactiveAccounts();

        assertThat(userRepository.findById(compte.id()))
            .as("trente jours veut dire trente jours depuis la demande")
            .isPresent();
    }

    /**
     * Le garde-fou de déploiement : pas de date de demande, pas de purge.
     *
     * <p>C'est l'état de <b>tous</b> les comptes désactivés avant V111 — la
     * migration ne remplit pas le passé, faute d'une donnée qui dise quand la
     * demande a eu lieu. Les comptes de démonstration que l'exploitation ferme
     * sont dans ce cas. Ils ne doivent jamais partir dans un job nocturne : leur
     * effacement relève d'un runbook et d'un avis produit.
     *
     * <p>Ce test est aussi ce qui protège les 189 autres classes de la suite : un
     * compte désactivé par n'importe laquelle d'entre elles porte une date du
     * jour, donc n'est pas candidat ; et s'il n'en portait pas, il ne le serait
     * pas non plus.
     */
    @Test
    void unCompteDesactiveSansDateDeDemande_neDoitJamaisEtrePurge() {
        Compte compte = inscrire("purge-sans-date");

        demanderLaSuppression(compte);
        desdaterLaDemande(compte.id(), Instant.now().minus(400, ChronoUnit.DAYS));

        gdprService.purgeInactiveAccounts();

        assertThat(userRepository.findById(compte.id()))
            .as("un compte sans date de demande est hors de portée du job, "
                + "définitivement et par construction")
            .isPresent();
    }

    // ------------------------------------------------------------------
    // Les gardes-fous durables
    // ------------------------------------------------------------------

    /**
     * Toute clé étrangère vers {@code users} déclare un {@code ON DELETE} qui
     * laisse passer la suppression.
     *
     * <p><b>C'est le livrable durable de la fiche</b>, plus que la migration :
     * V111 ferme les deux clés qui manquaient, ce test empêche la troisième. Il
     * échouera donc sur toute table livrée avec un {@code REFERENCES users(id)}
     * nu — la réponse attendue est un {@code ON DELETE} dans la migration qui
     * crée la table, pas une exception ici.
     *
     * <p>{@code NO ACTION} et {@code RESTRICT} sont les deux formes qui bloquent ;
     * {@code CASCADE}, {@code SET NULL} et {@code SET DEFAULT} laissent passer.
     * Une clé nue prend {@code NO ACTION} par défaut, ce qui explique que le
     * défaut soit passé inaperçu pendant quarante migrations : rien, dans la DDL,
     * ne dit qu'on vient de rendre un compte inaffaçable.
     *
     * <p>Lecture seule, aucune fixture : ce test ne dépend pas de l'ordre des
     * classes.
     */
    @Test
    void chaqueCleEtrangereVersUsers_doitDeclarerUnOnDelete() {
        List<String> bloquantes = jdbcTemplate.queryForList("""
            SELECT format('%s.%s (ON DELETE %s)',
                          c.conrelid::regclass, c.conname,
                          CASE c.confdeltype WHEN 'a' THEN 'NO ACTION'
                                             WHEN 'r' THEN 'RESTRICT'
                                             ELSE c.confdeltype END)
              FROM pg_constraint c
             WHERE c.contype = 'f'
               AND c.confrelid = 'users'::regclass
               AND c.connamespace = 'public'::regnamespace
               AND c.confdeltype IN ('a', 'r')
             ORDER BY 1
            """, String.class);

        assertThat(bloquantes)
            .as("""
                Ces clés étrangères empêchent la suppression d'une ligne users, \
                donc l'effacement d'un compte : l'article 17 n'est pas tenu, et \
                l'échec ne se voit que dans les journaux du job de 3 h. La \
                réponse est une clause ON DELETE dans la migration qui crée la \
                table — CASCADE si la ligne n'a aucune raison de survivre à la \
                personne, SET NULL si elle doit survivre anonyme.""")
            .isEmpty();
    }

    /**
     * Fermer son compte détache ses appareils (P-BL-12, étape 5).
     *
     * <p>Sans cela, un compte fermé continue de recevoir les notifications de ses
     * conversations et de ses créneaux pendant les trente jours du délai, sur un
     * téléphone dont l'application affiche un écran de connexion — alors que
     * c'est la seule chose que la fermeture promette immédiatement.
     *
     * <p>{@code unregisterAllUserTokens} n'avait <b>aucun appelant</b> : la
     * méthode existait, complète et juste, et rien ne l'appelait.
     */
    @Test
    void desactiverSonCompte_doitDetacherTousSesAppareils() {
        Compte compte = inscrire("purge-appareils");
        deviceTokenService.registerToken(compte.id(), "jeton-purge-" + UUID.randomUUID(),
            DevicePlatform.ANDROID, "Téléphone jetable", "fr", "Europe/Paris");
        deviceTokenService.registerToken(compte.id(), "jeton-purge-" + UUID.randomUUID(),
            DevicePlatform.IOS, "Tablette jetable", "fr", "Europe/Paris");
        assertThat(deviceTokenService.getUserTokens(compte.id())).hasSize(2);

        demanderLaSuppression(compte);

        assertThat(deviceTokenService.getUserTokens(compte.id()))
            .as("plus un seul appareil ne vise ce compte")
            .isEmpty();
    }

    /**
     * La date de la demande est écrite par la route que l'application appelle.
     *
     * <p>Le test unitaire de {@code UserService} le vérifie déjà ; celui-ci
     * vérifie le <b>câblage</b> : c'est la route de l'app, pas le service, qui a
     * passé deux mois à ne rien écrire (P-BL-01).
     */
    @Test
    void supprimerSonCompte_doitPoserLaDateDeLaDemande_parLaRouteDeLApplication() {
        Compte compte = inscrire("purge-date-demande");
        Instant avant = Instant.now().minusSeconds(5);

        demanderLaSuppression(compte);

        var enBase = userRepository.findById(compte.id()).orElseThrow();
        assertThat(enBase.getIsActive()).isFalse();
        assertThat(enBase.getDeactivatedAt())
            .as("sans cette date, le délai de trente jours ne commence jamais "
                + "et le compte n'est jamais purgé")
            .isNotNull()
            .isAfterOrEqualTo(avant);
    }

    // ------------------------------------------------------------------
    // Fabrique et outils — tout est jetable et nommément désigné
    // ------------------------------------------------------------------

    /**
     * Un compte jetable, connu de la seule méthode qui l'a créé.
     *
     * <p>Le jeton de l'inscription est conservé plutôt que re-demandé par une
     * connexion : le limiteur de débit compte les connexions, et une méthode qui
     * en enchaîne trois recevrait un {@code 429} — un échec sans aucun rapport
     * avec ce qui est éprouvé ici.
     */
    private record Compte(UUID id, String jeton) {}

    private Compte inscrire(String prefixe) {
        AuthResponse session = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail(prefixe), MOT_DE_PASSE,
                "Compte jetable purge"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(session).isNotNull();
        assertThat(session.userId()).isNotNull();
        comptesJetables.add(session.userId());
        return new Compte(session.userId(), session.accessToken());
    }

    /** La route que l'application appelle, et elle seule (P-BL-01). */
    private void demanderLaSuppression(Compte compte) {
        webTestClient.delete()
            .uri("/api/gdpr/delete-account")
            .headers(headers -> headers.setBearerAuth(compte.jeton()))
            .exchange()
            .expectStatus().isNoContent();
    }

    /**
     * Recule la demande de {@code jours}, en laissant {@code last_active_at} du
     * jour — donc en éprouvant bien que c'est la <i>demande</i> qui compte.
     */
    private void antidaterLaDemande(UUID userId, int jours) {
        antidater(userId, Instant.now().minus(jours, ChronoUnit.DAYS), Instant.now());
    }

    /**
     * L'antidatage, et la seule forme qu'il a le droit de prendre.
     *
     * <p>{@code WHERE id = ?} sur une ligne créée par la méthode courante, et
     * <b>vérification qu'une seule ligne a bougé</b>. Un antidatage qui
     * déborderait rendrait purgeables les comptes désactivés par les autres
     * classes de la suite : l'échec tomberait ailleurs, plus tard, sans rapport
     * visible avec cette classe — le pire mode de défaillance d'une base
     * partagée.
     */
    private void antidater(UUID userId, Instant demande, Instant derniereActivite) {
        uneSeuleLigne(jdbcTemplate.update(
            "UPDATE users SET deactivated_at = ?, last_active_at = ? WHERE id = ?",
            Timestamp.from(demande), Timestamp.from(derniereActivite), userId));
    }

    /**
     * L'état exact d'un compte fermé avant V111 : inactif depuis longtemps, et
     * sans date de demande.
     *
     * <p>Le {@code NULL} est écrit en clair dans le SQL et non passé en
     * paramètre : {@code JdbcTemplate} ne connaît pas le type d'un paramètre nul
     * et le pilote PostgreSQL n'accepte pas toujours {@code setNull} sans type.
     */
    private void desdaterLaDemande(UUID userId, Instant derniereActivite) {
        uneSeuleLigne(jdbcTemplate.update("""
            UPDATE users SET deactivated_at = NULL, last_active_at = ? WHERE id = ?
            """, Timestamp.from(derniereActivite), userId));
    }

    private void effacerLaDerniereActivite(UUID userId) {
        uneSeuleLigne(jdbcTemplate.update(
            "UPDATE users SET last_active_at = NULL WHERE id = ?", userId));
    }

    private static void uneSeuleLigne(int lignes) {
        assertThat(lignes)
            .as("une mutation de cette classe doit toucher exactement la ligne de ce test")
            .isEqualTo(1);
    }

    /**
     * Un compte que la suppression de sa ligne {@code users} ne peut pas
     * atteindre, par une clé étrangère de test en {@code ON DELETE RESTRICT}.
     *
     * <p>Le schéma est détruit par {@link #nettoyerLesComptesJetables()}, quoi
     * qu'il arrive : laissé en place, il retiendrait ce compte pour toute la
     * suite et pour l'exécution suivante.
     */
    private void rendreImpurgeable(UUID userId) {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_DE_BLOCAGE);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS %s.blocage (
                user_id UUID PRIMARY KEY REFERENCES public.users(id) ON DELETE RESTRICT
            )
            """.formatted(SCHEMA_DE_BLOCAGE));
        jdbcTemplate.update(
            "INSERT INTO " + SCHEMA_DE_BLOCAGE + ".blocage (user_id) VALUES (?)", userId);
    }

    /**
     * Un créneau en ligne, passé, sur un programme privé et non publié.
     *
     * <p>Trois précautions plutôt qu'une, parce que la base est partagée :
     * {@code ONLINE} évite d'avoir à poser un point géographique — huit classes
     * se disputaient déjà le même point de Strasbourg ; une date passée le met
     * hors de portée des requêtes de créneaux à venir ; et un programme
     * {@code DRAFT} non public le met hors des fils et des pages publiques. Ce
     * test n'a besoin que de lignes en base, pas d'un créneau visible.
     */
    private UUID creerUnCreneauDe(UUID hote) {
        // Lu en texte puis converti : aucun test du dépôt ne demande un uuid
        // directement à JdbcTemplate, et le type de retour du pilote n'a pas à
        // être un pari pris dans un test de purge.
        UUID activite = UUID.fromString(jdbcTemplate.queryForObject(
            "SELECT id::text FROM activities ORDER BY id LIMIT 1", String.class));

        UUID activiteDuCompte = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO user_activities (id, user_id, activity_id, visible_on_map)
            VALUES (?, ?, ?, false)
            """, activiteDuCompte, hote, activite);

        UUID programme = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO programs (id, user_activity_id, title, status, is_public,
                                  is_publicly_shareable)
            VALUES (?, ?, ?, 'DRAFT', false, false)
            """, programme, activiteDuCompte, "Programme jetable purge " + programme);

        UUID creneau = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO schedules (id, program_id, place_name, place_type, starts_at, ends_at,
                                   is_publicly_shareable)
            VALUES (?, ?, 'Salon jetable', 'ONLINE', ?, ?, false)
            """, creneau, programme, Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)));

        return creneau;
    }

    private void rejoindre(UUID creneau, UUID userId) {
        jdbcTemplate.update("""
            INSERT INTO slot_participations (id, schedule_id, user_id, status)
            VALUES (?, ?, ?, 'CONFIRMED')
            """, UUID.randomUUID(), creneau, userId);
    }

    private void marquerPresent(UUID creneau, UUID userId) {
        jdbcTemplate.update("""
            INSERT INTO attendances (id, schedule_id, user_id, was_present, attended_at)
            VALUES (?, ?, ?, true, ?)
            """, UUID.randomUUID(), creneau, userId,
            Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS)));
    }

    /** Une veille : elle est en {@code CASCADE}, donc elle ne partait jamais. */
    private void armerUneVeille(UUID creneau, UUID userId) {
        jdbcTemplate.update("""
            INSERT INTO watches (id, schedule_id, user_id, deadline_at)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), creneau, userId,
            Timestamp.from(Instant.now().minus(59, ChronoUnit.DAYS)));
    }

    /**
     * Un contact d'urgence externe — donnée sensible s'il en est, et l'apport de
     * P-BS-05 : sa ligne est en {@code CASCADE}, elle ne partait donc jamais
     * puisque son parent ne partait pas.
     *
     * <p><b>Les deux colonnes sont bornées, et les deux valeurs viennent d'où
     * elles viennent en production</b> — c'est la correction du 12/09, et le
     * raisonnement compte plus que la longueur.
     *
     * <ul>
     *   <li>{@code consent_token} est un {@code VARCHAR(22)} (V84), exactement
     *       {@link ShareToken#LENGTH}. Un {@code UUID.toString()} y faisait
     *       trente-six caractères et la contrainte l'a refusé. La réponse n'est
     *       pas de tronquer jusqu'à ce que ça passe : le jeton est ce que le
     *       contact présente dans le lien accepter/refuser, et
     *       {@code GuardianService} le tire par {@code ShareToken.nextUnique}.
     *       Le test appelle donc le <b>même générateur</b> — base62, sans
     *       caractère à échapper dans une URL — plutôt que d'inventer une
     *       seconde forme de jeton, ce que la javadoc de {@code ShareToken}
     *       nomme précisément comme la façon dont une colonne devient trop
     *       courte un jour en production.</li>
     *   <li>{@code phone} est un {@code VARCHAR(20)} qui ne contient <b>jamais la
     *       saisie brute</b> : {@code GuardianService} n'écrit que la forme E.164
     *       rendue par {@code PhoneNumber.toE164}. Le {@code 06…} national que ce
     *       test écrivait tenait dans la colonne sans être ce que la production
     *       contient — un contact d'urgence sert à alerter quelqu'un, et un
     *       numéro d'une forme que le produit refuserait aurait rendu ce test
     *       vert sur une donnée fausse. {@code uniqueMobile()} est conservé, pour
     *       son unicité (un numéro refusé l'est pour tout le monde et pour toute
     *       la suite), et normalisé par le code de production.</li>
     * </ul>
     */
    private void designerUnContactDurgence(UUID owner) {
        String numeroE164 = PhoneNumber.toE164(uniqueMobile()).orElseThrow(
            () -> new AssertionError("uniqueMobile() doit rendre un mobile français normalisable"));

        jdbcTemplate.update("""
            INSERT INTO guardians (id, owner_id, name, phone, consent_token)
            VALUES (?, ?, 'Contact jetable', ?, ?)
            """, UUID.randomUUID(), owner, numeroE164, ShareToken.next());
    }

    private int lignesDe(String table, UUID userId) {
        return lignesDe(table, "user_id", userId);
    }

    private int lignesDe(String table, String colonne, UUID userId) {
        Integer nombre = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + colonne + " = ?",
            Integer.class, userId);
        return nombre == null ? 0 : nombre;
    }

    /** Un compteur sans étiquette, ou zéro si sa série n'existe pas encore. */
    private double compteur(String nom) {
        Counter compteur = registreDeMesures.find(nom).counter();
        return compteur == null ? 0d : compteur.count();
    }
}
