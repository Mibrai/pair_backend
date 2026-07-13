package org.program.pair.domain.search;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTaxonomyTest {

    private final ActivityTaxonomy taxonomy = new ActivityTaxonomy();

    @Test
    void laufen_resoutVersLeSlugRunning() {
        assertThat(taxonomy.matchSlugs("Laufen")).containsExactly("running");
    }

    @Test
    void courseAPied_resoutVersLeSlugRunning() {
        assertThat(taxonomy.matchSlugs("je cherche un partenaire de course à pied")).contains("running");
    }

    @Test
    void jogging_resoutVersLeSlugRunning() {
        assertThat(taxonomy.matchSlugs("jogging entre midi et deux")).contains("running");
    }

    @Test
    void klettern_resoutVersLeSlugClimbing() {
        assertThat(taxonomy.matchSlugs("Klettern am Wochenende")).contains("climbing");
    }

    @Test
    void escalade_etClimbing_resolventVersLeMemeSlug() {
        assertThat(taxonomy.matchSlugs("escalade")).isEqualTo(taxonomy.matchSlugs("climbing"));
    }

    @Test
    void requeteInconnue_neMatcheAucunSlug() {
        assertThat(taxonomy.matchSlugs("azkjebazkjeb")).isEmpty();
    }

    @Test
    void matchLabels_retourneLesLabelsMultilinguesDuSlugResolu() {
        Set<String> labels = taxonomy.matchLabels("Laufen");
        assertThat(labels).contains("running", "laufen", "course a pied", "jogging");
    }

    @Test
    void normalize_supprimeAccentsEtCasse() {
        assertThat(taxonomy.normalize("Randonnée")).isEqualTo("randonnee");
    }
}
