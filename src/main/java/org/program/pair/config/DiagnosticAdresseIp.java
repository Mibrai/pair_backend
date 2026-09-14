package org.program.pair.config;

import jakarta.servlet.ServletException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * <b>TEMPORAIRE — à retirer après le relevé.</b> Étape 1 de la fiche P-BS-08.
 *
 * <p>Journalise, pour chaque {@code POST /api/auth/login}, ce que Tomcat reçoit
 * <b>avant</b> tout filtre : l'adresse du pair TCP (l'arête Railway) et les
 * en-têtes de proxy bruts. C'est ce qu'il faut savoir pour poser
 * {@code pair.proxy-de-confiance.plages} (voir {@link ProxyDeConfiance}) : si
 * l'arête ajoute ou remplace {@code X-Forwarded-For}, et depuis quelles plages
 * elle parle à l'application.
 *
 * <p>Une valve et non un journal dans le contrôleur : sur Railway,
 * {@code ForwardedHeaderFilter} a déjà remplacé {@code getRemoteAddr()} et retiré
 * les en-têtes {@code X-Forwarded-*} quand la requête atteint le contrôleur.
 *
 * <p>Inactive sans {@code pair.diagnostic.ip=true}. Les adresses clientes sont
 * masquées au dernier octet ; celle du pair TCP aussi, ce qui garde la plage.
 */
@Configuration
@ConditionalOnProperty(name = "pair.diagnostic.ip", havingValue = "true")
@Slf4j
public class DiagnosticAdresseIp {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> valveDeDiagnosticIp() {
        return factory -> factory.addEngineValves(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/api/auth/login")) {
                    String proxyHeaders = Collections.list(request.getHeaderNames()).stream()
                        .filter(nom -> {
                            String n = nom.toLowerCase();
                            return n.startsWith("x-forwarded") || n.startsWith("x-real")
                                || n.startsWith("x-envoy") || n.equals("forwarded")
                                || n.startsWith("cf-connecting") || n.startsWith("true-client");
                        })
                        .map(nom -> nom + "=" + masquer(Collections.list(request.getHeaders(nom))
                            .stream().collect(Collectors.joining(" ; "))))
                        .collect(Collectors.joining(" | "));
                    log.warn("[DIAGNOSTIC-IP] pair TCP={} port local={} en-têtes proxy : {}",
                        masquer(request.getRemoteAddr()), request.getLocalPort(), proxyHeaders);
                }
                getNext().invoke(request, response);
            }
        });
    }

    /** Masque le dernier octet de chaque adresse IPv4 d'une valeur, et la fin des IPv6. */
    static String masquer(String valeur) {
        if (valeur == null) {
            return null;
        }
        return valeur
            .replaceAll("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\b", "$1.x")
            .replaceAll("([0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}):[0-9a-fA-F:]+", "$1:…");
    }
}
