package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le fichier de configuration de la production dit vraiment.
 *
 * <p><b>Pourquoi lire le fichier, et pas se contenter d'un test de
 * comportement.</b> {@code application-railway.properties} posait
 * {@code pair.seed.demo-data.enabled=true}, et railway est la production : c'est
 * cette seule ligne qui a fait créer vingt comptes de démonstration au mot de
 * passe publié sur la base réelle (fiche d'audit P-BS-02). Un test qui ne
 * vérifierait que le garde-fou de {@code SeedRunner} passerait au vert avec
 * cette ligne remise à {@code true} — le garde-fou ferait alors échouer le
 * démarrage, certes, mais on ne l'apprendrait qu'au déploiement, sur une
 * production à l'arrêt. Il faut donc que le retour de la ligne échoue <b>ici</b>,
 * dans la suite, avant d'être poussé.
 *
 * <p>{@code PropertiesLoaderUtils} lit la ressource telle qu'elle est, sans
 * contexte Spring, sans résolution de variables et sans hiérarchie de profils :
 * le fichier est éprouvé pour lui-même, ce qui est exactement l'objet.
 *
 * <p>Le reste de la classe verrouille l'invariant sur lequel tout repose : les
 * deux ensembles de {@link Profils} ne doivent pas se confondre. {@code staging}
 * appartient à {@code DEPLOIEMENT} — l'absence de clé JWT y est une erreur de
 * démarrage — et n'appartient pas à {@code PRODUCTION} — le seed de
 * démonstration y est voulu. Fusionner les deux casserait l'un ou l'autre.
 */
class ProfilRailwayTest {

    private static final String DRAPEAU_SEED_DEMO = "pair.seed.demo-data.enabled";

    /**
     * Le test qui empêche la rechute : le profil de la production n'active pas
     * le seed de démonstration, et ce fichier-là le dit noir sur blanc.
     */
    @Test
    void leProfilRailway_neDoitPasActiverLeSeedDeDemo() throws IOException {
        Properties railway = charger("application-railway.properties");

        assertThat(railway.getProperty(DRAPEAU_SEED_DEMO))
            .as("%s dans application-railway.properties — railway EST la production, "
                + "et y allumer le seed de démonstration y crée des comptes vérifiés "
                + "au mot de passe commun", DRAPEAU_SEED_DEMO)
            .isEqualTo("false");
    }

    /**
     * Le mot de passe de démonstration ne doit réapparaître nulle part dans la
     * configuration de la production, sous aucune forme : ni valeur, ni défaut
     * de variable d'environnement. La propriété n'y a rien à faire du tout,
     * puisque le seed y est éteint.
     */
    @Test
    void leProfilRailway_neDoitPorterAucunMotDePasseDeDemo() throws IOException {
        Properties railway = charger("application-railway.properties");

        assertThat(railway.getProperty("pair.seed.demo-password"))
            .as("aucun mot de passe de démonstration dans la configuration de production")
            .isNull();
    }

    /**
     * {@code application.properties} ne doit pas déclarer le mot de passe : le
     * dépôt est public, et un défaut posé là serait un mot de passe publié —
     * exactement le défaut que la fiche ferme, déplacé d'un fichier à l'autre.
     */
    @Test
    void laConfigurationCommune_neDoitPasDeclarerLeMotDePasseDeDemo() throws IOException {
        Properties communes = charger("application.properties");

        assertThat(communes.getProperty("pair.seed.demo-password"))
            .as("pair.seed.demo-password doit rester absente de application.properties")
            .isNull();
        assertThat(communes.getProperty(DRAPEAU_SEED_DEMO)).isEqualTo("false");
    }

    /**
     * Staging garde {@code true}, et c'est voulu : c'est l'environnement fait
     * pour ces comptes. Ce test est là pour que personne ne « corrige » staging
     * en croyant prolonger le correctif de production — et pour rendre visible,
     * si quelqu'un le change, qu'il l'a fait exprès.
     */
    @Test
    void leProfilStaging_gardeLeSeedDeDemo_etCestVoulu() throws IOException {
        Properties staging = charger("application-staging.properties");

        assertThat(staging.getProperty(DRAPEAU_SEED_DEMO)).isEqualTo("true");
        // Et il le reçoit par l'environnement, jamais par le dépôt.
        assertThat(staging.getProperty("pair.seed.demo-password"))
            .isEqualTo("${PAIR_SEED_DEMO_PASSWORD:}");
    }

    /**
     * L'invariant des deux ensembles. {@code railway} manquait à l'ensemble de
     * production, et ce manque est toute l'histoire de cette fiche.
     */
    @Test
    void lesDeuxEnsemblesDeProfils_neDoiventPasSeConfondre() {
        assertThat(Profils.PRODUCTION).containsExactlyInAnyOrder("prod", "railway");
        assertThat(Profils.DEPLOIEMENT).containsExactlyInAnyOrder("prod", "railway", "staging");

        // Le seed est interdit là où il y a de vraies données...
        assertThat(Profils.PRODUCTION).doesNotContain("staging");
        // ...mais la clé JWT est exigée partout où c'est déployé, staging inclus.
        assertThat(Profils.DEPLOIEMENT).contains("staging");
        assertThat(Profils.DEPLOIEMENT).containsAll(Profils.PRODUCTION);
    }

    /**
     * {@code actif} cherche dans la liste entière, pas seulement le premier
     * profil, et ne se laisse pas prendre par un nom voisin : {@code production}
     * n'est pas {@code prod}, et une comparaison approximative rendrait la garde
     * imprévisible dans les deux sens.
     */
    @Test
    void actif_doitReconnaitreUnProfilDeLEnsembleOuAucun() {
        assertThat(Profils.actif(environnement("railway"), Profils.PRODUCTION)).isTrue();
        assertThat(Profils.actif(environnement("metrics", "prod"), Profils.PRODUCTION)).isTrue();
        assertThat(Profils.actif(environnement("dev"), Profils.PRODUCTION)).isFalse();
        assertThat(Profils.actif(environnement("staging"), Profils.PRODUCTION)).isFalse();
        assertThat(Profils.actif(environnement("staging"), Profils.DEPLOIEMENT)).isTrue();
        assertThat(Profils.actif(new MockEnvironment(), Profils.PRODUCTION)).isFalse();
        assertThat(Profils.actif(environnement("production"), Profils.PRODUCTION))
            .as("« production » n'est pas le profil « prod »")
            .isFalse();
    }

    /** Le nom rendu est celui qui sert au message de refus. */
    @Test
    void premierProfilActif_doitNommerLeProfilTrouve() {
        assertThat(Profils.premierProfilActif(environnement("metrics", "railway"), Profils.PRODUCTION))
            .isEqualTo("railway");
        assertThat(Profils.premierProfilActif(environnement("dev"), Profils.PRODUCTION))
            .isNull();
    }

    private static MockEnvironment environnement(String... profils) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles(profils);
        return environnement;
    }

    /**
     * Lit un fichier de propriétés du classpath principal tel qu'il est écrit.
     * Aucune substitution de variable, aucune hiérarchie de profils : c'est le
     * contenu du fichier qui est éprouvé, pas la configuration résolue.
     */
    private static Properties charger(String nom) throws IOException {
        ClassPathResource ressource = new ClassPathResource(nom);
        assertThat(ressource.exists()).as("%s doit être sur le classpath", nom).isTrue();
        return PropertiesLoaderUtils.loadProperties(ressource);
    }
}
