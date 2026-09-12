package org.program.pair.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Le garde-fou du seed de démonstration : sous quels profils il doit refuser de
 * démarrer, et sous lesquels il doit continuer de laisser passer.
 *
 * <p><b>Le défaut fermé ici.</b> La garde ne connaissait qu'un seul nom de
 * profil, {@code "prod"}. Or la production de ce projet tourne sous le profil
 * {@code railway}, et {@code application-railway.properties} posait
 * {@code pair.seed.demo-data.enabled=true} : la garde regardait le bon drapeau,
 * au bon moment, et ne voyait rien à refuser. Vingt comptes
 * {@code demo<n>@pair.app} vérifiés, partageant un mot de passe alors publié
 * dans {@code docs/seeds/}, ont ainsi été créés en production. Le défaut
 * n'était pas l'absence de garde, c'était une garde qui comparait un nom au
 * lieu d'interroger un ensemble — et le nom qui manquait était celui de la
 * production.
 *
 * <p><b>Ce que ces tests verrouillent, et pourquoi ainsi.</b> Le refus doit
 * porter sur {@code Profils.PRODUCTION} — {@code prod} et {@code railway} — et
 * <b>pas</b> sur {@code Profils.DEPLOIEMENT}, qui contient {@code staging}. La
 * distinction n'est pas cosmétique : sous staging, le seed de démonstration est
 * voulu ({@code AdminSeedController} y est exposé), tandis que l'absence de clé
 * JWT doit y faire échouer le démarrage. Un ensemble unique aurait forcément
 * cassé l'un des deux. C'est pourquoi {@code dev} et {@code staging} sont
 * éprouvés ici comme des cas qui doivent <b>passer</b> : un test qui ne
 * vérifierait que les refus laisserait quelqu'un « durcir » la garde en y
 * ajoutant staging sans rien voir échouer.
 *
 * <p>Tests unitaires avec {@link MockEnvironment} : aucun contexte Spring, donc
 * aucun démarrage réel à provoquer pour éprouver un refus de démarrage.
 */
class SeedRunnerTest {

