package org.program.pair.domain.search;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.MediaType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramMedia;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le mapping Program -> SearchResultDto ne touche aucune dépendance injectée
 * (repositories, LLM, embeddings) : instancier le service avec des dépendances
 * nulles suffit pour tester la logique de priorité imageUrl / media[0] isolément.
 */
class SemanticSearchServiceTest {

    private final SemanticSearchService service =
        new SemanticSearchService(null, null, null, null, null, null, null, null, null);

    @Test
    void thumbnailUrl_devraitPrivilegierImageUrl_quandAucunMedia() {
        Program program = programWithImageAndMedia("https://example.com/cover.png", List.of());

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), 48.85, 2.35);

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

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), 48.85, 2.35);

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

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), 48.85, 2.35);

        assertThat(results.get(0).thumbnailUrl()).isEqualTo("https://example.com/gallery-0.png");
    }

    @Test
    void thumbnailUrl_devraitEtreNull_sansImageUrlEtSansMedia() {
        Program program = programWithImageAndMedia(null, List.of());

        List<SearchResultDto> results = service.toSearchResultDtos(List.of(program), 48.85, 2.35);

        assertThat(results.get(0).thumbnailUrl()).isNull();
    }

    private Program programWithImageAndMedia(String imageUrl, List<ProgramMedia> media) {
        Category category = Category.builder().id(UUID.randomUUID()).name("Sport").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").category(category).build();
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
