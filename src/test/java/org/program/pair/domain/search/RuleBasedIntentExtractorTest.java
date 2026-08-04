package org.program.pair.domain.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.program.pair.config.LocaleConfig;
import org.program.pair.domain.search.dto.SearchIntent;
import org.program.pair.shared.i18n.Messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RuleBasedIntentExtractorTest {

    // Messages branché sur les vrais bundles plutôt que mocké : une clé absente
    // de messages*.properties doit faire échouer ces tests, pas passer inaperçue.
    private final RuleBasedIntentExtractor extractor =
        new RuleBasedIntentExtractor(new ActivityTaxonomy(), new Messages(new LocaleConfig().messageSource()));

    @ParameterizedTest
    @ValueSource(strings = {
        "je debute en escalade", "escalade debutant", "initiation escalade",
        "beginner climbing", "just starting climbing", "new to this, climbing",
        "einsteiger klettern", "anfanger klettern"
    })
    void niveauDebutant_detecteEnFrEnDe(String query) {
        assertThat(extractor.extractIntent(query).level()).isEqualTo("BEGINNER");
    }

    @Test
    void niveauIntermediaire_detecteEnFrEnDe() {
        assertThat(extractor.extractIntent("tennis intermediaire").level()).isEqualTo("INTERMEDIATE");
        assertThat(extractor.extractIntent("intermediate tennis").level()).isEqualTo("INTERMEDIATE");
        assertThat(extractor.extractIntent("fortgeschritten tennis").level()).isEqualTo("INTERMEDIATE");
    }

    @Test
    void niveauAvance_detecteEnFrEnDe() {
        assertThat(extractor.extractIntent("joueur confirme").level()).isEqualTo("ADVANCED");
        assertThat(extractor.extractIntent("advanced player").level()).isEqualTo("ADVANCED");
        assertThat(extractor.extractIntent("erfahrener spieler").level()).isEqualTo("ADVANCED");
    }

    @Test
    void niveauExpert_distinctDAvance() {
        assertThat(extractor.extractIntent("niveau expert").level()).isEqualTo("EXPERT");
        assertThat(extractor.extractIntent("expert level").level()).isEqualTo("EXPERT");
        assertThat(extractor.extractIntent("experte spielerin").level()).isEqualTo("EXPERT");
    }

    @Test
    void formatDuo_detecteMemeQuandLeMotSeulEstPresent() {
        // "seul à seul" contient littéralement "seul" (SOLO) — la phrase la plus
        // spécifique doit l'emporter, pas le mot isolé qu'elle contient.
        assertThat(extractor.extractIntent("je cherche un partenaire pour jouer seul a seul").format())
            .isEqualTo("DUO");
    }

    @Test
    void formatSolo_detecteQuandAucunePhraseDuoOuGroupeNeMatche() {
        assertThat(extractor.extractIntent("je veux courir seul").format()).isEqualTo("SOLO");
        assertThat(extractor.extractIntent("by myself running").format()).isEqualTo("SOLO");
        assertThat(extractor.extractIntent("allein laufen").format()).isEqualTo("SOLO");
    }

    @Test
    void formatGroupe_detecteEnFrEnDe() {
        assertThat(extractor.extractIntent("yoga en groupe").format()).isEqualTo("GROUP");
        assertThat(extractor.extractIntent("yoga in a group").format()).isEqualTo("GROUP");
        assertThat(extractor.extractIntent("yoga in der gruppe").format()).isEqualTo("GROUP");
    }

    @Test
    void timeHint_tomorrowEvening_produitDemainEtSoir() {
        String hint = extractor.extractIntent("tennis tomorrow evening").timeHint();
        assertThat(hint).contains("demain").contains("soir");
    }

    @Test
    void timeHint_demainSoir_produitLesDeuxJetonsEnFrancais() {
        assertThat(extractor.extractIntent("du yoga demain soir").timeHint())
            .contains("demain").contains("soir");
    }

    @Test
    void timeHint_heuteMorgen_neSignifiePasDemain() {
        // Ambiguïté connue : "heute morgen" (ce matin) ne doit pas être confondu
        // avec "morgen" seul (demain).
        String hint = extractor.extractIntent("laufen heute morgen").timeHint();
        assertThat(hint).contains("matin");
    }

    @Test
    void timeHint_morgenSeul_signifieDemain() {
        String hint = extractor.extractIntent("laufen morgen").timeHint();
        assertThat(hint).isEqualTo("demain");
    }

    @Test
    void timeHint_weekend_detecteEnFrEnDe() {
        assertThat(extractor.extractIntent("tennis ce week-end").timeHint()).isEqualTo("week-end");
        assertThat(extractor.extractIntent("tennis this weekend").timeHint()).isEqualTo("week-end");
        assertThat(extractor.extractIntent("tennis am wochenende").timeHint()).isEqualTo("week-end");
    }

    @Test
    void rayon_kilometresExtrait() {
        assertThat(extractor.extractIntent("tennis dans un rayon de 10km").suggestedRadius()).isEqualTo(10_000);
        assertThat(extractor.extractIntent("tennis within 5 miles").suggestedRadius()).isEqualTo(Math.round(5 * 1609f));
    }

    @Test
    void rayon_absent_retombeSurLeDefaut() {
        assertThat(extractor.extractIntent("tennis").suggestedRadius()).isEqualTo(5000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "je veux faire du sport", "je m'ennuie", "je veux sortir",
        "i want to do something", "i'm bored", "find me something",
        "mir ist langweilig", "ich will raus"
    })
    void phraseVague_declencheLaClarification(String query) {
        SearchIntent intent = extractor.extractIntent(query);
        assertThat(intent.needsClarification()).isTrue();
        assertThat(intent.clarificationQuestion()).isNotBlank();
    }

    @Test
    void phraseAvecActiviteConnue_neDeclenchePasLaClarificationMemeCourte() {
        SearchIntent intent = extractor.extractIntent("yoga");
        assertThat(intent.needsClarification()).isFalse();
    }

    @Test
    void canonicalActivitySlug_resoluViaLaTaxonomieCrossLingue() {
        assertThat(extractor.extractIntent("Laufen am Sonntag").canonicalActivitySlug()).isEqualTo("running");
        assertThat(extractor.extractIntent("course a pied dimanche").canonicalActivitySlug()).isEqualTo("running");
    }

    @Test
    void activityKeyword_conserveLaRequeteBrute() {
        assertThat(extractor.extractIntent("yoga en groupe").activityKeyword()).isEqualTo("yoga en groupe");
    }

    @Test
    void requeteMultilingue_neLevePasDException() {
        assertThatCode(() -> extractor.extractIntent("yoga beginner ce soir dans 10km avec un partenaire zu zweit"))
            .doesNotThrowAnyException();
    }

    @Test
    void requeteVide_neLevePasDException() {
        assertThatCode(() -> extractor.extractIntent("")).doesNotThrowAnyException();
    }

    @Test
    void neuf_arguments_utilisesToujours_canonicalActivitySlugJamaisPerdu() {
        // Non-régression : s'assurer qu'on n'utilise jamais le constructeur legacy
        // à 8 arguments qui mettrait canonicalActivitySlug à null.
        SearchIntent intent = extractor.extractIntent("Laufen");
        assertThat(intent.canonicalActivitySlug()).isNotNull();
    }
}
