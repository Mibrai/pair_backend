package org.program.pair.domain.subscription;

/**
 * Volume d'un abonnement, réglé <b>cible par cible</b>.
 *
 * <p>Sans lui, le seul recours contre le bruit était le désabonnement : on
 * perdait le lien alors que la personne voulait seulement baisser le son.
 *
 * <p><b>Ce qu'il ne faut pas confondre avec les préférences de notification.</b>
 * {@link org.program.pair.domain.notification.NotificationPref} est unique par
 * {@code (user_id, notification_type)} : c'est un réglage par <b>type</b>, sans
 * notion de cible. Le niveau ci-dessous est porté par une ligne d'abonnement :
 * c'est un réglage par <b>cible</b>, sans notion de type. Les deux axes sont
 * orthogonaux et aucun ne peut exprimer l'autre.
 *
 * <p>Ordre d'évaluation à l'émission : le niveau décide <b>si</b> la
 * notification existe, la préférence décide <b>par quel canal</b> elle sort.
 *
 * <p><b>{@code MUTED} ne coupe que l'émission.</b> La ligne reste, la cible
 * reste dans {@code /users/me/subscriptions}, et {@code subscribed} continue de
 * valoir {@code true} sur les DTO de cible — le bouton doit dire « Abonné », pas
 * « S'abonner ». C'est le point où une implémentation naïve trahit l'intention :
 * un abonnement en sourdine affiché comme absent sera recliqué, et le second
 * {@code POST} rendra {@code 409}.
 */
public enum SubscriptionLevel {

    /** Tout : créations et modifications. Valeur par défaut. */
    ALL,

    /** Les créations seules — nouveau programme, nouvelle activité. */
    NEW_ONLY,

    /**
     * Rien n'est poussé. L'abonnement subsiste et reste un signal d'intérêt
     * exploitable ; un désabonnement ne l'est plus. C'est la soupape qui évite
     * de perdre le lien.
     */
    MUTED
}
