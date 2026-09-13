package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.chat.ConversationMember;
import org.program.pair.domain.chat.ConversationType;
import org.program.pair.domain.chat.dto.ConversationDetailDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ConversationMemberRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-15 — le détail d'un fil se charge en autant de requêtes pour trente
 * membres que pour trois. Les membres étaient lus un par un.
 */
class ChatQueryCountIntegrationTest extends AbstractIntegrationTest {

    @Autowired ChatService chatService;
    @Autowired UserRepository userRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired ConversationMemberRepository conversationMemberRepository;

    @Test
    void leDetailDUnFil_seChargeEnAutantDeRequetesPourTrenteMembresQuePourTrois() {
        Fil trois = fil(3);
        Fil trente = fil(30);

        SqlCompteur.Releve<ConversationDetailDto> petit = SqlCompteur.pendant(
            () -> chatService.getConversationDetail(trois.lecteur(), trois.id()));
        SqlCompteur.Releve<ConversationDetailDto> grand = SqlCompteur.pendant(
            () -> chatService.getConversationDetail(trente.lecteur(), trente.id()));

        assertThat(petit.resultat().members()).hasSize(3);
        assertThat(grand.resultat().members()).hasSize(30);
        assertThat(SqlCompteur.lisant(grand.requetes(), "users"))
            .as("les membres se lisent en une fois, quel que soit leur nombre")
            .isEqualTo(SqlCompteur.lisant(petit.requetes(), "users"));
        assertThat(grand.requetes()).hasSameSizeAs(petit.requetes());
    }

    private record Fil(UUID id, UUID lecteur) {}

    private Fil fil(int membres) {
        Conversation conversation = conversationRepository.save(
            Conversation.builder().type(ConversationType.GROUP).build());
        List<User> personnes = new ArrayList<>();
        for (int i = 0; i < membres; i++) {
            User user = userRepository.save(User.builder()
                .email(uniqueEmail("chat-qc"))
                .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
                .displayName("Membre " + i)
                .isActive(true)
                .build());
            personnes.add(user);
            ConversationMember membre = new ConversationMember();
            membre.getId().setConversationId(conversation.getId());
            membre.getId().setUserId(user.getId());
            membre.setConversation(conversation);
            membre.setUser(user);
            conversationMemberRepository.save(membre);
        }
        return new Fil(conversation.getId(), personnes.get(0).getId());
    }
}
