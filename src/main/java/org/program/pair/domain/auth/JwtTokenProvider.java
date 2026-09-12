package org.program.pair.domain.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.Profils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenProvider {

    /**
     * Le claim qui dit à quoi sert un jeton, et la seule valeur qu'il prenne.
     *
     * <p><b>L'absence du claim vaut « jeton d'accès », et doit continuer de le
     * valoir.</b> Les jetons d'accès émis jusqu'ici n'en portent pas ; en exiger
     * un reviendrait à refuser d'un seul déploiement tous les jetons en
     * circulation, c'est-à-dire à déconnecter tout le monde au moment précis où
     * ce lot cherche à ce que plus personne ne le soit. On lit donc le claim
     * pour reconnaître un jeton de rafraîchissement, jamais pour reconnaître un
     * jeton d'accès.
     */
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_RAFRAICHISSEMENT = "refresh";

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

        // Profils.DEPLOIEMENT, et non Profils.PRODUCTION : staging compris. Une
        // clé éphémère y serait tout aussi contournable qu'en production, et
        // personne ne lit le journal de démarrage d'un environnement déployé.
        if (Profils.actif(environment, Profils.DEPLOIEMENT)) {
            throw new IllegalStateException("""
                JWT_SECRET est absente sous un profil de déploiement.

                Cette clé signe les jetons d'accès. Sans elle, le serveur \
                refuse de démarrer plutôt que de se rabattre sur une clé de \
                repli : ce dépôt est public, une clé publiée laisserait forger \
                un jeton pour n'importe quel compte.

                Posez JWT_SECRET, par exemple « openssl rand -base64 48 ».""");
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
            .claim(CLAIM_TYPE, TYPE_RAFRAICHISSEMENT)
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

    /**
     * Ce que vaut un jeton présenté, en trois issues plutôt qu'en un booléen.
     *
     * <p><b>Pourquoi l'expiration mérite d'être séparée du reste.</b>
     * {@link #validateToken} répondait {@code false} aussi bien pour un jeton
     * simplement périmé que pour une signature fausse ou une chaîne illisible :
     * le {@code catch} attrapait {@link JwtException} et l'expiration est un
     * sous-type de celle-ci. Le point d'entrée d'authentification n'avait donc
     * qu'un seul refus à rendre pour trois situations qui n'appellent pas la
     * même chose chez le client — un jeton périmé se rafraîchit en silence,
     * un jeton absent ou forgé désigne une session finie et doit renvoyer à
     * l'écran de connexion. {@code io.jsonwebtoken} nous donne exactement cette
     * frontière avec {@link ExpiredJwtException} ; il suffisait de cesser de
     * l'avaler.
     *
     * <p>Cette méthode ne dit rien du <i>type</i> du jeton : un jeton de
     * rafraîchissement parfaitement valide est {@link EtatJeton#VALIDE} ici.
     * C'est {@link #estJetonDeRafraichissement} qui tranche l'usage.
     */
    public EtatJeton etatDe(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return EtatJeton.VALIDE;
        } catch (ExpiredJwtException e) {
            return EtatJeton.EXPIRE;
        } catch (JwtException | IllegalArgumentException e) {
            return EtatJeton.INVALIDE;
        }
    }

    /** Les trois issues de la lecture d'un jeton — voir {@link #etatDe}. */
    public enum EtatJeton {
        /** Signature reconnue, échéance non atteinte. */
        VALIDE,
        /** Signature reconnue, mais l'échéance est passée. Un rafraîchissement le répare. */
        EXPIRE,
        /** Illisible, mal signé, ou vide. Rien ne le répare. */
        INVALIDE
    }

    /**
     * Signature et échéance, sans la nuance : conservé parce que la plupart des
     * appelants n'ont qu'un oui ou un non à en tirer. Ceux qui doivent
     * distinguer un jeton périmé d'un jeton illisible passent par
     * {@link #etatDe}.
     */
    public boolean validateToken(String token) {
        return etatDe(token) == EtatJeton.VALIDE;
    }

    /**
     * Dit si le jeton présenté est un jeton de <b>rafraîchissement</b>.
     *
     * <p><b>Ce que cette lecture ferme.</b> Le claim {@code type} était écrit par
     * {@link #generateRefreshToken} et n'était lu nulle part. Le filtre
     * d'authentification acceptait donc comme jeton d'accès n'importe quel JWT
     * signé par notre clé, jeton de rafraîchissement compris : un secret de
     * trente jours ouvrait toutes les routes protégées, là où le quart d'heure
     * du jeton d'accès était précisément ce qui devait borner le coût d'une
     * fuite. Dans l'autre sens, {@code /auth/refresh} acceptait un jeton d'accès
     * et rendait une session complète — moins grave, mais tout aussi faux.
     *
     * <p>Un jeton illisible, mal signé ou périmé rend {@code false}. La question
     * ne se pose que sur un jeton dont on a déjà établi qu'il vaut quelque
     * chose, et là où elle se poserait quand même, refuser est la réponse sûre.
     */
    public boolean estJetonDeRafraichissement(String token) {
        try {
            String type = Jwts.parser().verifyWith(getSigningKey()).build()
                .parseSignedClaims(token).getPayload().get(CLAIM_TYPE, String.class);
            return TYPE_RAFRAICHISSEMENT.equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * La durée de vie d'un jeton d'accès, en secondes.
     *
     * <p>Exposée parce que le client la recopiait à la main d'un document
     * d'août : le jour où {@code jwt.access-token-expiry-ms} changera, rien ne
     * le lui aurait dit, et son rafraîchissement anticipé serait parti trop tard
     * (des 401 en rafale) ou beaucoup trop tôt (un rafraîchissement par
     * requête). Elle part désormais dans chaque {@code AuthResponse}.
     *
     * <p>Une durée <b>relative</b>, jamais une date : l'horloge d'un téléphone
     * peut être fausse de plusieurs heures, et c'est en comparant une échéance
     * absolue à l'heure de l'appareil que le client a fermé des sessions
     * valides. Et lue ici plutôt que recopiée dans le service, pour qu'un seul
     * endroit connaisse le nombre.
     */
    public long accessTokenExpirySeconds() {
        return accessTokenExpiryMs / 1000;
    }

    /** La durée de vie d'un jeton de rafraîchissement, en secondes. Mêmes raisons
     *  que {@link #accessTokenExpirySeconds}. */
    public long refreshTokenExpirySeconds() {
        return refreshTokenExpiryMs / 1000;
    }

    private SecretKey getSigningKey() {
        return signingKey;
    }
}
