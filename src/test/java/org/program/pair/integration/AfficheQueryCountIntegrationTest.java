package org.program.pair.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.affiche.Affiche;
import org.program.pair.domain.affiche.AfficheAudience;
import org.program.pair.domain.affiche.AfficheService;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.attendance.AttendanceService;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AfficheRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les trois routes du lot ne doivent pas coûter <b>plus cher quand elles rendent
 * plus de choses</b>.
 *
 * <p>Ce n'est pas une mesure de durée : nos tests tournent sur un Postgres local
 * où l'aller-retour vaut 0,1 ms, et une durée relevée ici ne voudrait rien dire.
 * C'est le <b>nombre</b> de requêtes qui se transporte — il est le même en
 * production, où la base est à San Francisco et le service en Europe, soit
 * ~200 ms l'aller-retour. C'est la leçon de {@link RecapsMineQueryCountIntegrationTest},
 * écrite après que {@code /recaps/mine} eut mis soixante secondes à la dire.
 *
 * <p><b>Pourquoi quarante affiches et pas dix.</b> Le dépôt porte
 * {@code hibernate.default_batch_fetch_size=32}. En deçà de ce plafond, un rendu
 * qui marche la chaîne {@code Schedule → Program → UserActivity → Activity →
 * Category} à la demande <i>a l'air</i> gratuit : Hibernate résout les créneaux
 * en un seul {@code WHERE id = ANY(?)}, et le coût marginal par affiche est nul
 * comme ici. Une galerie de dix affiches ne peut donc pas distinguer un rendu
 * correct d'un rendu qui ne tient que par le plafond. <b>Au-delà de 32, le rendu
 * paresseux repart par paliers ; le {@code JOIN FETCH} non.</b> C'est cette
 * différence-là que la classe garde, et elle n'est visible qu'au-dessus du
 * plafond.
 *
 * <p>Le décor est à Toulouse et ses comptes tirés à chaque exécution : la base
 * est partagée par toute la suite depuis le 08/09, et une fixture commune
 * empoisonnerait les classes qui passent après.
 */
class AfficheQueryCountIntegrationTest extends AbstractIntegrationTest {

    /**
     * Au-dessus de {@code default_batch_fetch_size} (32), et volontairement.
     * Voir la javadoc de la classe : en dessous, le test passerait aussi avec le
     * rendu paresseux qu'il existe pour interdire.
     */
    private static final int AU_DESSUS_DU_PLAFOND_DE_LOT = 40;

    @Autowired AfficheService afficheService;
    @Autowired AttendanceService attendanceService;
    @Autowired AfficheRepository afficheRepository;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * {@code GET /api/users/{id}/affiches} — sa propre galerie, l'écran « Mes
     * moments ».
     *
     * <p>Une requête, et la même à une affiche qu'à quarante. C'est ce que le
     * {@code JOIN FETCH} des deux lectures d'{@code AfficheRepository} achète :
     * sans lui, {@code activityName} et {@code categoryColorRamp} feraient
     * marcher la chaîne à la demande, soit cinq requêtes de plus — une seconde
     * chez le client, pour une galerie de n'importe quelle taille.
     */
    @Test
    void saGalerie_neCoutePasPlusCherQuandElleGrossit() {
        Auteur petite = decor("aqc-1", 1);
        Auteur grande = decor("aqc-40", AU_DESSUS_DU_PLAFOND_DE_LOT);

        Mesure une = mesure(() -> afficheService.forUser(petite.id(), petite.id()).size());
        Mesure quarante = mesure(() -> afficheService.forUser(grande.id(), grande.id()).size());

        raconte("GET /api/users/{id}/affiches — chez soi", une, quarante);

        assertThat(une.lignes).isEqualTo(1);
        assertThat(quarante.lignes).isEqualTo(AU_DESSUS_DU_PLAFOND_DE_LOT);

        // LE GARDE-FOU. Une seule requête, quelle que soit la taille de la
        // galerie — c'est le nombre exact, pas une borne, parce qu'un rendu qui
        // en coûterait deux aurait rouvert un chemin paresseux et devrait être
        // relu plutôt que toléré.
        assertThat(quarante.requetes)
            .as("la galerie doit tenir en une requête, quarante affiches comprises")
            .isEqualTo(1);
        assertThat(quarante.requetes)
            .as("le coût ne doit pas dépendre du nombre d'affiches")
            .isEqualTo(une.requetes);
    }

