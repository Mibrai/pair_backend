package org.program.pair.domain.search;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.MediaType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramMedia;
import org.program.pair.domain.search.dto.ProgramVenue;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le mapping Program -> SearchResultDto ne touche aucune dépendance injectée
 * (repositories, LLM, embeddings) : instancier le service avec des dépendances
 * nulles suffit pour tester isolément la priorité imageUrl / media[0] et la
 * situation géographique du résultat.
 */
class SemanticSearchServiceTest {

    private final SemanticSearchService service =
        new SemanticSearchService(null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void thumbnailUrl_devraitPrivilegierImageUrl_quandAucunMedia() {
        Program program = programWithImageAndMedia("https://example.com/cover.png", List.of());

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), Map.of());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).thumbnailUrl()).isEqualTo("https://example.com/cover.png");
    }

    @Test
    void thumbnailUrl_devraitPrivilegierImageUrl_memeAvecMediaPresent() {
        ProgramMedia media = ProgramMedia.builder()
            .url("https://example.com/gallery-0.png")
            .mediaType(MediaType.IMAGE)
            .sortOrder(0)
            .build();
        Program program = programWithImageAndMedia("https://example.com/cover.png", List.of(media));

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), Map.of());

        assertThat(results.get(0).thumbnailUrl()).isEqualTo("https://example.com/cover.png");
    }

    @Test
    void thumbnailUrl_devraitReplierSurPremierMedia_quandPasDImageUrl() {
        ProgramMedia media = ProgramMedia.builder()
            .url("https://example.com/gallery-0.png")
            .mediaType(MediaType.IMAGE)
            .sortOrder(0)
            .build();
        Program program = programWithImageAndMedia(null, List.of(media));

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), Map.of());

        assertThat(results.get(0).thumbnailUrl()).isEqualTo("https://example.com/gallery-0.png");
    }

    @Test
    void thumbnailUrl_devraitEtreNull_sansImageUrlEtSansMedia() {
        Program program = programWithImageAndMedia(null, List.of());

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), Map.of());

        assertThat(results.get(0).thumbnailUrl()).isNull();
    }

    /**
     * La demande du client, en un test : le résultat porte le lieu de la séance.
     *
     * <p>Le programme est délibérément construit avec un organisateur situé
     * ailleurs — c'est cette coordonnée-là qui était rendue, et le test échouerait
     * si elle revenait.
     */
    @Test
    void leProgramme_doitEtreSitueASaSeance_pasChezSonOrganisateur() {
        Program program = programWithImageAndMedia(null, List.of());
        ProgramVenue venue = new ProgramVenue(51.5513825, 7.0758985, 4_073.0);

        List<SearchResultDto> results =
            service.toSearchResultDtos(List.of(program), Map.of(program.getId(), venue));

        SearchResultDto result = results.get(0);
        assertThat(result.lat()).isEqualTo(51.5513825);
        assertThat(result.lng()).isEqualTo(7.0758985);
        assertThat(result.distanceMeters()).isEqualTo(4_073.0);
    }

    /**
     * Le cas que le client a explicitement demandé de ne pas replier : sans
     * séance localisée, on ne sait pas situer le programme, et on le dit.
     */
    @Test
    void sansSeanceLocalisee_lesCoordonneesDoiventEtreNulles() {
        Program program = programWithImageAndMedia(null, List.of());

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), Map.of());

        SearchResultDto result = results.get(0);
        assertThat(result.lat()).isNull();
        assertThat(result.lng()).isNull();
        assertThat(result.distanceMeters()).isNull();
    }

    /**
     * Un programme à distance n'a pas de lieu, <b>même si une séance localisée
     * traîne en base</b> — une saisie HYBRID mal faite, par exemple. La modalité
     * l'emporte sur la donnée résiduelle, sinon on afficherait une distance pour
     * quelque chose qui se suit depuis chez soi.
     */
    @Test
    void programmeADistance_neDoitPorterNiLieuNiDistance() {
        for (LocationType type : List.of(LocationType.REMOTE, LocationType.ONLINE)) {
            Program program = programWithImageAndMedia(null, List.of());
            program.setLocationType(type);
            ProgramVenue residual = new ProgramVenue(51.55, 7.07, 4_073.0);

            List<SearchResultDto> results =
                service.toSearchResultDtos(List.of(program), Map.of(program.getId(), residual));

            SearchResultDto result = results.get(0);
            assertThat(result.lat()).as("%s", type).isNull();
            assertThat(result.lng()).as("%s", type).isNull();
            assertThat(result.distanceMeters()).as("%s", type).isNull();
        }
    }

    /**
     * Le pendant du précédent : une modalité en présentiel ne masque rien.
     * Sans ce test, annuler les coordonnées de tout le monde passerait.
     */
    @Test
    void programmeEnPresentiel_doitConserverSonLieu() {
        Program program = programWithImageAndMedia(null, List.of());
        program.setLocationType(LocationType.IN_PERSON);
        ProgramVenue venue = new ProgramVenue(48.85, 2.35, 12.0);

        List<SearchResultDto> results =
            service.toSearchResultDtos(List.of(program), Map.of(program.getId(), venue));

        assertThat(results.get(0).lat()).isEqualTo(48.85);
        assertThat(results.get(0).distanceMeters()).isEqualTo(12.0);
    }

    private Program programWithImageAndMedia(String imageUrl, List<ProgramMedia> media) {
        Category category = Category.builder().id(UUID.randomUUID()).name("Sport").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").category(category).build();
        // Organisateur volontairement situé loin des séances : c'est la
        // coordonnée que la recherche rendait, et qu'aucun test ne doit revoir.
        User owner = User.builder()
            .id(UUID.randomUUID())
            .displayName("Owner")
            .verificationStatus(VerificationStatus.EMAIL_VERIFIED)
            .build();
        UserActivity userActivity = UserActivity.builder()
            .id(UUID.randomUUID())
            .user(owner)
            .activity(activity)
            .build();

        return Program.builder()
            .id(UUID.randomUUID())
            .userActivity(userActivity)
            .title("Yoga du matin")
            .imageUrl(imageUrl)
            .media(media)
            .build();
    }
}
