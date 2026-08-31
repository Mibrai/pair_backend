package org.program.pair.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le poivre : ce qu'il protège, et ce qu'il refuse de laisser passer.
 *
 * <p>La moitié de ces tests portent sur des refus au démarrage. C'est voulu :
 * la valeur de cette classe tient moins dans le HMAC qu'elle calcule — trois
 * lignes — que dans les configurations qu'elle refuse. Une clé absente, trop
 * courte ou introuvable en base ne se remarque pas à l'exécution : les
 * empreintes se calculent, la route rend 200, et l'on ne découvre le problème
 * qu'en fuitant la base.
 */
class PepperTest {

    private static final String CLE_A = base64(32, (byte) 0x11);
    private static final String CLE_B = base64(32, (byte) 0x22);

    // ---------------------------------------------------------------- calcul

    @Test
    void laMemeEntree_doitDonnerLaMemeEmpreinte() {
        Pepper poivre = poivre("1:" + CLE_A, 1, "test");
        byte[] sel = poivre.nouveauSel();

        assertThat(poivre.empreinte("A7K2M", sel))
            .isEqualTo(poivre.empreinte("A7K2M", sel));
    }

    /**
     * Le sel est ce qui empêche de reconnaître deux fois le même code d'un coup
     * d'œil à la base, et de constituer une table des codes les plus fréquents.
     */
    @Test
    void deuxSels_doiventDonnerDeuxEmpreintesPourLeMemeCode() {
        Pepper poivre = poivre("1:" + CLE_A, 1, "test");

        assertThat(poivre.empreinte("A7K2M", poivre.nouveauSel()))
            .isNotEqualTo(poivre.empreinte("A7K2M", poivre.nouveauSel()));
    }

    /**
     * Le cœur de l'affaire : sans la clé, le sel ne sert à rien à l'attaquant.
     * Deux serveurs au même sel et à la clé différente ne produisent pas la même
     * empreinte — c'est ce qui rend une base fuitée seule inexploitable.
     */
    @Test
    void deuxClesDifferentes_neDoiventPasProduireLaMemeEmpreinte() {
        Pepper avecA = poivre("1:" + CLE_A, 1, "test");
        Pepper avecB = poivre("1:" + CLE_B, 1, "test");
        byte[] sel = avecA.nouveauSel();

        assertThat(avecA.empreinte("A7K2M", sel))
            .isNotEqualTo(avecB.empreinte("A7K2M", sel));
    }

    @Test
    void leBonSecret_doitCorrespondre() {
        Pepper poivre = poivre("1:" + CLE_A, 1, "test");
        byte[] sel = poivre.nouveauSel();
        String empreinte = poivre.empreinte("A7K2M", sel);

        assertThat(poivre.correspond("A7K2M", sel, 1, empreinte)).isTrue();
        assertThat(poivre.correspond("A7K2N", sel, 1, empreinte)).isFalse();
    }

    /**
     * Un code de contrainte non enregistré rend {@code false} sans lever — et,
     * ce que ce test ne peut pas mesurer mais que le code garantit, en ayant
     * calculé l'empreinte quand même : le temps de réponse ne doit pas
     * distinguer les comptes qui ont un code de contrainte de ceux qui n'en ont
     * pas.
     */
    @Test
    void uneEmpreinteAbsente_doitRendreFauxSansLever() {
        Pepper poivre = poivre("1:" + CLE_A, 1, "test");
        byte[] sel = poivre.nouveauSel();

        assertThat(poivre.correspond("A7K2M", sel, 1, null)).isFalse();
    }

    // -------------------------------------------------------------- rotation

    /**
     * Ce qui rend la rotation possible : une empreinte écrite sous l'ancienne
     * clé se vérifie encore après le passage à la nouvelle. Sans cela, tourner
     * la clé lèverait toutes les veilles en cours d'un coup — ou pire, les
     * rendrait inlevables.
     */
    @Test
    void uneEmpreinteEcriteEnV1_doitSeVerifierApresPassageEnV2() {
        Pepper avant = poivre("1:" + CLE_A, 1, "test");
        byte[] sel = avant.nouveauSel();
        String empreinteV1 = avant.empreinte("A7K2M", sel);

        Pepper apres = poivre("1:" + CLE_A + ",2:" + CLE_B, 2, "test");

        assertThat(apres.versionCourante()).isEqualTo(2);
        assertThat(apres.correspond("A7K2M", sel, 1, empreinteV1)).isTrue();
        assertThat(apres.empreinte("A7K2M", sel)).isNotEqualTo(empreinteV1);
    }

