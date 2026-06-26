package org.program.pair.shared.sanitizer;

import org.springframework.stereotype.Component;

/**
 * Sanitizer simple pour nettoyer le HTML et prévenir les attaques XSS
 * Version basique : supprime tous les tags HTML
 * TODO Phase 2: Utiliser OWASP Java HTML Sanitizer pour une protection complète
 */
@Component
public class HtmlSanitizer {

    /**
     * Nettoie une chaîne en supprimant tout HTML potentiellement dangereux
     * Version simplifiée : supprime tous les tags HTML
     */
    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // Supprimer tous les tags HTML
        String sanitized = input.replaceAll("<[^>]*>", "");

        // Décoder les entités HTML courantes
        sanitized = sanitized
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/");

        // Re-encoder pour la sécurité
        sanitized = sanitized
            .replace("<", "&lt;")
            .replace(">", "&gt;");

        return sanitized.trim();
    }
}
