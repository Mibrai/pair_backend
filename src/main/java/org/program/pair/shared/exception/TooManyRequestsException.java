package org.program.pair.shared.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * Refus d'un quota, avec le délai au bout duquel la porte rouvre réellement.
 *
 * <p><b>Le délai est obligatoire depuis le 10/09.</b> Le refus ne portait qu'un
 * message — « Réessayez dans quelques minutes », « dans une heure » — et le
 * client mobile devait deviner : nos fenêtres sont glissantes, donc l'instant de
 * réouverture ne se déduit ni du message ni de l'heure du refus. Un client qui
 * suivait le conseil se faisait refuser autant de fois. C'est la demande 4 du
 * chantier mobile du 10/09, et {@link GlobalExceptionHandler} en fait un en-tête
 * {@code Retry-After}.
 *
 * <p>Le constructeur exige donc la durée, plutôt que de l'accepter absente :
 * c'est le compilateur, et non une relecture, qui garantit que <b>tout</b> 429
 * de cette API dit quand revenir. Seul {@link org.program.pair.shared.security.RateLimiter}
 * la connaît — c'est lui qui tient les horodatages de la fenêtre.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration delaiAvantNouvelEssai;

    public TooManyRequestsException(String message, Duration delaiAvantNouvelEssai) {
        super(message);
        this.delaiAvantNouvelEssai = Objects.requireNonNull(
            delaiAvantNouvelEssai, "un refus de quota doit dire quand la porte rouvre");
    }

    /** Le temps restant tel que la fenêtre l'impose, à la précision de l'horloge. */
    public Duration getDelaiAvantNouvelEssai() {
        return delaiAvantNouvelEssai;
    }

    /**
     * La même durée, telle qu'un en-tête {@code Retry-After} l'écrit : des
     * secondes entières, arrondies vers le haut, et jamais zéro.
     *
     * <p>L'arrondi vers le haut est le seul qui ne mente pas : tronquer 900,4 s à
     * 900 rendrait un instant où la porte est encore fermée, donc un refus de
     * plus. Et {@code Retry-After: 0} invite à réessayer sur-le-champ, ce qui est
     * exactement le contraire de ce que cet en-tête existe pour dire — le
     * plancher d'une seconde couvre le cas de la tentative assise pile sur le
     * bord de la fenêtre, qui n'en sort qu'à l'instant <em>suivant</em>.
     */
    public long getRetryAfterSecondes() {
        long secondes = delaiAvantNouvelEssai.toNanos() / 1_000_000_000L;
        if (delaiAvantNouvelEssai.toNanos() % 1_000_000_000L > 0) {
            secondes++;
        }
        return Math.max(1L, secondes);
    }
}
