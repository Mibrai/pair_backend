package org.program.pair.domain.notification;

public enum NotificationType {
    NEW_MESSAGE,
    NEW_MATCH,
    NEARBY_PROGRAM,
    NEW_FOLLOWER,
    PEER_RECOMMENDATION,
    PROGRAM_REVIEW,
    BADGE_EARNED,
    PROGRAM_REMINDER,
    PROGRESSION_REMINDER,
    ACCOUNT_VERIFICATION,
    PASSWORD_RESET,
    MODERATION_ACTION,
    AUTHOR_NEW_ACTIVITY,
    AUTHOR_NEW_PROGRAM,
    ACTIVITY_UPDATED,
    ACTIVITY_NEW_PROGRAM,
    CATEGORY_NEW_ACTIVITY,
    // Valeurs déjà présentes dans les données de seed V12/V13/V27, absentes de l'enum d'origine
    NEW_REVIEW,
    NEW_BADGE,
    NEW_PEER_REC,
    MATCH_FOUND,
    PROGRAM_CANCELLED,
    SCHEDULE_CHANGED,
    SYSTEM,
    // meetDo — créneaux, présence et alertes
    SLOT_JOINED,             // quelqu'un a rejoint mon créneau
    SLOT_CANCELLED,          // un créneau que j'ai rejoint est annulé
    WAITLIST_PROMOTED,       // une place s'est libérée, j'y entre
    ATTENDANCE_PROMPT,       // "tu y étais ?" après un créneau
    ACTIVITY_ALERT_MATCH,    // quelqu'un pratique enfin cette activité près de moi
    STREAK_MILESTONE,        // série de N semaines atteinte
    PARTNER_MILESTONE,       // Nème partenaire différent
    // Diffusion de l'auteur d'un programme à ses participants. Distinct de
    // NEW_MESSAGE : le tap doit ouvrir le fil du programme, pas une
    // conversation à deux, et le client range les deux dans des rubriques
    // différentes de son catalogue de préférences.
    PROGRAM_BROADCAST,
    // Quelqu'un vous a désigné comme son contact d'urgence pour une veille retour
    // et demande votre accord. Le tap ouvre l'écran accepter / refuser. Distinct
    // de tout le reste : il porte une décision de consentement, pas une
    // information ni un engagement.
    GUARDIAN_CONSENT_REQUEST,
    // Rappel de retour adressé à la personne veillée : son heure limite est
    // passée et elle n'a pas confirmé. Time-sensitive, et critique — il doit
    // traverser les heures de silence, sinon le silence de confort ferait partir
    // une alerte chez un proche à la place d'un rappel qui aurait suffi.
    WATCH_RETURN_REMINDER,
    // Alerte in-app à un contact d'urgence qui est membre meetDo : la personne
    // qu'il veille n'a pas confirmé son retour. Le pendant du SMS ② pour un
    // contact qui a l'application. Critique, pour la même raison.
    WATCH_GUARDIAN_ALERT
;

