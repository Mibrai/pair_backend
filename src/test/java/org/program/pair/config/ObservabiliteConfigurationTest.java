package org.program.pair.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que les fichiers de configuration disent de l'exposition d'actuator, de la
 * collecte des métriques et du déploiement.
 *
 * <p><b>Pourquoi lire les fichiers.</b> Même raison que
 * {@link ProfilRailwayTest}, dont cette classe reprend le procédé : le défaut
 * fermé ici <b>était une ligne de configuration</b>, pas une ligne de code.
 * {@code management.endpoints.web.exposure.include} contenait {@code metrics} et
 * {@code show-details} valait {@code when-authorized} : n'importe quel compte —
 * y compris un compte créé à l'instant — lisait les métriques et le détail de
 * santé (fiche d'audit P-BS-16). Un test de comportement passerait au vert le
 * jour où quelqu'un remet {@code metrics} dans la liste, puisque
 * {@code SecurityConfig} refuserait quand même la route ; on ne l'apprendrait
 * qu'en voyant la métrique manquer. Il faut que le retour de la ligne échoue
 * <b>ici</b>.
 *
 * <p>{@code PropertiesLoaderUtils} lit la ressource telle qu'elle est écrite :
 * pas de contexte Spring, pas de résolution de {@code ${…}}, pas de hiérarchie
 * de profils. Aucune configuration de contexte n'est donc ajoutée à la suite —
 * le cache de contextes est saturé à quatre entrées, et une cinquième
 * configuration a déjà coûté 28 minutes sur une exécution complète.
 */
class ObservabiliteConfigurationTest {

    private static final String EXPOSITION = "management.endpoints.web.exposure.include";
    private static final String DETAIL_SANTE = "management.endpoint.health.show-details";
    private static final String DRAPEAU_LIENS = "pair.email.journaliser-liens";

    // ------------------------------------------------- P-BS-16 : ce qui est fermé

    /**
     * Le test qui empêche la rechute : {@code metrics} ne revient pas dans la
     * liste d'exposition commune.
     */
    @Test
    void lExpositionCommune_neDoitPasPublierLesMetriques() throws IOException {
        assertThat(charger("application.properties").getProperty(EXPOSITION))
            .as("%s — « metrics » y rendait les noms de routes, les tailles de "
                + "pool et les compteurs d'erreurs lisibles par tout compte connecté",
                EXPOSITION)
            .isEqualTo("health,info");
    }

    /**
     * {@code when-authorized} ne veut pas dire ce qu'il a l'air de dire : sans
     * {@code management.endpoint.health.roles}, « autorisé » signifie « tout
     * compte connecté », et le détail nomme la base, Redis et le disque.
     */
    @Test
    void leDetailDeSante_doitResterFerme() throws IOException {
        assertThat(charger("application.properties").getProperty(DETAIL_SANTE))
            .as("%s : never, et non when-authorized — voir P-BS-16", DETAIL_SANTE)
            .isEqualTo("never");
    }

    /**
     * Les sondes vivent dans la configuration commune et non dans le seul profil
     * railway : c'est ce qui rend {@code /actuator/health/readiness} éprouvable
     * par la suite, qui tourne sous le profil test.
     */
    @Test
    void lesSondesDeSante_doiventEtreActiveesPartout() throws IOException {
        assertThat(charger("application.properties")
            .getProperty("management.endpoint.health.probes.enabled"))
            .isEqualTo("true");
    }

    /**
     * L'indicateur de santé Redis suit le drapeau applicatif.
     *
     * <p>Relevé le 12/09 : {@code /actuator/health} rendait {@code DOWN}.
     * L'autoconfiguration Redis de Spring Boot ignore {@code redis.enabled} —
     * c'est le drapeau du projet, pas le sien : elle crée sa fabrique de
     * connexions dans tous les cas, et l'indicateur allait taper un Redis que
     * personne ne fournit, ni en test ni en production. Les deux lignes doivent
     * porter la <b>même</b> variable, sinon le jour où Redis est réellement
     * fourni la surveillance reste éteinte et une panne de Redis passe pour un
     * service en bonne santé.
     */
    @Test
    void laSanteDeRedis_doitSuivreLeDrapeauApplicatif() throws IOException {
        Properties communes = charger("application.properties");

        assertThat(communes.getProperty("management.health.redis.enabled"))
            .as("sans cette ligne, l'agrégat de santé est DOWN pour une dépendance "
                + "optionnelle que l'application n'utilise pas")
            .isEqualTo(communes.getProperty("redis.enabled"))
            .isEqualTo("${REDIS_ENABLED:false}");
    }

    /**
     * Le point de collecte n'est publié que sous railway, où il ne répond que
     * sur le port de gestion privé. Publié dans {@code application.properties},
     * il serait servi sur le port public de toutes les instances — c'est
     * exactement le défaut que P-BS-16 vient de fermer, rouvert sous un autre
     * nom.
     */
    @Test
    void lePointPrometheus_neDoitEtrePublieQueSousRailway() throws IOException {
        assertThat(charger("application.properties").getProperty(EXPOSITION))
            .doesNotContain("prometheus");
        assertThat(charger("application-railway.properties").getProperty(EXPOSITION))
            .isEqualTo("health,info,prometheus");
    }

    // ------------------------------------- P-BA-21 : la collecte et les journaux

    /**
     * Le port de gestion séparé est ce qui protège les métriques : Railway ne
     * publie sur Internet que le port de {@code PORT}. Retirer cette ligne
     * ramènerait {@code /actuator/prometheus} sur le port public.
     */
    @Test
    void leProfilRailway_doitPoserUnPortDeGestionDistinct() throws IOException {
        Properties railway = charger("application-railway.properties");

        assertThat(railway.getProperty("management.server.port"))
            .as("management.server.port — sans lui, l'exposition prometheus "
                + "ci-dessus est servie sur le port public")
            .isEqualTo("${MANAGEMENT_PORT:9090}");
        assertThat(railway.getProperty("server.port"))
            .as("et il doit différer du port de l'application")
            .isEqualTo("${PORT:8080}");
    }

    /** Les journaux de production parlent JSON, avec le requestId du MDC. */
    @Test
    void leProfilRailway_doitStructurerSesJournaux() throws IOException {
        assertThat(charger("application-railway.properties")
            .getProperty("logging.structured.format.console"))
            .isEqualTo("${LOGGING_STRUCTURED_FORMAT_CONSOLE:ecs}");
    }

    // ----------------------------------------- P-BA-05 étape 3 : les connexions

    /**
     * Les trois réglages qui empêchent une connexion coupée en silence par
     * l'infrastructure d'être découverte par la requête d'un utilisateur.
     */
    @Test
    void leProfilRailway_doitGarderLesConnexionsChaudes() throws IOException {
        Properties railway = charger("application-railway.properties");

        assertThat(railway.getProperty("spring.datasource.hikari.keepalive-time"))
            .isEqualTo("30000");
        assertThat(railway.getProperty("spring.datasource.hikari.max-lifetime"))
            .isEqualTo("600000");
        assertThat(railway.getProperty("spring.datasource.hikari.minimum-idle"))
            .isEqualTo("5");
    }

    /**
     * {@code max-lifetime} doit rester sous le délai d'inactivité du proxy, et
     * {@code minimum-idle} sous la taille du pool. Le second est un invariant
     * arithmétique : un plancher supérieur au plafond est une configuration que
     * Hikari corrige en silence, donc une intention perdue.
     */
    @Test
    void lePlancherDeConnexions_doitResterSousLaTailleDuPool() throws IOException {
        int plancher = Integer.parseInt(charger("application-railway.properties")
            .getProperty("spring.datasource.hikari.minimum-idle"));
        int plafond = Integer.parseInt(charger("application.properties")
            .getProperty("spring.datasource.hikari.maximum-pool-size"));

        assertThat(plancher).isLessThan(plafond);
    }

    // ------------------------------------------- Drapeaux posés pour les vagues

    /**
     * Les trois drapeaux allumés d'avance. Ce test n'éprouve aucun comportement
     * — le code qui les lit n'existe pas encore — il éprouve leur <b>présence</b>
     * et leur valeur : c'est le contrat passé avec les fiches P-BL-06, P-BL-03 et
     * P-BS-03, dont aucune n'a à rouvrir ce fichier.
     */
    @Test
    void lesDrapeauxDesVaguesSuivantes_doiventEtrePosesEtAllumes() throws IOException {
        Properties communes = charger("application.properties");

        assertThat(communes.getProperty("pair.notifications.schedule-changed.enabled"))
            .as("P-BL-06 — avis de séance modifiée")
            .isEqualTo("true");
        assertThat(communes.getProperty("pair.gdpr.purge.enabled"))
            .as("P-BL-03 — purge RGPD des comptes inactifs")
            .isEqualTo("true");
        assertThat(communes.getProperty("pair.session.adoption-jetons-historiques"))
            .as("P-BS-03 — jetons émis avant token_version")
            .isEqualTo("true");
    }

    /**
     * Le seul drapeau dont la valeur dépend du profil, et le seul dont une
     * erreur de profil se paierait : un lien de réinitialisation de mot de passe
     * recopié dans les journaux d'une plateforme donne le compte à quiconque
     * lit ces journaux — ce qui se fait sans les droits de la base (P-BS-09).
     */
    @Test
    void laJournalisationDesLiens_neDoitEtreAllumeeQuEnDeveloppement() throws IOException {
        assertThat(charger("application.properties").getProperty(DRAPEAU_LIENS))
            .as("false dans la configuration commune, donc partout par défaut")
            .isEqualTo("false");
        assertThat(charger("application-dev.properties").getProperty(DRAPEAU_LIENS))
            .as("dev est la seule exception : aucune boîte aux lettres n'y reçoit rien")
            .isEqualTo("true");

        for (String profil : new String[] {"railway", "prod", "staging"}) {
            assertThat(charger("application-" + profil + ".properties").getProperty(DRAPEAU_LIENS))
                .as("application-%s.properties ne doit pas rallumer %s", profil, DRAPEAU_LIENS)
                .isNotEqualTo("true");
        }
    }

    // ------------------------------------------------- P-BA-21 étape 6 : railway.json

    /**
     * Une seule réplique, et ce n'est pas un réglage de coût.
     *
     * <p>Aucun verrou distribué n'existe dans cette application : les dix jobs
     * {@code @Scheduled} balaient des tables sans réserver les lignes qu'ils
     * traitent. Deux instances enverraient donc deux fois chaque rappel de
     * programme, deux fois chaque SMS de veille, et feraient deux fois le
     * rollover des créneaux récurrents — en doublant les créneaux créés. Le
     * verrou distribué est au Lot 2 ; jusque-là, le nombre de répliques EST le
     * verrou.
     */
    @Test
    void leDeploiement_doitResterSurUneSeuleReplique() throws IOException {
        JsonNode deploiement = railwayJson().path("deploy");

        assertThat(deploiement.path("numReplicas").asInt())
            .as("numReplicas — aucun verrou distribué avant le Lot 2 : deux "
                + "instances doublent rappels, SMS et rollover")
            .isEqualTo(1);
    }

    /**
     * Le contrôle de santé vise la sonde de disponibilité. Avec le port de
     * gestion séparé, elle n'existe sur le port public — le seul que la
     * plateforme sonde — que parce que {@code SantePubliqueController} la
     * republie (décision §5.1 du 13/09) ; {@code SantePubliqueIntegrationTest}
     * éprouve ce comportement. Les 300 secondes couvrent Flyway et le
     * chargement du modèle d'embeddings.
     */
    @Test
    void leControleDeSante_doitViserUneRouteDuPortPublic() throws IOException {
        JsonNode deploiement = railwayJson().path("deploy");

        assertThat(deploiement.path("healthcheckPath").asText())
            .as("la sonde de disponibilité, republiée sur le port public")
            .isEqualTo("/actuator/health/readiness");
        assertThat(deploiement.path("healthcheckTimeout").asInt())
            .as("Flyway et le chargement du modèle d'embeddings tiennent dans ce délai")
            .isEqualTo(300);
        assertThat(deploiement.path("restartPolicyType").asText())
            .as("avec -XX:+ExitOnOutOfMemoryError, c'est ce qui relance l'instance")
            .isEqualTo("ON_FAILURE");
    }

    // ------------------------------------------- P-BA-21 étape 7 : la JVM du conteneur

    /**
     * Les trois options de l'{@code ENTRYPOINT}, qu'aucun test fonctionnel ne
     * saurait dire.
     *
     * <p>Elles ne se remarquent que par leur absence, et tard : un tas plafonné
     * au quart d'une enveloppe payée en entier, un {@code OutOfMemoryError} qui
     * laisse un processus debout et inutile, et des jobs en cron dont l'heure
     * suit le fuseau de l'hôte. Rien de tout cela ne casse un test ; tout cela
     * se paie en production. D'où une assertion sur le fichier lui-même.
     */
    @Test
    void lEntrypointDuConteneur_doitBornerLaMemoireEtFixerLeFuseau() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile)
            .as("sans MaxRAMPercentage, la JVM plafonne son tas au quart de la "
                + "mémoire du conteneur, dont les trois quarts sont payés pour rien")
            .contains("-XX:MaxRAMPercentage=75")
            .as("sans ExitOnOutOfMemoryError, un OOM tue un fil et laisse "
                + "l'instance « saine » aux yeux de la plateforme")
            .contains("-XX:+ExitOnOutOfMemoryError")
            .as("les jobs sont écrits en cron (P-BA-19) : leur heure de "
                + "déclenchement ne doit pas dépendre du fuseau de l'hôte")
            .contains("-Duser.timezone=UTC");
    }

    /**
     * Le sha du commit atteint Maven : sans {@code ARG}, une construction Docker ne
     * transmet pas la variable de la plateforme à {@code RUN}, et
     * {@code /actuator/info} rendait {@code "commit":"local"} sur chaque
     * déploiement — relevé par l'équipe mobile le 02/09, toujours vrai le 14/09.
     * Le profil du pom existait ; c'est cette ligne qui manquait.
     */
    @Test
    void laConstruction_doitRecevoirLeShaDuCommit_avantLePackage() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        int arg = dockerfile.indexOf("ARG RAILWAY_GIT_COMMIT_SHA");
        int packageMaven = dockerfile.indexOf("mvnw clean package");
        int etapeExecution = dockerfile.indexOf("FROM ", dockerfile.indexOf("FROM ") + 1);

        assertThat(arg).as("ARG RAILWAY_GIT_COMMIT_SHA déclaré").isNotNegative();
        assertThat(arg)
            .as("déclaré avant le package Maven, dans l'étape de construction")
            .isLessThan(packageMaven)
            .isLessThan(etapeExecution);
    }

    // ------------------------------------------------------------------ outils

    private static JsonNode railwayJson() throws IOException {
        Path fichier = Path.of("railway.json");
        assertThat(Files.exists(fichier))
            .as("railway.json à la racine — le réglage de déploiement est versionné, "
                + "pas seulement cliqué dans l'interface")
            .isTrue();
        return new ObjectMapper().readTree(Files.readString(fichier));
    }

    /**
     * Lit un fichier de propriétés du classpath principal tel qu'il est écrit.
     * Aucune substitution de variable, aucune hiérarchie de profils.
     */
    private static Properties charger(String nom) throws IOException {
        ClassPathResource ressource = new ClassPathResource(nom);
        assertThat(ressource.exists()).as("%s doit être sur le classpath", nom).isTrue();
        return PropertiesLoaderUtils.loadProperties(ressource);
    }
}
