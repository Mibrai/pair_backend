package org.program.pair.domain.chat;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.chat.dto.*;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final HtmlSanitizer sanitizer;

    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request) {
        // 1. Check if target accepts messages
        User target = userRepository.findById(request.targetUserId())
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        if (!Boolean.TRUE.equals(target.getReceiveMessages())) {
            throw new ForbiddenException("Cet utilisateur n'accepte pas les messages.");
        }

        // 2. Check if DIRECT conversation already exists
        return conversationRepository
            .findDirectBetween(initiatorId, request.targetUserId())
            .map(conv -> toSummaryDto(conv, initiatorId))
            .orElseGet(() -> {
                Conversation conv = new Conversation();
                conv.setType(ConversationType.DIRECT);
                conv = conversationRepository.save(conv);

                // Add both members
                addMember(conv.getId(), initiatorId);
                addMember(conv.getId(), request.targetUserId());

                return toSummaryDto(conv, initiatorId);
            });
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

        return dto;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getMyConversations(UUID userId) {
        return conversationRepository.findByMemberId(userId).stream()
            .map(conv -> toSummaryDto(conv, userId))
            .collect(Collectors.toList());
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

    public void markAsRead(UUID userId, UUID conversationId) {
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);
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

        // Calculate unread count
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conv.getId(), currentUserId)
            .orElse(null);

        int unreadCount = 0;
        if (member != null && member.getLastReadAt() != null) {
            unreadCount = messageRepository
                .countByConversationIdAndSentAtAfter(conv.getId(), member.getLastReadAt());
        } else if (member != null) {
            unreadCount = messageRepository.countByConversationId(conv.getId());
        }

        return new ConversationSummaryDto(
            conv.getId(),
            conv.getType().name(),
            otherUser,
            null, // activityContextName - TODO Phase 2
            lastMsg != null ? lastMsg.getContent() : null,
            lastMsg != null ? lastMsg.getSentAt() : conv.getCreatedAt(),
            unreadCount
        );
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
