package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.ActivityService;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.dto.CategoryDto;
import org.program.pair.domain.activity.dto.CreateCategoryRequest;
import org.program.pair.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit puis vérifie la correction du bug de production : les catégories
 * renvoyaient color_ramp au format nom-de-rampe, hexadécimal ou NULL, un
 * mélange inexploitable par un client qui attend une teinte cohérente.
 * V46 normalise les données existantes ; ce test garantit qu'aucune
 * catégorie — historique ou nouvellement créée — ne peut plus être en
 * hexadécimal ou NULL.
 */
class CategoryColorRampIntegrationTest extends AbstractIntegrationTest {

    private static final Pattern RAMP_NAME_PATTERN = Pattern.compile("^[a-z]+(-[a-z]+)+$");

    @Autowired CategoryRepository categoryRepository;
    @Autowired ActivityService activityService;

    @Test
    void aucuneCategorie_neDoitAvoirUnColorRampHexadecimalOuNull() {
        List<Category> categories = categoryRepository.findAll();

        assertThat(categories).isNotEmpty();
        assertThat(categories)
            .allSatisfy(c -> assertThat(c.getColorRamp())
                .as("colorRamp de la catégorie '%s'", c.getName())
                .isNotNull()
                .doesNotStartWith("#")
                .matches(RAMP_NAME_PATTERN));
    }

    @Test
    void createCategory_devraitToujoursAssignerUnColorRampValide() {
        String uniqueName = "Catégorie test " + UUID.randomUUID();
        CategoryDto created = activityService.createCategory(new CreateCategoryRequest(uniqueName));

        Category reloaded = categoryRepository.findById(created.id()).orElseThrow();

        assertThat(reloaded.getColorRamp())
            .isNotNull()
            .doesNotStartWith("#")
            .matches(RAMP_NAME_PATTERN);
    }
}
