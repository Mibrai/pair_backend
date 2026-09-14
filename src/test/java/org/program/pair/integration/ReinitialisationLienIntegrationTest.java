package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.AuthToken;
import org.program.pair.domain.auth.AuthTokenType;
import org.program.pair.domain.auth.EmailVerificationService;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Le lien « mot de passe oublié » — signalé le 14/09/2026 : il rendait
 * {@code 401 UNAUTHORIZED}, dans l'app comme dans un navigateur.
 *
 * <p>Ce qui est verrouillé ici : le lien de l'e-mail mène à une page, cette page
 * aboutit, ses échecs se lisent, et le jeton ne sort pas par un {@code Referer}.
 */
class ReinitialisationLienIntegrationTest extends AbstractIntegrationTest {

    private static final String MOT_DE_PASSE_INITIAL = "MotDePasse1!";
    private static final String NOUVEAU = "NouveauMotDePasse2!";

    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;

    /** Même déclaration qu'{@code EmailVerificationIntegrationTest}, pour partager son contexte. */
    @MockitoBean private ResendEmailService resendEmailService;

    @BeforeEach
    void envoiActif() {
        given(resendEmailService.isEnabled()).willReturn(true);
        given(resendEmailService.sendHtmlEmail(anyString(), anyString(), anyString())).willReturn(true);
    }

    // — la page —

    @Test
    void leLienCourt_doitRendreLeFormulaire_sansSession() {
        String jeton = jetonPour(inscrire("reset-page"));

        String html = page("/r/" + jeton)
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(texte(html))
            .contains("Choisissez un nouveau mot de passe")
            .contains("action=\"/r/" + jeton + "\"")
            .contains("autocomplete=\"new-password\"")
            .doesNotContain("UNAUTHORIZED");
    }

    @Test
    void lAncienLien_doitRendreLeMemeFormulaire_pourLesEmailsDejaPartis() {
        String jeton = jetonPour(inscrire("reset-ancien"));

        String html = corps(page("/reset-password?token=" + jeton));

        assertThat(texte(html))
            .contains("Choisissez un nouveau mot de passe")
            .contains("action=\"/r/" + jeton + "\"");
        assertThat(texte(corps(page("/reset-password")))).contains("n'est pas reconnu");
    }

