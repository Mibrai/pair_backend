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
 * <p><b>Profil dev seulement</b> (P-BS-12). Sans profil, il s'ouvrait partout — y
 * compris dans le conteneur Railway, où rien ne l'exposait mais où rien ne le
 * justifiait non plus. Hors dev, l'application n'écoute que son port principal.
 */
@Configuration
@Profile("dev")
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