    /**
     * La même galerie, lue par <b>quelqu'un d'autre</b> — ce qu'ouvre un tap sur
     * la bande d'affiches.
     *
     * <p>Le coût fixe est plus élevé de quelques requêtes, et c'est normal : ce
     * chemin résout d'abord à quelles audiences ce lecteur a droit — le compte
     * est-il actif, y a-t-il un blocage, est-il abonné. Ce qui est gardé ici est
     * le même que ci-dessus : ce supplément est <b>fixe</b>, il ne suit pas le
     * nombre d'affiches.
     */
    @Test
    void laGalerieDUnAutre_paieSonAudienceUneFoisEtPasParAffiche() {
        Auteur petite = decor("aqc-lu-1", 1);
        Auteur grande = decor("aqc-lu-40", AU_DESSUS_DU_PLAFOND_DE_LOT);
        UUID lecteur = nouvelUtilisateur("aqc-lecteur").getId();

        Mesure une = mesure(() -> afficheService.forUser(petite.id(), lecteur).size());
        Mesure quarante = mesure(() -> afficheService.forUser(grande.id(), lecteur).size());

        raconte("GET /api/users/{id}/affiches — vu par un tiers", une, quarante);

        // Les affiches sont publiées en EVERYONE : un tiers les voit toutes.
        assertThat(une.lignes).isEqualTo(1);
        assertThat(quarante.lignes).isEqualTo(AU_DESSUS_DU_PLAFOND_DE_LOT);

        assertThat(quarante.requetes)
            .as("la résolution de l'audience se paie une fois, jamais par affiche")
            .isEqualTo(une.requetes);
    }

    /**
     * {@code GET /api/affiches/updates} — la bande d'affiches du fil d'accueil.
     *
     * <p>Une requête, {@code displayName} et {@code avatarUrl} compris. La
     * jointure sur {@code users} qui les porte était déjà là, pour
     * {@code u.is_active} et pour le prédicat de blocage : les deux colonnes
     * montent dans le {@code SELECT} d'une table de toute façon parcourue. Ce
     * test existe pour que la seule autre issue — un {@code GET /users/{id}} par
     * personne, soit dix secondes pour dessiner cinquante avatars — ne puisse
     * pas rentrer par la porte de derrière.
     */
    @Test
    void laBandeDAffiches_tientEnUneRequete() {
        Auteur auteur = decor("aqc-bande", 3);
        UUID lecteur = nouvelUtilisateur("aqc-bande-lecteur").getId();
        Instant depuis = Instant.now().minus(2, ChronoUnit.DAYS);

        Mesure bande = mesure(() -> afficheService.updatesSince(lecteur, depuis).size());

        System.out.printf("%n  GET /api/affiches/updates : %d requête(s), %d personne(s)%n",
            bande.requetes, bande.lignes);

        assertThat(bande.lignes).as("l'auteur du décor a publié, il doit figurer").isPositive();
        assertThat(bande.requetes)
            .as("un nom et un avatar ne valent pas une requête par personne")
            .isEqualTo(1);
    }

    /**
     * {@code GET /api/attendances/mine} — l'histoire sur laquelle le client
     * calcule ses motifs.
     *
     * <p>Une projection, jointures comprises : rien n'est une entité, donc rien
     * n'est chargé paresseusement. Cette route se lit sans pagination et sur
     * l'historique entier d'une personne — c'est précisément le genre de liste
     * où un chargement par ligne ne se voit qu'en production.
     */
    @Test
    void lHistoireDesPresences_tientEnUneRequete() {
        Auteur auteur = decor("aqc-presences", AU_DESSUS_DU_PLAFOND_DE_LOT);

        Mesure histoire = mesure(() -> attendanceService.getMine(auteur.id()).size());

        System.out.printf("%n  GET /api/attendances/mine : %d requête(s), %d présence(s)%n",
            histoire.requetes, histoire.lignes);

        assertThat(histoire.lignes).isEqualTo(AU_DESSUS_DU_PLAFOND_DE_LOT);
        assertThat(histoire.requetes)
            .as("l'histoire entière doit tenir en une requête, jointures comprises")
            .isEqualTo(1);
    }

    // ————————————————————————— mesure —————————————————————————

    private record Mesure(int lignes, int requetes, Map<String, Integer> parTexte) {}

    /**
     * Compte les requêtes SQL réellement émises pendant l'appel.
     *
     * <p>Par le journal {@code org.hibernate.SQL} et non par les statistiques
     * Hibernate : celles-ci donnent un total, alors qu'on veut savoir
     * <b>lesquelles</b> se répètent. C'est le regroupement par texte qui nomme le
     * coupable quand le garde-fou tombe, et il s'affiche alors sans qu'il faille
     * réinstrumenter quoi que ce soit.
     */
    private Mesure mesure(IntSupplier appel) {
        ch.qos.logback.classic.Logger sql =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level niveauInitial = sql.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sql.addAppender(appender);
        sql.setLevel(Level.DEBUG);

        int lignes;
        try {
            lignes = appel.getAsInt();
        } finally {
            sql.setLevel(niveauInitial);
            sql.detachAppender(appender);
            appender.stop();
        }

        Map<String, Integer> parTexte = new LinkedHashMap<>();
        for (ILoggingEvent evenement : appender.list) {
            parTexte.merge(evenement.getFormattedMessage(), 1, Integer::sum);
        }
        return new Mesure(lignes, appender.list.size(), parTexte);
    }

