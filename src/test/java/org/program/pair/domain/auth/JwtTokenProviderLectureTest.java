package org.program.pair.domain.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-05 — un jeton se lit une fois, et cette lecture rend ensemble l'état, le
 * type et le sujet.
 */
class JwtTokenProviderLectureTest {

    private static final String CLE_FIXE =
        "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=";

    @Test
    void unJetonDAcces_seLitUneFois_etRendSonSujet() {
        JwtTokenProvider provider = provider(900_000L);
        UUID userId = UUID.randomUUID();

        JetonLu lu = provider.lire(provider.generateAccessToken(userId));

        assertThat(lu).isInstanceOfSatisfying(JetonLu.Valide.class, valide -> {
            assertThat(valide.sujet()).isEqualTo(userId);
            assertThat(valide.rafraichissement()).isFalse();
        });
    }

    @Test
    void unJetonDeRafraichissement_seReconnaitALaMemeLecture() {
        JwtTokenProvider provider = provider(900_000L);

        JetonLu lu = provider.lire(provider.generateRefreshToken(UUID.randomUUID()));

        assertThat(lu).isInstanceOfSatisfying(JetonLu.Valide.class,
            valide -> assertThat(valide.rafraichissement()).isTrue());
    }

    @Test
    void unJetonExpire_etUnJetonFalsifie_gardentDesIssuesDistinctes() {
        JwtTokenProvider provider = provider(-1_000L);
        String expire = provider.generateAccessToken(UUID.randomUUID());
        String falsifie = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                "YXV0cmUtY2xlLWF1dHJlLWNsZS1hdXRyZS1jbGUtYXV0cmUtY2xlLTEyMzQ1Njc=")))
            .compact();

        assertThat(provider.lire(expire)).isInstanceOf(JetonLu.Expire.class);
        assertThat(provider.lire(falsifie)).isInstanceOf(JetonLu.Invalide.class);
        assertThat(provider.lire("pas-un-jeton")).isInstanceOf(JetonLu.Invalide.class);
    }

    private static JwtTokenProvider provider(long accesMs) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles("test");
        JwtTokenProvider provider = new JwtTokenProvider(environnement);
        ReflectionTestUtils.setField(provider, "jwtSecret", CLE_FIXE);
        ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", accesMs);
        ReflectionTestUtils.setField(provider, "refreshTokenExpiryMs", 2_592_000_000L);
        provider.resoudreCle();
        return provider;
    }
}
