package org.program.pair.domain.review;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.user.User;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P-BA-12 — deux avis de même identifiant sont égaux sans charger leurs associations.
 *
 * <p>L'{@code equals} généré par {@code @Data} comparait tous les champs, donc
 * l'auteur et le programme : sur une entité chargée, autant de proxys
 * paresseux réveillés par une simple comparaison.
 */
class ReviewIdentiteTest {

    @Test
    void deuxAvisDeMemeIdentifiant_sontEgaux_sansToucherAuxAssociations() {
        UUID id = UUID.randomUUID();
        User auteur = mock(User.class);
        Program programme = mock(Program.class);

        Review premier = Review.builder().id(id).reviewer(auteur).program(programme).build();
        Review second = Review.builder().id(id).build();

        assertThat(premier).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(premier.toString()).contains(id.toString());
        verifyNoInteractions(auteur, programme);
    }

    @Test
    void deuxAvisNonPersistes_neSontPasEgaux_maisRestentStablesDansUnEnsemble() {
        Review premier = Review.builder().build();
        Review second = Review.builder().build();

        assertThat(premier).isNotEqualTo(second);

        Set<Review> ensemble = new HashSet<>(Set.of(premier));
        premier.setId(UUID.randomUUID());
        assertThat(ensemble).as("le hashCode ne dépend pas de l'identifiant").contains(premier);
    }
}
