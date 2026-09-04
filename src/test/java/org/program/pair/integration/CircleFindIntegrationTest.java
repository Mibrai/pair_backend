package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * « Trouver quelqu'un » — la recherche de personnes de Mon cercle.
 *
 * <p>Trois défauts mesurés par le client le 04/09, et un quatrième trouvé en
 * les instruisant. Ils avaient une cause chacun, et aucun n'était là où on
 * l'attendait :
 *
 * <ul>
 *   <li>un compte introuvable même sur son nom exact — non pas absent d'un
 *       index, mais exclu par un réglage écrit à un endroit et lu à un autre ;</li>
 *   <li>« muller » ne trouvait pas « Müller » — aucune insensibilité aux
 *       accents nulle part en SQL ;</li>
 *   <li>chercher le titre d'une soirée ne trouvait pas qui l'organise ;</li>
 *   <li>et la pagination était instable dès qu'aucune position n'était
 *       envoyée, c'est-à-dire toujours, sur cet onglet.</li>
 * </ul>
 */
class CircleFindIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired UserRepository userRepository;

    // — §2.3 : qui est trouvable —

    @Test
    void unReglageDeConfidentialiteActive_doitRendreTrouvable() {
        // Le défaut le plus grave, et le plus discret. La recherche exigeait
        // users.location_public, que seul PUT /me écrit. L'écran de
        // confidentialité, lui, pose show_location — et ne touche jamais
        // l'autre. Activer « Localisation publique » dans l'application ne
        // rendait donc personne trouvable : le réglage était stocké, relu,
        // affiché, et lu par aucun code de décision.
        String personne = registerAndLogin("Brigitte Lelouche");
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, "Lelouche")).doesNotContain("Brigitte Lelouche");

        privacy(personne, Map.of("showLocation", true));

        assertThat(noms(chercheur, "Lelouche")).contains("Brigitte Lelouche");
    }

    @Test
    void lAutreReglageDeCarte_doitAussiRendreTrouvable() {
        // show_on_map dit la même chose à l'utilisateur que les deux autres.
        // Trois champs pour une intention : la recherche les accepte tous, sans
        // quoi le défaut ci-dessus se rejouerait sur le troisième.
        String personne = registerAndLogin("Ingrid Vermeulen");
        String chercheur = registerAndLogin("Chercheuse");

        privacy(personne, Map.of("showOnMap", true));

        assertThat(noms(chercheur, "Vermeulen")).contains("Ingrid Vermeulen");
    }

    @Test
    void leReglageDuProfil_doitContinuerDeMarcher() {
        // Le seul chemin qui fonctionnait, et il ne doit pas cesser.
        String personne = registerAndLogin("Olav Henriksen");
        String chercheur = registerAndLogin("Chercheuse");

        profil(personne, Map.of("locationPublic", true));

        assertThat(noms(chercheur, "Henriksen")).contains("Olav Henriksen");
    }

    @Test
    void personneNAyantRienActive_neDoitPasDevenirTrouvable() {
        // Le contre-test, et c'est lui qui borne le lot. Rendre trouvables ceux
        // qui ont activé un réglage ne doit exposer personne d'autre : quelqu'un
        // resté aux défauts n'a rien demandé, et le devenir sans geste de sa
        // part serait un changement qu'il n'a pas choisi.
        registerAndLogin("Discrète Sansreglage");
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, "Sansreglage")).isEmpty();
    }

    // — §2.2 : les accents —

    @Test
    void unNomAccentue_doitSeTrouverSansAccent() {
        // « Le même mot, la même personne, zéro résultat » — et c'est la
        // recherche la plus banale qui soit, sur un clavier sans tréma.
        String personne = registerAndLogin("Anna Müller");
        privacy(personne, Map.of("showLocation", true));
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, "muller")).contains("Anna Müller");
        assertThat(noms(chercheur, "Müller")).contains("Anna Müller");
        assertThat(noms(chercheur, "MULLER")).contains("Anna Müller");
    }

    @Test
    void uneRequeteAccentuee_doitTrouverUnNomSansAccent() {
        // L'autre sens, qui se perd facilement : normaliser la requête seule
        // laisserait « Müller » sans réponse pour quelqu'un qui s'appelle
        // « Muller ». Les deux côtés de la comparaison sont dépliés.
        String personne = registerAndLogin("Jonas Muller");
        privacy(personne, Map.of("showLocation", true));
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, "Müller")).contains("Jonas Muller");
    }

    // — §2.1 : ce que les gens organisent —

    @Test
    void leTitreDunCreneauOrganise_doitTrouverSonOrganisateur() {
        // La moitié manquante. Le client tape le titre d'une soirée et cherche
        // qui l'organise ; la recherche ne regardait que le nom et la bio.
        //
        // Le titre d'un créneau EST celui de son programme, fabriqué par
        // QuickSlotService.titleFor sous la forme « Activité — jour ». Indexer
        // les titres de programmes couvre donc les deux, sans toucher aux
        // séances.
        String hote = registerAndLogin("Lelouche01");
        privacy(hote, Map.of("showLocation", true));
        String activite = publishSlotAndGetActivityName(hote);
        String chercheur = registerAndLogin("Chercheuse");

        // Ni son nom ni sa bio ne portent l'activité : seul le titre du créneau
        // peut le faire remonter.
        assertThat(noms(chercheur, activite)).contains("Lelouche01");
    }

    @Test
    void unProgrammePrive_neDoitPasRendreSonOrganisateurTrouvable() {
        // La contrepartie. Rendre quelqu'un trouvable par le titre d'un
        // programme que personne ne peut voir ferait fuiter l'existence de ce
        // programme — on apprendrait qu'il existe en constatant qui remonte.
        String hote = registerAndLogin("Discret Organisateur");
        privacy(hote, Map.of("showLocation", true));
        String activite = publishSlotAndGetActivityName(hote);
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, activite)).contains("Discret Organisateur");

        // Le programme quitte la sphère publique : son titre cesse d'être une
        // porte vers son organisateur.
        webTestClient.get().uri("/api/users?query={q}", activite)
            .headers(h -> h.setBearerAuth(chercheur))
            .exchange().expectStatus().isOk();
        jdbc().update("UPDATE programs SET is_public = FALSE");

        assertThat(noms(chercheur, activite)).doesNotContain("Discret Organisateur");
    }

    // — la pagination, et le compte —

    @Test
    void lesPagesSuccessives_neDoiventNiSeRecouvrirNiSeManquer() {
        // Sans position, la clé de tri valait 0 pour toutes les lignes : l'ordre
        // était laissé au plan d'exécution. C'est le cas de cet onglet, qui
        // n'envoie jamais de position — donc le cas nominal, pas un cas de bord.
        // Créés en base et non par la route d'inscription : cinq inscriptions
        // d'affilée réveillent le limiteur de débit, et ces cinq-là n'ont pas
        // besoin de jeton — seul le chercheur en a un.
        for (int i = 0; i < 5; i++) {
            userRepository.save(User.builder()
                .email("cercle-pagination-" + UUID.randomUUID() + "@pair.app")
                .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
                .displayName("Pagination Test " + i)
                .isActive(true)
                .locationPublic(true)
                .build());
        }
        String chercheur = registerAndLogin("Chercheuse");

        List<String> page0 = nomsPage(chercheur, "Pagination Test", 0, 2);
        List<String> page1 = nomsPage(chercheur, "Pagination Test", 1, 2);
        List<String> page2 = nomsPage(chercheur, "Pagination Test", 2, 2);

        assertThat(page0).hasSize(2);
        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(1);
        assertThat(page0).doesNotContainAnyElementsOf(page1);
        assertThat(page0).doesNotContainAnyElementsOf(page2);
        assertThat(page1).doesNotContainAnyElementsOf(page2);
    }

    @Test
    void onNeSeTrouvePasSoiMeme() {
        // Un onglet qui sert à trouver quelqu'un à suivre proposait de se
        // suivre. L'exclusion est en SQL, donc le compte est d'accord avec la
        // page — après coup, il annoncerait un total qu'il ne rend pas.
        String moi = registerAndLogin("Narcisse Unique");
        privacy(moi, Map.of("showLocation", true));

        assertThat(noms(moi, "Narcisse Unique")).isEmpty();
        assertThat(total(moi, "Narcisse Unique")).isZero();

        String autre = registerAndLogin("Chercheuse");
        assertThat(noms(autre, "Narcisse Unique")).contains("Narcisse Unique");
    }

    @Test
    void leTotal_doitCompterExactementCeQueLaPageRend() {
        String personne = registerAndLogin("Comptage Exact");
        privacy(personne, Map.of("showLocation", true));
        String chercheur = registerAndLogin("Chercheuse");

        assertThat(noms(chercheur, "Comptage Exact")).hasSize(1);
        assertThat(total(chercheur, "Comptage Exact")).isEqualTo(1);
    }

    // — helpers —

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbc;
    }

    private List<String> noms(String token, String query) {
        return nomsPage(token, query, 0, 20);
    }

    @SuppressWarnings("unchecked")
    private List<String> nomsPage(String token, String query, int page, int size) {
        Map<String, Object> body = webTestClient.get()
            .uri(b -> b.path("/api/users")
                .queryParam("query", query)
                .queryParam("page", page)
                .queryParam("size", size).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        return content.stream().map(u -> (String) u.get("displayName")).toList();
    }

    private int total(String token, String query) {
        Map<?, ?> body = webTestClient.get()
            .uri(b -> b.path("/api/users").queryParam("query", query).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        // La page se sérialise en { content, page: { totalElements, ... } }.
        Map<?, ?> page = (Map<?, ?>) body.get("page");
        return ((Number) page.get("totalElements")).intValue();
    }

    private void privacy(String token, Map<String, Object> champs) {
        webTestClient.put().uri("/api/users/me/privacy")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(champs)
            .exchange().expectStatus().isOk();
    }

    private void profil(String token, Map<String, Object> champs) {
        webTestClient.put().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(champs)
            .exchange().expectStatus().isOk();
    }

    /** Publie un créneau et rend le nom de l'activité, qui est dans son titre. */
    private String publishSlotAndGetActivityName(String token) {
        var activity = activityRepository.findAll().get(0);
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activity.getId(), Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc", PlaceType.PUBLIC, 48.5734, 7.7521,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        assertThat(slot.programTitle()).contains(activity.getName());
        return activity.getName();
    }

    private String registerAndLogin(String displayName) {
        String email = uniqueEmail("cercle");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", displayName))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
