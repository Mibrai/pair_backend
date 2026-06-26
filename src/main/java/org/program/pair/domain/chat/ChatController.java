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

    // WebSocket message handler

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
        UUID senderId = UUID.fromString(principal.getName());
        chatService.sendMessage(senderId, request);
    }
}
