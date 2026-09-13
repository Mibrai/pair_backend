package org.program.pair.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Connecteur HTTP en clair (8091), à côté du HTTPS de développement.
 *
 * <p><b>Profils dev et railway.</b> P-BS-12 l'avait réservé au dev en le croyant
 * inutile en production : c'était faux. Le domaine personnalisé
 * {@code lien.meetdo.fun} est routé par Railway vers le port <b>8091</b>, et le
 * retirer a rendu 502 sur tous les liens publics, e-mails et liens universels
 * compris (incident du 13/09, 21:20 → correctif). Il reste absent sans profil et
 * sous staging. Ne pas le retirer de railway sans avoir d'abord changé le port
 * cible du domaine dans Railway.
 */
@Configuration
@Profile({"dev", "railway"})
public class HttpConnectorConfig {

    @Value("${server.http.port:8091}")
    private int httpPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnector() {
        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setScheme("http");
            connector.setPort(httpPort);
            connector.setSecure(false);
            factory.addAdditionalConnectors(connector);
        };
    }
}
