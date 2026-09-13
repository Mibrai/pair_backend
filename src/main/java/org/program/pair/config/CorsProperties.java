package org.program.pair.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Les origines web autorisées à appeler l'API depuis un navigateur (P-BS-18).
 *
 * <p><b>Vide par défaut, et vide en production</b> : décision du 13/09, aucune
 * origine web n'est autorisée en production — ni localhost, ni l'ancien front
 * Vercel. L'app native n'envoie pas d'en-tête {@code Origin} et n'est pas
 * soumise au CORS ; les pages publiques sont servies par le même hôte. Seul le
 * profil dev pose des origines, celles des fronts lancés sur le poste.
 *
 * <p>La même liste gouverne la vérification d'origine du WebSocket.
 */
@ConfigurationProperties("pair.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
