package org.program.pair.domain.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenProvider {

    /** Profils sous lesquels l'absence de clé est une erreur de démarrage. */
    private static final Set<String> PROFILS_DE_DEPLOIEMENT =
        Set.of("prod", "railway", "staging");

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry-ms:900000}")
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token-expiry-ms:2592000000}")
    private long refreshTokenExpiryMs;

    private final Environment environment;

    /** La clé de signature, résolue une fois au démarrage — voir {@link #resoudreCle()}. */
    private SecretKey signingKey;

    public JwtTokenProvider(Environment environment) {
        this.environment = environment;
    }

    /**
     * Résout la clé de signature au démarrage, et refuse de démarrer sans elle
     * sous un profil de déploiement.
     *
     * <p><b>Pourquoi ici, et pas une valeur de repli dans la configuration.</b>
     * Le dépôt est public. Une clé écrite dans {@code application.properties}
     * comme repli de {@code JWT_SECRET} serait une clé publiée : quiconque la
     * lit peut forger un jeton pour n'importe quel identifiant — le filtre
     * valide la signature puis charge l'utilisateur nommé par le jeton, rôles
     * compris. Le repli donnait l'apparence d'une configuration correcte tout en
     * laissant l'authentification entière contournable. Le faire échouer au
     * démarrage est le seul comportement qui ne ment pas.
     *
     * <p>Hors déploiement, une clé aléatoire est tirée pour la session : le
     * développement n'a pas à poser de variable, et des jetons qui ne survivent
     * pas au redémarrage y sont sans conséquence. Le profil {@code test} pose sa
     * propre clé fixe, donc ce chemin ne le concerne pas.
     */
    @PostConstruct
    void resoudreCle() {
        if (jwtSecret != null && !jwtSecret.isBlank()) {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
            return;
        }

        for (String profil : environment.getActiveProfiles()) {
            if (PROFILS_DE_DEPLOIEMENT.contains(profil)) {
                throw new IllegalStateException("""
                    JWT_SECRET est absente sous un profil de déploiement.

                    Cette clé signe les jetons d'accès. Sans elle, le serveur \
                    refuse de démarrer plutôt que de se rabattre sur une clé de \
                    repli : ce dépôt est public, une clé publiée laisserait forger \
                    un jeton pour n'importe quel compte.

                    Posez JWT_SECRET, par exemple « openssl rand -base64 48 ».""");
            }
        }

        byte[] ephemere = new byte[48];
        new SecureRandom().nextBytes(ephemere);
        this.signingKey = Keys.hmacShaKeyFor(ephemere);
        log.warn("Aucune JWT_SECRET configurée : une clé de signature éphémère est "
            + "tirée pour ce démarrage. Les jetons ne survivront pas au redémarrage. "
            + "Acceptable en développement, jamais en déploiement.");
    }

    public String generateAccessToken(UUID userId, String email) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs))
            .signWith(getSigningKey())
            .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs))
            .signWith(getSigningKey())
            .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(
            Jwts.parser().verifyWith(getSigningKey()).build()
                .parseSignedClaims(token).getPayload().getSubject()
        );
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private SecretKey getSigningKey() {
        return signingKey;
    }
}
