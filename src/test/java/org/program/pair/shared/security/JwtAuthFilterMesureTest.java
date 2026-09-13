package org.program.pair.shared.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P-BA-05 — le filtre lit le jeton une fois, mesure son coût, et peut le dire à
 * l'app par {@code Server-Timing}.
 */
class JwtAuthFilterMesureTest {

    private final JwtTokenProvider provider = mock(JwtTokenProvider.class);
    private final UserDetailsServiceImpl comptes = mock(UserDetailsServiceImpl.class);
    private final SimpleMeterRegistry registre = new SimpleMeterRegistry();

    @Test
    void uneRequeteAuthentifiee_litLeJetonUneFois_etSeMesure() throws Exception {
        UUID userId = UUID.randomUUID();
        doReturn(new JetonLu.Valide(userId, false)).when(provider).lire(anyString());
        doReturn(User.withUsername(userId.toString()).password("x").authorities(List.of()).build())
            .when(comptes).loadUserById(userId);
        JwtAuthFilter filtre = new JwtAuthFilter(provider, comptes, registre);
        ReflectionTestUtils.setField(filtre, "serverTiming", true);

        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(requeteAvec("jeton"), reponse, new MockFilterChain());

        verify(provider, times(1)).lire("jeton");
        verify(provider, never()).etatDe(anyString());
        verify(provider, never()).extractUserId(anyString());
        assertThat(registre.timer("auth.filter", "issue", "authentifie").count()).isEqualTo(1);
        assertThat(reponse.getHeader("Server-Timing")).startsWith("auth;dur=");
        SecurityContextHolder.clearContext();
    }

    @Test
    void unJetonDeRafraichissementEnBearer_resteRefuse_avecSonMotif() throws Exception {
        doReturn(new JetonLu.Valide(UUID.randomUUID(), true)).when(provider).lire(anyString());
        JwtAuthFilter filtre = new JwtAuthFilter(provider, comptes, registre);
        MockHttpServletRequest requete = requeteAvec("rafraichissement");
        MockHttpServletResponse reponse = new MockHttpServletResponse();

        filtre.doFilter(requete, reponse, new MockFilterChain());

        assertThat(requete.getAttribute(MotifRefusJwt.ATTRIBUT)).isEqualTo(MotifRefusJwt.JETON_DE_RAFRAICHISSEMENT);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(reponse.getHeader("Server-Timing")).as("éteint par défaut").isNull();
        assertThat(registre.timer("auth.filter", "issue", "refuse").count()).isEqualTo(1);
    }

    private static MockHttpServletRequest requeteAvec(String jeton) {
        MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/api/users/me");
        requete.addHeader("Authorization", "Bearer " + jeton);
        return requete;
    }
}
