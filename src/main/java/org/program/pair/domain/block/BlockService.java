package org.program.pair.domain.block;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.block.dto.BlockedUserDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserBlockRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Poser et lever un blocage.
 *
 * <p><b>Le blocage doit rester indétectable</b> par la personne bloquée : aucune
 * notification n'est produite, aucun code d'erreur distinctif ne lui est rendu,
 * et rien dans son interface ne change à l'instant où il tombe. Cette classe
 * n'émet donc rien — c'est une omission délibérée, pas un oubli.
 *
 * <p>Deux signaux résiduels subsistent et sont assumés : la rupture des
 * abonnements fait baisser d'une unité le compteur d'abonnés visible du bloqué,
 * et son bouton « Abonné » repasse à « S'abonner ». Les masquer demanderait de
 * mentir sur des chiffres, ce qui coûterait plus cher que ce que ça protège.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BlockService {

    private final UserBlockRepository userBlockRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    /**
     * Bloque quelqu'un. Idempotent : rebloquer ne produit pas d'erreur.
     *
     * <p>Le blocage rompt immédiatement les abonnements qui liaient les deux
     * personnes, dans les deux sens. Sans cela, le fil de l'un continuerait de
     * porter les annonces de l'autre : bloquer serait un réglage d'affichage.
     */
    public void block(UUID blockerId, UUID blockedId, String reason) {
        if (blockerId.equals(blockedId)) {
            throw new ValidationException("On ne peut pas se bloquer soi-même.");
        }

        // Un compte inexistant ou désactivé rend 404, comme partout ailleurs.
        User blocked = userRepository.findById(blockedId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));

        User blocker = userRepository.findById(blockerId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));

        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            userBlockRepository.save(UserBlock.builder()
                .blocker(blocker)
                .blocked(blocked)
                .reason(reason)
                .build());
        }

        subscriptionRepository.deleteBetween(blockerId, blockedId);
    }

    /**
     * Lève un blocage. Idempotent : débloquer quelqu'un qui ne l'était pas ne
     * produit pas d'erreur.
     *
     * <p>Les abonnements rompus ne sont pas rétablis. Ils ont été rompus par une
     * décision ; les faire revenir supposerait de deviner qu'on la regrette.
     */
    public void unblock(UUID blockerId, UUID blockedId) {
        userBlockRepository.findByBlockerIdAndBlockedId(blockerId, blockedId)
            .ifPresent(userBlockRepository::delete);
    }

    @Transactional(readOnly = true)
    public Page<BlockedUserDto> listBlocked(UUID blockerId, Pageable pageable) {
        return userBlockRepository.findByBlockerId(blockerId, pageable)
            .map(block -> new BlockedUserDto(
                block.getBlocked().getId(),
                block.getBlocked().getDisplayName(),
                block.getBlocked().getAvatarUrl(),
                block.getCreatedAt()));
    }
}
