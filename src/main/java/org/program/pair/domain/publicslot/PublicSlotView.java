package org.program.pair.domain.publicslot;

import java.time.Instant;

/**
 * Ce qu'une page publique de créneau montre — et rien d'autre.
 *
 * <p>Type fermé, pour la même raison que {@code SafetyShareView} : réutiliser
 * {@code SlotFeedItemDto} publierait quatre identifiants internes et un
 * {@code UserPublicDto}, lequel porte l'UUID de l'organisateur. Sur une page
 * ouverte sans compte, ce serait donner à qui reçoit un lien la clé d'objets
 * qu'il n'a pas le droit de manipuler.
 *
 * <p>Autorisé par la spécification, et présent ici : titre, activité, catégorie,
 * date, nom du lieu, ville, nombre de participants, mot d'accueil, prénom et
 * avatar de l'organisateur, badge vérifié, image.
 *
 * <p>Interdit, et absent : e-mail, téléphone, UUID utilisateur, coordonnées
 * exactes d'un lieu privé, liste des participants.
 */
public record PublicSlotView(

    String programTitle,
    String activityName,
    String categoryName,

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