    /**
     * Une clé retirée alors que des lignes la référencent encore : on lève, et
     * on dit pourquoi. Retomber en silence sur la clé courante rendrait
     * « code faux » pour un code juste, ce qui ferait partir une alerte chez le
     * contact d'une personne rentrée chez elle.
     */
    @Test
    void uneVersionDeCleRetiree_doitLeverEnLexpliquant() {
        Pepper poivre = poivre("1:" + CLE_A, 1, "test");

        assertThatThrownBy(() -> poivre.empreinte("A7K2M", poivre.nouveauSel(), 7))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("version 7")
            .hasMessageContaining("plus aucune ligne ne la référence");
    }

    // ------------------------------------------------------------- démarrage

    /**
     * Le refus qui compte le plus. Une clé de repli inscrite dans le dépôt
     * donnerait l'apparence d'une configuration correcte tout en laissant une
     * base fuitée entièrement énumérable par quiconque a lu le dépôt.
     */
    @Test
    void sansCleSousUnProfilDeDeploiement_leDemarrageDoitEchouer() {
        for (String profil : new String[] {"prod", "railway", "staging"}) {
            assertThatThrownBy(() -> poivre("", 1, profil))
                .as("profil %s", profil)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY_PEPPER_KEYS");
        }
    }

    /**
     * Hors déploiement, une clé éphémère est tirée : le développement n'a pas à
     * poser une variable d'environnement pour lancer l'application, et les
     * empreintes qui ne survivent pas au redémarrage sont sans conséquence.
     */
    @Test
    void sansCleEnDeveloppement_uneCleEphemereDoitEtreTiree() {
        assertThatCode(() -> {
            Pepper poivre = poivre("", 1, "dev");
            poivre.empreinte("A7K2M", poivre.nouveauSel());
        }).doesNotThrowAnyException();
    }

    /**
     * Deux démarrages sans clé configurée ne partagent rien. C'est la propriété
     * qui rend la clé éphémère inoffensive : elle n'est jamais la même, donc
     * elle ne peut pas se retrouver en production par mégarde.
     */
    @Test
    void deuxDemarragesEphemeres_neDoiventPasPartagerDeCle() {
        Pepper premier = poivre("", 1, "dev");
        Pepper second = poivre("", 1, "dev");
        byte[] sel = premier.nouveauSel();

        assertThat(premier.empreinte("A7K2M", sel))
            .isNotEqualTo(second.empreinte("A7K2M", sel));
    }

    /**
     * Avancer {@code current-version} sans ajouter la clé correspondante est
     * l'erreur de rotation la plus facile à commettre. Elle doit arrêter le
     * démarrage, pas se découvrir à la première veille armée.
     */
    @Test
    void uneVersionCouranteSansCle_doitEmpecherLeDemarrage() {
        assertThatThrownBy(() -> poivre("1:" + CLE_A, 2, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("current-version");
    }

    /**
     * Une clé plus courte que l'empreinte qu'elle produit affaiblit le HMAC sans
     * que rien ne le signale : le calcul fonctionne, les empreintes se
     * ressemblent, et la protection vaut moins qu'annoncé.
     */
    @Test
    void uneCleTropCourte_doitEtreRefusee() {
        assertThatThrownBy(() -> poivre("1:" + base64(16, (byte) 0x33), 1, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 octets");
    }

    @Test
    void uneCleMalFormee_doitEtreRefuseeEnLeDisant() {
        assertThatThrownBy(() -> poivre("pas-de-deux-points", 1, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mal formée");

        assertThatThrownBy(() -> poivre("1:ceci-n-est-pas-du-base64!!", 1, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("base64");

        assertThatThrownBy(() -> poivre("un:" + CLE_A, 1, "prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non numérique");
    }

    // ---------------------------------------------------------------- outils

    /** Monte le composant comme Spring le ferait, avec le profil demandé actif. */
    private static Pepper poivre(String cles, int versionCourante, String profil) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setProperty("spring.profiles.active", profil);
        environnement.setActiveProfiles(profil);

        Pepper poivre = new Pepper(environnement);
        // Les clés passent telles quelles, préfixe de version compris. Un helper
        // qui ajouterait « 1: » quand il n'y en a pas rendrait intestable le cas
        // de l'entrée sans deux-points — c'est-à-dire le seul cas où l'absence
        // de préfixe est justement ce qu'on veut vérifier.
        ReflectionTestUtils.setField(poivre, "clesBrutes", cles);
        ReflectionTestUtils.setField(poivre, "versionCourante", versionCourante);
        poivre.charger();
        return poivre;
    }

    private static String base64(int octets, byte remplissage) {
        byte[] cle = new byte[octets];
        java.util.Arrays.fill(cle, remplissage);
        return Base64.getEncoder().encodeToString(cle);
    }
}