    /**
     * Le refus qui manquait. {@code railway} est la production, et son nom seul
     * suffisait à faire passer la garde.
     */
    @Test
    void seedDeDemo_devraitRefuserDeDemarrer_sousLeProfilRailway() {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(reference, demo, true, "railway");

        assertThatThrownBy(runner::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUS DE SÉCURITÉ")
            // Le message doit nommer le profil trouvé : un refus de démarrage
            // n'est lu qu'une fois, et « profil de production détecté » n'indique
            // pas quel fichier de configuration ouvrir.
            .hasMessageContaining("railway");

        verify(demo, never()).run();
    }

    /** Le refus historique, qui doit rester. */
    @Test
    void seedDeDemo_devraitRefuserDeDemarrer_sousLeProfilProd() {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(reference, demo, true, "prod");

        assertThatThrownBy(runner::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUS DE SÉCURITÉ")
            .hasMessageContaining("prod");

        verify(demo, never()).run();
    }

    /**
     * Le profil de production n'a pas à être le seul actif pour être vu : la
     * garde doit le trouver dans la liste, pas seulement quand il la constitue
     * à lui tout seul. C'est la forme réelle d'un déploiement, où le profil de
     * la plateforme cohabite avec d'autres.
     */
    @Test
    void seedDeDemo_devraitRefuser_memeQuandLeProfilDeProductionNestPasLeSeulActif() {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(reference, demo, true, "railway", "metrics");

        assertThatThrownBy(runner::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("railway");

        verify(demo, never()).run();
    }

    /**
     * Les deux profils où le seed de démonstration est légitime. {@code staging}
     * est le cas qui compte : il appartient à l'ensemble « déploiement », celui
     * qui exige la clé JWT, et n'appartient délibérément pas à l'ensemble
     * « production », celui qui interdit le seed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "staging"})
    void seedDeDemo_devraitResterPermis(String profil) throws Exception {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(reference, demo, true, profil);

        assertThatCode(runner::run).doesNotThrowAnyException();

        verify(demo).run();
    }

    /**
     * Le drapeau éteint est le premier des deux verrous : sous un profil de
     * production avec {@code demo-data.enabled=false}, il n'y a rien à refuser
     * et le démarrage doit se poursuivre. Sans ce test, un refus posé trop haut
     * — sur le profil seul, sans regarder le drapeau — empêcherait la production
     * de démarrer du tout, et cela se découvrirait en déploiement.
     */
    @Test
    void drapeauEteintEnProduction_neDevraitRienRefuser() throws Exception {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(reference, demo, false, "railway");

        assertThatCode(runner::run).doesNotThrowAnyException();

        verify(demo, never()).run();
    }

    /**
     * La garde doit lire {@code Environment}, et non la chaîne
     * {@code spring.profiles.active}. Les deux ne disent pas la même chose : un
     * profil activé par {@code SPRING_PROFILES_ACTIVE}, par un
     * {@code spring.profiles.include} ou par un initialiseur de contexte est
     * actif sans figurer dans cette propriété. Ici l'environnement déclare
     * {@code railway} actif alors que la propriété affirme {@code dev} : une
     * implémentation qui découperait la chaîne laisserait passer le seed en
     * production.
     */
    @Test
    void garde_devraitLireLesProfilsActifsEtPasLaProprieteSpringProfilesActive() {
        ReferenceDataSeeder reference = mock(ReferenceDataSeeder.class);
        DemoDataSeeder demo = mock(DemoDataSeeder.class);

        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles("railway");
        environnement.setProperty("spring.profiles.active", "dev");

        SeedRunner runner = new SeedRunner(reference, demo, environnement);
        ReflectionTestUtils.setField(runner, "referenceDataEnabled", false);
        ReflectionTestUtils.setField(runner, "demoDataEnabled", true);

        assertThatThrownBy(runner::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("railway");

        verify(demo, never()).run();
    }

    /**
     * Le message de refus doit dire quoi faire. Un refus de démarrage qui
     * n'indique ni le drapeau ni le fichier oblige à fouiller la configuration
     * d'un déploiement qui est déjà à l'arrêt.
     */
    @Test
    void messageDeRefus_devraitNommerLeDrapeauEtLeProfil() {
        SeedRunner runner = runner(mock(ReferenceDataSeeder.class), mock(DemoDataSeeder.class),
            true, "railway");

        assertThatThrownBy(runner::run)
            .hasMessageContaining("pair.seed.demo-data.enabled")
            .hasMessageContaining("application-railway.properties");
    }

    /** Sans profil actif du tout, rien n'est de la production. */
    @Test
    void sansAucunProfilActif_leSeedResteAutorise() throws Exception {
        DemoDataSeeder demo = mock(DemoDataSeeder.class);
        SeedRunner runner = runner(mock(ReferenceDataSeeder.class), demo, true);

        assertThatCode(runner::run).doesNotThrowAnyException();

        verify(demo).run();
    }

    /**
     * <b>Le garde-fou de cette fiche ne vaut que si {@code SeedRunner} est le seul
     * appelant des seeders.</b> Il ne l'était pas : les deux seeders étaient
     * eux-mêmes des {@link CommandLineRunner} annotés {@code @Order}, donc Spring
     * appelait leur {@code run()} à chaque démarrage, <b>sans</b> consulter ni le
     * drapeau ni le profil. Tous les tests ci-dessus passaient pendant que la
     * production créait ses vingt comptes de démonstration.
     *
     * <p>Ce test échoue si quelqu'un remet l'interface. Il est volontairement
     * structurel et non comportemental : le comportement, lui, est déjà couvert —
     * c'est précisément ce qui l'avait rendu invisible.
     */
    @Test
    void lesSeeders_neDoiventPasEtreDesCommandLineRunner_pourQueLeGardeFouGouverne() {
        assertThat(CommandLineRunner.class.isAssignableFrom(DemoDataSeeder.class))
            .as("DemoDataSeeder appelé directement par Spring contournerait "
                + "le drapeau et le garde-fou de profil de SeedRunner")
            .isFalse();
        assertThat(CommandLineRunner.class.isAssignableFrom(ReferenceDataSeeder.class))
            .as("ReferenceDataSeeder appelé directement par Spring rendrait "
                + "pair.seed.reference-data.enabled inopérant")
            .isFalse();
        assertThat(CommandLineRunner.class.isAssignableFrom(SeedRunner.class))
            .as("SeedRunner doit rester le point d'entrée du seed")
            .isTrue();
    }

    /**
     * Monte le composant comme Spring le ferait : les collaborateurs par le
     * constructeur, les deux drapeaux par réflexion — ce sont des {@code @Value}
     * et il n'y a pas de contexte ici pour les résoudre.
     */
    private static SeedRunner runner(ReferenceDataSeeder reference, DemoDataSeeder demo,
                                     boolean demoDataEnabled, String... profils) {
        MockEnvironment environnement = new MockEnvironment();
        if (profils.length > 0) {
            environnement.setActiveProfiles(profils);
        }

        SeedRunner runner = new SeedRunner(reference, demo, environnement);
        // Le seeder de données de référence n'est pas le sujet : il tourne
        // partout, production comprise, et l'allumer ici n'apprendrait rien.
        ReflectionTestUtils.setField(runner, "referenceDataEnabled", false);
        ReflectionTestUtils.setField(runner, "demoDataEnabled", demoDataEnabled);
        return runner;
    }
}
