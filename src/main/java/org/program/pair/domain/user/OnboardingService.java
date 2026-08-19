package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.user.dto.OnboardingStateDto;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * L'avancement du parcours d'accueil, et rien d'autre.
 *
 * <p><b>Pourquoi un service séparé plutôt que trois méthodes dans
 * {@code UserService}.</b> Ce dernier est monté dans ses tests unitaires par
 * {@code @InjectMocks} avec la liste exacte de ses dépendances ; lui en ajouter
 * une casse une classe de test qui n'a rien à voir avec l'accueil. Le dépôt a
 * déjà payé ce prix une fois. L'accueil ayant son propre cycle de vie et sa
 * propre trace d'audit, il vit ici.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public OnboardingStateDto getState(UUID userId) {
        return toDto(load(userId));
    }

    /**
     * Enregistre une étape franchie.
     *
     * <p>Idempotent par construction, et pas seulement pour le rejeu à
     * l'identique : le réseau mobile double les requêtes <i>et</i> les livre
     * parfois dans le désordre. Une étape déjà atteinte, ou antérieure à celle
     * enregistrée, ne fait donc rien du tout — l'avancement ne recule jamais.
     * Répondre par une erreur serait pire qu'inutile : le client n'aurait aucun
     * moyen de distinguer son propre doublon d'un vrai problème.
     *
     * <p>Franchir le dernier écran referme le parcours : il n'existe pas d'étape
     * « terminé » à part, la fin se lisant sur {@code onboardingCompletedAt}. La
     * date n'est posée qu'une fois — repasser par le dernier écran ne la réécrit
     * pas, sinon la mesure du parcours dépendrait du nombre de fois où le client
     * a réessayé.
     */
    public OnboardingStateDto advance(UUID userId, OnboardingStep step) {
        User user = load(userId);

        if (user.getOnboardingStep() == null || step.reaches(user.getOnboardingStep())) {
            user.setOnboardingStep(step);
        }

        if (step.isFinal() && user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
        }

        return toDto(userRepository.save(user));
    }

    /**
     * Sort du parcours sans le terminer.
     *
     * <p>Permis, et tracé. L'étape atteinte est conservée telle quelle : c'est
     * elle qui dit <i>où</i> les gens abandonnent, et l'écraser par {@code DONE}
     * effacerait la seule information que ce geste apporte.
     */
    public OnboardingStateDto skip(UUID userId) {
        User user = load(userId);

        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
            auditLogService.log(userId, AuditActionType.ONBOARDING_SKIP, "USER", userId,
                user.getOnboardingStep(), null);
        }

        return toDto(userRepository.save(user));
    }

    private User load(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    private OnboardingStateDto toDto(User user) {
        return new OnboardingStateDto(
            user.getOnboardingStep() == null ? null : user.getOnboardingStep().name(),
            user.getOnboardingCompletedAt(),
            user.getOnboardingCompletedAt() != null
        );
    }
}
