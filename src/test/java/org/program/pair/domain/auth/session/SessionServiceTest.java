package org.program.pair.domain.auth.session;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.InvalidTokenException;
import org.program.pair.shared.observabilite.ScheduledJobMetricsAspect;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P-BS-03 — les refus du rafraîchissement, et l'adoption des jetons émis avant
 * les sessions persistées, dans ses deux branches.
 */
class SessionServiceTest {

    private final RefreshSessionRepository sessions = mock(RefreshSessionRepository.class);
    private final RefreshTokenRepository jetons = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtTokenProvider provider = mock(JwtTokenProvider.class);
    private final ScheduledJobMetricsAspect metriques = mock(ScheduledJobMetricsAspect.class);

    private SessionService service(boolean adoption) {
        doReturn(Duration.ofDays(30)).when(provider).refreshTokenTtl();
        doAnswer(inv -> inv.getArgument(0)).when(sessions).save(any());
        doAnswer(inv -> inv.getArgument(0)).when(jetons).save(any());
        return new SessionService(sessions, jetons, users, provider, metriques, adoption);
    }

    @Test
    void unJetonDAcces_neSEchangePas() {
        doReturn(new JetonLu.Valide(UUID.randomUUID(), false)).when(provider).lire("acces");

        assertThatThrownBy(() -> service(true).echanger("acces")).isInstanceOf(InvalidTokenException.class);
        verify(users, never()).findById(any());
    }

    @Test
    void unJetonInvalideOuExpire_neSEchangePas() {
        doReturn(new JetonLu.Invalide()).when(provider).lire("bidon");
        doReturn(new JetonLu.Expire()).when(provider).lire("echu");

        assertThatThrownBy(() -> service(true).echanger("bidon")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service(true).echanger("echu")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unCompteDesactiveOuEfface_neRenouvellePasSaSession() {
        User desactive = compte(0);
        desactive.setIsActive(false);
        doReturn(new JetonLu.Valide(desactive.getId(), true)).when(provider).lire("desactive");
        doReturn(Optional.of(desactive)).when(users).findById(desactive.getId());
        UUID efface = UUID.randomUUID();
        doReturn(new JetonLu.Valide(efface, true)).when(provider).lire("efface");
        doReturn(Optional.empty()).when(users).findById(efface);

        assertThatThrownBy(() -> service(true).echanger("desactive")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service(true).echanger("efface")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unJetonHistoriqueSansSession_estAdopte_quandLAdoptionEstAllumee() {
        User user = compte(0);
        doReturn(new JetonLu.Valide(user.getId(), true)).when(provider).lire("historique");
        doReturn(Optional.of(user)).when(users).findById(user.getId());
        doReturn("acces").when(provider).generateAccessToken(any(), any(), anyInt());
        doReturn("rafraichissement").when(provider).generateRefreshToken(any(), any(), any(), any());

        SessionService.Jetons rendus = service(true).echanger("historique");

        assertThat(rendus.rafraichissement()).isEqualTo("rafraichissement");
        verify(sessions).save(any(RefreshSession.class));
        verify(jetons).save(any(RefreshToken.class));
    }

    @Test
    void unJetonHistoriqueSansSession_estRefuse_quandLAdoptionEstEteinte() {
        User user = compte(0);
        doReturn(new JetonLu.Valide(user.getId(), true)).when(provider).lire("historique");
        doReturn(Optional.of(user)).when(users).findById(user.getId());

        assertThatThrownBy(() -> service(false).echanger("historique")).isInstanceOf(InvalidTokenException.class);
        verify(sessions, never()).save(any());
    }

    @Test
    void unJetonHistorique_estRefuse_apresUnChangementDeMotDePasse() {
        // La version du compte a bougé : le jeton date d'avant, il ne s'adopte plus.
        User user = compte(1);
        doReturn(new JetonLu.Valide(user.getId(), true)).when(provider).lire("historique");
        doReturn(Optional.of(user)).when(users).findById(user.getId());

        assertThatThrownBy(() -> service(true).echanger("historique")).isInstanceOf(InvalidTokenException.class);
    }

    private static User compte(int version) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setIsActive(true);
        user.setTokenVersion(version);
        return user;
    }
}
