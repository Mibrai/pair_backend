package org.program.pair.config;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La confiance accordée au proxy, et à personne d'autre.
 *
 * <p>Point (a) de la fiche d'audit P-BS-08 : {@code getRemoteAddr()} derrière
 * {@code forward-headers-strategy=framework} rend la <b>première</b> valeur de
 * {@code X-Forwarded-For}, sans vérifier de qui elle vient. Si l'arête Railway
 * ajoute au lieu de remplacer, c'est le client qui la choisit — et tous les
 * plafonds par adresse deviennent gratuits.
 *
 * <p><b>Ce que cette classe éprouve, et ce qu'elle ne peut pas éprouver.</b> Elle
 * éprouve la <b>décision de confiance</b> : quelles adresses de pair autorisent à
 * croire l'en-tête, lesquelles ne l'autorisent pas, et que la valve est bien
 * configurée pour lire les deux en-têtes qui comptent. Elle n'éprouve pas le
 * parcours de la liste par Tomcat — c'est du code de Tomcat, couvert chez lui, et
 * le reproduire ici demanderait de fabriquer un {@code Connector} et une requête
 * interne, c'est-à-dire de tester notre imitation plutôt que la valve.
 *
 * <p>Elle éprouve enfin, et c'est le plus important aujourd'hui, que <b>la
 * configuration reste inactive</b> : le relevé d'exploitation de l'étape 1 n'a pas
 * été fait, et une plage devinée serait pire que pas de plage du tout — toutes les
 * requêtes seraient comptées à l'adresse de l'arête, donc ensemble, et cinquante
 * échecs de connexion refuseraient la plateforme entière.
 */
class ProxyDeConfianceTest {

    /** Les adresses que l'audit prend pour exemple d'un client qui annonce ce qu'il veut. */
    private static final String CLIENT_HORS_CONFIANCE = "198.51.100.1";
    private static final String ADRESSE_ANNONCEE = "203.0.113.9";

    @Test
    void laValve_doitEtreConfigureePourLesDeuxEnTetesQuiComptent() {
        RemoteIpValve valve = ProxyDeConfiance.valve(
            ProxyDeConfiance.PLAGES_PRIVEES_RFC1918_ET_CGNAT);

        // L'adresse, pour le limiteur de débit ; le protocole, pour que
        // request.isSecure() reste juste quand TLS est terminé en amont.
        assertThat(valve.getRemoteIpHeader()).isEqualTo("x-forwarded-for");
        assertThat(valve.getProtocolHeader()).isEqualTo("x-forwarded-proto");
        assertThat(valve.getInternalProxies())
            .isEqualTo(ProxyDeConfiance.PLAGES_PRIVEES_RFC1918_ET_CGNAT);
    }

