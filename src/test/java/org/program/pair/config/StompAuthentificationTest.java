package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Demande mobile du 13/09 — une session STOMP ne survit ni à son jeton, ni à un mot de passe changé. */
class StompAuthentificationTest {

    private final JwtTokenProvider jetons = fournisseur();
    private final UserRepository utilisateurs = mock(UserRepository.class);
    private final UUID userId = UUID.randomUUID();

    @Test
    void unJetonValide_ouvreLaSession_etLesTramesPassentAvantLEcheance() {
        compte(true, 0);
        Map<String, Object> session = new HashMap<>();
        StompAuthentification auth = new StompAuthentification(jetons, utilisateurs);

        auth.preSend(trame(StompCommand.CONNECT, session, jetons.generateAccessToken(userId, null, 0)), null);

        assertThat(session).containsKey(StompAuthentification.ECHEANCE);
        assertThatCode(() -> auth.preSend(trame(StompCommand.SEND, session, null), null)).doesNotThrowAnyException();
    }

    @Test
    void apresLEcheanceDuJeton_lesTramesSontRefusees() {
        compte(true, 0);
        Map<String, Object> session = new HashMap<>();
        new StompAuthentification(jetons, utilisateurs)
            .preSend(trame(StompCommand.CONNECT, session, jetons.generateAccessToken(userId, null, 0)), null);

        Instant apres = ((Instant) session.get(StompAuthentification.ECHEANCE)).plus(Duration.ofSeconds(1));
        StompAuthentification plusTard = new StompAuthentification(jetons, utilisateurs,
            Clock.fixed(apres, ZoneOffset.UTC));

        assertThatThrownBy(() -> plusTard.preSend(trame(StompCommand.SEND, session, null), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> plusTard.preSend(trame(StompCommand.SUBSCRIBE, session, null), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unJetonDAvantUnChangementDeMotDePasse_nOuvrePasDeSession() {
        compte(true, 1);
        assertThatThrownBy(() -> new StompAuthentification(jetons, utilisateurs).preSend(
                trame(StompCommand.CONNECT, new HashMap<>(), jetons.generateAccessToken(userId, null, 0)), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unCompteDesactive_ouUnJetonDeRafraichissement_nOuvrentPasDeSession() {
        compte(false, 0);
        StompAuthentification auth = new StompAuthentification(jetons, utilisateurs);
        assertThatThrownBy(() -> auth.preSend(
                trame(StompCommand.CONNECT, new HashMap<>(), jetons.generateAccessToken(userId, null, 0)), null))
            .isInstanceOf(AccessDeniedException.class);
        compte(true, 0);
        assertThatThrownBy(() -> auth.preSend(
                trame(StompCommand.CONNECT, new HashMap<>(), jetons.generateRefreshToken(userId)), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    /** Monte le composant comme Spring, avec une clé de test (jamais une clé publiée ailleurs). */
    private static JwtTokenProvider fournisseur() {
        JwtTokenProvider provider = new JwtTokenProvider(new MockEnvironment());
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "jwtSecret",
            java.util.Base64.getEncoder().encodeToString(new byte[64]));
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", 900_000L);
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "refreshTokenExpiryMs", 2_592_000_000L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(provider, "resoudreCle");
        return provider;
    }

    private void compte(boolean actif, int version) {
        User user = new User();
        user.setId(userId);
        user.setIsActive(actif);
        user.setTokenVersion(version);
        when(utilisateurs.findById(userId)).thenReturn(Optional.of(user));
    }

    private static Message<byte[]> trame(StompCommand commande, Map<String, Object> session, String jeton) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(commande);
        accessor.setSessionAttributes(session);
        if (jeton != null) {
            accessor.addNativeHeader("Authorization", "Bearer " + jeton);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
