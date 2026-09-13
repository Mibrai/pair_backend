package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.chat.ConversationType;
import org.program.pair.domain.chat.Message;
import org.program.pair.domain.chat.jobs.ExpiredLocationSweepJob;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.MessageRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-20 — une position partagée échue est effacée au passage suivant du balayage,
 * une position encore valable reste.
 */
class ExpiredLocationSweepJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired ExpiredLocationSweepJob job;
    @Autowired MessageRepository messageRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired UserRepository userRepository;

    @Test
    void unePositionPartageeExpiree_estEffaceeAuPassageSuivant() {
        User auteur = userRepository.save(User.builder()
            .email(uniqueEmail("position"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Position")
            .isActive(true)
            .build());
        Conversation fil = conversationRepository.save(Conversation.builder().type(ConversationType.DIRECT).build());
        Message echue = message(fil, auteur, Instant.now().minus(5, ChronoUnit.MINUTES));
        Message valable = message(fil, auteur, Instant.now().plus(20, ChronoUnit.MINUTES));

        job.sweep();

        Message echueRelue = messageRepository.findById(echue.getId()).orElseThrow();
        assertThat(echueRelue.getLocationLat()).isNull();
        assertThat(echueRelue.getLocationLng()).isNull();
        assertThat(echueRelue.getLocationExpiresAt()).isNull();
        assertThat(messageRepository.findById(valable.getId()).orElseThrow().getLocationLat()).isNotNull();
    }

    private Message message(Conversation fil, User auteur, Instant expiration) {
        return messageRepository.save(Message.builder()
            .conversation(fil)
            .sender(auteur)
            .content("Je suis là")
            .locationLat(45.7640)
            .locationLng(4.8357)
            .locationExpiresAt(expiration)
            .build());
    }
}
