package org.program.pair.shared.web;

import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * La pagination de l'API, écrite une fois (P-BA-14, décision D7 option C).
 *
 * <p>Neuf routes passaient {@code size} tel quel à {@code PageRequest.of} : une
 * seule requête pouvait demander cent mille lignes. D'autres plafonnaient à 50
 * en silence, chacune avec sa constante. La forme JSON, elle, est déjà stable —
 * {@code VIA_DTO}, {@code {content, page: {size, number, totalElements, totalPages}}} —
 * et ne change pas : c'est {@code page.size} qui dit la taille réellement servie.
 *
 * <ul>
 *   <li>{@link #borne} pour les routes <b>existantes</b> : une taille démesurée
 *       est ramenée au plafond. L'app publiée demande {@code /activities?size=1000}
 *       et doit continuer de recevoir une page ;</li>
 *   <li>{@link #strict} pour les routes <b>nouvelles</b> : au-delà du plafond, 400.
 *       Un client écrit aujourd'hui apprend la règle au lieu de la subir.</li>
 * </ul>
 * Dans les deux cas, une page négative ou une taille nulle est une erreur de
 * l'appelant, et rend 400 plutôt que l'{@code IllegalArgumentException} de
 * {@code PageRequest} — un 500.
 */
public final class Pages {

    public static final int TAILLE_MAX = 50;

    private Pages() {
    }

    public static Pageable borne(int page, int size) {
        return borne(page, size, Sort.unsorted());
    }

    public static Pageable borne(int page, int size, Sort sort) {
        if (page < 0 || size < 1) {
            throw new ValidationException(ErrorCode.INVALID_PARAMETER,
                "La page doit être positive ou nulle, et la taille au moins 1.");
        }
        return PageRequest.of(page, Math.min(size, TAILLE_MAX), sort);
    }

    public static Pageable strict(int page, int size) {
        if (size > TAILLE_MAX) {
            throw new ValidationException(ErrorCode.PAGE_SIZE_TOO_LARGE,
                "La taille de page ne peut pas dépasser " + TAILLE_MAX + ".");
        }
        return borne(page, size);
    }
}
