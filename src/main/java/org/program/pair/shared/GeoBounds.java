package org.program.pair.shared;

import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;

/**
 * Ce qu'est un rectangle acceptable, écrit une fois.
 *
 * <p>Trois routes bornent aujourd'hui leur recherche par une bbox —
 * {@code /map/activities}, {@code /map/bounds} et {@code /slots/bounds} — et
 * l'équipe cliente demande explicitement que la dernière prenne « les mêmes
 * paramètres que {@code MapBoundsRequest} ». Des paramètres identiques qui ne
 * refuseraient pas les mêmes rectangles seraient le pire des deux mondes : le
 * client croirait porter un contrat et en porterait deux.
 *
 * <p>Les règles vivaient en double dans {@code MapService}, une copie par
 * route. La troisième copie aurait été celle de trop.
 */
public final class GeoBounds {

    private GeoBounds() {}

    /**
     * Refuse ce qui ne décrit pas un rectangle utilisable.
     *
     * <p>Les quatre bornes sont supposées présentes : leur obligation est portée
     * par les {@code @NotNull} des requêtes, ou par un contrôle d'ensemble
     * quand elles sont facultatives ({@code MAP_BOUNDS_INCOMPLETE}).
     *
     * @throws ValidationException {@code MAP_BOUNDS_INVALID} si le rectangle est
     *         inversé, à cheval sur l'antiméridien, ou hors du globe
     */
    public static void validateRectangle(double north, double south, double east, double west) {
        if (south > north) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "'south' ne peut pas être supérieur à 'north'.");
        }
        // Une bbox à cheval sur l'antiméridien demanderait deux enveloppes ;
        // le cas n'est pas supporté, autant le dire plutôt que renvoyer vide.
        if (west > east) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "'west' ne peut pas être supérieur à 'east' : une zone à cheval "
                    + "sur l'antiméridien n'est pas supportée.");
        }
        if (south < -90 || north > 90 || west < -180 || east > 180) {
            throw new ValidationException(ErrorCode.MAP_BOUNDS_INVALID,
                "Les bornes doivent rester dans [-90, 90] en latitude et "
                    + "[-180, 180] en longitude.");
        }
    }
}
