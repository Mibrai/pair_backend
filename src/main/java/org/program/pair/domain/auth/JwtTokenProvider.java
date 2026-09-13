package org.program.pair.domain.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.Profils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
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

    /** La session qui a émis le jeton (P-BS-03). Absente sur les jetons émis avant. */
    private static final String CLAIM_SESSION = "sid";

    /** La version des jetons du compte à l'émission (P-BS-03). Absente vaut 0. */
    private static final String CLAIM_VERSION = "ver";

    /**
     * Qui émet le jeton, et pour qui (P-BS-07). Sans eux, un jeton signé par
     * la même clé pour un autre usage — un autre service, un outil interne —
     * vaudrait jeton d'accès ici.
     */
    static final String EMETTEUR = "meetdo-api";
    static final String AUDIENCE = "meetdo-mobile";

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry-ms:900000}")
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token-expiry-ms:2592000000}")
    private long refreshTokenExpiryMs;

    /**
     * Exiger l'émetteur et l'audience, ou seulement refuser ceux qui sont faux.
     * Voir {@code pair.jwt.exiger-emetteur} dans {@code application.properties}
     * pour la date de bascule et la preuve qui l'autorise.
     */
    @Value("${pair.jwt.exiger-emetteur:false}")
    private boolean exigerEmetteur;

    private final Environment environment;

    /**
     * Les jetons encore acceptés sans émetteur. C'est la preuve de la bascule :
     * tant qu'il monte, des jetons émis avant ce déploiement circulent encore.
     */
    private final Counter jetonsSansEmetteur;

    /** La clé de signature, résolue une fois au démarrage — voir {@link #resoudreCle()}. */
    private SecretKey signingKey;

    /**
     * Le parseur, construit une fois avec la clé (P-BA-05) : chaque lecture en
     * reconstruisait un, trois fois par requête authentifiée.
     */
    private JwtParser parser;

    @Autowired
    public JwtTokenProvider(Environment environment, MeterRegistry registre) {
        this.environment = environment;
        this.jetonsSansEmetteur = Counter.builder("auth.jwt.sans_emetteur")
            .description("Jetons acceptés sans émetteur ni audience, pendant la tolérance de P-BS-07")
            .register(registre);
    }

    /** Hors Spring (tests unitaires) : un registre local, que personne ne lit. */
    public JwtTokenProvider(Environment environment) {
        this(environment, new SimpleMeterRegistry());
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
            this.parser = Jwts.parser().verifyWith(signingKey).build();
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
        this.parser = Jwts.parser().verifyWith(signingKey).build();
        log.warn("Aucune JWT_SECRET configurée : une clé de signature éphémère est "
            + "tirée pour ce démarrage. Les jetons ne survivront pas au redémarrage. "
            + "Acceptable en développement, jamais en déploiement.");
    }

    /**
     * Le jeton d'accès. <b>Plus d'adresse e-mail dedans</b> (P-BS-07) : personne
     * ne la lisait, ni le serveur ni l'app, et une charge JWT se décode sans clé —
     * l'adresse partait en clair dans chaque journal qui garde un en-tête.
     */
    public String generateAccessToken(UUID userId) {
        return generateAccessToken(userId, null, 0);
    }

    /**
     * Le jeton d'accès d'une session (P-BS-03) : il porte la session qui l'a émis
     * ({@code sid}) et la version des jetons du compte ({@code ver}). Le filtre
     * refuse un jeton dont la version n'est plus celle du compte.
     */
    public String generateAccessToken(UUID userId, UUID sessionId, int version) {
        var builder = Jwts.builder()
            .subject(userId.toString())
            .issuer(EMETTEUR)
            .audience().add(AUDIENCE).and()
            .claim(CLAIM_VERSION, version)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs));
        if (sessionId != null) {
            builder.claim(CLAIM_SESSION, sessionId.toString());
        }
        return builder.signWith(getSigningKey()).compact();
    }

    /**
     * Un jeton de rafraîchissement hors session — ce qu'émettait le serveur avant
     * P-BS-03. Il ne sert plus qu'aux tests du fournisseur ; {@code SessionService}
     * émet toujours la forme persistée.
     */
    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim(CLAIM_TYPE, TYPE_RAFRAICHISSEMENT)
            .issuer(EMETTEUR)
            .audience().add(AUDIENCE).and()
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs))
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * Le jeton de rafraîchissement d'une session persistée (P-BS-03) : son
     * {@code jti} désigne la ligne {@code refresh_tokens} qui dit s'il a servi,
     * s'il est révoqué, et de quel jeton il est né.
     */
    public String generateRefreshToken(UUID userId, UUID sessionId, UUID jti, Instant echeance) {
        return Jwts.builder()
            .id(jti.toString())
            .subject(userId.toString())
            .claim(CLAIM_TYPE, TYPE_RAFRAICHISSEMENT)
            .claim(CLAIM_SESSION, sessionId.toString())
            .issuer(EMETTEUR)
            .audience().add(AUDIENCE).and()
            .issuedAt(new Date())
            .expiration(Date.from(echeance))
            .signWith(getSigningKey())
            .compact();
    }

    /** La durée de vie d'un jeton de rafraîchissement, pour qui calcule une échéance. */
    public java.time.Duration refreshTokenTtl() {
        return java.time.Duration.ofMillis(refreshTokenExpiryMs);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(verifier(token).getSubject());
    }

    /**
     * Signature, échéance, puis émetteur et audience — la seule lecture d'un jeton.
     *
     * <p><b>Tolérante tant que {@link #exigerEmetteur} est faux.</b> Un émetteur
     * ou une audience <i>présents</i> doivent valoir les nôtres, sinon le jeton
     * est refusé. <i>Absents</i>, ils sont acceptés et comptés : les jetons émis
     * avant ce déploiement n'en portent pas — trente jours pour un jeton de
     * rafraîchissement — et les refuser d'emblée déconnecterait tout le monde.
     */
    private Claims verifier(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        String emetteur = claims.getIssuer();
        Set<String> audience = claims.getAudience();
        boolean emetteurAbsent = emetteur == null;
        boolean audienceAbsente = audience == null || audience.isEmpty();

        if (emetteurAbsent ? exigerEmetteur : !EMETTEUR.equals(emetteur)) {
            throw new JwtException("Émetteur du jeton refusé.");
        }
        if (audienceAbsente ? exigerEmetteur : !audience.contains(AUDIENCE)) {
            throw new JwtException("Audience du jeton refusée.");
        }
        if (emetteurAbsent || audienceAbsente) {
            jetonsSansEmetteur.increment();
        }
        return claims;
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
            verifier(token);
            return EtatJeton.VALIDE;
        } catch (ExpiredJwtException e) {
            return EtatJeton.EXPIRE;
        } catch (JwtException | IllegalArgumentException e) {
            return EtatJeton.INVALIDE;
        }
    }

    /**
     * Une lecture, trois réponses : l'état, le type et le sujet (P-BA-05).
     *
     * <p>Le filtre d'authentification appelait {@link #etatDe}, puis
     * {@link #estJetonDeRafraichissement}, puis {@link #extractUserId} : trois
     * vérifications de signature pour une requête. Celle-ci n'en fait qu'une, et
     * rend un résultat que le filtre n'a plus qu'à lire. Les trois méthodes
     * restent pour leurs autres appelants (WebSocket, rafraîchissement).
     */
    public JetonLu lire(String token) {
        try {
            Claims claims = verifier(token);
            String sid = claims.get(CLAIM_SESSION, String.class);
            Integer version = claims.get(CLAIM_VERSION, Integer.class);
            return new JetonLu.Valide(UUID.fromString(claims.getSubject()),
                TYPE_RAFRAICHISSEMENT.equals(claims.get(CLAIM_TYPE, String.class)),
                sid == null ? null : UUID.fromString(sid),
                claims.getId() == null ? null : UUID.fromString(claims.getId()),
                version == null ? 0 : version);
        } catch (ExpiredJwtException e) {
            return new JetonLu.Expire();
        } catch (JwtException | IllegalArgumentException e) {
            return new JetonLu.Invalide();
        }
    }

    /** Ce qu'une lecture de jeton a établi — voir {@link #lire}. */
    public sealed interface JetonLu {
        /**
         * Signature, échéance, émetteur et audience reconnus. {@code session} et
         * {@code jti} sont nuls sur un jeton émis avant P-BS-03 ; {@code version}
         * y vaut 0.
         */
        record Valide(UUID sujet, boolean rafraichissement, UUID session, UUID jti, int version)
            implements JetonLu {

            public Valide(UUID sujet, boolean rafraichissement) {
                this(sujet, rafraichissement, null, null, 0);
            }
        }
        /** Bien signé, mais échu : un rafraîchissement le répare. */
        record Expire() implements JetonLu {}
        /** Illisible, mal signé, d'un autre émetteur, ou vide. */
        record Invalide() implements JetonLu {}
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
            String type = verifier(token).get(CLAIM_TYPE, String.class);
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
