package org.program.pair.config;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider tokenProvider;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker (will use Redis in Phase 4)
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Main WebSocket endpoint for chat with SockJS fallback
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(
                "https://pair-frontend-omega.vercel.app",
                "http://localhost:5173",
                "http://localhost:3000"
            )
            .withSockJS();

        // Alternative endpoint without SockJS for native WebSocket clients
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(
                "https://pair-frontend-omega.vercel.app",
                "http://localhost:5173",
                "http://localhost:3000"
            );
    }

    /**
     * Authentifie la session STOMP à l'ouverture, et la refuse à défaut.
     *
     * <p>Le refus est le point important. La version précédente posait
     * l'utilisateur quand le jeton était valide et, sinon, <b>laissait passer la
     * trame</b> : une connexion sans en-tête, ou porteuse d'un jeton expiré ou
     * forgé, était acceptée et donnait une session anonyme. Une telle session ne
     * reçoit rien des files {@code /user/**}, qui sont résolues par principal —
     * mais elle est établie, elle consomme une connexion, elle peut s'abonner à
     * tout ce qui n'est pas nominatif, et surtout elle contredit ce que le nom
     * de cette méthode annonce.
     *
     * <p>Le canal HTTP suit une règle inverse et volontaire : {@code /ws/**} est
     * ouvert dans {@code SecurityConfig}, parce que la poignée de main WebSocket
     * ne porte pas d'en-tête {@code Authorization}. L'authentification ne peut
     * donc avoir lieu qu'ici, à la trame {@code CONNECT}, et c'est le seul
     * endroit où elle peut être exigée.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);

                if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;
                }

                String header = accessor.getFirstNativeHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ")) {
                    throw new AccessDeniedException("Connexion WebSocket sans jeton.");
                }

                String token = header.substring(7);
                if (!tokenProvider.validateToken(token)) {
                    throw new AccessDeniedException("Connexion WebSocket avec un jeton invalide.");
                }

                UUID userId = tokenProvider.extractUserId(token);
                accessor.setUser(() -> userId.toString());
                return message;
            }
        });
    }
}
