package org.program.pair.domain.publicslot;

import java.time.Instant;

/**
 * Ce qu'une page publique de programme montre — et rien d'autre.
 *
 * <p>Type fermé, exactement pour la raison de {@link PublicSlotView} : réutiliser
 * {@code ProgramDto} publierait des identifiants internes et un
 * {@code UserPublicDto} portant l'UUID de l'organisateur. Sur une page ouverte
 * sans compte, ce serait donner à quiconque reçoit un lien la clé d'objets qu'il
 * n'a pas le droit de manipuler.
 *
 * <p>Interdit, et absent : e-mail, téléphone, UUID d'utilisateur ou de programme,
 * adresse exacte, liste des inscrits, identifiants de conversation.
 */
public record PublicProgramView(

    String title,
    String description,

    String activityName,
    String categoryName,

    /** Couleur de la catégorie, pour la vignette et l'habillage. */
    String categoryColorRamp,

    /** REMOTE | ONLINE | IN_PERSON | HYBRID, ou nul si l'auteur n'a rien dit. */
    String locationType,

    /**
     * Ville et nom du lieu de la prochaine séance, jamais l'adresse.
     *
     * <p>Un programme n'a pas de lieu à lui : ces deux champs viennent du
     * prochain créneau, et sont nuls quand il n'y en a pas — ou quand le
     * programme est à distance.
     */
    String city,
    String placeName,

    Instant nextSessionAt,

    /** Nombre de séances à venir. Zéro pour un programme sans créneau futur. */
    int sessionCount,

    int enrolledCount,
    Integer maxParticipants,

    String organizerGivenName,
    boolean organizerVerified,

    /** Vrai si le programme porte une image ; l'URL est composée par la page. */
    boolean hasImage
) {}