    /** Le relevé, imprimé même quand le test passe : c'est lui qu'on relit. */
    private void raconte(String route, Mesure une, Mesure quarante) {
        System.out.printf("%n  %s%n    1 affiche : %d requête(s)   |   %d affiches : %d requête(s)%n",
            route, une.requetes, quarante.lignes, quarante.requetes);
        if (quarante.requetes > une.requetes) {
            System.out.println("    ce qui se répète :");
            quarante.parTexte.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .forEach(e -> System.out.printf("      %3d ×  %s%n", e.getValue(), resume(e.getKey())));
        }
    }

    // ————————————————————————— décor —————————————————————————

    private record Auteur(UUID id, List<UUID> scheduleIds) {}

    /**
     * Un auteur, ses présences et ses affiches — une par créneau distinct, comme
     * une vraie galerie.
     *
     * <p>Trois programmes pour toutes les affiches, à l'image d'un compte réel :
     * on va plusieurs fois chez les mêmes hôtes. Les créneaux, eux, sont tous
     * distincts, et c'est leur nombre qui doit dépasser le plafond de lot pour
     * que le test ait un sens.
     */
    private Auteur decor(String prefixe, int affiches) {
        User auteur = nouvelUtilisateur(prefixe + "-auteur");

        List<Program> programmes = new ArrayList<>();
        for (int h = 0; h < Math.min(3, affiches); h++) {
            User hote = nouvelUtilisateur(prefixe + "-hote-" + h);
            UserActivity userActivity = userActivityRepository.save(UserActivity.builder()
                .user(hote).activity(activiteDuReferentiel(h)).visibleOnMap(true).build());
            programmes.add(programRepository.save(Program.builder()
                .userActivity(userActivity)
                .title("Programme " + prefixe + " " + h)
                .status(ProgramStatus.ACTIVE)
                .isPublic(true)
                .build()));
        }

        List<UUID> creneaux = new ArrayList<>();
        for (int i = 0; i < affiches; i++) {
            Program programme = programmes.get(i % programmes.size());
            Instant debut = Instant.now().minus(i + 2L, ChronoUnit.DAYS);
            Instant fin = debut.plus(1, ChronoUnit.HOURS);

            Schedule creneau = scheduleRepository.save(Schedule.builder()
                .program(programme)
                .placeName("Lieu " + prefixe + " " + i)
                .placeType(PlaceType.PUBLIC)
                .city("Toulouse")
                // Toulouse, et non le point de Strasbourg que huit classes se
                // disputent : le fil lu dans 20 km rendrait leurs créneaux avec
                // les nôtres.
                .location(geometryFactory.createPoint(new Coordinate(1.4442, 43.6047)))
                .startsAt(debut)
                .endsAt(fin)
                .status(SlotStatus.PAST)
                .isOpenToPartners(true)
                .build());
            creneaux.add(creneau.getId());

            attendanceRepository.save(Attendance.builder()
                .schedule(creneau).user(auteur).wasPresent(true).attendedAt(debut).build());

            afficheRepository.save(Affiche.builder()
                .user(auteur).schedule(creneau)
                .occurrenceStart(debut).occurrenceEnd(fin)
                .motif("PREMIERE_FOIS")
                .audience(AfficheAudience.EVERYONE)
                .publishedAt(fin)
                .build());
        }
        return new Auteur(auteur.getId(), creneaux);
    }

    /**
     * Trois activités du référentiel, jamais des activités à soi.
     *
     * <p>Le réflexe de la base partagée est de tout se fabriquer ; ici il serait
     * contre-productif. Cette classe n'asserte que des <b>nombres de requêtes</b>
     * — aucun nom, aucune rampe — donc elle n'a rien à isoler ; et une activité
     * créée grossit le référentiel que les classes de navigation parcourent, dont
     * certaines lisent avec un {@code LIMIT} et verraient la leur sortir de la
     * fenêtre. Le décor de {@link RecapsMineQueryCountIntegrationTest} lit le
     * référentiel pour cette raison, et celui-ci fait de même.
     *
     * <p>Les trois sont distinctes entre elles : c'est ce qui empêche la chaîne
     * de se réduire à une seule ligne à charger, et rend la mesure représentative
     * d'un compte réel — on va chez plusieurs hôtes, pour plusieurs activités.
     */
    private Activity activiteDuReferentiel(int rang) {
        List<Activity> referentiel = activityRepository.findAll();
        return referentiel.get(rang % referentiel.size());
    }

    private User nouvelUtilisateur(String prefixe) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefixe))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName(prefixe)
            .avatarUrl("https://example.invalid/" + prefixe + ".png")
            .isActive(true)
            .build());
    }

    /** Une requête SQL ramenée à ce qui la nomme : son verbe et sa table. */
    private static String resume(String sql) {
        String plat = sql.replaceAll("\\s+", " ").trim();
        return plat.length() <= 150 ? plat : plat.substring(0, 150) + "…";
    }
}
