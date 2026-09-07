package org.program.pair.domain.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Vérification d'adresse e-mail — ticket client du 2026-08-25.
 *
 * <p>Le lien envoyé pointait sur {@code localhost:3000}, et les jetons vivaient
 * en mémoire. Ces tests verrouillent les deux corrections, plus la distinction
 * des quatre états que la page doit rendre.
 */
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationService emailVerificationService;

    /**
     * Il ne sert plus à lire le lien — l'e-mail passe par l'outbox depuis le
     * 07/09, et son corps s'y lit en clair — mais à faire croire au service
     * qu'un fournisseur est configuré : sans cela, {@code EmailService} prend son
     * repli de développement, journalise le lien et ne dépose rien.
     */
    @MockitoBean private ResendEmailService resendEmailService;

    @Autowired private org.program.pair.repository.OutboxMessageRepository outboxRepository;

    @BeforeEach
    void envoiActif() {
        given(resendEmailService.isEnabled()).willReturn(true);
        given(resendEmailService.sendHtmlEmail(anyString(), anyString(), anyString())).willReturn(true);
    }

    /**
     * Aplatit les blancs du HTML rendu.
     *
     * <p>Le gabarit coupe ses phrases sur plusieurs lignes pour rester lisible.
     * Sans cela, une assertion sur une phrase entière échouerait au premier
     * reformatage du gabarit — un test qui casse pour une raison qui n'est pas
     * celle qu'il vérifie.
     */
    private static String texte(String html) {
        return html == null ? "" : html.replaceAll("\\s+", " ");
    }

    private String inscrire(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", "MotDePasse1!", "displayName", "Testeur"))
            .exchange()
            .expectStatus().isCreated();
        return email;
    }

    private String jetonDe(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return authTokenRepository.findAll().stream()
            .filter(t -> t.getUser().getId().equals(user.getId()))
            .filter(t -> t.getType() == AuthTokenType.EMAIL_VERIFICATION)
            .findFirst().orElseThrow().getToken();
    }

    @Test
    @DisplayName("l'inscription pose un jeton en base, pas seulement en mémoire")
    void jetonPersiste() {
        String email = uniqueEmail("persistance");
        inscrire(email);

        User user = userRepository.findByEmail(email).orElseThrow();
        // C'est le cœur du correctif : avant, rien n'atteignait la base et un
        // redéploiement effaçait le jeton sans laisser de trace.
        assertThat(authTokenRepository.findAll())
            .anySatisfy(t -> {
                assertThat(t.getUser().getId()).isEqualTo(user.getId());
                assertThat(t.getType()).isEqualTo(AuthTokenType.EMAIL_VERIFICATION);
                assertThat(t.getConsumedAt()).isNull();
                assertThat(t.getExpiresAt()).isAfter(Instant.now());
            });
    }

    @Test
    @DisplayName("un navigateur reçoit une page HTML, pas du JSON")
    void navigateurRecoitDuHtml() {
        String email = uniqueEmail("navigateur");
        inscrire(email);

        String corps = webTestClient.get()
            .uri("/api/auth/verify-email?token=" + jetonDe(email))
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("Votre adresse est vérifiée");
        // Le pire cas du ticket : une réussite qui ressemble à une panne.
        assertThat(texte(corps)).doesNotContain("{\"");

        assertThat(userRepository.findByEmail(email).orElseThrow().getVerificationStatus())
            .isEqualTo(VerificationStatus.EMAIL_VERIFIED);
    }

    @Test
    @DisplayName("un second clic dit « déjà vérifiée », et non « lien inconnu »")
    void secondClicNeRessembblePasAUnePanne() {
        String email = uniqueEmail("second-clic");
        inscrire(email);
        String jeton = jetonDe(email);

        webTestClient.get().uri("/api/auth/verify-email?token=" + jeton)
            .accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk();

        String corps = webTestClient.get().uri("/api/auth/verify-email?token=" + jeton)
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("déjà vérifiée");
        assertThat(texte(corps)).doesNotContain("n'est pas reconnu");
    }

    @Test
    @DisplayName("un jeton échu est distingué d'un jeton inconnu")
    void jetonEchuDistingue() {
        String email = uniqueEmail("echu");
        inscrire(email);

        AuthToken jeton = authTokenRepository.findByTokenAndType(
            jetonDe(email), AuthTokenType.EMAIL_VERIFICATION).orElseThrow();
        jeton.setExpiresAt(Instant.now().minusSeconds(60));
        authTokenRepository.save(jeton);

        String corps = webTestClient.get().uri("/api/auth/verify-email?token=" + jeton.getToken())
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("a expiré");
        assertThat(texte(corps)).contains("nouvel e-mail de vérification");
    }

    @Test
    @DisplayName("un jeton inconnu rend une page, pas une trace d'erreur")
    void jetonInconnuRendUnePage() {
        String corps = webTestClient.get().uri("/api/auth/verify-email?token=nexiste-pas")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(corps)).contains("n'est pas reconnu");
    }

    @Test
    @DisplayName("le contrat JSON de l'app mobile est inchangé")
    void contratJsonInchange() {
        String email = uniqueEmail("contrat-json");
        inscrire(email);

        webTestClient.get().uri("/api/auth/verify-email?token=" + jetonDe(email))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk();

        // Un jeton invalide reste un 401 INVALID_TOKEN — c'est ce que le client
        // a mesuré depuis l'extérieur, et ce sur quoi l'app s'appuie.
        webTestClient.get().uri("/api/auth/verify-email?token=nexiste-pas")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("émettre un nouveau jeton referme le précédent")
    void nouveauJetonFermeLePrecedent() {
        String email = uniqueEmail("renvoi");
        inscrire(email);
        String premier = jetonDe(email);

        User user = userRepository.findByEmail(email).orElseThrow();
        emailVerificationService.sendVerificationEmail(user);

        assertThat(authTokenRepository.findByTokenAndType(premier, AuthTokenType.EMAIL_VERIFICATION)
            .orElseThrow().getConsumedAt())
            .as("deux liens actifs pour une même adresse laisseraient l'utilisateur choisir au hasard")
            .isNotNull();
    }

    @Test
    @DisplayName("le renvoi ne révèle pas si une adresse est inscrite")
    void renvoiNeRevelePasLesAdresses() {
        webTestClient.post().uri("/api/auth/resend-verification")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", "personne-ici@pair.app"))
            .exchange()
            .expectStatus().isOk();
    }

    // --- Chemin court /v/{token} — lot du 2026-08-26 -----------------------
    //
    // Un fichier d'association Apple ne raisonne que sur le chemin, et n'accepte
    // pas /api/* sans offrir à iOS de détourner toute l'API. D'où une seconde
    // adresse pour la même chose, qui doit se comporter exactement comme la
    // première : ces tests sont le miroir de ceux qui précèdent.

    @Test
    @DisplayName("le chemin court rend les quatre mêmes états que la route historique")
    void cheminCourtRendLesQuatreEtats() {
        String email = uniqueEmail("chemin-court");
        inscrire(email);
        String jeton = jetonDe(email);

        assertThat(texte(pageDe("/v/" + jeton))).contains("Votre adresse est vérifiée");
        assertThat(userRepository.findByEmail(email).orElseThrow().getVerificationStatus())
            .isEqualTo(VerificationStatus.EMAIL_VERIFIED);

        assertThat(texte(pageDe("/v/" + jeton))).contains("déjà vérifiée");
        assertThat(texte(pageDe("/v/nexiste-pas"))).contains("n'est pas reconnu");

        String autre = uniqueEmail("chemin-court-echu");
        inscrire(autre);
        AuthToken echu = authTokenRepository.findByTokenAndType(
            jetonDe(autre), AuthTokenType.EMAIL_VERIFICATION).orElseThrow();
        echu.setExpiresAt(Instant.now().minusSeconds(60));
        authTokenRepository.save(echu);
        assertThat(texte(pageDe("/v/" + echu.getToken()))).contains("a expiré");
    }

    @Test
    @DisplayName("le chemin court honore le contrat JSON, lui aussi")
    void cheminCourtHonoreLeContratJson() {
        // L'application annonce intercepter l'adresse avant que la requête ne
        // parte. Le jour où l'interception échouera, une route qui rendrait du
        // HTML à un client qui demande du JSON serait un trou silencieux.
        String email = uniqueEmail("chemin-court-json");
        inscrire(email);

        webTestClient.get().uri("/v/" + jetonDe(email))
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk();

        webTestClient.get().uri("/v/nexiste-pas")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("le lien envoyé porte le chemin court, seul déclaré à iOS")
    void lienEnvoyePorteLeCheminCourt() {
        // Le motif dans le fichier d'association et la route /v/{token} ne
        // servent à rien si l'e-mail continue d'envoyer l'ancienne adresse :
        // iOS ne regarde que le lien écrit dans le message.
        //
        // Le corps se lit désormais dans l'outbox plutôt que sur un mock : c'est
        // exactement ce qui partira, alors qu'un appel capturé ne prouvait que ce
        // qu'on avait demandé d'envoyer.
        String email = uniqueEmail("forme-du-lien");
        inscrire(email);

        assertThat(corpsDeposePour(email))
            .contains("/v/" + jetonDe(email))
            .doesNotContain("/api/auth/verify-email");
    }

    @Test
    @DisplayName("l'e-mail est déposé dans l'outbox, et le compte passe à PENDING")
    void lEmailPasseParLOutbox() {
        // Le cœur du lot du 07/09 : cet e-mail était le seul à partir par un
        // appel direct, donc le seul dont l'identifiant Resend était jeté, donc
        // le seul dont le rebond n'était rapporté à personne.
        String email = uniqueEmail("par-loutbox");
        inscrire(email);

        assertThat(outboxRepository.findAll())
            .filteredOn(m -> email.equals(m.getRecipient()))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.getPurpose())
                    .isEqualTo(org.program.pair.domain.outbox.OutboxPurpose.EMAIL_VERIFICATION);
                assertThat(m.getUserId()).isNotNull();
            });

        assertThat(userRepository.findByEmail(email).orElseThrow()
                .getVerificationEmailDelivery())
            .isEqualTo(org.program.pair.domain.user.VerificationEmailDelivery.PENDING);
    }

    @Test
    @DisplayName("l'e-mail est rédigé dans la langue demandée")
    void lEmailSuitAcceptLanguage() {
        // Il était un littéral français en dur : un germanophone, dont l'écran
        // d'inscription s'appelle Registrieren, recevait du français.
        String allemand = uniqueEmail("langue-de");
        inscrireEn(allemand, "de");
        assertThat(corpsDeposePour(allemand)).contains("Adresse bestätigen");

        String anglais = uniqueEmail("langue-en");
        inscrireEn(anglais, "en");
        assertThat(corpsDeposePour(anglais)).contains("Verify my address");

        // Sans en-tête, le repli reste le français — comportement d'avant.
        String defaut = uniqueEmail("langue-defaut");
        inscrire(defaut);
        assertThat(corpsDeposePour(defaut)).contains("Vérifier mon adresse");
    }

    private String corpsDeposePour(String email) {
        return outboxRepository.findAll().stream()
            .filter(m -> email.equals(m.getRecipient()))
            .map(org.program.pair.domain.outbox.OutboxMessage::getBody)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Aucun e-mail déposé pour " + email));
    }

    private void inscrireEn(String email, String langue) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Accept-Language", langue)
            .bodyValue(Map.of("email", email, "password", "MotDePasse1!", "displayName", "Testeur"))
            .exchange()
            .expectStatus().isCreated();
    }

    @Test
    @DisplayName("la page offre un retour vers l'app quand le compte est actif")
    void pageOffreUnRetourVersLApp() {
        // Sans lui, quelqu'un qui vient de valider son compte lit « c'est bon »
        // dans Safari et n'a nulle part où aller : le geste attendu — revenir à
        // l'app à la main — n'était écrit nulle part.
        String email = uniqueEmail("retour-app");
        inscrire(email);
        String jeton = jetonDe(email);

        String vientDEtreVerifie = pageDe("/v/" + jeton);
        String dejaVerifie = pageDe("/v/" + jeton);

        // Sur le lien lui-même, et non sur le texte de la page : le gabarit
        // porte un commentaire qui cite l'adresse, et une assertion plus lâche
        // passerait sans qu'aucun bouton n'existe.
        assertThat(vientDEtreVerifie).contains("href=\"meetdo://verify\"");
        assertThat(dejaVerifie).contains("href=\"meetdo://verify\"");

        // Sans jeton : à ce stade il ne sert plus à rien, et une URL qui en
        // porte un traîne ensuite dans l'historique du navigateur.
        assertThat(vientDEtreVerifie).doesNotContain("meetdo://verify/" + jeton);

        // Sur « expiré » et « inconnu » le compte n'est pas actif : il n'y
        // aurait rien à aller faire dans l'app.
        assertThat(pageDe("/v/nexiste-pas")).doesNotContain("href=\"meetdo://verify\"");
    }

    private String pageDe(String uri) {
        return webTestClient.get().uri(uri)
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();
    }
}
