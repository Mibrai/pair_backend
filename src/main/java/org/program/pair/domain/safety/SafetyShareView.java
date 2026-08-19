package org.program.pair.domain.safety;

import java.time.Instant;

/**
 * Tout ce que la page publique de sécurité affiche — et rien d'autre.
 *
 * <p>Ce type existe pour que la liste soit <b>fermée</b>. Réutiliser un DTO de
 * créneau reviendrait à publier, sur une page ouverte sans compte, l'adresse
 * exacte, les coordonnées, l'identifiant interne de l'organisateur et son profil
 * complet : {@code SlotFeedItemDto} porte quatre UUID internes et un
 * {@code UserPublicDto}, lequel porte à son tour l'identifiant de la personne.
 * Un champ ajouté ici est un champ que quelqu'un a décidé de publier.
 *
 * <p>Ce qui n'y figure pas, et ne doit jamais y figurer : adresse exacte,
 * coordonnées, téléphone, e-mail, identifiants internes, liste des autres
 * participants.
 */
public record SafetyShareView(

    /** Nom de l'activité pratiquée. */
    String activityName,

    /** Début de la séance partagée, figé à la création du lien. */
    Instant startsAt,

    /** Fin prévue, figée elle aussi. C'est l'heure à laquelle on s'inquiète. */
    Instant endsAt,

    /** Nom du lieu — jamais son adresse. */
    String placeName,

    /** Ville, si elle a été saisie. Jamais devinée. */
    String city,

    /** Prénom de l'organisateur, réduit depuis son nom affiché. */
    String organizerGivenName
) {}
