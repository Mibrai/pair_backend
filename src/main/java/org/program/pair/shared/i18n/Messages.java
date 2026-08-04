package org.program.pair.shared.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * Accès aux textes traduits, dans la langue de la requête en cours.
 *
 * <p>La langue est celle résolue par
 * {@code LocaleConfig.AcceptLanguageLocaleResolver} et déposée par Spring MVC
 * dans le {@link LocaleContextHolder}.
 */
@Component
@RequiredArgsConstructor
public class Messages {

    private final MessageSource messageSource;

    /** Texte de la clé, dans la langue de la requête. */
    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /**
     * Texte de la clé dans une langue imposée, indépendamment de la requête.
     *
     * <p>Réservé au cas où la langue est déduite d'autre chose que de
     * l'en-tête — aujourd'hui, la seule heuristique restante : les mots de la
     * requête de recherche, quand aucun {@code Accept-Language} n'a été posé.
     */
    public String getIn(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    /** Texte de la clé, ou {@code null} si elle n'existe pas. */
    public String getOrNull(String key, Object... args) {
        return messageSource.getMessage(key, args, null, LocaleContextHolder.getLocale());
    }

    /**
     * Vrai si l'appelant a explicitement demandé une langue.
     *
     * <p>Sert au seul endroit où « en-tête absent » ne veut pas dire « français »
     * mais « comportement d'avant » : la question de clarification de
     * {@code POST /search}, qui devinait déjà la langue à partir des mots de la
     * requête. Basculer ce cas sur le français ferait régresser des utilisateurs
     * germanophones et anglophones qui reçoivent aujourd'hui leur langue.
     *
     * <p>Hors contexte web (tâches planifiées, tests unitaires), renvoie
     * {@code false} : il n'y a pas d'appelant à qui demander.
     */
    public boolean localeWasRequested() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return false;
        }
        String header = servletAttributes.getRequest().getHeader("Accept-Language");
        return header != null && !header.isBlank();
    }
}
