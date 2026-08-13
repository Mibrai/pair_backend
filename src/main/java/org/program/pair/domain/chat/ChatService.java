package org.program.pair.domain.chat;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.chat.dto.*;
import org.program.pair.domain.notification.UnreadChangedEvent;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final MessageEditHistoryRepository messageEditHistoryRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final HtmlSanitizer sanitizer;
    private final ApplicationEventPublisher eventPublisher;

    /** Longueur de l'aperçu de message porté par la push. */
    private static final int PREVIEW_MAX_LENGTH = 120;

    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request) {
        return createConversation(initiatorId, request, null, null);
    }

    /**
     * Ouvre — ou retrouve — la conversation directe entre deux personnes, en y
     * inscrivant le contexte qui les lie.
     *
     * <p>{@code programId} et {@code scheduleId} ne viennent pas du client : ils
     * sont dérivés du créneau par l'appelant qui le connaît ({@code SlotService}
     * au moment de rejoindre). Le corps de {@code POST /api/conversations} reste
     * inchangé, et une conversation ouverte depuis un profil naît sans contexte —
     * ce qui est le cas nominal, pas une dégradation.
     */
    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request,
                                                      UUID programId,
                                                      UUID scheduleId) {
        // 1. Check if target accepts messages
        User target = userRepository.findById(request.targetUserId())
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        if (!Boolean.TRUE.equals(target.getReceiveMessages())) {
            throw new ForbiddenException("Cet utilisateur n'accepte pas les messages.");
        }

        // 2. Check if DIRECT conversation already exists
        return conversationRepository
            .findDirectBetween(initiatorId, request.targetUserId())
            .map(conv -> {
                // Le contexte d'une conversation qui existe déjà est rafraîchi,
                // pas conservé : c'est la séance qu'on vient de rejoindre
                // ensemble qui lie les deux personnes maintenant, et c'est sa
                // date que le client compare pour griser le fil. Garder la
                // première fixerait l'en-tête sur un créneau passé alors qu'un
                // autre est à venir.
                applyContext(conv, request.activityContextId(), programId, scheduleId);
                return toSummaryDto(conversationRepository.save(conv), initiatorId);
            })
            .orElseGet(() -> {
                Conversation conv = new Conversation();
                conv.setType(ConversationType.DIRECT);
                applyContext(conv, request.activityContextId(), programId, scheduleId);
                Conversation saved = conversationRepository.save(conv);

                // Add both members
                addMember(saved.getId(), initiatorId);
                addMember(saved.getId(), request.targetUserId());

                return toSummaryDto(saved, initiatorId);
            });
    }

    /**
     * Écrit le contexte sur la conversation, sans l'effacer quand rien n'est
     * fourni : une conversation rouverte depuis un profil ne doit pas perdre le
     * programme et la séance qu'un passage par un créneau lui avait donnés.
     */
    private void applyContext(Conversation conv, UUID activityContextId,
                              UUID programId, UUID scheduleId) {
        if (activityContextId != null) {
            conv.setActivityContext(activityRepository.findById(activityContextId)
                .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable.")));
        }
        if (programId != null) {
            conv.setProgramId(programId);
        }
        if (scheduleId != null) {
            conv.setScheduleId(scheduleId);
        }
    }

    public MessageDto sendMessage(UUID senderId, SendMessageRequest request) {
        // 1. Verify sender is member of conversation
        Conversation conv = conversationRepository
            .findByIdAndMemberId(request.conversationId(), senderId)
            .orElseThrow(() -> new ForbiddenException("Accès conversation refusé."));

        // 2. Sanitize content (anti-XSS required)
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException("Message vide après sanitisation.");
        }

        // 3. Create message
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        Message message = new Message();
        message.setConversation(conv);
        message.setSender(sender);
        message.setContent(cleanContent);
        message.setStatus(MessageStatus.SENT);
        message = messageRepository.save(message);

        MessageDto dto = toMessageDto(message);

        // 4. Broadcast to all conversation members via WebSocket
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(conv.getId());

        for (UUID memberId : memberIds) {
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages",
                dto
            );
        }

        // 5. Push aux destinataires — le WebSocket ci-dessus ne porte que jusqu'à
        // une app ouverte, or le badge sert précisément quand elle est fermée.
        // L'expéditeur est exclu : il vient d'écrire, il n'a rien à lire.
        for (UUID memberId : memberIds) {
            if (!memberId.equals(senderId)) {
                eventPublisher.publishEvent(new MessageSentEvent(
                    memberId,
                    senderId,
                    conv.getId(),
                    message.getId(),
                    sender.getDisplayName(),
                    preview(cleanContent)));
            }
        }

        return dto;
    }

    /**
     * Aperçu affiché sur l'écran verrouillé. Tronqué : {@code content} monte à
     * 4000 caractères, une notification n'en montre qu'une poignée, et la charge
     * push est plafonnée à 4 Ko par APNs.
     *
     * <p>La coupe tombe sur une <b>frontière de mot</b> quand il y en a une dans
     * la fenêtre : couper au caractère près donne « … devant le cou… », qu'un
     * lecteur pressé lit comme un mot entier. Un texte de plus de 120 caractères
     * sans le moindre espace — une URL, un collage — n'en a pas : il est alors
     * coupé net, la seule règle qui tienne étant de ne pas dépasser.
     */
    private static String preview(String content) {
        if (content.length() <= PREVIEW_MAX_LENGTH) {
            return content;
        }
        String window = content.substring(0, PREVIEW_MAX_LENGTH);
        int lastSpace = window.lastIndexOf(' ');
        String cut = lastSpace > 0 ? window.substring(0, lastSpace) : window;
        // stripTrailing : la ponctuation reste, mais « bonjour , » ne doit pas
        // devenir « bonjour  … ».
        return cut.stripTrailing() + "…";
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getMyConversations(UUID userId) {
        List<Conversation> conversations = conversationRepository.findByMemberId(userId);

        // Contextes chargés en une fois pour toute la liste, plutôt qu'un aller
        // par fil : l'écran de messagerie les demande tous, à chaque ouverture.
        Map<UUID, ConversationContextDto> contexts = contextsOf(
            conversations.stream().map(Conversation::getId).toList());

        return conversations.stream()
            .map(conv -> toSummaryDto(conv, userId,
                contexts.getOrDefault(conv.getId(), ConversationContextDto.empty(conv.getId()))))
            .collect(Collectors.toList());
    }

    private Map<UUID, ConversationContextDto> contextsOf(List<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return conversationRepository.findContextsByIds(conversationIds).stream()
            .collect(Collectors.toMap(ConversationContextDto::conversationId, ctx -> ctx));
    }

    private ConversationContextDto contextOf(UUID conversationId) {
        return conversationRepository.findContextsByIds(List.of(conversationId)).stream()
            .findFirst()
            .orElseGet(() -> ConversationContextDto.empty(conversationId));
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID userId, UUID conversationId, int limit) {
        // Verify user is member
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ForbiddenException("Accès conversation refusé.");
        }

        return messageRepository
            .findByConversationIdOrderBySentAtDesc(conversationId, limit)
            .stream()
            .map(this::toMessageDto)
            .collect(Collectors.toList());
    }

    /**
     * Nombre de messages non lus, tous fils confondus.
     *
     * <p>Sert {@code GET /api/conversations/unread-count}, et c'est la moitié
     * « messagerie » du badge d'icône : la même requête alimente les deux, de
     * sorte que la somme du client et le nombre porté par la push ne peuvent pas
     * diverger.
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return messageRepository.countUnreadByUserId(userId);
    }

    public void markAsRead(UUID userId, UUID conversationId) {
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        // Lire ici fait baisser le badge des autres appareils du compte, qui
        // eux ne reçoivent aucune push sur une lecture.
        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    private void addMember(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation introuvable."));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        ConversationMember.ConversationMemberId id = new ConversationMember.ConversationMemberId();
        id.setConversationId(conversationId);
        id.setUserId(userId);

        ConversationMember member = new ConversationMember();
        member.setId(id);
        member.setConversation(conversation);
        member.setUser(user);
        member.setJoinedAt(Instant.now());
        conversationMemberRepository.save(member);
    }

    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId) {
        return toSummaryDto(conv, currentUserId, contextOf(conv.getId()));
    }

    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId,
                                                ConversationContextDto context) {
        // Get other user for DIRECT conversation
        UserPublicDto otherUser = null;
        if (conv.getType() == ConversationType.DIRECT) {
            List<UUID> memberIds = conversationMemberRepository
                .findUserIdsByConversationId(conv.getId());
            UUID otherUserId = memberIds.stream()
                .filter(id -> !id.equals(currentUserId))
                .findFirst()
                .orElse(null);

            if (otherUserId != null) {
                User other = userRepository.findById(otherUserId).orElse(null);
                if (other != null) {
                    otherUser = new UserPublicDto(
                        other.getId(),
                        other.getDisplayName(),
                        other.getBio(),
                        other.getAvatarUrl(),
                        other.getVerificationStatus().name(),
                        List.of(),
                        List.of(),
                        false
                    );
                }
            }
        }

        // Get last message
        Message lastMsg = messageRepository
            .findFirstByConversationIdOrderBySentAtDesc(conv.getId())
            .orElse(null);

        // Non lus du fil : les messages des autres, arrivés depuis la dernière
        // lecture. Ses propres messages et ceux qui ont été supprimés n'en sont
        // pas — c'est le décompte qu'un client somme pour obtenir son badge.
        int unreadCount = messageRepository
            .countUnreadByUserIdAndConversationId(currentUserId, conv.getId());

        return new ConversationSummaryDto(
            conv.getId(),
            conv.getType().name(),
            otherUser,
            context.activityName(),
            context.programId(),
            context.programTitle(),
            context.activityName(),
            context.scheduleId(),
            context.scheduleStartsAt(),
            context.scheduleEndsAt(),
            lastMsg != null ? lastMsg.getContent() : null,
            lastMsg != null ? lastMsg.getSentAt() : conv.getCreatedAt(),
            unreadCount
        );
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversationDetail(UUID userId, UUID conversationId) {
        // Verify user is member
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ForbiddenException("Accès conversation refusé.");
        }

        Conversation conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation introuvable."));

        // Get all members
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(conversationId);

        List<UserPublicDto> members = memberIds.stream()
            .map(id -> userRepository.findById(id).orElse(null))
            .filter(user -> user != null)
            .map(user -> new UserPublicDto(
                user.getId(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getVerificationStatus().name(),
                List.of(),
                List.of(),
                false
            ))
            .collect(Collectors.toList());

        ConversationContextDto context = contextOf(conversationId);

        return new ConversationDetailDto(
            conv.getId(),
            conv.getType().name(),
            members,
            context.activityName(),
            context.programId(),
            context.programTitle(),
            context.activityName(),
            context.scheduleId(),
            context.scheduleStartsAt(),
            context.scheduleEndsAt(),
            conv.getCreatedAt()
        );
    }

    public void deleteConversation(UUID userId, UUID conversationId) {
        // Verify user is member
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Accès conversation refusé."));

        // Soft delete: just remove the member
        conversationMemberRepository.delete(member);

        // Note: Actual conversation and messages remain in DB for other participants
        // This is a "hide" rather than true delete
    }

    public MessageDto editMessage(UUID userId, UUID messageId, EditMessageRequest request) {
        // 1. Find message and verify sender
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez modifier que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException("Impossible de modifier un message supprimé.");
        }

        // 2. Sanitize new content
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException("Message vide après sanitisation.");
        }

        // 3. Save edit history
        MessageEditHistory history = MessageEditHistory.builder()
            .message(message)
            .previousContent(message.getContent())
            .editedAt(Instant.now())
            .build();
        messageEditHistoryRepository.save(history);

        // 4. Update message
        message.setContent(cleanContent);
        message.setEditedAt(Instant.now());
        message = messageRepository.save(message);

        MessageDto dto = toMessageDto(message);

        // 5. Broadcast update via WebSocket
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(message.getConversation().getId());

        for (UUID memberId : memberIds) {
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages.edited",
                dto
            );
        }

        return dto;
    }

    public void deleteMessage(UUID userId, UUID messageId) {
        // 1. Find message and verify sender
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez supprimer que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException("Message déjà supprimé.");
        }

        // 2. Soft delete
        message.setDeletedAt(Instant.now());
        message.setContent("[Message supprimé]");
        messageRepository.save(message);

        // 3. Broadcast deletion via WebSocket
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(message.getConversation().getId());

        for (UUID memberId : memberIds) {
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages.deleted",
                messageId
            );
        }
    }

    public void markAllAsRead(UUID userId, UUID conversationId) {
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    public String uploadImage(UUID userId, UUID conversationId, String imageUrl) {
        // Verify user is member
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new ForbiddenException("Accès conversation refusé.");
        }

        // This method expects the image to be already uploaded to storage
        // and returns the URL. The actual file upload logic would be in the controller
        // using a file storage service (S3, local, etc.)
        return imageUrl;
    }

    private MessageDto toMessageDto(Message msg) {
        return new MessageDto(
            msg.getId(),
            msg.getConversation().getId(),
            msg.getSender().getId(),
            msg.getSender().getDisplayName(),
            msg.getSender().getAvatarUrl(),
            msg.getContent(),
            msg.getStatus().name(),
            msg.getSentAt()
        );
    }
}
