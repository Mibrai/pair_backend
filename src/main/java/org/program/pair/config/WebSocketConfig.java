package org.program.pair.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;


@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthentification stompAuthentification;
    private final CorsProperties corsProperties;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker (will use Redis in Phase 4)
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origines : la liste de pair.cors.allowed-origins (P-BS-18), vide hors
        // dev. Liste vide = même origine seulement ; un client natif, qui
        // n'envoie pas d'en-tête Origin, reste accepté.
        String[] origines = corsProperties.allowedOrigins().toArray(new String[0]);

        // Main WebSocket endpoint for chat with SockJS fallback
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(origines)
            .withSockJS();

        // Alternative endpoint without SockJS for native WebSocket clients
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins(origines);
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
        registration.interceptors(stompAuthentification);
    }
}
