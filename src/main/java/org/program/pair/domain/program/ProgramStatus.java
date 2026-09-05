package org.program.pair.domain.program;

public enum ProgramStatus {
    DRAFT,
    ACTIVE,
    PAUSED,

    /**
     * Endormi : resté sans aucun créneau non annulé pendant le délai de
     * {@code meetdo.cycle.dormancy-delay-days} après sa création.
     *
     * <p><b>Ce n'est pas une suppression, et c'est le point.</b> La demande
     * initiale prévoyait un effacement automatique ; il n'aura pas lieu. Une
     * suppression silencieuse est une perte de données sans consentement, et
     * « j'avais créé mon cours, il a disparu » est un ticket qu'on ne peut pas
     * fermer. Le sommeil rend tout le service attendu — retirer les coquilles
     * vides des listes, de la carte et des recherches — sans ce coût.
     *
     * <p><b>Il n'a demandé aucun filtre nouveau.</b> Toutes les surfaces
     * publiques bornent déjà sur {@code status = 'ACTIVE'} : la carte
     * ({@code findVisibleNearScheduleOrOrganizerIds}), le catalogue
     * d'activités, la recherche plein texte, et le fil de créneaux qui l'exclut
     * même deux fois. {@code ProgramEnrollmentService} refuse déjà de rejoindre
     * ce qui n'est pas {@code ACTIVE}. La valeur suffit donc à produire l'effet.
     *
     * <p><b>Son auteur, lui, continue de le voir</b> : {@code GET /programs}
     * borne sur {@code status <> ARCHIVED}, et {@code GET /programs/{id}} ne
     * regarde que la propriété et {@code isPublic}. C'est ce qui rend le réveil
     * possible — {@code PATCH /programs/{id}} avec {@code {"status":"ACTIVE"}},
     * réservé à l'auteur.
     *
     * <p>Le réveil est aussi <b>automatique</b> dès qu'un créneau est posé : voir
     * {@code ProgramService.addSchedule}. Le tenir côté serveur plutôt que de
     * l'attendre du client est ce qui empêche l'état incohérent — un programme
     * endormi qui a pourtant un pin, invisible sur la carte sans que personne ne
     * puisse le comprendre.
     *
     * @see org.program.pair.domain.program.jobs.ProgramDormancyJob
     */
    DORMANT,

    ARCHIVED
}
