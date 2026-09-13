package org.program.pair.config;

import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.program.pair.repository.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Qui parle sur le canal STOMP, et jusqu'à quand.
 *
 * <p><b>Au {@code CONNECT}</b>, la même exigence qu'une requête HTTP : un jeton
 * d'accès (jamais de rafraîchissement), un compte actif, et la version du jeton
 * égale à celle du compte — un mot de passe changé ferme la porte ici aussi.
 * L'ancienne vérification ne regardait que la signature et l'échéance.
 *
 * <p><b>Ensuite, l'échéance du jeton borne la session</b> (demande mobile du
 * 13/09). Le jeton ne se vérifie qu'à la connexion, et une session STOMP dure
 * bien plus que ses quinze minutes : sans cette borne, une session ouverte
 * survivait indéfiniment à son jeton. Passé l'échéance, tout {@code SEND} et tout
 * {@code SUBSCRIBE} sont refusés ; le client se reconnecte avec un jeton frais.
 * Le blocage, lui, n'a pas besoin de la session : il est appliqué à chaque envoi,
 * destinataires compris.
 */
@Component
public class StompAuthentification implements ChannelInterceptor {

    static final String ECHEANCE = "pair.jeton.echeance";

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final Clock horloge;

    @org.springframework.beans.factory.annotation.Autowired
    public StompAuthentification(JwtTokenProvider tokenProvider, UserRepository userRepository) {
        this(tokenProvider, userRepository, Clock.systemUTC());
    }

    StompAuthentification(JwtTokenProvider tokenProvider, UserRepository userRepository, Clock horloge) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.horloge = horloge;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            connecter(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())
                || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            exigerJetonEnCours(accessor);
        }
        return message;
    }

    private void connecter(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Connexion WebSocket sans jeton.");
        }
        String token = header.substring(7);
        JetonLu lu = tokenProvider.lire(token);
        if (!(lu instanceof JetonLu.Valide valide) || valide.rafraichissement()) {
            throw new AccessDeniedException("Connexion WebSocket avec un jeton invalide.");
        }
        UUID userId = valide.sujet();
        boolean accepte = userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .filter(u -> (u.getTokenVersion() == null ? 0 : u.getTokenVersion()) == valide.version())
            .isPresent();
        if (!accepte) {
            throw new AccessDeniedException("Connexion WebSocket avec un jeton qui ne vaut plus.");
        }
        Map<String, Object> attributs = accessor.getSessionAttributes();
        if (attributs != null) {
            attributs.put(ECHEANCE, tokenProvider.echeanceDe(token));
        }
        accessor.setUser(() -> userId.toString());
    }

    private void exigerJetonEnCours(StompHeaderAccessor accessor) {
        Map<String, Object> attributs = accessor.getSessionAttributes();
        Object echeance = attributs == null ? null : attributs.get(ECHEANCE);
        if (echeance instanceof Instant fin && !Instant.now(horloge).isBefore(fin)) {
            throw new AccessDeniedException(
                "Jeton expiré : reconnectez la session STOMP avec un jeton frais.");
        }
    }
}