    @Test
    void chaqueReponse_doitRetenirLeJeton_horsDuRefererEtDesCaches() {
        String jeton = jetonPour(inscrire("reset-referer"));

        for (String uri : new String[] {"/r/" + jeton, "/reset-password?token=" + jeton, "/r/jeton-bidon"}) {
            page(uri).expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        poster("/r/" + jeton, Map.of("motDePasse", "court", "confirmation", "court"))
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }

    @Test
    void unJetonBidon_doitRendreUnePage_etProposerUnNouveauLien() {
        // Le relevé de vérification du client, mot pour mot : pas de 401.
        String html = corps(page("/r/jeton-bidon").expectStatus().isOk());

        assertThat(texte(html))
            .contains("n'est pas reconnu")
            .contains("Recevoir un nouveau lien")
            .contains("action=\"/r\"")
            .doesNotContain("action=\"/r/jeton-bidon\"");
    }

    @Test
    void unJetonEchu_doitDireQuIlAExpire() {
        String jeton = jetonPour(inscrire("reset-echu"));
        AuthToken ligne = authTokenRepository
            .findByTokenHashAndType(AuthToken.empreinte(jeton), AuthTokenType.PASSWORD_RESET).orElseThrow();
        ligne.setExpiresAt(Instant.now().minusSeconds(60));
        authTokenRepository.save(ligne);

        assertThat(texte(corps(page("/r/" + jeton)))).contains("a expiré").contains("Recevoir un nouveau lien");
        // Et le formulaire posté quand même ne change rien.
        assertThat(texte(corps(poster("/r/" + jeton, Map.of("motDePasse", NOUVEAU, "confirmation", NOUVEAU)))))
            .contains("a expiré");
    }

    @Test
    void sansInterrupteur_aucuneSmartAppBanner() {
        // L'interrupteur est éteint par défaut : la page ne propose pas d'ouvrir
        // une app qui, dans sa version publiée, ne gère pas ce lien.
        String jeton = jetonPour(inscrire("reset-banniere"));

        assertThat(corps(page("/r/" + jeton))).doesNotContain("apple-itunes-app");
        assertThat(corps(page("/r/jeton-bidon"))).doesNotContain("apple-itunes-app");
    }

    @Test
    void headSurLeLien_doitRepondreCommeGet() {
        webTestClient.head().uri("/r/jeton-bidon").exchange().expectStatus().isOk();
        webTestClient.head().uri("/reset-password?token=jeton-bidon").exchange().expectStatus().isOk();
    }

    // — le formulaire —

    @Test
    void leFormulaire_doitChangerLeMotDePasse_etConsommerLeLien() {
        String email = inscrire("reset-reussi");
        String jeton = jetonPour(email);

        String html = corps(poster("/r/" + jeton, Map.of("motDePasse", NOUVEAU, "confirmation", NOUVEAU))
            .expectStatus().isOk());

        assertThat(texte(html)).contains("Votre mot de passe est changé");
        connexion(email, NOUVEAU).expectStatus().isOk();
        connexion(email, MOT_DE_PASSE_INITIAL).expectStatus().isUnauthorized();

        // Le même lien rouvert, ou le formulaire renvoyé par un double clic.
        assertThat(texte(corps(page("/r/" + jeton)))).contains("déjà servi");
        assertThat(texte(corps(poster("/r/" + jeton, Map.of("motDePasse", "Autre3!xyz", "confirmation", "Autre3!xyz")))))
            .contains("déjà servi");
        connexion(email, NOUVEAU).expectStatus().isOk();
    }

    @Test
    void deuxSaisiesDifferentes_doiventEtreRefusees_sansToucherAuLien() {
        String email = inscrire("reset-difference");
        String jeton = jetonPour(email);

        String html = corps(poster("/r/" + jeton, Map.of("motDePasse", NOUVEAU, "confirmation", NOUVEAU + "x")));

        assertThat(texte(html))
            .contains("ne sont pas identiques")
            .contains("action=\"/r/" + jeton + "\"")
            // Aucune saisie réinjectée dans la page.
            .doesNotContain(NOUVEAU);
        connexion(email, MOT_DE_PASSE_INITIAL).expectStatus().isOk();
        assertThat(texte(corps(page("/r/" + jeton)))).contains("Choisissez un nouveau mot de passe");
    }

    @Test
    void unMotDePasseTropCourt_doitEtreRefuse() {
        String email = inscrire("reset-court");
        String jeton = jetonPour(email);

        assertThat(texte(corps(poster("/r/" + jeton, Map.of("motDePasse", "court", "confirmation", "court")))))
            .contains("au moins 8 caractères");
        connexion(email, MOT_DE_PASSE_INITIAL).expectStatus().isOk();
    }

    @Test
    void leFormulaire_doitPartagerLePlafondDeLApi() {
        String jeton = jetonPour(inscrire("reset-plafond"));

        for (int i = 0; i < 20; i++) {
            poster("/r/" + jeton, Map.of("motDePasse", "court", "confirmation", "court")).expectStatus().isOk();
        }
        String html = corps(poster("/r/" + jeton, Map.of("motDePasse", "court", "confirmation", "court"))
            .expectStatus().isEqualTo(429)
            .expectHeader().exists(HttpHeaders.RETRY_AFTER)
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        assertThat(texte(html)).contains("Trop de tentatives");
    }

    // — redemander un lien —

    @Test
    void redemanderUnLien_doitRepondreLaMemeChose_queLeCompteExisteOuNon() {
        String email = inscrire("reset-redemande");
        String ancien = jetonPour(email);

        String connu = corps(poster("/r", Map.of("email", email)).expectStatus().isOk());
        String inconnu = corps(poster("/r", Map.of("email", uniqueEmail("personne"))).expectStatus().isOk());

        assertThat(texte(connu)).contains("Vérifiez votre boîte de réception");
        assertThat(texte(inconnu)).isEqualTo(texte(connu));
        // Le nouveau lien ferme le précédent.
        assertThat(texte(corps(page("/r/" + ancien)))).contains("déjà servi");
    }

    @Test
    void uneAdresseIncomplete_doitEtreSignalee() {
        assertThat(texte(corps(poster("/r", Map.of("email", "pas-une-adresse")))))
            .contains("ne semble pas complète")
            .contains("action=\"/r\"");
    }

    // — l'e-mail —

    @Test
    void lEmail_doitPorterLeLienCourt() {
        String email = inscrire("reset-email");
        String jeton = jetonPour(email);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        // Au moins une fois : l'e-mail de vérification de l'inscription peut
        // partir par le même fournisseur, depuis l'outbox.
        verify(resendEmailService, atLeastOnce()).sendHtmlEmail(eq(email), anyString(), html.capture());
        assertThat(html.getAllValues())
            .anySatisfy(corps -> assertThat(corps).contains("/r/" + jeton))
            .noneSatisfy(corps -> assertThat(corps).contains("reset-password?token="));
    }

    // — outils —

    private String inscrire(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", MOT_DE_PASSE_INITIAL, "displayName", "Testeur"))
            .exchange()
            .expectStatus().isCreated();
        return email;
    }

    private String jetonPour(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return emailVerificationService.generatePasswordResetToken(user);
    }

    private WebTestClient.ResponseSpec page(String uri) {
        return webTestClient.get().uri(uri).accept(MediaType.TEXT_HTML).exchange();
    }

    private WebTestClient.ResponseSpec poster(String uri, Map<String, String> champs) {
        LinkedMultiValueMap<String, String> formulaire = new LinkedMultiValueMap<>();
        champs.forEach(formulaire::add);
        return webTestClient.post().uri(uri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.TEXT_HTML)
            .body(BodyInserters.fromFormData(formulaire))
            .exchange();
    }

    private WebTestClient.ResponseSpec connexion(String email, String motDePasse) {
        return webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", motDePasse))
            .exchange();
    }

    private static String corps(WebTestClient.ResponseSpec reponse) {
        return reponse.expectBody(String.class).returnResult().getResponseBody();
    }

    /** Aplatit les blancs : le gabarit coupe ses phrases sur plusieurs lignes. */
    private static String texte(String html) {
        return html == null ? "" : html.replaceAll("\\s+", " ");
    }
}
