package org.program.pair.domain.report;

/**
 * Ce qu'un signalant a le droit de savoir de son propre signalement.
 *
 * <p><b>Un vocabulaire distinct de {@link ReportStatus}, et c'est le point.</b>
 * {@code ReportStatus} sert la modération : il distingue {@code REVIEWED} —
 * « quelqu'un a regardé » — d'{@code ACTIONED} — « et une sanction a suivi ».
 * Cette distinction appartient à l'équipe qui traite, pas à la personne qui a
 * signalé : lui dire qu'une sanction est tombée revient à lui rendre compte de
 * ce qui est arrivé à quelqu'un d'autre, ce que ni elle ni nous n'avons à
 * connaître l'un de l'autre. Les deux se projettent donc sur {@code RESOLVED}.
 *
 * <p><b>Pourquoi une projection et non un renommage.</b> {@code V82} a normalisé
 * la colonne {@code status} le 27/08, après qu'un second vocabulaire hérité de
 * {@code V9} eut fait rendre {@code 500} à cette même route. Un troisième
 * vocabulaire sur la même colonne, six semaines après le premier, est la manière
 * la plus sûre de rouvrir ce défaut. La projection donne au client les mots qu'il
 * affiche sans toucher à ceux que la base garde.
 *
 * <p><b>{@code IN_REVIEW} n'existe pas ici</b>, alors que le contrat client le
 * prévoit. Aucun geste de modération ne l'écrirait : un modérateur passe de
 * {@code PENDING} à son verdict en une fois. Servir un état que rien ne produit
 * ferait un écran qui n'affiche jamais « en cours » — ce qui ment aussi sûrement
 * qu'un écran qui l'afficherait toujours. Le client retombe sur
 * {@code RECEIVED} pour toute valeur qu'il ne connaît pas ; le jour où la
 * modération gagne ce geste, la valeur peut donc être servie sans rien casser et
 * sans prévenir personne.
 */
public enum ReportPublicState {

    /** Reçu, personne ne l'a encore regardé. */
    RECEIVED,

    /** Traité. Ce qui a suivi, s'il a suivi quelque chose, ne se dit pas. */
    RESOLVED,

    /**
     * Classé sans suite.
     *
     * <p>Il doit exister et s'afficher. Un signalement écarté qu'on maintient
     * indéfiniment en « en cours » est pire qu'un refus assumé : il apprend à
     * l'utilisateur que le suivi ment, et un suivi auquel on ne croit plus est
     * un signalement qu'on ne refait pas.
     */
    DISMISSED;

    public static ReportPublicState of(ReportStatus status) {
        return switch (status) {
            case PENDING -> RECEIVED;
            case REVIEWED, ACTIONED -> RESOLVED;
            case DISMISSED -> DISMISSED;
        };
    }
}
