package org.program.pair.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que dit le {@code code} d'un 401, et pourquoi la distinction vaut une
 * classe à elle seule.
 *
 * <p>Le point d'entrée d'authentification rendait {@code UNAUTHORIZED} pour
 * trois situations : jeton absent, jeton illisible, jeton simplement périmé.
 * Une seule des trois appelle un rafraîchissement — et le client, ne pouvant
 * pas trancher, rafraîchissait sur les trois. C'est la demande 5 du chantier
 * mobile du 10/09.
 *
 * <p>Le sens de ces tests tient donc autant dans ce qui <b>ne</b> doit
 * <b>pas</b> être rendu : un {@code TOKEN_EXPIRED} servi à tort enverrait le
 * client rafraîchir en boucle une session qu'aucun rafraîchissement ne répare.
 */
class AuthEntryPointCodesIntegrationTest extends AbstractIntegrationTest {

    /**
     * La clé du profil de test, pour forger un jeton <b>déjà périmé</b>.
     *
     * <p>Il n'y a pas d'autre moyen d'éprouver ce chemin : le seul jeton
     * expiré qu'on puisse obtenir autrement demanderait d'attendre un quart
     * d'heure. Signer ici est acceptable parce que ce qui est vérifié est
     * précisément que le serveur reconnaît sa propre signature <i>et</i>
     * constate l'échéance — les deux ensemble, ce qu'un jeton bidon ne
     * distinguerait pas.
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Le cas qui motive tout le lot : la session est intacte, seul le jeton
     * court a fait son temps. C'est le seul 401 sur lequel le client doit
     * rafraîchir en silence.
     */
    @Test
    void unJetonDAccesPerime_estNommeTOKEN_EXPIRED() {
        assertThat(refuser(jetonDAccesPerime()).code()).isEqualTo("TOKEN_EXPIRED");
    }

    /**
     * L'accent doit arriver entier.
     *
     * <p>Le point d'entrée écrit dans le {@code Writer} de la réponse, dont
     * l'encodage par défaut est l'ISO-8859-1 de la spécification servlet — et
     * {@code application/json} sans paramètre ne le change pas. Le message
     * précédent n'avait aucun accent, si bien que rien ne l'avait révélé ;
     * celui-ci en a deux, et le client l'affiche tel quel.
     */
    @Test
    void leMessageDUnJetonPerime_arriveEnUtf8() {
        assertThat(refuser(jetonDAccesPerime()).message())
            .isEqualTo("Le jeton d'accès a expiré.");
    }

    /** Aucun en-tête d'autorisation : ce n'est pas une session finie, c'est un appel anonyme. */
    @Test
    void aucunJeton_resteUNAUTHORIZED() {
        ErrorResponse erreur = webTestClient.get()
            .uri("/api/users/me")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("UNAUTHORIZED");
    }

    /** Une chaîne qui n'est pas un jeton. Rien ne la répare, surtout pas un rafraîchissement. */
    @Test
    void unJetonIllisible_resteUNAUTHORIZED() {
        assertThat(refuser("pas.un.jeton").code()).isEqualTo("UNAUTHORIZED");
    }

    /**
     * Un jeton signé par une <b>autre</b> clé : lisible, bien formé, et refusé.
     * Distinct du cas précédent, qui ne franchissait même pas l'analyse.
     */
    @Test
    void unJetonSigneAilleurs_resteUNAUTHORIZED() {
        SecretKey autreCle = Keys.hmacShaKeyFor(
            "une-autre-cle-de-signature-qui-fait-bien-quarante-huit-octets!!".getBytes());
        String etranger = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .expiration(new Date(System.currentTimeMillis() + 900_000))
            .signWith(autreCle)
            .compact();

        assertThat(refuser(etranger).code()).isEqualTo("UNAUTHORIZED");
    }

    /**
     * Un jeton de rafraîchissement présenté en {@code Bearer}. Il est parfaitement
     * valide et son échéance est à trente jours : il ne doit surtout pas être
     * nommé {@code TOKEN_EXPIRED}, sans quoi le client rafraîchirait — obtenant
     * une session neuve dont il referait aussitôt le même mauvais usage, en
     * boucle.
     */
    @Test
    void unJetonDeRafraichissementEnBearer_nEstPasNommePerime() {
        AuthResponse session = inscrire();

        assertThat(refuser(session.refreshToken()).code())
            .isEqualTo("UNAUTHORIZED")
            .isNotEqualTo("TOKEN_EXPIRED");
    }

    /** Le contrôle : un jeton d'accès valide passe, sinon les refus ci-dessus ne prouvent rien. */
    @Test
    void unJetonDAccesValide_passe() {
        AuthResponse session = inscrire();

        webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(session.accessToken()))
            .exchange()
            .expectStatus().isOk();
    }

    // ------------------------------------------------------------------ outils

    /** Le corps du 401 rendu sur une route protégée pour ce jeton-là. */
    private ErrorResponse refuser(String jeton) {
        ErrorResponse erreur = webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(jeton))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        return erreur;
    }

    /**
     * Un jeton d'accès de la bonne clé, périmé depuis une minute. Sans claim
     * {@code type} : c'est ce qui en fait un jeton d'accès, et non l'inverse.
     */
    private String jetonDAccesPerime() {
        SecretKey cle = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        long ilYAUneMinute = System.currentTimeMillis() - 60_000;
        return Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("email", "perime@exemple.test")
            .issuedAt(new Date(ilYAUneMinute - 900_000))
            .expiration(new Date(ilYAUneMinute))
            .signWith(cle)
            .compact();
    }

    private AuthResponse inscrire() {
        AuthResponse session = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(
                uniqueEmail("codes-401"), "MotDePasse123!", "Compte de test"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(session).isNotNull();
        return session;
    }
}
