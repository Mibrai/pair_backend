package org.program.pair.domain.publicslot;

import java.util.List;
import java.util.Locale;

/**
 * Les robots d'aperçu des messageries, reconnus à leur {@code User-Agent}.
 *
 * <p>Ils sont la raison d'être de la page publique — c'est leur visite qui
 * fabrique la vignette collée dans une conversation — et c'est précisément
 * pourquoi ils ne doivent pas compter comme des ouvertures. Un seul lien partagé
 * dans un groupe WhatsApp en déclenche plusieurs, avant que quiconque n'ait
 * cliqué : compté brut, le chiffre dirait que le partage fonctionne alors que
 * personne n'a rien ouvert, et c'est sur ce chiffre qu'on arbitrera les
 * investissements d'acquisition.
 *
 * <p><b>La liste est forcément incomplète, et le compteur reste indicatif.</b>
 * Elle couvre les émetteurs cités par la spécification et les plus courants ;
 * un robot inconnu passera. C'est un dégrossissage, pas une mesure d'audience —
 * les caches des messageries faussent le chiffre dans l'autre sens de toute
 * façon, une vignette servie depuis un cache ne repassant jamais ici.
 */
public final class PreviewBots {

    /**
     * Fragments cherchés en minuscules dans le {@code User-Agent}.
     *
     * <p>Une recherche de sous-chaîne et non une égalité : ces agents portent
     * presque tous un numéro de version et une URL de contact qui changent sans
     * préavis, et une liste de valeurs exactes serait périmée au premier
     * déploiement de leur côté.
     */
    private static final List<String> FRAGMENTS = List.of(
        "facebookexternalhit",  // Facebook, Messenger, Instagram
        "whatsapp",
        "twitterbot",
        "telegrambot",
        "discordbot",
        "slackbot",
        "linkedinbot",
        "pinterest",
        "redditbot",
        "skypeuripreview",
        "applebot",             // aperçus iMessage et Safari
        "googlebot",
        "bingbot",
        "embedly",
        "vkshare",
        "bot",                  // filet large, volontairement en dernier
        "crawler",
        "spider",
        "preview"
    );

    private PreviewBots() {
    }

    /**
     * Vrai pour un agent qu'on ne veut pas compter.
     *
     * <p>Un {@code User-Agent} absent est traité comme un robot. C'est le choix
     * prudent dans le sens qui compte : un navigateur en envoie toujours un, et
     * ce qui n'en envoie pas est un outil. Se tromper ici fait manquer une vue,
     * jamais en inventer une — et un compteur qui exagère est plus nuisible
     * qu'un compteur qui sous-estime, puisqu'il donne confiance.
     */
    public static boolean isPreviewBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        return FRAGMENTS.stream().anyMatch(agent::contains);
    }
}
