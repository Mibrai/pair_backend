package org.program.pair.domain.program;

import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;

/**
 * Publier un créneau demande une adresse vérifiée (P-MU-17, demande mobile du
 * 14/09/2026, modules/verification-email).
 *
 * <p>L'app interdisait déjà les écrans de création à un compte non vérifié, et
 * c'était la seule barrière : un autre client publiait sans. Le refus vit donc
 * ici, et l'app pourra laisser préparer un créneau pendant la vérification.
 *
 * <p><b>Ce qui publie</b>, et donc passe par ce contrôle :
 * <ul>
 *   <li>le créneau rapide ({@code POST /api/quick-slots}), qui naît publié ;</li>
 *   <li>un créneau posé sur un programme qui n'est pas en brouillon
 *       ({@code POST /api/programs/{id}/schedules}) ;</li>
 *   <li>le passage d'un programme à {@code ACTIVE} ({@code PUT}/{@code PATCH
 *       /api/programs/{id}}), qui rend visibles les créneaux qu'il porte.</li>
 * </ul>
 *
 * <p><b>Ce qui reste permis</b> : tout le reste. Un programme en brouillon et
 * ses créneaux ne sont vus de personne ; rejoindre, écrire, suivre relèvent
 * d'autres règles et ne sont pas touchés par celle-ci.
 *
 * <p>Une classe sans état plutôt qu'un service : les deux appelants tiennent
 * déjà la personne en main, et une dépendance de plus dans le constructeur de
 * {@code ProgramService} laisserait ses tests à {@code @InjectMocks} sur un
 * {@code null}.
 */
public final class PublicationVerifiee {

    private PublicationVerifiee() {
    }

    public static void exiger(User auteur) {
        if (auteur.getVerificationStatus() == null
                || auteur.getVerificationStatus() == VerificationStatus.UNVERIFIED) {
            throw new ForbiddenException(ErrorCode.EMAIL_NOT_VERIFIED,
                "Vérifiez votre adresse e-mail pour publier un créneau.");
        }
    }
}