    @Test
    void leCustomiseur_doitPoserLaValveSurLeServeur_avecLesPlagesRecues() {
        TomcatServletWebServerFactory fabrique = new TomcatServletWebServerFactory();

        new ProxyDeConfiance()
            .adresseReelleDuClient("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
            .customize(fabrique);

        List<String> plagesPosees = fabrique.getEngineValves().stream()
            .filter(RemoteIpValve.class::isInstance)
            .map(valve -> ((RemoteIpValve) valve).getInternalProxies())
            .toList();

        assertThat(plagesPosees)
            .as("une valve d'adresse réelle, et une seule, portant les plages reçues")
            .containsExactly("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    @Test
    void uneAdresseAnnonceeParUnClientHorsProxyDeConfiance_doitEtreIgnoree() {
        // C'est la seule question que la valve se pose avant de croire l'en-tête :
        // le pair qui vient de parler est-il dans les plages ? Un client public ne
        // l'est pas, et son X-Forwarded-For n'est donc jamais lu.
        assertThat(estDeConfiance(CLIENT_HORS_CONFIANCE)).isFalse();
        assertThat(estDeConfiance(ADRESSE_ANNONCEE)).isFalse();

        // Ni une adresse publique quelconque, ni une qui ressemble de loin aux
        // plages privées sans y être : 9.x n'est pas 10.x, et 100.128 est hors de
        // la plage partagée 100.64.0.0/10, qui s'arrête à 100.127.
        assertThat(estDeConfiance("9.10.0.5")).isFalse();
        assertThat(estDeConfiance("100.128.0.1")).isFalse();
        assertThat(estDeConfiance("110.0.0.1")).isFalse();
    }

    @Test
    void leProxyInterne_doitEtreCru_surLesPlagesCandidates() {
        // L'autre moitié de la même décision : si aucune adresse n'était de
        // confiance, l'en-tête ne serait jamais lu et l'adresse retenue serait
        // celle de l'arête — la même pour tout le monde, donc un plafond commun.
        assertThat(estDeConfiance("10.0.0.5")).isTrue();
        assertThat(estDeConfiance("10.255.255.254")).isTrue();
        assertThat(estDeConfiance("100.64.0.1")).isTrue();
        assertThat(estDeConfiance("100.127.255.254")).isTrue();
    }

    // — la configuration reste inactive tant que le relevé n'est pas fait —

    @Test
    void aucunProfil_neDoitPoserDePlages_avantLeReleveDExploitation() throws IOException {
        // Le relevé de l'étape 1 de la fiche demande un déploiement de diagnostic
        // et la lecture des journaux Railway. Il n'a pas été fait : la propriété
        // ne doit donc exister nulle part, et ce test est ce qui empêche qu'une
        // plage devinée se glisse dans un fichier de profil.
        for (String fichier : FICHIERS_DE_PROFIL) {
            assertThat(charger(fichier).getProperty(PROPRIETE_PLAGES))
                .as("%s dans %s — une plage devinée fait compter toutes les requêtes "
                    + "à l'adresse de l'arête, et cinquante échecs de connexion "
                    + "refusent alors la plateforme entière", PROPRIETE_PLAGES, fichier)
                .isNull();
        }
    }

    @Test
    void lesDeuxLignesDuProxy_doiventAllerEnsemble_dansChaqueProfil() throws IOException {
        // L'invariant que la javadoc de ProxyDeConfiance explique, rendu
        // vérifiable : la valve et la stratégie d'en-têtes se posent ensemble.
        //
        // Poser les plages en laissant « framework » ne referme rien : le filtre
        // de Spring lit le reliquat de X-Forwarded-For que la valve a laissé, qui
        // est précisément la partie écrite par le client. Poser « none » sans les
        // plages fait voir toutes les requêtes comme venant de l'arête.
        //
        // Ce test passe aujourd'hui parce qu'aucune des deux lignes n'y est —
        // sauf « framework » sur railway, qui est le point de départ. Il servira
        // le jour où quelqu'un en posera une.
        for (String fichier : FICHIERS_DE_PROFIL) {
            Properties profil = charger(fichier);
            String plages = profil.getProperty(PROPRIETE_PLAGES);
            String strategie = profil.getProperty("server.forward-headers-strategy");

            if (plages != null && !plages.isBlank()) {
                assertThat(strategie)
                    .as("%s pose des plages de proxy : la stratégie d'en-têtes doit "
                        + "passer à « none », sinon le filtre de Spring réécrit "
                        + "l'adresse avec ce que le client annonce", fichier)
                    .isEqualTo("none");
            }
            if ("none".equals(strategie)) {
                assertThat(plages)
                    .as("%s pose « none » sans plages de proxy : l'adresse vue serait "
                        + "celle de l'arête, la même pour tout le monde", fichier)
                    .isNotBlank();
            }
        }
    }

    private static final String PROPRIETE_PLAGES = "pair.proxy-de-confiance.plages";

    private static final List<String> FICHIERS_DE_PROFIL = List.of(
        "application.properties",
        "application-railway.properties",
        "application-staging.properties",
        "application-prod.properties",
        "application-dev.properties");

    /** La question que la valve pose au pair qui vient de parler, et rien d'autre. */
    private static boolean estDeConfiance(String adresse) {
        RemoteIpValve valve = ProxyDeConfiance.valve(
            ProxyDeConfiance.PLAGES_PRIVEES_RFC1918_ET_CGNAT);
        // La valve conserve l'expression telle quelle : on éprouve donc la plage
        // réellement configurée, pas une copie du littéral.
        return Pattern.matches(valve.getInternalProxies(), adresse);
    }

    /**
     * Lit un fichier de propriétés du classpath principal tel qu'il est écrit —
     * même procédé que {@code ProfilRailwayTest} : ni substitution de variable ni
     * hiérarchie de profils, c'est le fichier qui est éprouvé.
     */
    private static Properties charger(String nom) throws IOException {
        ClassPathResource ressource = new ClassPathResource(nom);
        assertThat(ressource.exists()).as("%s doit être sur le classpath", nom).isTrue();
        return PropertiesLoaderUtils.loadProperties(ressource);
    }
}
