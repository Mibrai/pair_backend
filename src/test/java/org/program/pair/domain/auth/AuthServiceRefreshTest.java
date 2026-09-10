package org.program.pair.domain.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.auth.dto.AuthResponse;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code /auth/refresh}, la route qui n'avait aucun test.
 *
 * <p>C'est l'absence de cette classe qui a laissé s'installer les deux défauts
 * qu'elle couvre : un jeton d'accès y était accepté et rendait une session
 * complète, et un compte désactivé y renouvelait la sienne indéfiniment. Rien ne
 * les signalait, parce que la route la plus décisive du parcours — celle qui
 * décide si les gens restent connectés — n'était vérifiée nulle part.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

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

    /**
     * Le cas nominal : une session entière, réémise. Le jeton rendu n'est pas
     * celui qui a été présenté — la réémission ne recopie rien, ce qui est ce
     * qui rend l'échéance glissante.
     */
    @Test
    void refresh_devraitRendreUneSessionNeuve_avecLesDurees() {
        User user = utilisateurActif();
        String presente = "jeton-de-rafraichissement-presente";

        doReturn(true).when(tokenProvider).validateToken(presente);
        doReturn(true).when(tokenProvider).estJetonDeRafraichissement(presente);
        doReturn(user.getId()).when(tokenProvider).extractUserId(presente);
        doReturn(Optional.of(user)).when(userRepository).findById(user.getId());
        doReturn("acces-neuf").when(tokenProvider).generateAccessToken(user.getId(), user.getEmail());
        doReturn("rafraichissement-neuf").when(tokenProvider).generateRefreshToken(user.getId());
        doReturn(900L).when(tokenProvider).accessTokenExpirySeconds();
        doReturn(2_592_000L).when(tokenProvider).refreshTokenExpirySeconds();

        AuthResponse reponse = authService.refreshToken(presente);

        assertThat(reponse.accessToken()).isEqualTo("acces-neuf");
        assertThat(reponse.refreshToken())
            .isEqualTo("rafraichissement-neuf")
            .isNotEqualTo(presente);
        assertThat(reponse.expiresIn()).isEqualTo(900L);
        assertThat(reponse.refreshExpiresIn()).isEqualTo(2_592_000L);
        assertThat(reponse.userId()).isEqualTo(user.getId());
    }

    /**
     * Le défaut symétrique de celui du filtre : un jeton d'accès ouvrait ici une
     * session complète, alors que le claim {@code type} disait déjà ce qu'il
     * fallait savoir. Personne ne le lisait.
     */
    @Test
    void refresh_devraitRefuser_unJetonDAcces() {
        String jetonDAcces = "jeton-d-acces";

        doReturn(true).when(tokenProvider).validateToken(jetonDAcces);
        doReturn(false).when(tokenProvider).estJetonDeRafraichissement(jetonDAcces);

        assertThatThrownBy(() -> authService.refreshToken(jetonDAcces))
            .isInstanceOf(InvalidTokenException.class)
            .hasMessage("Refresh token invalide ou expiré.");

        // Aucun jeton n'est émis, et le compte n'est même pas chargé : le refus
        // tombe avant toute lecture de base.
        verify(userRepository, never()).findById(any());
        verify(tokenProvider, never()).generateAccessToken(any(), any());
    }

    /** Signature fausse ou échéance passée : le refus qui existait déjà, tenu. */
    @Test
    void refresh_devraitRefuser_unJetonQuiNeSeValidePas() {
        doReturn(false).when(tokenProvider).validateToken("jeton-bidon");

        assertThatThrownBy(() -> authService.refreshToken("jeton-bidon"))
            .isInstanceOf(InvalidTokenException.class);

        verify(tokenProvider, never()).generateRefreshToken(any());
    }

    /**
     * Un compte désactivé renouvelait sa session sans fin : {@code login} filtre
     * sur {@code isActive} depuis toujours, cette route chargeait par identifiant
     * et émettait. Le compte n'y perdait que la possibilité de se reconnecter —
     * ce qu'il n'avait aucune raison de faire, puisqu'il ne se déconnectait
     * jamais.
     */
    @Test
    void refresh_devraitRefuser_unCompteDesactive() {
        User desactive = utilisateurActif();
        desactive.setIsActive(false);
        String presente = "jeton-de-rafraichissement";

        doReturn(true).when(tokenProvider).validateToken(presente);
        doReturn(true).when(tokenProvider).estJetonDeRafraichissement(presente);
        doReturn(desactive.getId()).when(tokenProvider).extractUserId(presente);
        doReturn(Optional.of(desactive)).when(userRepository).findById(desactive.getId());

        assertThatThrownBy(() -> authService.refreshToken(presente))
            .isInstanceOf(InvalidTokenException.class);

        verify(tokenProvider, never()).generateRefreshToken(any());
    }

    /**
     * Un compte effacé — la suppression RGPD retire réellement la ligne — rend
     * lui aussi « cette session est finie » plutôt qu'un 404 : c'est le seul
     * refus que le client sait traduire par une déconnexion, et il n'y a rien à
     * réessayer.
     */
    @Test
    void refresh_devraitRefuser_unCompteEfface() {
        UUID disparu = UUID.randomUUID();
        String presente = "jeton-de-rafraichissement";

        doReturn(true).when(tokenProvider).validateToken(presente);
        doReturn(true).when(tokenProvider).estJetonDeRafraichissement(presente);
        doReturn(disparu).when(tokenProvider).extractUserId(presente);
        doReturn(Optional.empty()).when(userRepository).findById(disparu);

        assertThatThrownBy(() -> authService.refreshToken(presente))
            .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * Un compte en mémoire, jamais écrit : cette classe ne touche pas la base,
     * et son identifiant est tiré à chaque appel pour qu'aucune autre classe ne
     * puisse le croiser.
     */
    private static User utilisateurActif() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("refresh-" + UUID.randomUUID() + "@pair.app");
        user.setDisplayName("Compte de test");
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setIsActive(true);
        return user;
    }
}
