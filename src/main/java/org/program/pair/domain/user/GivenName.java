package org.program.pair.domain.user;

/**
 * Le prénom à montrer hors de l'application — au mieux de ce qu'on sait.
 *
 * <p><b>Le modèle ne contient pas de prénom.</b> {@code users} ne porte qu'un
 * {@code display_name}, et {@code programs.organizer_name} n'en est qu'une
 * copie. Cette classe n'invente donc pas une donnée manquante : elle réduit
 * celle qui existe, et le fait savoir par son nom.
 *
 * <p><b>Pourquoi réduire.</b> Le nom affiché est déjà public <i>dans</i>
 * l'application — fil, carte, profil, recherche. Ce que changent la page de
 * partage de sécurité et la page publique de créneau, c'est qu'elles le rendent
 * lisible <i>sur le web ouvert</i>, sans compte, par qui détient le lien. La
 * réduction ne protège pas d'un membre de meetDo ; elle limite ce qui sort de
 * meetDo.
 *
 * <p><b>La règle.</b> Le premier segment avant une espace, borné en longueur.
 * Un nom sans espace est rendu tel quel : c'est un pseudonyme, il n'y a rien à
 * retirer. Aucun repli sur l'adresse e-mail ni sur quoi que ce soit d'autre —
 * mieux vaut ne rien afficher qu'afficher une donnée que personne n'a choisi de
 * publier.
 *
 * <p><b>Ce que la règle ne prétend pas.</b> Elle se trompera sur les noms
 * composés sans trait d'union et sur les cultures où le nom de famille précède
 * le prénom. C'est assumé : elle n'existe pas pour être exacte, mais pour ne
 * jamais en dire plus que le nom affiché. Le jour où la justesse comptera, elle
 * demandera une colonne dédiée et une interface pour la remplir, pas une
 * heuristique plus fine.
 *
 * <p>Partagée entre le partage de sécurité (V63) et la page publique de créneau
 * (V65), qui affichent tous deux « le prénom de l'organisateur » : deux
 * réductions écrites séparément finiraient par ne pas montrer la même chose.
 */
public final class GivenName {

    /**
     * Au-delà, on tronque. {@code display_name} monte à 80 caractères, ce qui
     * dépasse largement ce qu'une page de partage a besoin d'afficher.
     */
    private static final int MAX_LENGTH = 40;

    private GivenName() {}

    /**
     * @param displayName le nom affiché, tel qu'il est en base
     * @return le prénom à publier, ou {@code null} s'il n'y a rien à publier
     */
    public static String from(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }

        String trimmed = displayName.strip();
        int firstSpace = indexOfWhitespace(trimmed);
        String given = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;

        return given.length() > MAX_LENGTH ? given.substring(0, MAX_LENGTH) : given;
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
