package org.program.pair.domain.guardian;

/**
 * Lequel de ses contacts d'urgence une personne veut prévenir en premier.
 *
 * <p><b>Un réglage, pas un droit.</b> Le rôle ne change rien à ce qu'un contact
 * peut faire ni à ce qu'il reçoit : il dit seulement lequel la feuille d'armement
 * propose par défaut. Sans lui, l'app retombait sur « le premier contact accepté
 * de la liste » — un ordre qui vient du serveur, qui n'a aucun sens pour la
 * personne, et qui peut changer entre deux ouvertures.
 *
 * <p><b>Le contact ne l'apprend pas.</b> Il sait qu'il est désigné, il a donné son
 * accord ; savoir qu'il est principal plutôt que secours est une information de
 * plus, sans usage pour lui, et sur laquelle il n'a rien à dire. Le rôle ne figure
 * donc dans aucune vue servie au contact — voir {@code PublicGuardianConsentController},
 * qui passe par une projection dédiée et non par {@code GuardianDto}.
 */
public enum GuardianRole {

    /** Celui que la feuille d'armement propose en premier. Au plus un par personne. */
    PRIMARY,

    /** Celui que l'escalade sollicite si le principal n'a pas répondu. Au plus un. */
    BACKUP,

    /**
     * Aucun rôle — l'état de la plupart des contacts.
     *
     * <p>Représenté par {@code NULL} en base plutôt que par un mot : c'est le cas
     * de la grande majorité des lignes, et l'absence n'a pas besoin d'être
     * rétro-remplie. La conversion se fait aux deux bornes, jamais au milieu.
     */
    NONE;

    /** Le rôle stocké, où {@code null} vaut {@link #NONE}. */
    public static GuardianRole ofNullable(GuardianRole stocke) {
        return stocke == null ? NONE : stocke;
    }

    /** Ce qu'on écrit en base : {@code null} pour {@link #NONE}. */
    public GuardianRole toStored() {
        return this == NONE ? null : this;
    }
}
