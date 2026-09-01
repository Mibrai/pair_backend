package org.program.pair.domain.watch;

/**
 * Ce qui s'est passé sur une veille, tel que la chronologie le retient.
 *
 * <p>Cet ensemble ne contient que ce qu'un geste écrit <b>aujourd'hui</b>. Les
 * priorités suivantes du module ajouteront leurs propres entrées — arrivée,
 * rappels, escalade, levée, renvoi de code — quand le code qui les produit
 * existera. Ajouter ici une valeur que rien n'inscrit encore ferait une
 * chronologie qui promet des lignes qu'elle n'écrit jamais.
 */
public enum WatchEventType {

    /** La veille a été armée. Toujours la première ligne de la chronologie. */
    ARMED,

    /** Désarmée avant tout départ, sans message et sans compter d'absence. */
    DISARMED_BEFORE_DEPARTURE,

    /** Arrivée sur place validée : le code de retour a été créé. */
    ARRIVED_ON_SITE,

    /**
     * Refermée par le code de retour. Le même événement quel que soit le code
     * présenté — normal ou de contrainte : la chronologie ne doit pas trahir, à
     * qui la lirait, lequel des deux a servi. Ce qui distingue les deux cas est
     * l'état de la veille, que le client sait ne pas montrer sous contrainte, pas
     * une ligne de journal. L'horodatage est celui saisi par l'utilisateur
     * ({@code enteredAt}), qui fait foi, et non l'heure de réception.
     */
    CLOSED_BY_CODE,

    /** Un rappel de retour a été envoyé à la personne (push). Un des trois. */
    REMINDER_SENT,

    /** Sans réponse après les trois rappels, le contact principal a été prévenu. */
    ESCALATED,

    /** Le contact de secours a été prévenu à son tour. */
    BACKUP_ALERTED,

    /** La levée est partie : la personne a fini par confirmer après une alerte. */
    LEVEE_SENT,

    /** Une demande « tu y es ? » a été envoyée à la personne (boucle aller). */
    ARRIVAL_PROMPTED,

    /** « Je suis en chemin » : la relance d'arrivée est repoussée de quinze minutes. */
    STILL_COMING,

    /** Désarmée avant départ, « je n'y vais pas » — sans message et sans absence. */
    ABANDONED,

    /**
     * Perdu en chemin : trois demandes d'arrivée sans réponse. L'organisateur est
     * prévenu, le message ⑤ part au contact, et un incident est journalisé — jamais
     * une absence.
     */
    LOST_ON_THE_WAY,

    /** Un contact a cliqué « j'ai vu » sur la page publique. Remonte dans l'app. */
    GUARDIAN_ACK_SEEN,

    /** Un contact a cliqué « je l'ai eue au téléphone ». Remonte dans l'app. */
    GUARDIAN_ACK_CALLED,

    /** Snooze : l'échéance est repoussée de trente minutes et la chaîne réarmée. */
    SNOOZED,

    /** Panic : l'utilisateur a fait partir le message immédiatement. */
    PANIC_TRIGGERED,

    /** Le code de retour a été renvoyé (régénéré) après vérification du mot de passe. */
    CODE_RESENT,

    /** Interruption en cours de séance : la personne repart plus tôt. */
    INTERRUPTED,

    /**
     * L'organisateur a signalé qu'il voit la personne : « elle est là ». Repousse
     * la relance d'arrivée de 15 min. L'organisateur ne valide pas l'arrivée et ne
     * crée aucun code — ce geste appartient à l'intéressée seule.
     */
    SEEN_BY_HOST
}
