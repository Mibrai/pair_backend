package org.program.pair.domain.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ws")
public class WebSocketInfoController {

    private Map<String, Object> buildWebSocketInfo() {
        return Map.of(
            "error", "Invalid WebSocket endpoint",
            "message", "You are trying to connect to /ws which is not a valid WebSocket endpoint.",
            "correctEndpoint", Map.of(
                "url", "ws://localhost:8090/ws/chat",
                "protocol", "STOMP over WebSocket",
                "sockjs", true,
                "description", "Real-time chat messaging"
            ),
            "documentation", Map.of(
                "connect", "Use STOMP client to connect to ws://localhost:8090/ws/chat",
                "subscribe", "Subscribe to /topic/* or /queue/* for messages",
                "send", "Send messages to /app/* destinations"
            ),
            "example", "Use SockJS client: new SockJS('http://localhost:8090/ws/chat')"
        );
    }

    @GetMapping
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> getWebSocketInfo() {
        return buildWebSocketInfo();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> postWebSocketInfo() {
        return buildWebSocketInfo();
    }

    @RequestMapping(method = {RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> otherMethods() {
        return buildWebSocketInfo();
    }
}