    /**
     * Les notifications qu'on envoie même quand la personne a demandé le silence.
     *
     * <p>La distinction que porte cet ensemble est celle entre <b>information
     * indispensable</b> et <b>engagement</b>. Une annulation appartient à la
     * première : quelqu'un s'apprête à traverser la ville pour une séance qui
     * n'aura pas lieu, et le lui apprendre le lendemain matin ne sert plus à
     * rien. Une suggestion d'activité appartient à la seconde, et peut attendre.
     *
     * <p>Le critère qui a servi à trancher, et qu'il faut reprendre pour tout
     * ajout : <b>que coûte le fait de l'apprendre trop tard ?</b> Un déplacement
     * pour rien, ou une séance manquée à laquelle on s'était engagé, valent le
     * réveil. Une occasion ratée ne le vaut pas.
     *
     * <p><b>PROGRAM_REMINDER en fait partie</b>, ce qui peut surprendre pour un
     * rappel. Il part deux heures avant une séance qu'on a choisi de rejoindre :
     * s'il tombe dans le silence, c'est que la séance elle-même y est presque, et
     * l'étouffer transforme un réglage de confort en engagement manqué. Le rappel
     * est unique, et ne parvient qu'à qui s'est inscrit.
     *
     * <p><b>SCHEDULE_CHANGED en fait partie aussi</b>, alors qu'aucun code ne
     * l'émet encore. C'est délibéré, à rebours de la note qui figurait ici : une
     * séance déplacée produit exactement le déplacement pour rien qu'une
     * annulation produit, et le classement découle du coût de l'erreur, pas de
     * l'existence d'un producteur. Le jour où quelqu'un écrira l'émetteur, il
     * n'aura pas à redécouvrir cette question.
     *
     * <p><b>PROGRAM_BROADCAST n'en fait PAS partie</b>, et c'est le cas ambigu que
     * la note précédente signalait. Son contenu est un texte libre, qui peut aussi
     * bien annoncer une annulation qu'une relance — le serveur ne sait pas le
     * lire. Le classer comme critique donnerait à tout auteur de programme le
     * moyen de passer outre le silence de tous ses participants, avec n'importe
     * quel message. C'est le mode d'échec au coût le plus élevé, et l'auteur qui
     * doit vraiment joindre son groupe la nuit dispose de l'annulation, qui passe.
     *
     * <p><b>WAITLIST_PROMOTED n'en fait pas partie non plus</b>, et c'est le
     * jugement le plus serré. Une place libérée est une bonne nouvelle qui peut se
     * périmer ; mais celui qui l'apprend n'a encore engagé aucun déplacement, et
     * la notification in-app l'attend au réveil. Perdre une place coûte moins que
     * réveiller quelqu'un pour une place.
     */
    private static final java.util.Set<NotificationType> CRITICAL = java.util.EnumSet.of(
        SLOT_CANCELLED, PROGRAM_CANCELLED, SCHEDULE_CHANGED, PROGRAM_REMINDER,
        // Le rappel de retour traverse le silence pour la raison la plus forte de
        // cet ensemble : le coût de l'apprendre trop tard n'est pas une séance
        // manquée mais une alerte envoyée chez un proche. Étouffer les trois
        // rappels, c'est supprimer les trois occasions de lever l'alerte soi-même.
        WATCH_RETURN_REMINDER,
        // L'alerte à un contact membre est le fait même que la fonctionnalité
        // existe pour délivrer : elle ne se tait jamais.
        WATCH_GUARDIAN_ALERT);

    /**
     * Celles qui méritent en plus un e-mail.
     *
     * <p><b>Un ensemble distinct, et non le même.</b> Les deux questions se
     * ressemblent — « faut-il déranger ? », « faut-il écrire ? » — mais elles ne
     * se répondent pas ensemble, et cette liste-ci a longtemps été confondue avec
     * la précédente.
     *
     * <p>Le contre-exemple qui les sépare est {@code PROGRAM_REMINDER}. Il mérite
     * de traverser les heures de silence, sans quoi un réglage de confort
     * transforme un engagement en séance manquée. Il ne mérite pas un e-mail :
     * il en partirait un pour chaque séance rejointe par chacun, ce qui remplirait
     * les boîtes plus sûrement qu'aucune fonctionnalité et ferait couper le canal
     * entier — y compris pour les annulations, qui sont la raison d'être de ce
     * canal.
     *
     * <p>Ne restent donc ici que les faits qui rendent un déplacement inutile, et
     * qu'on veut retrouver écrits quelque part même en ayant raté la notification.
     */
    private static final java.util.Set<NotificationType> EMAILED = java.util.EnumSet.of(
        SLOT_CANCELLED, PROGRAM_CANCELLED, SCHEDULE_CHANGED);

    /** Vrai si cette notification passe outre les heures de silence. */
    public boolean isCritical() {
        return CRITICAL.contains(this);
    }

    /** Vrai si cette notification part aussi par e-mail, en plus de la push. */
    public boolean warrantsEmail() {
        return EMAILED.contains(this);
    }
}
