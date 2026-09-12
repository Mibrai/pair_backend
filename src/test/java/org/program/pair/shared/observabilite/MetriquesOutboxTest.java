package org.program.pair.shared.observabilite;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les noms, les étiquettes, et ce que rend une jauge quand la base ne répond
 * pas.
 *
 * <p>Aucun contexte Spring et aucune base : le {@link JdbcTemplate} est
 * construit sans source de données, ce qui n'est pas un raccourci mais
 * <b>l'objet du dernier test</b> — une jauge est évaluée par le registre au
 * moment de la collecte, sur un fil qui n'a ni transaction ni requête HTTP, et
 * ce qu'elle fait quand la lecture échoue décide de ce qu'un graphique montrera
 * le jour d'un incident. Zéro voudrait dire « aucun arriéré, aucun blocage »,
 * c'est-à-dire le contraire de ce qu'on sait ; {@code NaN} laisse un trou, et un
 * trou se voit.
 *
 * <p>Ce que cette classe <b>ne</b> vérifie <b>pas</b> : la requête SQL
 * elle-même. Elle demande une base PostgreSQL — {@code FILTER}, {@code EXTRACT},
 * {@code interval} — et le premier scrape en production en est l'épreuve. Voir
 * le rapport de livraison.
 */
class MetriquesOutboxTest {

    private SimpleMeterRegistry registre;
    private MetriquesOutbox metriques;

    @BeforeEach
    void preparer() {
        registre = new SimpleMeterRegistry();
        // Sans DataSource, délibérément : toute lecture échoue, ce qui est le
        // cas éprouvé par le dernier test et sans effet sur les autres.
        metriques = new MetriquesOutbox(registre, new JdbcTemplate());
    }

    /**
     * Le compteur de réclamation existe avant le premier message, parce que
     * c'est son <b>immobilité</b> qu'on surveille : une série absente ne se
     * distingue pas d'une série à zéro, et « plus aucun balayage ne tourne »
     * serait alors illisible.
     */
    @Test
    void leCompteurDeReclamation_doitExisterAZero_desLeDemarrage() {
        assertThat(registre.find(MetriquesOutbox.COMPTEUR_RECLAMES).counter())
            .isNotNull()
            .satisfies(compteur -> assertThat(compteur.count()).isZero());
    }

    /** Une réclamation vide ne fait pas monter le compteur, et ne le casse pas. */
    @Test
    void uneReclamationVide_neDoitPasFaireMonterLeCompteur() {
        metriques.messagesReclames(0);
        metriques.messagesReclames(7);

        assertThat(registre.find(MetriquesOutbox.COMPTEUR_RECLAMES).counter().count())
            .isEqualTo(7);
    }

    /**
     * Refus et abandon sont deux séries distinctes, et c'est tout l'intérêt :
     * {@code outbox.failed} ne bouge qu'au bout des dix essais, près de deux
     * heures après le premier refus, alors qu'{@code outbox.refused} monte
     * pendant la panne.
     */
    @Test
    void refusEtAbandon_doiventResterDeuxSeriesDistinctes() {
        metriques.messageRefuse("EMAIL", "WATCH_ALERT");
        metriques.messageRefuse("EMAIL", "WATCH_ALERT");
        metriques.messageEnEchec("EMAIL", "WATCH_ALERT");

        assertThat(compteur(MetriquesOutbox.COMPTEUR_REFUS, "EMAIL", "WATCH_ALERT")).isEqualTo(2);
        assertThat(compteur(MetriquesOutbox.COMPTEUR_ECHECS, "EMAIL", "WATCH_ALERT")).isEqualTo(1);
        assertThat(registre.find(MetriquesOutbox.COMPTEUR_PARTIS).counter())
            .as("rien n'est parti : la série ne doit pas être inventée")
            .isNull();
    }

    /**
     * Le canal et l'objet séparent les séries. Sans eux, un SMS de veille en
     * échec et un récapitulatif hebdomadaire en échec compteraient dans la même
     * ligne, et l'alerte ne dirait pas lequel des deux est cassé.
     */
    @Test
    void lesEtiquettes_doiventSeparerCanalEtObjet() {
        metriques.messageParti("EMAIL", "WATCH_ALERT");
        metriques.messageParti("SMS", "WATCH_ALERT");
        metriques.messageParti("SMS", "WATCH_ALERT");

        assertThat(compteur(MetriquesOutbox.COMPTEUR_PARTIS, "EMAIL", "WATCH_ALERT")).isEqualTo(1);
        assertThat(compteur(MetriquesOutbox.COMPTEUR_PARTIS, "SMS", "WATCH_ALERT")).isEqualTo(2);
    }

    /**
     * Les deux jauges d'arriéré portent le même nom et se distinguent par leur
     * étiquette de priorité. Cinq minutes de retard sur une alerte de veille et
     * cinq minutes sur un récapitulatif ne veulent pas dire la même chose ; une
     * série mélangée aurait noyé la première.
     */
    @Test
    void lArriere_doitSeDeclinerParPriorite() {
        assertThat(registre.find(MetriquesOutbox.JAUGE_ARRIERE).gauges())
            .hasSize(2)
            .extracting(jauge -> jauge.getId().getTag(MetriquesOutbox.ETIQUETTE_PRIORITE))
            .containsExactlyInAnyOrder("all", "0");
    }

    /**
     * Le test qui compte. La base injoignable, les trois jauges rendent
     * {@code NaN} et non zéro : un trou dans le graphique, pas une file
     * faussement vide et un verrou faussement sain.
     */
    @Test
    void lesJauges_doiventRendreNaN_quandLaLectureEchoue() {
        assertThat(registre.find(MetriquesOutbox.JAUGE_BLOQUES).gauge())
            .isNotNull()
            .satisfies(jauge -> assertThat(jauge.value()).isNaN());

        for (Gauge jauge : registre.find(MetriquesOutbox.JAUGE_ARRIERE).gauges()) {
            assertThat(jauge.value())
                .as("arriéré priorité « %s »",
                    jauge.getId().getTag(MetriquesOutbox.ETIQUETTE_PRIORITE))
                .isNaN();
        }
    }

    // ------------------------------------------------------------------ outils

    private double compteur(String nom, String canal, String objet) {
        var compteur = registre.find(nom)
            .tag(MetriquesOutbox.ETIQUETTE_CANAL, canal)
            .tag(MetriquesOutbox.ETIQUETTE_OBJET, objet)
            .counter();
        return compteur == null ? 0 : compteur.count();
    }
}
