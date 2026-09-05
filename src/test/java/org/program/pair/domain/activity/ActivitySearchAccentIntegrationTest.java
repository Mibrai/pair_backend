package org.program.pair.domain.activity;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.dto.ActivityDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /activities?search=} et les accents.
 *
 * <p>Le relevé du client, le 04/09 : {@code course} rend « Course à pied »,
 * {@code COURSE} aussi, {@code yog} rend les quatre yogas — mais
 * <b>{@code course a pied} ne rend rien</b>. Sur un clavier où l'on ne met pas
 * ses accents, c'est-à-dire le cas courant, la suggestion n'apparaît pas et
 * l'auteur crée le cinquième doublon de « Course à pied » au catalogue.
 *
 * <p>Les six cas du relevé sont repris tels quels, pour que ce fichier serve de
 * réponse et pas seulement de garde-fou.
 */
class ActivitySearchAccentIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityService activityService;
    @Autowired ActivityRepository activityRepository;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Test
    void course_trouveCourseAPied() {
        assertThat(names("course")).contains("Course à pied");
    }

    @Test
    void cours_trouveCourseAPied() {
        assertThat(names("cours")).contains("Course à pied");
    }

    @Test
    void laCasseEstIgnoree() {
        assertThat(names("COURSE")).contains("Course à pied");
    }

    @Test
    void lInfixeFonctionne() {
        // « yog » attrape Hatha Yoga, Vinyasa Yoga, Yin Yoga, Yoga : la
        // correspondance ne porte pas que sur le début du nom.
        assertThat(names("yog")).contains("Yoga");
        assertThat(names("yog").size()).isGreaterThan(1);
    }

    @Test
    void lAccentEstIgnore_leCasQuiNeMarchaitPas() {
        // Le défaut relevé : « course a pied » rendait 0 là où « Course à pied »
        // rendait 1.
        assertThat(names("course a pied")).contains("Course à pied");
    }

    @Test
    void lAccentEstIgnoreDansLesDeuxSens() {
        // Et symétriquement : chercher AVEC l'accent doit marcher aussi, y
        // compris en majuscules. unaccent des deux côtés, pas d'un seul.
        assertThat(names("COURSE À PIED")).contains("Course à pied");
        assertThat(names("Course à pied")).contains("Course à pied");
    }

    @Test
    void lesTroisEcrituresRendentLaMemeChose() {
        assertThat(names("course a pied"))
            .isEqualTo(names("COURSE À PIED"))
            .isEqualTo(names("Course à pied"));
    }

    @Test
    void laFauteDeFrappeEstRattrapee_parRepli() {
        // « corse » rendait 0 : aucun rapprochement phonétique ni flou ne
        // s'appliquait sur cette route. C'était le « souhaité, non bloquant » du
        // contrat.
        assertThat(names("corse")).contains("Course à pied");
    }

    @Test
    void leFlouNeSExecuteQuEnRepli_jamaisMeleAuxResultatsExacts() {
        // Une requête qui trouve son mot ne doit pas voir surgir de voisins
        // approchants : « Yoga » et « Toga » partagent trois trigrammes sur
        // quatre, et la similarité ne sait pas qu'un seul des deux est un mot.
        var exacts = names("yoga");
        assertThat(exacts).isNotEmpty();
        assertThat(exacts).allSatisfy(nom ->
            assertThat(nom.toLowerCase()).contains("yoga"));
    }

    @Test
    void lesJokersDeLUtilisateurNeSontPasDesJokers() {
        // Les requêtes dérivées de Spring Data échappent « % », « _ » et « \\ »
        // dans l'argument avant de composer le motif ; une requête native écrite
        // à la main, non. Sans échappement, chercher « % » rendait TOUT le
        // référentiel, et « cours_ » se mettait à trouver « course ».
        //
        // L'assertion porte sur le dépôt et non sur le service : c'est là que
        // vit l'échappement, et le repli flou du service masquerait le résultat
        // — « cours_ » ressemble assez à « Course à pied » pour qu'il le rende,
        // ce qui est le comportement voulu mais ne dit rien du joker.
        assertThat(activityRepository.searchByNameUnaccented("%", FIRST_PAGE)).isEmpty();
        assertThat(activityRepository.searchByNameUnaccented("_", FIRST_PAGE)).isEmpty();
        assertThat(activityRepository.searchByNameUnaccented("cours_", FIRST_PAGE)).isEmpty();

        // Et le motif reste fonctionnel une fois l'échappement en place.
        assertThat(activityRepository.searchByNameUnaccented("cours", FIRST_PAGE)).isNotEmpty();
    }

    @Test
    void chercherUnJokerNeRendPasToutLeCatalogue() {
        // Vu de la route : le symptôme qu'on ne veut pas, quelle que soit la
        // couche qui l'aurait produit.
        long total = activityRepository.count();
        assertThat(names("%").size()).isLessThan((int) total);
    }

    @Test
    void uneRequeteSansAucunRapprochementRendVide() {
        assertThat(names("zzzzqxwv")).isEmpty();
    }

    @Test
    void laRechercheParCategorieIgnoreAussiLesAccents() {
        var toutes = activityService.searchActivities(null, "course a pied", FIRST_PAGE);
        assertThat(toutes).isNotEmpty();

        var categoryId = toutes.getContent().get(0).category().id();
        var dansLaCategorie =
            activityService.searchActivities(categoryId, "course a pied", FIRST_PAGE);

        assertThat(dansLaCategorie.map(ActivityDto::name)).contains("Course à pied");
    }

    private java.util.List<String> names(String search) {
        Page<ActivityDto> page = activityService.searchActivities(null, search, FIRST_PAGE);
        return page.getContent().stream().map(ActivityDto::name).toList();
    }
}
