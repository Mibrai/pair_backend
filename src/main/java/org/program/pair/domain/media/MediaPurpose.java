package org.program.pair.domain.media;

/**
 * À quoi sert un fichier déposé — la colonne {@code media_files.purpose}.
 *
 * <p><b>Distinct de {@link MediaType}, et il faut dire pourquoi.</b>
 * {@code MediaType} décide du <i>répertoire</i> de stockage au moment du dépôt,
 * et c'est tout ce qu'il sait : l'application téléverse une pièce jointe
 * d'incident et une photo de souvenir par le même appel
 * {@code POST /api/media/upload/image}, <b>sans paramètre {@code type}</b>. Les
 * deux atterrissent donc dans {@code program_image/}, indiscernables — c'est le
 * constat de la fiche P-BS-01. L'usage réel ne se connaît qu'à l'instant du
 * <i>rattachement</i> : {@code MediaFileService.attacher} le corrige alors sur
 * la ligne de propriété.
 *
 * <p>C'est cette valeur, et non le répertoire, qui permet à
 * {@code MediaController.serveFile} de réserver la lecture d'une pièce jointe
 * d'incident à son déposant (fiche P-BS-11).
 */
public enum MediaPurpose {

    /** Photo de profil. Lisible par tout compte connecté. */
    AVATAR,

    /** Couverture d'un programme. Servie aussi sur les pages publiques de partage. */
    PROGRAM_IMAGE,

    /** Image d'une étape de progression. */
    PROGRESSION_IMAGE,

    /** Icône d'une activité du référentiel partagé. */
    ACTIVITY_ICON,

    /**
     * Pièce jointe d'un incident de sécurité — une preuve, souvent de
     * harcèlement. Le seul usage dont la <b>lecture</b> est restreinte à son
     * déposant, et par un 404 plutôt qu'un 403 : un refus explicite
     * confirmerait que la pièce existe.
     */
    INCIDENT_ATTACHMENT,

    /** Photo de souvenir rattachée à une présence confirmée. */
    RECAP_PHOTO,

    /**
     * Déposé, mais pas encore rattaché à quoi que ce soit — ou déposé par un
     * chemin qui ne dit pas son usage. C'est le défaut de la colonne.
     */
    UNKNOWN;

    /**
     * L'usage présumé d'un fichier au moment de son dépôt, à partir du seul
     * renseignement disponible alors : le répertoire de destination.
     *
     * <p>Volontairement approximatif pour {@code PROGRAM_IMAGE} : c'est la
     * valeur que porteront aussi les pièces jointes et les photos de souvenir
     * jusqu'à leur rattachement, puisque l'application les téléverse par le
     * chemin générique. {@code attacher} tranche ensuite.
     */
    public static MediaPurpose ofMediaType(MediaType type) {
        return switch (type) {
            case USER_AVATAR -> AVATAR;
            case PROGRAM_IMAGE -> PROGRAM_IMAGE;
            case PROGRESSION_IMAGE -> PROGRESSION_IMAGE;
            case ACTIVITY_ICON -> ACTIVITY_ICON;
        };
    }
}
