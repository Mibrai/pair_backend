package org.program.pair.domain.guidelines;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.guidelines.dto.GuidelinesStateDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * L'acceptation des règles de communauté.
 *
 * <p><b>Ce que le serveur porte, et ce qu'il ne porte pas.</b> Il porte la
 * version en vigueur et la trace de l'acceptation. Il ne porte pas le texte :
 * celui-ci reste dans l'application, faute d'un pipeline de contenu multilingue
 * dans ce projet. C'est le minimum sans lequel une modification substantielle du
 * texte ne serait pas redemandable — un client seul ne peut pas savoir qu'un
 * texte qu'il embarque a changé pour les autres.
 *
 * <p>Vit dans son propre service, comme l'onboarding, et pour la même raison :
 * {@code UserService} est monté dans ses tests par {@code @InjectMocks} avec la
 * liste exacte de ses dépendances.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class GuidelinesService {

    private final UserRepository userRepository;

    @Value("${pair.guidelines.current-version:1.0}")
    private String currentVersion;

    @Transactional(readOnly = true)
    public GuidelinesStateDto getState(UUID userId) {
        return toDto(load(userId));
    }

    /**
     * Enregistre l'acceptation de la version présentée.
     *
     * <p><b>La version fait partie de la demande</b>, et elle est refusée si elle
     * n'est pas celle en vigueur. Accepter sans la demander serait plus simple et
     * faux : une application restée sur un texte ancien ferait enregistrer
     * l'acceptation d'un texte que personne n'a lu, ce qui est exactement ce
     * qu'une acceptation est censée prouver.
     *
     * <p>Idempotent : réaccepter la version déjà acceptée ne fait rien et
     * <b>ne réécrit pas la date</b>. C'est la première acceptation de ce
     * texte-là qui a une valeur ; l'écraser à chaque relance de l'application
     * ferait dépendre la trace du nombre de réessais.
     */
    public GuidelinesStateDto accept(UUID userId, String version) {
        if (!currentVersion.equals(version)) {
            throw new ValidationException(ErrorCode.GUIDELINES_VERSION_MISMATCH,
                "Cette version des règles n'est plus celle en vigueur.");
        }

        User user = load(userId);

        if (Guidelines.acceptanceRequired(currentVersion, user.getGuidelinesVersion())) {
            user.setGuidelinesVersion(currentVersion);
            user.setGuidelinesAcceptedAt(Instant.now());
            user = userRepository.save(user);
        }

        return toDto(user);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    private GuidelinesStateDto toDto(User user) {
        return new GuidelinesStateDto(
            currentVersion,
            user.getGuidelinesVersion(),
            user.getGuidelinesAcceptedAt(),
            Guidelines.acceptanceRequired(currentVersion, user.getGuidelinesVersion()));
    }
}
