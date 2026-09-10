package org.program.pair.domain.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.JwtTokenProvider.EtatJeton;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le type d'un jeton et son état : les deux lectures qui manquaient.
 *
 * <p><b>Ce que ces tests ferment.</b> {@code generateRefreshToken} écrivait un
 * claim {@code type} que personne ne lisait. Un jeton de rafraîchissement valait
 * donc jeton d'accès partout, et un jeton d'accès valait jeton de
 * rafraîchissement sur {@code /auth/refresh} — deux confusions symétriques, dont
 * la première offrait trente jours là où quinze minutes étaient prévues.
 *
 * <p>Et {@code validateToken} confondait « périmé » avec « illisible », ce qui
 * empêchait le point d'entrée d'authentification de distinguer un 401 qu'un
 * rafraîchissement répare d'un 401 qui renvoie à l'écran de connexion.
 */
class JwtTokenProviderTypeTest {

    private static final String CLE_FIXE =
        "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=";

    /** Quinze minutes et trente jours, en millisecondes — les valeurs de production. */
    private static final long ACCES_MS = 900_000L;
    private static final long RAFRAICHISSEMENT_MS = 2_592_000_000L;

    /**
     * L'asymétrie qui protège les jetons déjà en circulation : un jeton d'accès
     * ne porte aucun {@code type}, et son absence ne doit surtout pas être lue
     * comme un doute. Exiger le claim des deux côtés aurait refusé d'un seul
     * déploiement tous les jetons émis jusqu'ici.
     */
    @Test
    void unJetonDAcces_nEstPasUnJetonDeRafraichissement() {
        JwtTokenProvider provider = provider(ACCES_MS, RAFRAICHISSEMENT_MS);

        String acces = provider.generateAccessToken(UUID.randomUUID(), "a@b.c");

        assertThat(claims(acces).get("type")).isNull();
        assertThat(provider.estJetonDeRafraichissement(acces)).isFalse();
        assertThat(provider.etatDe(acces)).isEqualTo(EtatJeton.VALIDE);
    }

    /** Et le jeton long, lui, se reconnaît — c'est ce qui permet de le refuser en Bearer. */
    @Test
    void unJetonDeRafraichissement_seReconnait() {
        JwtTokenProvider provider = provider(ACCES_MS, RAFRAICHISSEMENT_MS);

        String rafraichissement = provider.generateRefreshToken(UUID.randomUUID());

        assertThat(provider.estJetonDeRafraichissement(rafraichissement)).isTrue();
        assertThat(provider.etatDe(rafraichissement)).isEqualTo(EtatJeton.VALIDE);
    }

    /**
     * Un jeton périmé se distingue d'un jeton illisible. C'est toute la couture
     * offerte au point d'entrée d'authentification : le premier vaut un
     * rafraîchissement silencieux, le second une reconnexion.
     */
    @Test
    void unJetonPerime_estExpireEtNonInvalide() {
        JwtTokenProvider provider = provider(-1_000L, -1_000L);

        String perime = provider.generateAccessToken(UUID.randomUUID(), "a@b.c");

        assertThat(provider.etatDe(perime)).isEqualTo(EtatJeton.EXPIRE);
        assertThat(provider.validateToken(perime)).isFalse();
    }

    /** Ce qui n'est pas un jeton du tout, et ce qu'une autre clé a signé. */
    @Test
    void unJetonIllisibleOuMalSigne_estInvalide() {
        JwtTokenProvider provider = provider(ACCES_MS, RAFRAICHISSEMENT_MS);

        String forge = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("type", "refresh")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                "YXByaWNvZGV2YXBwbGljYXRpb25wYWlyYXV0aGVudGljYXRpb25zZWNyZXRrZXk=")))
            .compact();

        assertThat(provider.etatDe("pas.un.jeton")).isEqualTo(EtatJeton.INVALIDE);
        assertThat(provider.etatDe("")).isEqualTo(EtatJeton.INVALIDE);
        assertThat(provider.etatDe(forge)).isEqualTo(EtatJeton.INVALIDE);
        // Un jeton signé ailleurs porte bien « refresh », et doit tout de même
        // être refusé : le type ne se lit qu'après la signature.
        assertThat(provider.estJetonDeRafraichissement(forge)).isFalse();
    }

    /**
     * La rotation est <b>glissante</b>, et c'est la question qui décidait de tout
     * pour le client : un jeton réémis vaut trente jours à compter de sa propre
     * émission, jamais l'échéance d'un prédécesseur. Rien n'est persisté pour ces
     * jetons, donc rien ne pourrait être hérité — mais c'est une propriété qu'on
     * veut voir tenue par un test plutôt que déduite de l'absence de code.
     */
    @Test
    void unJetonDeRafraichissement_porteUneEcheanceRecalculee() {
        JwtTokenProvider provider = provider(ACCES_MS, RAFRAICHISSEMENT_MS);
        UUID userId = UUID.randomUUID();

        Claims premier = claims(provider.generateRefreshToken(userId));
        Claims second = claims(provider.generateRefreshToken(userId));

        long dureePremier = premier.getExpiration().getTime() - premier.getIssuedAt().getTime();
        long dureeSecond = second.getExpiration().getTime() - second.getIssuedAt().getTime();

        // Chacun vaut la durée pleine à partir de sa propre émission : le second
        // n'est pas ce qui restait au premier.
        assertThat(dureePremier).isEqualTo(RAFRAICHISSEMENT_MS);
        assertThat(dureeSecond).isEqualTo(RAFRAICHISSEMENT_MS);
        assertThat(second.getExpiration()).isAfterOrEqualTo(premier.getExpiration());
        assertThat(second.getIssuedAt()).isCloseTo(new java.util.Date(), 60_000L);
    }

    /**
     * Les durées telles qu'elles partent au client, en secondes : c'est la seule
     * source, pour que le jour où la configuration change, un seul endroit le
     * sache.
     */
    @Test
    void lesDurees_sontExposeesEnSecondes() {
        JwtTokenProvider provider = provider(ACCES_MS, RAFRAICHISSEMENT_MS);

        assertThat(provider.accessTokenExpirySeconds()).isEqualTo(900L);
        assertThat(provider.refreshTokenExpirySeconds()).isEqualTo(2_592_000L);
    }

    /** Lit les claims avec la clé de ce test — la signature est donc vérifiée au passage. */
    private static Claims claims(String token) {
        return Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(CLE_FIXE)))
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /** Monte le composant comme Spring le ferait, avec les échéances demandées. */
    private static JwtTokenProvider provider(long accesMs, long rafraichissementMs) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles("test");

        JwtTokenProvider provider = new JwtTokenProvider(environnement);
        ReflectionTestUtils.setField(provider, "jwtSecret", CLE_FIXE);
        ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", accesMs);
        ReflectionTestUtils.setField(provider, "refreshTokenExpiryMs", rafraichissementMs);
        provider.resoudreCle();
        return provider;
    }
}
