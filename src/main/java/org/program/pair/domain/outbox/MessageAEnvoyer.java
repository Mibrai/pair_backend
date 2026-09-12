package org.program.pair.domain.outbox;

import java.util.UUID;

/**
 * Ce qu'il faut savoir d'un message réclamé pour le remettre à son fournisseur —
 * et rien de plus.
 *
 * <p><b>Pourquoi une projection et non l'entité.</b> Depuis le lot 1 de P-BA-03,
 * l'appel au fournisseur se fait <b>hors transaction</b> : c'est tout l'objet du
 * lot, puisqu'une connexion tenue pendant un appel HTTP de plusieurs secondes est
 * une connexion retirée du pool. Or {@code open-in-view} ne couvre que les
 * requêtes web, pas les jobs : une entité lue ici serait détachée dès le retour
 * de la lecture, et toute association paresseuse qu'on lui ajouterait un jour
 * lèverait une {@code LazyInitializationException} dans le balayage — un défaut
 * qui ne se verrait ni à la compilation ni sur le chemin web. C'est exactement la
 * classe de bug que les boucles de veille ont fermée en lisant des identifiants
 * plutôt que des entités (voir {@code WatchReturnLoopJob}).
 *
 * <p>Un enregistrement de valeurs scalaires ferme la question par construction :
 * il n'y a rien à charger paresseusement. Ce qui doit être <i>écrit</i> l'est
 * plus tard, dans la transaction de l'{@code OutboxConfirmer}, sur l'entité
 * rechargée là — jamais sur celle-ci.
 *
 * <p>Le corps et le destinataire sont sensibles. Ils ne vivent le temps d'un
 * envoi, ne sont jamais journalisés, et c'est la raison pour laquelle cette
 * projection ne porte pas non plus l'identifiant du compte ou de la veille : ce
 * qui sert à l'envoi, et non ce qui sert à en rendre compte.
 */
public record MessageAEnvoyer(
    UUID id,
    OutboxChannel channel,
    String recipient,
    String subject,
    String body) {
}
