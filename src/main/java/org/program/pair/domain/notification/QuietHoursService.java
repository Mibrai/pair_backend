package org.program.pair.domain.notification;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.notification.dto.QuietHoursDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Le réglage des heures de silence, et rien d'autre.
 *
 * <p>Service séparé pour la raison qui a déjà coûté une classe de test au dépôt :
 * {@code UserService} est monté par {@code @InjectMocks} avec la liste exacte de
 * ses dépendances, et lui en ajouter une casse des tests qui n'ont rien à voir.
 * Voir {@code OnboardingService}, sorti pour le même motif.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class QuietHoursService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public QuietHoursDto get(UUID userId) {
        return toDto(load(userId));
    }

    /**
     * Règle ou retire la fenêtre.
     *
     * <p>Les deux bornes vont ensemble : deux valeurs nulles retirent le silence,
     * une seule est refusée. Deviner la borne manquante reviendrait à faire taire
     * des notifications sur une intention supposée — et dans un sens qui ne se
     * remarque pas, puisqu'un silence de trop ne produit rien de visible.
     *
     * <p>Deux bornes égales sont refusées aussi. « 22 → 22 » se lit aussi bien
     * comme « une minute de silence » que comme « toute la journée », et aucune
     * des deux lectures ne s'impose : mieux vaut le dire que choisir en silence.
     */
    public QuietHoursDto update(UUID userId, Integer start, Integer end) {
        if ((start == null) != (end == null)) {
            throw new ValidationException(
                "Les heures de silence vont par deux : fournissez le début et la fin, "
                    + "ou aucun des deux pour retirer le silence.");
        }
        if (start != null && start.equals(end)) {
            throw new ValidationException(
                "Le début et la fin des heures de silence ne peuvent pas être identiques.");
        }

        User user = load(userId);
        user.setQuietHoursStart(start == null ? null : start.shortValue());
        user.setQuietHoursEnd(end == null ? null : end.shortValue());
        return toDto(userRepository.save(user));
    }

    private User load(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    private QuietHoursDto toDto(User user) {
        Integer start = user.getQuietHoursStart() == null ? null : user.getQuietHoursStart().intValue();
        Integer end = user.getQuietHoursEnd() == null ? null : user.getQuietHoursEnd().intValue();
        return new QuietHoursDto(start, end, !QuietHours.of(start, end).disabled());
    }
}
