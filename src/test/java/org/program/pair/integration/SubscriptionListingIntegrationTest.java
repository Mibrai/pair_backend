package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.dto.CategoryDto;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.activity.dto.UserActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les deux listes du lot C, contre une vraie base.
 *
 * <p>Ce que seule une base réelle établit : les deux JPQL écrites à la main —
 * dont celle des abonnés, qui réunit deux chemins d'appartenance dans une seule
 * condition — filtrent bien ce qu'elles prétendent filtrer.
 *
 * <p>Une adresse e-mail distincte par méthode : le conteneur est partagé par la
 * classe, et deux inscriptions du même e-mail rendent {@code 409} selon l'ordre
 * de tirage JUnit.
 *
 * <p>Les corps paginés sont lus en {@code Map} plutôt qu'en {@code JsonNode} :
 * les codecs de WebTestClient sérialisent en Jackson 3 tandis que l'
 * {@code ObjectMapper} du contexte est en Jackson 2, et un {@code JsonNode} de
 * la mauvaise arborescence échoue à la construction.
 */
class SubscriptionListingIntegrationTest extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() {};

    private record Compte(String token, UUID id) {}

    private Compte inscrire(String email, String nom) {
        AuthResponse resp = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "MotDePasse123!", nom))
            .exchange()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        UUID id = webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(resp.accessToken()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult().getResponseBody()
            .id();

        return new Compte(resp.accessToken(), id);
    }

    /** Une activité du référentiel, posée sur le profil de l'appelant. */
    private UUID creerActivite(String token) {
        Object referentielId = contenu(page("/api/activities?page=0&size=1", token))
            .get(0).get("id");

        UserActivityDto ua = webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new UpsertUserActivityRequest(
                UUID.fromString(referentielId.toString()), true, null, null, null))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(UserActivityDto.class)
            .returnResult().getResponseBody();

        return ua.id();
    }

    private Map<String, Object> page(String uri, String token) {
        return webTestClient.get().uri(uri)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(JSON_OBJECT)
            .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contenu(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    /**
     * Le total vit sous la clé {@code page}, et non à la racine : c'est la forme
     * d'enveloppe que sert déjà {@code /activities/browse}, et celle que le
     * client sait lire.
     */
    @SuppressWarnings("unchecked")
    private int total(Map<String, Object> page) {
        Map<String, Object> meta = (Map<String, Object>) page.get("page");
        return ((Number) meta.get("totalElements")).intValue();
    }

    @Test
    void mesAbonnements_doitRendreUneEnveloppePaginee() {
        Compte moi = inscrire("listc1@pair.app", "Lecteur");

        webTestClient.post().uri("/api/users/{id}/subscription",
                UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isCreated();

        Map<String, Object> body = page("/api/users/me/subscriptions", moi.token());

        // L'enveloppe Page, celle que le client sait déjà lire.
        assertThat(body).containsKeys("content", "page");
        assertThat(total(body)).isEqualTo(1);
        assertThat(contenu(body).get(0)).containsEntry("type", "AUTHOR");
        assertThat(contenu(body).get(0)).containsEntry("level", "ALL");
    }

    @Test
    void mesAbonnements_doitFiltrerParType() {
        Compte moi = inscrire("listc2@pair.app", "Filtreur");

        webTestClient.post().uri("/api/users/{id}/subscription",
                UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isCreated();

        assertThat(total(page("/api/users/me/subscriptions?type=AUTHOR", moi.token()))).isEqualTo(1);
        assertThat(total(page("/api/users/me/subscriptions?type=CATEGORY", moi.token()))).isZero();
    }

    @Test
    void mesAbonnes_doitListerCeuxVenusParLeProfilEtParUneActivite() {
        Compte auteur = inscrire("listc3-auteur@pair.app", "Auteur");
        Compte suiveurProfil = inscrire("listc3-profil@pair.app", "Par le profil");
        Compte suiveurActivite = inscrire("listc3-activite@pair.app", "Par l'activite");

        UUID activiteId = creerActivite(auteur.token());

        webTestClient.post().uri("/api/users/{id}/subscription", auteur.id())
            .headers(h -> h.setBearerAuth(suiveurProfil.token()))
            .exchange().expectStatus().isCreated();

        webTestClient.post().uri("/api/user-activities/{id}/subscription", activiteId)
            .headers(h -> h.setBearerAuth(suiveurActivite.token()))
            .exchange().expectStatus().isCreated();

        Map<String, Object> body = page("/api/users/me/subscribers", auteur.token());
        assertThat(total(body)).isEqualTo(2);

        Map<String, Object> parProfil = contenu(body).stream()
            .filter(n -> "AUTHOR".equals(n.get("type"))).findFirst().orElseThrow();
        Map<String, Object> parActivite = contenu(body).stream()
            .filter(n -> "USER_ACTIVITY".equals(n.get("type"))).findFirst().orElseThrow();

        // Un abonné arrivé par le profil ne désigne aucune activité.
        assertThat(parProfil.get("targetId")).isNull();
        assertThat(parProfil).containsEntry("displayName", "Par le profil");

        assertThat(parActivite.get("targetId")).isEqualTo(activiteId.toString());
        assertThat(parActivite.get("targetName")).isNotNull();
        assertThat(parActivite.get("subscribedAt")).isNotNull();
    }

    /** Personne ne peut savoir qui suit un tiers, y compris par le paramètre targetId. */
    @Test
    void mesAbonnes_activiteDAutrui_doitRendre403() {
        Compte auteur = inscrire("listc4-auteur@pair.app", "Proprietaire");
        Compte curieux = inscrire("listc4-curieux@pair.app", "Curieux");

        UUID activiteId = creerActivite(auteur.token());

        webTestClient.get().uri("/api/users/me/subscribers?targetId={id}", activiteId)
            .headers(h -> h.setBearerAuth(curieux.token()))
            .exchange().expectStatus().isForbidden();
    }

    /**
     * Le filtre CATEGORY est refusé à tous : une catégorie n'appartient à
     * personne, et exposer qui la suit révélerait une donnée personnelle que
     * rien ne justifie de transporter.
     */
    @Test
    void mesAbonnes_filtreCategorie_doitRendre403() {
        Compte moi = inscrire("listc5@pair.app", "Demandeur");

        webTestClient.get().uri("/api/users/me/subscribers?type=CATEGORY")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isForbidden();
    }

    /** Un abonnement CATEGORY n'entre dans les abonnés de personne. */
    @Test
    void mesAbonnes_neDoitJamaisContenirUnAbonnementCategorie() {
        Compte moi = inscrire("listc6@pair.app", "Sans abonnes");

        List<CategoryDto> categories = webTestClient.get().uri("/api/categories")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(CategoryDto.class)
            .returnResult().getResponseBody();

        webTestClient.post().uri("/api/categories/{id}/subscription", categories.get(0).id())
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isCreated();

        assertThat(total(page("/api/users/me/subscribers", moi.token()))).isZero();
    }

    @Test
    void mesAbonnes_doitRestreindreAUneActiviteDemandee() {
        Compte auteur = inscrire("listc7-auteur@pair.app", "Auteur cible");
        Compte suiveur = inscrire("listc7-suiveur@pair.app", "Suiveur");

        UUID activiteId = creerActivite(auteur.token());

        // Un abonnement par le profil, un par l'activité : le filtre écarte le premier.
        webTestClient.post().uri("/api/users/{id}/subscription", auteur.id())
            .headers(h -> h.setBearerAuth(suiveur.token()))
            .exchange().expectStatus().isCreated();
        webTestClient.post().uri("/api/user-activities/{id}/subscription", activiteId)
            .headers(h -> h.setBearerAuth(suiveur.token()))
            .exchange().expectStatus().isCreated();

        assertThat(total(page("/api/users/me/subscribers", auteur.token()))).isEqualTo(2);

        Map<String, Object> cible =
            page("/api/users/me/subscribers?targetId=" + activiteId, auteur.token());
        assertThat(total(cible)).isEqualTo(1);
        assertThat(contenu(cible).get(0)).containsEntry("type", "USER_ACTIVITY");
    }
}
