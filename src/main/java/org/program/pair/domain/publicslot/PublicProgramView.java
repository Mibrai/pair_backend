package org.program.pair.domain.publicslot;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ce qu'une page publique de programme montre — et rien d'autre.
 *
 * <p>Type fermé, exactement pour la raison de {@link PublicSlotView} : réutiliser
 * {@code ProgramDto} publierait l'identifiant de l'organisateur et un
 * {@code UserPublicDto} qui porte le sien. Sur une page ouverte sans compte, ce
 * serait donner à quiconque reçoit un lien la clé de personnes qu'il n'a pas
 * choisi de recevoir.
 *
 * <p>L'identifiant du programme, lui, <b>est présent</b> : c'est l'objet même du
 * partage, et sans lui le jeton se résout en une description qu'aucun client ne
 * peut afficher, faute d'adresse où aller. Voir {@link PublicSlotView} pour la
 * règle complète et la raison de sa correction.
 *
 * <p>Interdit, et absent : e-mail, téléphone, identifiant d'utilisateur ou de
 * conversation, adresse exacte, liste des inscrits.
 */
public record PublicProgramView(

    @Schema(description = "Identifiant du programme partagé, pour ouvrir sa fiche dans "
        + "l'application. Il n'adresse rien sur le web ouvert — c'est le jeton qui "
        + "adresse — et n'est lisible qu'en présentant ce jeton.")
    java.util.UUID programId,

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
