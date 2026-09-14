package org.program.pair.domain.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.block.UserBlockedEvent;
import org.program.pair.repository.ConversationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ce que le blocage fait à la messagerie après coup : les partages de position
 * encore ouverts entre les deux personnes prennent fin, dans les deux sens.
 *
 * <p>Depuis P-BL-05, un bloqué ne partage plus sa position dans un fil commun.
 * Restait le partage envoyé <b>avant</b> le blocage, servi jusqu'à son échéance
 * à la personne qu'on venait de bloquer (demande mobile du 14/09/2026,
 * tracabilite §4). La primitive est celle de « tout couper », restreinte au fil
 * {@code DIRECT} des deux personnes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatBlockEffects {

    private final ConversationRepository conversationRepository;
    private final ChatService chatService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserBlocked(UserBlockedEvent event) {
        try {
            conversationRepository.findDirectBetween(event.blockerId(), event.blockedId())
                .ifPresent(fil -> {
                    chatService.echoirPartagesDePosition(event.blockerId(), fil.getId());
                    chatService.echoirPartagesDePosition(event.blockedId(), fil.getId());
                });
        } catch (Exception e) {
            // Le blocage est commité et il tient : le faire échouer ici ne
            // protégerait personne. Le point échoit de lui-même sous trente minutes.
            log.error("Fin des partages de position échouée entre {} et {} : {}",
                event.blockerId(), event.blockedId(), e.getMessage(), e);
        }
    }
}
