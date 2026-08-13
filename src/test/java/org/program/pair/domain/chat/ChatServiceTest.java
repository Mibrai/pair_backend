package org.program.pair.domain.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.MessageRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    ConversationRepository conversationRepository;

    @Mock
    MessageRepository messageRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    SimpMessagingTemplate messagingTemplate;

    @Mock
    HtmlSanitizer sanitizer;

    @Mock
    org.program.pair.repository.ConversationMemberRepository conversationMemberRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    ChatService chatService;

    @Test
    void createConversation_devraitRejeter_siCibleNAccepteAucunMessage() {
        // Given
        UUID targetId = UUID.randomUUID();
        User target = new User();
        target.setId(targetId);
        target.setReceiveMessages(false);

        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        // When / Then
        assertThatThrownBy(() -> chatService.createConversation(
            UUID.randomUUID(),
            new CreateConversationRequest(targetId, null, null)))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("n'accepte pas les messages");
    }

    @Test
    void sendMessage_devraitSanitizerLeContenu_avantPersistance() {
        // Given
        UUID senderId = UUID.randomUUID();
        Conversation conv = buildConversationWithMember(senderId);

        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.of(conv));

        String malicious = "<img src=x onerror=alert(1)>Salut";
        String cleaned = "Salut";
        when(sanitizer.sanitize(malicious)).thenReturn(cleaned);

        User sender = new User();
        sender.setId(senderId);
        sender.setDisplayName("Sender");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMemberRepository.findUserIdsByConversationId(any()))
            .thenReturn(List.of(senderId));

        // When
        chatService.sendMessage(senderId,
            new SendMessageRequest(conv.getId(), malicious));

        // Then
        verify(sanitizer).sanitize(malicious);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());

        assertThat(captor.getValue().getContent()).doesNotContain("<script");
        assertThat(captor.getValue().getContent()).doesNotContain("onerror");
        assertThat(captor.getValue().getContent()).isEqualTo(cleaned);
    }

    @Test
    void sendMessage_devraitRejeter_siExpediteurNonMembre() {
        // Given
        UUID senderId = UUID.randomUUID();
        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> chatService.sendMessage(senderId,
            new SendMessageRequest(UUID.randomUUID(), "test")))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sendMessage_doitDemanderUnePush_pourChaqueDestinataire_jamaisPourLExpediteur() {
        // Le WebSocket ne porte que jusqu'à une app ouverte. Sans cette push,
        // un message reçu app fermée ne pose aucun aps.badge — et iOS garde alors
        // la valeur précédente, ce qui fige le badge sur l'icône.
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Conversation conv = buildConversationWithMember(senderId);

        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.of(conv));
        when(sanitizer.sanitize("Salut")).thenReturn("Salut");

        User sender = new User();
        sender.setId(senderId);
        sender.setDisplayName("Alice");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMemberRepository.findUserIdsByConversationId(any()))
            .thenReturn(List.of(senderId, recipientId));

        chatService.sendMessage(senderId, new SendMessageRequest(conv.getId(), "Salut"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue()).isInstanceOf(MessageSentEvent.class);
        MessageSentEvent event = (MessageSentEvent) captor.getValue();
        assertThat(event.recipientId()).isEqualTo(recipientId);
        assertThat(event.senderId()).isEqualTo(senderId);
        assertThat(event.senderName()).isEqualTo("Alice");
        assertThat(event.preview()).isEqualTo("Salut");
    }

    @Test
    void sendMessage_apercu_doitEtreTronque_pourTenirDansUneCharge() {
        // content monte à 4000 caractères, la charge APNs est plafonnée à 4 Ko,
        // et un écran verrouillé n'en montre qu'une poignée.
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Conversation conv = buildConversationWithMember(senderId);

        String longContent = "a".repeat(500);
        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.of(conv));
        when(sanitizer.sanitize(longContent)).thenReturn(longContent);

        User sender = new User();
        sender.setId(senderId);
        sender.setDisplayName("Alice");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMemberRepository.findUserIdsByConversationId(any()))
            .thenReturn(List.of(senderId, recipientId));

        chatService.sendMessage(senderId, new SendMessageRequest(conv.getId(), longContent));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(((MessageSentEvent) captor.getValue()).preview())
            .hasSize(121)
            .endsWith("…");
    }

    @Test
    void sendMessage_apercu_doitCouperSurUneFrontiereDeMot() {
        // Couper au caractère près donne « … devant le cou… », qu'un lecteur
        // pressé lit comme un mot entier. La coupe recule jusqu'au dernier espace.
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Conversation conv = buildConversationWithMember(senderId);

        String longContent = "On se retrouve devant le court numéro trois vers dix-huit heures "
            + "trente, juste après le cours de yoga du soir qui finit un peu avant.";
        stubSend(senderId, recipientId, conv, longContent);

        chatService.sendMessage(senderId, new SendMessageRequest(conv.getId(), longContent));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        String preview = ((MessageSentEvent) captor.getValue()).preview();

        assertThat(preview).endsWith("…");
        // Jamais plus long que la fenêtre : c'est ce qui protège la charge APNs.
        assertThat(preview.length()).isLessThanOrEqualTo(121);
        // Et le dernier mot conservé est entier — pas de moitié de mot suivie
        // d'une ellipse.
        String withoutEllipsis = preview.substring(0, preview.length() - 1);
        assertThat(longContent).startsWith(withoutEllipsis);
        assertThat(longContent.charAt(withoutEllipsis.length())).isEqualTo(' ');
    }

    private void stubSend(UUID senderId, UUID recipientId, Conversation conv, String content) {
        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.of(conv));
        when(sanitizer.sanitize(content)).thenReturn(content);

        User sender = new User();
        sender.setId(senderId);
        sender.setDisplayName("Alice");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMemberRepository.findUserIdsByConversationId(any()))
            .thenReturn(List.of(senderId, recipientId));
    }

    private Conversation buildConversationWithMember(UUID userId) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID());

        ConversationMember member = new ConversationMember();
        User u = new User();
        u.setId(userId);
        member.setUser(u);

        // Note: Les membres ne sont pas mappés bidirectionnellement dans Conversation
        // mais le repository findByIdAndMemberId le gère

        return conv;
    }
}
