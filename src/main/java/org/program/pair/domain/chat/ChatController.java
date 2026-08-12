package org.program.pair.domain.chat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.chat.dto.*;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Validated
public class ChatController {

    private final ChatService chatService;

    // REST endpoints for conversation management

    @PostMapping("/api/conversations")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummaryDto createConversation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateConversationRequest request) {
        return chatService.createConversation(principal.getId(), request);
    }

    @GetMapping("/api/conversations")
    @ResponseBody
    public List<ConversationSummaryDto> getMyConversations(
            @AuthenticationPrincipal UserPrincipal principal) {
        return chatService.getMyConversations(principal.getId());
    }

    /**
     * Nombre de <b>messages</b> non lus, tous fils confondus — pendant exact de
     * {@code GET /api/notifications/unread-count}, même forme de réponse.
     *
     * <p>Sans lui, connaître un seul entier coûte le chargement de toute la liste
     * des conversations, au démarrage et à chaque retour au premier plan. C'est
     * aussi la valeur que {@code aps.badge} additionne : l'exposer rend les deux
     * calculs vérifiables l'un par l'autre.
     */
    @GetMapping("/api/conversations/unread-count")
    @ResponseBody
    public Map<String, Long> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return Map.of("unreadCount", chatService.getUnreadCount(principal.getId()));
    }

    @PostMapping("/api/conversations/{conversationId}/messages")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto sendMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        return chatService.sendMessage(principal.getId(), request);
    }

    @GetMapping("/api/conversations/{conversationId}/messages")
    @ResponseBody
    public List<MessageDto> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        return chatService.getMessages(principal.getId(), conversationId, Math.min(limit, 100));
    }

    @PostMapping("/api/conversations/{conversationId}/read")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId) {
        chatService.markAsRead(principal.getId(), conversationId);
    }

    @GetMapping("/api/conversations/{conversationId}")
    @ResponseBody
    public ConversationDetailDto getConversationDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId) {
        return chatService.getConversationDetail(principal.getId(), conversationId);
    }

    @DeleteMapping("/api/conversations/{conversationId}")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId) {
        chatService.deleteConversation(principal.getId(), conversationId);
    }

    @PatchMapping("/api/messages/{messageId}")
    @ResponseBody
    public MessageDto editMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID messageId,
            @Valid @RequestBody EditMessageRequest request) {
        return chatService.editMessage(principal.getId(), messageId, request);
    }

    @DeleteMapping("/api/messages/{messageId}")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID messageId) {
        chatService.deleteMessage(principal.getId(), messageId);
    }

    @PostMapping("/api/conversations/{conversationId}/read-all")
    @ResponseBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllAsRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId) {
        chatService.markAllAsRead(principal.getId(), conversationId);
    }

    @PostMapping("/api/conversations/{conversationId}/images")
    @ResponseBody
    public String uploadImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam("image") String imageUrl) {
        return chatService.uploadImage(principal.getId(), conversationId, imageUrl);
    }

    // WebSocket message handler

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
        UUID senderId = UUID.fromString(principal.getName());
        chatService.sendMessage(senderId, request);
    }
}
