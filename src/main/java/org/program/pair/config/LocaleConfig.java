package org.program.pair.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Résolution de la langue des textes destinés à l'utilisateur final.
 *
 * <p>Trois langues de plein droit : {@code fr}, {@code en}, {@code de}. Elles
 * couvrent les textes que le serveur rédige et que le client affiche verbatim —
 * question de clarification, suggestions d'action sur un résultat vide, et
 * message des refus métier. Les valeurs d'énumération
 * ({@code resultType}, {@code type}, {@code status}, les codes d'erreur) ne
 * passent jamais par ici : elles sont parsées par le client et restent en
 * anglais SCREAMING_SNAKE_CASE.
 */
@Configuration
public class LocaleConfig {

    public static final Locale FRENCH = Locale.forLanguageTag("fr");
    public static final Locale ENGLISH = Locale.forLanguageTag("en");
    public static final Locale GERMAN = Locale.forLanguageTag("de");

    static final List<Locale> SUPPORTED = List.of(FRENCH, ENGLISH, GERMAN);

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Sans ça, une clé absente de messages_de.properties irait chercher la
        // langue de la JVM du serveur — c'est-à-dire n'importe laquelle selon
        // l'hôte. Le repli doit être le français, de façon déterministe.
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(FRENCH);
        return source;
    }

    /**
     * Deux règles distinctes, demandées explicitement par le client mobile :
     *
     * <ul>
     *   <li><b>en-tête absent</b> → {@code fr}. C'est un binaire meetDo déjà
     *       déployé — aucun ne pose l'en-tête aujourd'hui — et sa
     *       non-régression est le français ;</li>
     *   <li><b>en-tête présent mais hors des trois langues</b> → {@code en}.
     *       C'est un appareil réel dont l'utilisateur ne lit pas le français ;
     *       l'anglais est le meilleur repli disponible.</li>
     * </ul>
     *
     * <p>Ce n'est pas le comportement par défaut de Spring, qui retombe sur la
     * locale par défaut dans les deux cas. D'où ce resolver plutôt qu'un
     * {@code AcceptHeaderLocaleResolver} configuré.
     */
    @Bean
    public LocaleResolver localeResolver() {
        return new AcceptLanguageLocaleResolver();
    }

    static class AcceptLanguageLocaleResolver implements LocaleResolver {

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            return resolve(request.getHeader("Accept-Language"));
        }

        @Override
        public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            throw new UnsupportedOperationException(
                "La langue vient de l'en-tête Accept-Language, elle ne se fixe pas côté serveur.");
        }

        static Locale resolve(String headerValue) {
            if (headerValue == null || headerValue.isBlank()) {
                return FRENCH;
            }
            try {
                Locale match = Locale.lookup(Locale.LanguageRange.parse(headerValue), SUPPORTED);
                // En-tête présent : même illisible ou sans langue connue, ce
                // n'est pas un client historique — l'anglais vaut mieux que le
                // français pour quelqu'un qui a demandé autre chose.
                return match != null ? match : ENGLISH;
            } catch (IllegalArgumentException malformed) {
                return ENGLISH;
            }
        }
    }
}
