package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.dto.CategoryDto;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.activity.dto.UserActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.user.dto.PrivacySettingsDto;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le lot A des abonnements, éprouvé contre une vraie base.
 *
 * <p>Ce qui ne peut pas se vérifier autrement : les contraintes {@code CHECK} de
 * la V58, le défaut {@code ALL} sur les lignes existantes, et le fait qu'un
 * abonnement en sourdine reste bien un abonnement — un test à doublures dirait
 * seulement que le code appelle ce qu'on lui a dit d'appeler.
 *
 * <p>Adresses e-mail distinctes d'une méthode à l'autre : le conteneur est
 * partagé par la classe, et deux inscriptions du même e-mail rendent
 * {@code 409} selon l'ordre de tirage JUnit.
 */
class SubscriptionLotAIntegrationTest extends AbstractIntegrationTest {

    /** Seedée par V27 : Lena Müller. */
    private static final UUID LENA = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private String register(String email) {
        AuthResponse resp = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "MotDePasse123!", "Abonné Test"))
            .exchange()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();
        return resp.accessToken();
    }

    private UUID anyCategoryId(String token) {
        List<CategoryDto> categories = webTestClient.get()
            .uri("/api/categories")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(CategoryDto.class)
            .returnResult().getResponseBody();
        assertThat(categories).isNotEmpty();
        return categories.get(0).id();
    }

    @Test
    void sAbonnerDeuxFois_doitRendre409AvecUnCodeNomme() {
        String token = register("lota1@pair.app");

        webTestClient.post().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated();

        ErrorResponse error = webTestClient.post().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        // Le client traite ce code comme un succès : l'état voulu est en base.
        assertThat(error.code()).isEqualTo("ALREADY_SUBSCRIBED");
    }

    /**
     * Le retrait est optimiste côté client : un 404 sur un désabonnement déjà
     * effectué le ferait revenir en arrière à tort.
     */
    @Test
    void seDesabonnerSansAbonnement_doitRendre204() {
        String token = register("lota2@pair.app");

        webTestClient.delete().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isNoContent();
    }

    @Test
    void abonnementNeuf_doitPorterLeNiveauAllEtAucunePortee() {
        String token = register("lota3@pair.app");

        SubscriptionDto dto = webTestClient.post().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(SubscriptionDto.class)
            .returnResult().getResponseBody();

        assertThat(dto.level()).isEqualTo("ALL");
        assertThat(dto.lat()).isNull();
        assertThat(dto.lng()).isNull();
        assertThat(dto.radiusMeters()).isNull();
    }

    /**
     * Le point où une implémentation naïve trahit {@code MUTED} : la sourdine
     * coupe l'émission, pas le lien. Un abonnement en sourdine affiché comme
     * absent serait recliqué, et le second POST rendrait 409.
     */
    @Test
    void mettreEnSourdine_doitGarderSubscribedVraiEtLeCompteur() {
        String token = register("lota4@pair.app");

        webTestClient.post().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated();

        SubscriptionDto muted = webTestClient.patch().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("level", "MUTED"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SubscriptionDto.class)
            .returnResult().getResponseBody();

        assertThat(muted.level()).isEqualTo("MUTED");

        UserPublicDto profile = webTestClient.get().uri("/api/users/{id}", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPublicDto.class)
            .returnResult().getResponseBody();

        assertThat(profile.subscribed()).isTrue();
        assertThat(profile.subscriberCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void patchNiveauInconnu_doitRendre400() {
        String token = register("lota5@pair.app");

        webTestClient.post().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated();

        webTestClient.patch().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("level", "PARFOIS"))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void patchSansAbonnement_doitRendre404() {
        String token = register("lota6@pair.app");

        webTestClient.patch().uri("/api/users/{id}/subscription", LENA)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("level", "MUTED"))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void abonnementCategorie_doitAccepterUnePorteePuisLaRetirer() {
        String token = register("lota7@pair.app");
        UUID categoryId = anyCategoryId(token);

        SubscriptionDto withScope = webTestClient.post()
            .uri("/api/categories/{id}/subscription", categoryId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("lat", 48.8566, "lng", 2.3522, "radiusMeters", 20_000))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(SubscriptionDto.class)
            .returnResult().getResponseBody();

        assertThat(withScope.radiusMeters()).isEqualTo(20_000);
        assertThat(withScope.lat()).isEqualTo(48.8566);

        SubscriptionDto cleared = webTestClient.patch()
            .uri("/api/categories/{id}/subscription", categoryId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("clearScope", true))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SubscriptionDto.class)
            .returnResult().getResponseBody();

        assertThat(cleared.lat()).isNull();
        assertThat(cleared.radiusMeters()).isNull();
    }

    /** Sans corps, le comportement d'avant la portée : aucune contrainte géographique. */
    @Test
    void abonnementCategorie_sansCorps_doitResterMondial() {
        String token = register("lota8@pair.app");
        UUID categoryId = anyCategoryId(token);

        SubscriptionDto dto = webTestClient.post()
            .uri("/api/categories/{id}/subscription", categoryId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(SubscriptionDto.class)
            .returnResult().getResponseBody();

        assertThat(dto.radiusMeters()).isNull();
    }

    /** Un centre sans rayon ne décrit rien : la contrainte vaut aussi côté API. */
    @Test
    void abonnementCategorie_porteeIncomplete_doitRendre400() {
        String token = register("lota9@pair.app");
        UUID categoryId = anyCategoryId(token);

        webTestClient.post()
            .uri("/api/categories/{id}/subscription", categoryId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("lat", 48.8566, "radiusMeters", 20_000))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void listeDesCategories_doitPorterCompteurEtEtatDAbonnement() {
        String token = register("lota10@pair.app");
        UUID categoryId = anyCategoryId(token);

        webTestClient.post().uri("/api/categories/{id}/subscription", categoryId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated();

        List<CategoryDto> categories = webTestClient.get().uri("/api/categories")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(CategoryDto.class)
            .returnResult().getResponseBody();

        CategoryDto subscribed = categories.stream()
            .filter(c -> c.id().equals(categoryId))
            .findFirst().orElseThrow();

        assertThat(subscribed.subscribed()).isTrue();
        assertThat(subscribed.subscriberCount()).isGreaterThanOrEqualTo(1L);

        // Les autres catégories restent à zéro et non abonnées : le compteur est
        // par cible, il ne déborde pas sur ses voisines.
        assertThat(categories.stream()
            .filter(c -> !c.id().equals(categoryId))
            .allMatch(c -> Boolean.FALSE.equals(c.subscribed()))).isTrue();
    }

    /**
     * Le réglage ne vaut que pour l'avenir : il refuse les nouveaux abonnements,
     * il ne supprime pas les existants.
     */
    @Test
    void profilFermeAuxAbonnements_doitRefuserLesNouveauxSansToucherAuxAnciens() {
        String cible = register("lota11-cible@pair.app");
        String suiveurAvant = register("lota11-avant@pair.app");
        String suiveurApres = register("lota11-apres@pair.app");

        UUID cibleId = webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(cible))
            .exchange()
            .expectStatus().isOk()
            .expectBody(org.program.pair.domain.user.dto.UserPrivateDto.class)
            .returnResult().getResponseBody()
            .id();

        // Un abonné avant la fermeture.
        webTestClient.post().uri("/api/users/{id}/subscription", cibleId)
            .headers(h -> h.setBearerAuth(suiveurAvant))
            .exchange().expectStatus().isCreated();

        PrivacySettingsDto settings = webTestClient.put().uri("/api/users/me/privacy")
            .headers(h -> h.setBearerAuth(cible))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("allowSubscriptions", "NOBODY"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(PrivacySettingsDto.class)
            .returnResult().getResponseBody();

        assertThat(settings.allowSubscriptions()).isEqualTo("NOBODY");

        ErrorResponse refus = webTestClient.post().uri("/api/users/{id}/subscription", cibleId)
            .headers(h -> h.setBearerAuth(suiveurApres))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(refus.code()).isEqualTo("SUBSCRIPTIONS_NOT_ALLOWED");

        // L'abonné d'avant est toujours là, et le compteur le dit.
        UserPublicDto profil = webTestClient.get().uri("/api/users/{id}", cibleId)
            .headers(h -> h.setBearerAuth(suiveurAvant))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPublicDto.class)
            .returnResult().getResponseBody();

        assertThat(profil.subscribed()).isTrue();
        assertThat(profil.subscriberCount()).isEqualTo(1L);
    }

    /**
     * Le contournement : « qui peut me suivre » n'était vérifié que sur le
     * profil, et l'on pouvait suivre la même personne par n'importe laquelle de
     * ses activités — en recevant bien ses nouveaux programmes.
     */
    @Test
    void profilFerme_doitAussiRefuserLAbonnementParUneDeSesActivites() {
        String cible = register("lota12-cible@pair.app");
        String suiveur = register("lota12-suiveur@pair.app");

        // Une activité du référentiel, posée sur le profil de la cible.
        UUID referentielId = UUID.fromString(webTestClient.get()
            .uri("/api/activities?page=0&size=1")
            .headers(h -> h.setBearerAuth(cible))
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult().getResponseBody()
            .get("content") instanceof List<?> contenu
                ? ((Map<?, ?>) contenu.get(0)).get("id").toString()
                : null);

        UUID activiteId = webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(cible))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new UpsertUserActivityRequest(referentielId, true, null, null, null))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(UserActivityDto.class)
            .returnResult().getResponseBody()
            .id();

        webTestClient.put().uri("/api/users/me/privacy")
            .headers(h -> h.setBearerAuth(cible))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("allowSubscriptions", "NOBODY"))
            .exchange().expectStatus().isOk();

        ErrorResponse refus = webTestClient.post()
            .uri("/api/user-activities/{id}/subscription", activiteId)
            .headers(h -> h.setBearerAuth(suiveur))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(refus.code()).isEqualTo("SUBSCRIPTIONS_NOT_ALLOWED");
    }

    /** On ne se suit pas soi-même, y compris par le détour d'une activité. */
    @Test
    void sAbonnerASaPropreActivite_doitEtreRefuse() {
        String moi = register("lota13@pair.app");

        UUID referentielId = UUID.fromString(webTestClient.get()
            .uri("/api/activities?page=0&size=1")
            .headers(h -> h.setBearerAuth(moi))
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult().getResponseBody()
            .get("content") instanceof List<?> contenu
                ? ((Map<?, ?>) contenu.get(0)).get("id").toString()
                : null);

        UUID maPropreActivite = webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(moi))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new UpsertUserActivityRequest(referentielId, true, null, null, null))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(UserActivityDto.class)
            .returnResult().getResponseBody()
            .id();

        webTestClient.post().uri("/api/user-activities/{id}/subscription", maPropreActivite)
            .headers(h -> h.setBearerAuth(moi))
            .exchange().expectStatus().isForbidden();
    }
}
