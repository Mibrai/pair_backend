package org.program.pair.domain.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.EmailAlreadyExistsException;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    JwtTokenProvider tokenProvider;

    @Mock
    EmailVerificationService emailVerificationService;

    @InjectMocks
    AuthService authService;

    @Test
    void register_devraitRejeter_siEmailDejaUtilise() {
        when(userRepository.existsByEmail("test@pair.app")).thenReturn(true);

        RegisterRequest request = new RegisterRequest(
            "test@pair.app", "Password123!", "Test User");

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_devraitHasherLeMotDePasse_avantSauvegarde() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("$2a$hashed");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.generateAccessToken(any(), any())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any())).thenReturn("refresh");

        authService.register(new RegisterRequest(
            "test@pair.app", "Password123!", "Test"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$hashed");
        // Verifier qu'on ne stocke jamais le mot de passe en clair
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("Password123!");
    }

    @Test
    void login_devraitRejeter_siMotDePasseIncorrect() {
        User user = buildActiveUser();
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("test@pair.app", "wrong")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_devraitRejeter_siCompteDesactive() {
        User inactiveUser = buildActiveUser();
        inactiveUser.setIsActive(false);
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        // findByEmail filtre deja is_active=true cote repository

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("test@pair.app", "any")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_messageErreur_devraitEtreGenerique_pourEmailEtMotDePasse() {
        // Securite : ne jamais reveler si c'est l'email ou le mot de passe qui est faux
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("inconnu@pair.app", "any")))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Identifiants invalides.");
    }

    /**
     * Empreinte bcrypt de forme réaliste : {@code $2a$}, 60 caractères.
     *
     * <p>Ce détail n'est pas cosmétique. {@code login} refuse d'emblée toute
     * empreinte qui ne ressemble pas à du bcrypt — moins de 60 caractères, ou
     * ne commençant pas par {@code $2} — et lève {@code InvalidCredentialsException}
     * sans jamais interroger le {@code PasswordEncoder}. Avec l'ancien fixture
     * {@code "$2a$hashed"} (11 caractères), le test du mot de passe incorrect
     * passait donc par ce garde-fou-là : il rendait bien l'exception attendue,
     * mais n'avait jamais comparé de mot de passe, et Mockito le signalait en
     * refusant un stub jamais appelé.
     */
    private static final String BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private User buildActiveUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("test@pair.app");
        u.setPasswordHash(BCRYPT_HASH);
        u.setIsActive(true);
        u.setVerificationStatus(VerificationStatus.UNVERIFIED);
        return u;
    }
}
