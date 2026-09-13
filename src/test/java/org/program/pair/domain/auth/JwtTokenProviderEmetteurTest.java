package org.program.pair.domain.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider.EtatJeton;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-BS-07 — le jeton dit qui l'a émis et pour qui, et ne porte plus l'adresse.
 *
 * <p>La tolérance est l'essentiel : les jetons émis avant ce déploiement n'ont ni
 * émetteur ni audience, et doivent rester acceptés jusqu'à la bascule. Un
 * émetteur <b>faux</b>, lui, est refusé dès maintenant.
 */
class JwtTokenProviderEmetteurTest {

    private static final String CLE_FIXE =
        "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=";
    private static final SecretKey CLE = Keys.hmacShaKeyFor(Decoders.BASE64.decode(CLE_FIXE));

    @Test
    void unJetonEmis_porteLEmetteurEtLAudienceDeMeetDo() {
        JwtTokenProvider provider = provider(false, new SimpleMeterRegistry());

        for (String jeton : new String[] {
                provider.generateAccessToken(UUID.randomUUID()),
                provider.generateRefreshToken(UUID.randomUUID())}) {
            Claims claims = claims(jeton);
            assertThat(claims.getIssuer()).isEqualTo("meetdo-api");
            assertThat(claims.getAudience()).containsExactly("meetdo-mobile");
        }
    }

    @Test
    void leJetonDAcces_neContientPlusLAdresseEmail() {
        JwtTokenProvider provider = provider(false, new SimpleMeterRegistry());

        Claims claims = claims(provider.generateAccessToken(UUID.randomUUID()));

        assertThat(claims.get("email")).isNull();
    }

    @Test
    void unJetonSansEmetteur_resteAccepte_tantQueLExigenceEstEteinte() {
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        JwtTokenProvider provider = provider(false, registre);
        UUID userId = UUID.randomUUID();
        String historique = jetonHistorique(userId);

        assertThat(provider.etatDe(historique)).isEqualTo(EtatJeton.VALIDE);
        assertThat(provider.extractUserId(historique)).isEqualTo(userId);
        assertThat(registre.counter("auth.jwt.sans_emetteur").count())
            .as("chaque acceptation tolérée est comptée : c'est la preuve de la bascule")
            .isPositive();
    }

    @Test
    void unJetonSansEmetteur_estRefuse_quandLExigenceEstAllumee() {
        JwtTokenProvider provider = provider(true, new SimpleMeterRegistry());
        String historique = jetonHistorique(UUID.randomUUID());

        assertThat(provider.etatDe(historique)).isEqualTo(EtatJeton.INVALIDE);
        assertThatThrownBy(() -> provider.extractUserId(historique)).isInstanceOf(JwtException.class);
    }

    @Test
    void unJetonDUnAutreEmetteur_estToujoursRefuse() {
        JwtTokenProvider provider = provider(false, new SimpleMeterRegistry());
        String etranger = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .issuer("un-autre-service")
            .audience().add("meetdo-mobile").and()
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(CLE)
            .compact();

        assertThat(provider.etatDe(etranger)).isEqualTo(EtatJeton.INVALIDE);
    }

    @Test
    void unJetonPourUneAutreAudience_estToujoursRefuse() {
        JwtTokenProvider provider = provider(false, new SimpleMeterRegistry());
        String etranger = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .issuer("meetdo-api")
            .audience().add("outil-interne").and()
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(CLE)
            .compact();

        assertThat(provider.etatDe(etranger)).isEqualTo(EtatJeton.INVALIDE);
        assertThat(provider.estJetonDeRafraichissement(etranger)).isFalse();
    }

    /** Un jeton tel qu'on les émettait avant P-BS-07 : ni iss, ni aud, et l'adresse. */
    private static String jetonHistorique(UUID userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", "ancien@meetdo.test")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(CLE)
            .compact();
    }

    private static Claims claims(String token) {
        return Jwts.parser().verifyWith(CLE).build().parseSignedClaims(token).getPayload();
    }

    private static JwtTokenProvider provider(boolean exigerEmetteur, SimpleMeterRegistry registre) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles("test");

        JwtTokenProvider provider = new JwtTokenProvider(environnement, registre);
        ReflectionTestUtils.setField(provider, "jwtSecret", CLE_FIXE);
        ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpiryMs", 2_592_000_000L);
        ReflectionTestUtils.setField(provider, "exigerEmetteur", exigerEmetteur);
        provider.resoudreCle();
        return provider;
    }
}
