package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RefreshRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /auth/refresh} de bout en bout : la route qui décide si les gens
 * restent connectés, et qui n'était couverte par rien.
 *
 * <p>Trois choses s'y jouent, et aucune ne se voyait avant ce lot : que la
 * réémission soit bien <b>glissante</b> (l'échéance du nouveau jeton est
 * recalculée, pas héritée — c'est ce que le client nous demandait de confirmer),
 * que les deux sortes de jetons cessent d'être interchangeables, et que les
 * durées partent au client au lieu d'être recopiées à la main chez lui.
 */
class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    /** Les durées de {@code application-test.properties}, qui sont celles de la production. */
    private static final long ACCES_SECONDES = 900L;
    private static final long RAFRAICHISSEMENT_SECONDES = 2_592_000L;

    @Autowired
    private UserRepository userRepository;

    /**
     * Le cœur du lot : une session réémise porte des jetons neufs, et le jeton
     * de rafraîchissement rendu vaut trente jours <b>à compter de maintenant</b>.
     *
     * <p>La vérification ne se contente pas de comparer deux échéances — deux
     * appels dans la même seconde donneraient la même — mais lit l'écart entre
     * l'émission et l'échéance <i>du jeton rendu</i>. Un jeton qui aurait hérité
     * de la chaîne d'origine porterait un écart plus court, et toute session
     * mourrait trente jours après la première connexion.
     */
    @Test
    void refresh_rendUneSessionNeuve_dontLEcheanceEstRecalculee() {
        AuthResponse session = inscrire("refresh-glissant");

        AuthResponse renouvelee = webTestClient.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshRequest(session.refreshToken()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(renouvelee).isNotNull();
        assertThat(renouvelee.userId()).isEqualTo(session.userId());
        assertThat(renouvelee.accessToken()).isNotBlank();
        assertThat(renouvelee.refreshToken()).isNotBlank();

        JsonNode nouveau = charge(renouvelee.refreshToken());
        long emission = nouveau.get("iat").asLong();
        long echeance = nouveau.get("exp").asLong();

        // Trente jours pleins, comptés depuis l'émission du jeton rendu.
        assertThat(echeance - emission).isEqualTo(RAFRAICHISSEMENT_SECONDES);
        // Et cette émission est bien « maintenant », pas celle de son prédécesseur.
        assertThat(emission).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(120L));
        // L'échéance du jeton rendu ne peut donc pas être en retrait de l'ancienne.
        assertThat(echeance).isGreaterThanOrEqualTo(charge(session.refreshToken()).get("exp").asLong());

        assertThat(renouvelee.expiresIn()).isEqualTo(ACCES_SECONDES);
        assertThat(renouvelee.refreshExpiresIn()).isEqualTo(RAFRAICHISSEMENT_SECONDES);
    }

    /**
     * Les durées partent aussi sur {@code /auth/register} — et sur
     * {@code /auth/login}, par la même méthode. Sans cela, un client qui n'a
     * jamais rafraîchi ne les connaîtrait pas.
     */
    @Test
    void register_porteLesDeuxDurees() {
        AuthResponse session = inscrire("refresh-durees");

        assertThat(session.expiresIn()).isEqualTo(ACCES_SECONDES);
        assertThat(session.refreshExpiresIn()).isEqualTo(RAFRAICHISSEMENT_SECONDES);
    }

    /**
     * Un jeton d'accès présenté à {@code /auth/refresh} ouvrait une session
     * complète : le claim {@code type} n'était lu ni ici ni ailleurs.
     */
    @Test
    void refresh_devraitRefuser_unJetonDAcces() {
        AuthResponse session = inscrire("refresh-jeton-acces");

        ErrorResponse erreur = webTestClient.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshRequest(session.accessToken()))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("INVALID_TOKEN");
    }

    /**
     * Le défaut le plus sérieux de l'audit, dans l'autre sens : le filtre
     * acceptait <b>tout</b> JWT signé par notre clé, si bien qu'un jeton de
     * rafraîchissement ouvrait toutes les routes protégées pendant trente jours,
     * là où le quart d'heure du jeton d'accès devait borner le coût d'une fuite.
     *
     * <p>Le jeton d'accès du même compte est éprouvé dans la foulée : sans ce
     * témoin, le test passerait tout aussi bien si la route s'était mise à
     * refuser tout le monde.
     */
    @Test
    void routeProtegee_devraitRefuser_unJetonDeRafraichissementEnBearer() {
        AuthResponse session = inscrire("refresh-en-bearer");

        webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(session.refreshToken()))
            .exchange()
            .expectStatus().isUnauthorized();

        webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(session.accessToken()))
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Un compte désactivé renouvelait sa session indéfiniment : {@code login}
     * filtrait sur {@code isActive}, {@code refreshToken} chargeait par
     * identifiant et émettait. La désactivation ne lui retirait donc que la
     * possibilité de se reconnecter — ce qu'il n'avait aucune raison de faire.
     */
    @Test
    void refresh_devraitRefuser_unCompteDesactive() {
        String email = uniqueEmail("refresh-desactive");
        AuthResponse session = inscrire(email, "MotDePasse123!");

        // Désactivation de ce compte-là, et de lui seul : la base est partagée
        // par toute la suite.
        User compte = userRepository.findByEmail(email).orElseThrow();
        compte.setIsActive(false);
        userRepository.save(compte);

        ErrorResponse erreur = webTestClient.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshRequest(session.refreshToken()))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("INVALID_TOKEN");
    }

    /** Une chaîne qui n'est pas un jeton : le refus qui existait déjà, tenu. */
    @Test
    void refresh_devraitRefuser_unJetonIllisible() {
        webTestClient.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshRequest("jeton.tout.a.fait.bidon"))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    private AuthResponse inscrire(String prefixeEmail) {
        return inscrire(uniqueEmail(prefixeEmail), "MotDePasse123!");
    }

    private AuthResponse inscrire(String email, String motDePasse) {
        AuthResponse session = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, motDePasse, "Compte de test"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(session).isNotNull();
        return session;
    }

    /**
     * La charge utile d'un JWT, lue sans vérifier la signature.
     *
     * <p>Le test regarde {@code iat} et {@code exp}, que le contrat ne publie
     * pas : les lire ici évite d'inscrire la clé de signature dans cette classe,
     * et ce qui est éprouvé — l'écart entre émission et échéance — ne dépend
     * pas de la signature. Celle-ci est vérifiée par ailleurs, à chaque appel
     * que ces tests font passer.
     */
    private JsonNode charge(String jwt) {
        String segment = jwt.split("\\.")[1];
        byte[] json = Base64.getUrlDecoder().decode(segment);
        try {
            return objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Charge utile JWT illisible : " + segment, e);
        }
    }
}
