package org.program.pair.domain.recap;

/**
 * Portée d'une carte-souvenir.
 *
 * <p>{@code PARTICIPANTS} est traité exactement comme {@code PRIVATE} — il
 * n'existe que pour nommer l'intention, le jour où « les participants » et
 * « personne d'autre que moi » cesseraient de coïncider. Le client accepte les
 * trois valeurs et applique la même règle.
 */
public enum RecapVisibility {
    PRIVATE,
    PARTICIPANTS,
    PUBLIC;

    /** Vrai quand la carte est lisible au-delà du cercle de ses participants. */
    public boolean isPublic() {
        return this == PUBLIC;
    }
}
