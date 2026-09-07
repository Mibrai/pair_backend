package org.program.pair.domain.affiche;

import java.util.Locale;
import java.util.Optional;

/**
 * Qui a le droit de voir une affiche publiée.
 *
 * <p><b>Ce réglage est opposable, et c'est toute sa raison d'être.</b> Il est
 * appliqué dans {@code GET /api/users/{id}/affiches} et dans
 * {@code GET /api/affiches/updates} : un lecteur qui n'y a pas droit reçoit une
 * liste vide, pas une liste que le client devrait filtrer. La différence n'est
 * pas théorique — filtrer côté client supposerait de faire descendre à chaque
 * lecteur la liste des abonnés de la personne regardée, c'est-à-dire d'exposer
 * exactement ce que l'absence de {@code GET /users/{id}/subscribers} protège.
 *
 * <p><b>Pourquoi ce n'est pas rangé dans {@code /users/me/preferences}.</b> Ce
 * magasin de clés a été choisi le 06/09 pour un réglage dont il était écrit que
 * « le serveur range cette valeur, il ne l'interprète pas », et il n'est
 * lisible que par son propriétaire — deux propriétés justes pour un réglage
 * d'affichage local, fausses ici : une affiche publiée est vue par d'autres, et
 * un réglage de confidentialité que rien n'applique est une promesse non tenue.
 *
 * <p>{@link #NOBODY} est le défaut, à la création comme en base : une affiche
 * dont l'audience n'a pas été choisie n'est vue de personne. Le sens de
 * l'erreur va vers le silence.
 */
public enum AfficheAudience {

    /** Personne. L'affiche existe, elle n'est lue que par son auteur. */
    NOBODY,

    /** Ceux qui se sont abonnés à moi — {@code Subscription} de type {@code AUTHOR}. */
    SUBSCRIBERS,

    /** Tout le monde, y compris qui ne me suit pas. */
    EVERYONE;

    /**
     * Rang d'ouverture, du plus fermé au plus ouvert.
     *
     * <p>Sert à décider si une modification est une <b>ouverture</b> — auquel cas
     * elle vaut publication et rafraîchit {@code publishedAt}, donc rallume
     * l'anneau — ou un simple ajustement. Sans cet ordre, deux modes d'échec
     * symétriques : changer de motif rallumerait l'anneau chez tout le monde,
     * et passer de {@code SUBSCRIBERS} à {@code EVERYONE} ne l'allumerait chez
     * personne alors que l'affiche vient de devenir visible pour des gens qui ne
     * l'avaient jamais vue.
     */
    public int openness() {
        return ordinal();
    }

    /** Vrai dès que quelqu'un d'autre que l'auteur peut voir l'affiche. */
    public boolean isVisibleToOthers() {
        return this != NOBODY;
    }

    /**
     * Lecture d'une valeur reçue. Insensible à la casse et aux espaces, comme
     * les autres énumérations reçues au contrat ; une valeur inconnue ne se
     * range pas silencieusement en {@code NOBODY} — l'appelant doit apprendre
     * que son réglage n'a pas été compris plutôt que de croire une affiche
     * publiée alors qu'elle est muette.
     */
    public static Optional<AfficheAudience> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
