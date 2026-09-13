package org.program.pair.config;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L'adresse du client, telle qu'un proxy <b>de confiance</b> l'établit — et non
 * telle que le client l'annonce.
 *
 * <p><b>Inactive à dessein.</b> Aucun bean n'est créé tant que
 * {@code pair.proxy-de-confiance.plages} n'est pas posée, et elle ne l'est nulle
 * part : le comportement actuel est conservé à l'octet. Ce qui manque n'est pas du
 * code, c'est un <b>relevé d'exploitation</b> — voir plus bas.
 *
 * <h2>Le défaut que cette classe existe pour refermer</h2>
 *
 * <p>Point (a) de la fiche d'audit P-BS-08. {@code application-railway.properties}
 * pose {@code server.forward-headers-strategy=framework}, ce qui installe le
 * {@code ForwardedHeaderFilter} de Spring. Ce filtre réécrit
 * {@code getRemoteAddr()} avec la <b>première</b> valeur de
 * {@code X-Forwarded-For} et <b>ne vérifie pas de qui elle vient</b> : c'est une
 * convention de composition d'URL, pas un mécanisme de sécurité, et sa
 * documentation le dit. Si l'arête Railway <i>ajoute</i> sa valeur au lieu de
 * <i>remplacer</i> l'en-tête reçu, la première valeur est celle que le client a
 * écrite lui-même. Tous les plafonds par adresse de
 * {@code shared/security/RateLimiter} se contournent alors avec un en-tête
 * différent à chaque requête.
 *
 * <h2>Ce que fait la valve, et pourquoi elle est le bon outil</h2>
 *
 * <p>{@link RemoteIpValve} parcourt {@code X-Forwarded-For} <b>de droite à
 * gauche</b> et s'arrête au premier bond qui n'appartient pas aux plages de
 * confiance. L'adresse retenue est donc la dernière que le client n'a pas pu
 * écrire : ce que le client annonce en tête de liste est ignoré, puisque le
 * parcours ne va jamais jusque-là. {@code protocol-header} fait le même travail
 * pour {@code X-Forwarded-Proto}, si bien que {@code request.isSecure()} reste
 * juste sans que personne n'ait à faire confiance à un en-tête arbitraire.
 *
 * <h2>Ce qu'il manque : un relevé, pas une décision de conception</h2>
 *
 * <p>Étape 1 de la fiche, et <b>elle n'a pas été faite</b> : il faut envoyer une
 * requête de diagnostic en production avec un {@code X-Forwarded-For} choisi, lire
 * les journaux Railway, et apprendre deux choses — si l'arête ajoute ou remplace,
 * et depuis <b>quelles plages</b> elle parle à l'application.
 *
 * <p><b>Deviner ces plages serait pire que de ne rien poser.</b> Une plage trop
 * étroite fait ignorer l'en-tête légitime : chaque requête est alors comptée à
 * l'adresse de l'arête, donc toutes ensemble, et le plafond de cinquante échecs
 * par adresse devient un plafond de cinquante échecs pour <b>toute la
 * plateforme</b> — des 429 en masse sur la connexion, pour des gens qui n'ont rien
 * fait. Une plage trop large rouvre le défaut qu'on referme. C'est pour cela que
 * cette classe attend une valeur et n'en propose aucune par défaut.
 *
 * <h2>Les lignes à poser dans {@code application-railway.properties}, le relevé fait</h2>
 *
 * <pre>
 * # L'adresse vue est celle qu'un proxy de confiance établit (fiche P-BS-08).
 * # Les deux lignes vont ENSEMBLE : voir config/ProxyDeConfiance.
 * server.forward-headers-strategy=none
 * pair.proxy-de-confiance.plages=&lt;plages relevées à l'étape 1&gt;
 * </pre>
 *
 * <p><b>Les deux lignes vont ensemble, et l'ordre des effets compte.</b> La valve
 * tourne avant les filtres et réécrit {@code X-Forwarded-For} en n'y laissant que
 * les bonds qu'elle n'a pas voulu croire. Si {@code forward-headers-strategy}
 * restait à {@code framework}, le filtre de Spring lirait ce reliquat — c'est-à-dire
 * précisément la partie que le client a pu écrire — et réécrirait
 * {@code getRemoteAddr()} avec elle : le défaut serait intact, valve ou pas.
 *
 * <p><b>Et {@code none} ne doit pas être posé seul.</b> Sans la valve, l'adresse
 * vue est celle de l'arête Railway, la même pour tout le monde : c'est l'effondrement
 * en masse décrit ci-dessus. Poser une des deux lignes sans l'autre est un défaut
 * dans les deux sens.
 *
 * <p>Ce que ces lignes ne changent pas : les liens de partage. Les {@code og:url}
 * des pages publiques sont composés sur {@code pair.public.base-url}
 * (« https://lien.meetdo.fun »), jamais sur la requête — contrairement à ce que
 * laissait croire le commentaire de {@code application-railway.properties}, qui
 * donne les liens universels iOS comme la raison d'être de
 * {@code forward-headers-strategy}. Aucune URL absolue de ce dépôt n'est bâtie sur
 * la requête entrante. Un test le tient : {@code PublicSlotPageIntegrationTest}
 * §« le partage reste en https », qui envoie {@code X-Forwarded-Proto: http} et un
 * hôte annoncé faux, et relit {@code og:url}.
 *
 * <p>La valeur candidate est {@link #PLAGES_PRIVEES_RFC1918_ET_CGNAT}, à confirmer
 * et non à croire : réseau privé RFC 1918 en 10.x et plage partagée de fournisseur
 * (RFC 6598, 100.64.0.0/10), qui est ce qu'un réseau interne de plateforme utilise
 * d'ordinaire. Les plages de Railway ne sont pas documentées comme stables : le
 * relevé de l'étape 1 est à refaire après tout changement d'infrastructure.
 */
@Configuration
public class ProxyDeConfiance {

    /**
     * Le candidat le plus probable, et rien de plus qu'un candidat.
     *
     * <p>Réseau privé RFC 1918 en 10.0.0.0/8 et plage partagée de fournisseur RFC
     * 6598 en 100.64.0.0/10 — les deux formes qu'un réseau interne de plateforme
     * prend presque toujours. À confirmer par le relevé de l'étape 1 : une plage
     * juste par déduction reste une plage devinée, et les conséquences d'une
     * erreur sont décrites dans la javadoc de la classe.
     *
     * <p>La forme est celle qu'attend {@code RemoteIpValve} : une expression
     * régulière sur l'adresse littérale, une alternative par plage. 172.16/12
     * n'y est pas, faute de raison de l'y mettre ; l'ajouter demanderait le même
     * relevé.
     */
    static final String PLAGES_PRIVEES_RFC1918_ET_CGNAT =
        "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
            + "|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d{1,3}\\.\\d{1,3}";

    /**
     * Installe la valve, et seulement si les plages sont connues.
     *
     * <p>Pas de valeur par défaut à la propriété : l'absence de bean <b>est</b> le
     * comportement actuel, et c'est ce qui rend ce fichier sûr à livrer avant le
     * relevé.
     */
    @Bean
    @ConditionalOnProperty(name = "pair.proxy-de-confiance.plages")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adresseReelleDuClient(
            @Value("${pair.proxy-de-confiance.plages}") String plages) {
        return factory -> factory.addEngineValves(valve(plages));
    }

    /**
     * La valve telle qu'elle serait installée.
     *
     * <p>Visible du paquet pour que {@code ProxyDeConfianceTest} éprouve la
     * décision de confiance sans démarrer de serveur : ce qui se teste ici, c'est
     * quelles adresses sont crues et lesquelles sont ignorées.
     */
    static RemoteIpValve valve(String plages) {
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(plages);
        valve.setRemoteIpHeader("x-forwarded-for");
        valve.setProtocolHeader("x-forwarded-proto");
        return valve;
    }
}
