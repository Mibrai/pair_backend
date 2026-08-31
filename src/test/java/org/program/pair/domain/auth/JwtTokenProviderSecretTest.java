package org.program.pair.domain.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La clé de signature JWT : ce que son absence doit provoquer.
 *
 * <p>Le contournement que ces tests ferment : le dépôt est public, et une clé de
 * repli inscrite dans {@code application.properties} y serait publiée. Comme le
 * filtre valide la signature puis prend l'identifiant du jeton au pied de la
 * lettre, une clé publiée laisse forger un jeton pour n'importe quel compte. Le
 * seul comportement correct sans clé, sous un profil de déploiement, est de
 * refuser de démarrer.
 */
class JwtTokenProviderSecretTest {

    private static final String CLE_FIXE =
        "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=";

    /**
     * Le refus qui compte. Sans clé, sous prod/railway/staging, le démarrage
     * doit échouer plutôt que de se rabattre sur quoi que ce soit.
     */
    @Test
    void sansCleSousUnProfilDeDeploiement_leDemarrageDoitEchouer() {
        for (String profil : new String[] {"prod", "railway", "staging"}) {
            assertThatThrownBy(() -> provider("", profil))
                .as("profil %s", profil)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        }
    }

    /**
     * Hors déploiement, une clé éphémère est tirée : le développement démarre
     * sans variable, et des jetons qui ne survivent pas au redémarrage y sont
     * sans conséquence.
     */
    @Test
    void sansCleEnDeveloppement_uneCleEphemereEstTiree() {
        assertThatCode(() -> {
            JwtTokenProvider p = provider("", "dev");
            String token = p.generateAccessToken(UUID.randomUUID(), "a@b.c");
            assertThat(p.validateToken(token)).isTrue();
        }).doesNotThrowAnyException();
    }

    /**
     * Deux démarrages éphémères ne partagent pas la clé : un jeton signé par
     * l'un est rejeté par l'autre. C'est ce qui empêche la clé de dev de servir
     * ailleurs par mégarde.
     */
    @Test
    void deuxDemarragesEphemeres_neDoiventPasSeReconnaitre() {
        JwtTokenProvider premier = provider("", "dev");
        JwtTokenProvider second = provider("", "dev");

        String token = premier.generateAccessToken(UUID.randomUUID(), "a@b.c");
        assertThat(second.validateToken(token)).isFalse();
    }

    /**
     * Le contournement lui-même, reproduit : un jeton forgé avec une clé
     * <b>autre</b> que celle du serveur est rejeté. C'est la garantie que le
     * changement de clé en production invalide bien les jetons forgés avec
     * l'ancienne clé publiée.
     */
    @Test
    void unJetonForgeAvecUneAutreCle_doitEtreRejete() {
        JwtTokenProvider serveur = provider(CLE_FIXE, "prod");

        String cleForgeur = "YXByaWNvZGV2YXBwbGljYXRpb25wYWlyYXV0aGVudGljYXRpb25zZWNyZXRrZXk=";
        String jetonForge = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("email", "attaquant@example.invalid")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(cleForgeur)))
            .compact();

        assertThat(serveur.validateToken(jetonForge)).isFalse();
        assertThatThrownBy(() -> serveur.extractUserId(jetonForge))
            .isInstanceOf(JwtException.class);
    }

    /** Avec la bonne clé, un jeton du serveur se valide et rend son sujet. */
    @Test
    void unJetonSigneParLeServeur_seValide() {
        JwtTokenProvider serveur = provider(CLE_FIXE, "prod");
        UUID userId = UUID.randomUUID();

        String token = serveur.generateAccessToken(userId, "a@b.c");
        assertThat(serveur.validateToken(token)).isTrue();
        assertThat(serveur.extractUserId(token)).isEqualTo(userId);
    }

    /** Monte le composant comme Spring le ferait, avec le profil demandé actif. */
    private static JwtTokenProvider provider(String secret, String profil) {
        MockEnvironment environnement = new MockEnvironment();
        environnement.setActiveProfiles(profil);

        JwtTokenProvider provider = new JwtTokenProvider(environnement);
        ReflectionTestUtils.setField(provider, "jwtSecret", secret);
        ReflectionTestUtils.setField(provider, "accessTokenExpiryMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpiryMs", 2_592_000_000L);
        provider.resoudreCle();
        return provider;
    }
}
