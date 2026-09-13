package org.program.pair.domain.chat.dto;

/**
 * Pourquoi ce fil ne s'écrit plus — la raison rendue au client, pour qu'il
 * explique au lieu de planter.
 *
 * <p><b>Le défaut fermé ici.</b> Un fil pouvait devenir en lecture seule sans
 * qu'aucune réponse ne le dise : l'application affichait son composeur, la
 * personne écrivait son message, et le serveur le refusait en 403. Le geste était
 * perdu et rien n'était expliqué. Ce champ est ce qui permet de retirer le
 * composeur <b>avant</b> la frappe, et de nommer la cause.
 *
 * <p><b>Ce que chaque valeur dit, et surtout ce qu'elle ne dit pas.</b> La règle
 * du blocage est qu'il reste indétectable par la personne bloquée : la valeur
 * rendue dépend donc du côté où l'on se trouve, et c'est le seul endroit de cette
 * énumération où deux personnes du même fil ne lisent pas la même chose.
 *
 * <ul>
 *   <li>{@link #BLOCKED} ne part que vers <b>celle qui a bloqué</b> : elle sait
 *       pourquoi, c'est sa décision, et le lui rappeler évite qu'elle croie à une
 *       panne.</li>
 *   <li>{@link #PARTICIPANT_UNAVAILABLE} part vers <b>celle qui est bloquée</b>,
 *       et c'est la forme neutre : « cette personne n'est plus joignable », ce
 *       qu'un compte désactivé dirait aussi. Rien ne doit permettre de distinguer
 *       les deux, sinon le blocage s'apprend par comparaison.</li>
 * </ul>
 *
 * <p>Les deux autres valeurs ne portent aucun secret : elles décrivent un réglage
 * public du programme, et les deux côtés lisent la même.
 */
public enum ConversationReadOnlyReason {

    /**
     * L'appelant a bloqué son interlocuteur. Jamais rendue à l'autre.
     *
     * <p>Le pendant du refus {@code USER_BLOCKED} que renverrait un envoi.
     */
    BLOCKED,

    /**
     * L'interlocuteur n'est plus joignable, sans dire pourquoi.
     *
     * <p>Rendue à la personne bloquée. Le refus correspondant côté écriture est
     * un 403 sans code nommé — la même neutralité, dans les deux réponses.
     */
    PARTICIPANT_UNAVAILABLE,

    /**
     * Fil de diffusion d'un programme : seul son auteur y écrit.
     *
     * <p>Le pendant de {@code PROGRAM_BROADCAST_READ_ONLY}.
     */
    PROGRAM_BROADCAST_READ_ONLY,

    /**
     * L'auteur du programme n'accepte pas les messages de ses participants.
     *
     * <p>Le pendant de {@code PROGRAM_MESSAGES_DISABLED}.
     */
    PROGRAM_MESSAGES_DISABLED
}
