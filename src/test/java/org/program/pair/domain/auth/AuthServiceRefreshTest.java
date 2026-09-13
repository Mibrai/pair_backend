package org.program.pair.domain.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LogoutRequest;
import org.program.pair.domain.auth.session.SessionService;
import org.program.pair.domain.notification.DeviceTokenService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.InvalidTokenException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ce qu'{@code AuthService} fait d'un rafraîchissement et d'une déconnexion
 * depuis P-BS-03 : il délègue à {@link SessionService}, qui porte désormais les
 * refus — jeton d'accès, jeton invalide, compte désactivé ou effacé (voir
 * {@code SessionServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider tokenProvider;
    @Mock EmailVerificationService emailVerificationService;
    @Mock SessionService sessionService;
    @Mock DeviceTokenService deviceTokenService;

    @InjectMocks AuthService authService;

    @Test
    void refresh_devraitRendreUneSessionNeuve_avecLesDurees() {
        User user = utilisateurActif();
        doReturn(new SessionService.Jetons("acces-neuf", "rafraichissement-neuf", UUID.randomUUID()))
            .when(sessionService).echanger("presente");
        doReturn(user.getId()).when(tokenProvider).extractUserId("acces-neuf");
        doReturn(Optional.of(user)).when(userRepository).findById(user.getId());
        doReturn(900L).when(tokenProvider).accessTokenExpirySeconds();
        doReturn(2_592_000L).when(tokenProvider).refreshTokenExpirySeconds();

        AuthResponse reponse = authService.refreshToken("presente");

        assertThat(reponse.accessToken()).isEqualTo("acces-neuf");
        assertThat(reponse.refreshToken()).isEqualTo("rafraichissement-neuf");
        assertThat(reponse.expiresIn()).isEqualTo(900L);
        assertThat(reponse.refreshExpiresIn()).isEqualTo(2_592_000L);
        assertThat(reponse.userId()).isEqualTo(user.getId());
    }

    @Test
    void refresh_devraitLaisserPasserLeRefusDeLaSession() {
        doThrow(new InvalidTokenException("Refresh token invalide ou expiré."))
            .when(sessionService).echanger("jeton-bidon");

        assertThatThrownBy(() -> authService.refreshToken("jeton-bidon"))
            .isInstanceOf(InvalidTokenException.class)
            .hasMessage("Refresh token invalide ou expiré.");
    }

    @Test
    void seDeconnecter_detacheLAppareil_duSeulProprietaireDeLaSession() {
        UUID proprietaire = UUID.randomUUID();
        doReturn(Optional.of(proprietaire)).when(sessionService).fermer("rafraichissement");

        authService.logout(new LogoutRequest("rafraichissement", "jeton-fcm"));

        verify(deviceTokenService).unregisterToken(proprietaire, "jeton-fcm");
    }

    @Test
    void seDeconnecterAvecUnJetonInconnu_neDetacheRien() {
        doReturn(Optional.empty()).when(sessionService).fermer(anyString());

        authService.logout(new LogoutRequest("inconnu", "jeton-fcm"));
        authService.logout(null);

        verify(deviceTokenService, never()).unregisterToken(any(), any());
    }

    private static User utilisateurActif() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("refresh-" + UUID.randomUUID() + "@meetdo.test");
        user.setDisplayName("Compte de test");
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setIsActive(true);
        return user;
    }
}
