package org.program.pair.domain.publicslot;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ce qu'une page publique de créneau montre — et rien d'autre.
 *
 * <p>Type fermé, pour la même raison que {@code SafetyShareView} : réutiliser
 * {@code SlotFeedItemDto} publierait les identifiants de l'organisateur, de son
 * activité et de sa conversation. Sur une page ouverte sans compte, ce serait
 * donner à qui reçoit un lien la clé d'objets — et de personnes — qu'il n'a pas
 * choisi de recevoir.
 *
 * <p><b>La règle, précisée le 2026-08-20.</b> Elle a d'abord été écrite « aucun
 * identifiant interne », ce qui était trop large et rendait la fonctionnalité
 * inopérante : le client résolvait le jeton en une description qu'il ne pouvait
 * afficher nulle part, faute d'adresse où aller. Le lien ouvrait l'application et
 * la laissait où elle était — sans erreur, donc sans que rien ne s'en plaigne.
 *
 * <p>La bonne règle distingue <b>ce que le lien partage</b> de <b>ce qu'il ne
 * partage pas</b> : l'identifiant du créneau est précisément l'objet du partage,
 * et il n'est lisible qu'en présentant un jeton valide — donc par quelqu'un qui
 * voit déjà tout ce que la page montre. Ceux des tiers restent exclus, car ils
 * donnent prise sur des personnes que l'organisateur n'a pas partagées.
 *
 * <p>Ce que le garde-fou visait reste entier : l'identifiant n'apparaît
 * <b>jamais dans l'URL</b>. Une adresse bâtie sur la clé primaire s'énumère, et
 * l'on remonte la base en incrémentant ; c'est le jeton opaque qui adresse.
 *
 * <p>Interdit, et toujours absent : e-mail, téléphone, identifiant d'utilisateur
 * ou de conversation, coordonnées exactes d'un lieu privé, liste des participants.
 */
public record PublicSlotView(

    @Schema(description = "Identifiant du créneau partagé, pour ouvrir sa fiche dans "
        + "l'application. Présent depuis le 2026-08-20 : sans lui, un lien ouvrait "
        + "l'application sans pouvoir la mener nulle part. Il n'adresse rien sur le web "
        + "ouvert — c'est le jeton qui adresse — et n'est lisible qu'en présentant ce jeton.")
    java.util.UUID scheduleId,

    String programTitle,
    String activityName,
    String categoryName,

    /**
     * Couleur de la catégorie ({@code #RRGGBB}). Sert la vignette de repli et
     * l'habillage de la page ; une couleur ne révèle rien de personne.
     */
    String categoryColorRamp,

    /**
     * Langue annoncée de la séance, si l'organisateur l'a renseignée. Elle fixe
     * la langue de la page — c'est celle dans laquelle la séance se tiendra, donc
     * celle du lecteur visé, mieux que l'{@code Accept-Language} d'un appareil
     * qui n'appartient peut-être pas à quelqu'un du coin.
     */
    String primaryLanguage,

    Instant startsAt,
    Instant endsAt,

    /** Nom du lieu. L'adresse exacte n'y figure que si elle est déjà diffusable. */
    String placeName,
    String city,

    /**
     * Adresse, seulement lorsqu'elle est diffusable sans connaître le demandeur.
     * Nulle pour un lieu privé non partagé — c'est {@code broadcastableAddress}
     * qui tranche, la même règle que pour une notification envoyée à plusieurs.
     */
    String displayAddress,

    Integer participantCount,
    Integer maxParticipants,
    String welcomeNote,

    String organizerGivenName,
    boolean organizerVerified,

    /** Vrai si le créneau porte une image ; l'URL est composée par la page. */
    boolean hasImage
) {}
