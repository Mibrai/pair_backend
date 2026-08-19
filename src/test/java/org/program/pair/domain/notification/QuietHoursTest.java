package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D6 — la fenêtre de silence.
 *
 * <p>Tout le lot tient dans une comparaison, et cette comparaison a un piège :
 * « 22 h – 7 h » n'est pas un intervalle croissant. Écrite naïvement, elle ne
 * contient rien du tout — le réglage le plus courant du produit n'aurait alors
 * aucun effet, sans erreur, sans trace, et invisible à tout test qui n'essaierait
 * qu'une fenêtre diurne.
 */
class QuietHoursTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    // — la fenêtre qui traverse minuit —

    @Test
    void uneNuit_doitFaireTaire_avantEtApresMinuit() {
        QuietHours night = QuietHours.of(22, 7);

        assertThat(night.silences(at(23, 30), PARIS)).isTrue();
        assertThat(night.silences(at(3, 0), PARIS)).isTrue();
        assertThat(night.silences(at(6, 59), PARIS)).isTrue();
    }

    @Test
    void uneNuit_doitLaisserPasser_lJournee() {
        QuietHours night = QuietHours.of(22, 7);

        assertThat(night.silences(at(7, 0), PARIS)).isFalse();
        assertThat(night.silences(at(14, 0), PARIS)).isFalse();
        assertThat(night.silences(at(21, 59), PARIS)).isFalse();
    }

    @Test
    void lesBornes_doiventEtreIncluseEtExclue() {
        // C'est la lecture que fait quelqu'un d'un réglage « 22 h – 7 h » : ça se
        // tait à 22 h pile, et ça repart à 7 h pile.
        QuietHours night = QuietHours.of(22, 7);

        assertThat(night.silences(at(22, 0), PARIS)).isTrue();
        assertThat(night.silences(at(7, 0), PARIS)).isFalse();
    }

    // — la fenêtre ordinaire —

    @Test
    void uneFenetreDiurne_doitSeComporterNormalement() {
        QuietHours meeting = QuietHours.of(9, 12);

        assertThat(meeting.silences(at(10, 0), PARIS)).isTrue();
        assertThat(meeting.silences(at(8, 59), PARIS)).isFalse();
        assertThat(meeting.silences(at(12, 0), PARIS)).isFalse();
    }

    // — le fuseau —

    @Test
    void leMemeInstant_doitSeLireDifferemment_selonLeFuseau() {
        // La raison pour laquelle le filtrage descend jusqu'à l'appareil : à cet
        // instant il fait nuit à Tokyo et pas encore à Paris.
        QuietHours night = QuietHours.of(22, 7);
        Instant instant = at(16, 0);  // 16 h à Paris, 23 h à Tokyo

        assertThat(night.silences(instant, PARIS)).isFalse();
        assertThat(night.silences(instant, TOKYO)).isTrue();
    }

    // — l'absence de fenêtre —

    @Test
    void sansReglage_rienNeDoitEtreFaitTaire() {
        assertThat(QuietHours.of(null, null).disabled()).isTrue();
        assertThat(QuietHours.of(null, null).silences(at(3, 0), PARIS)).isFalse();
    }

    @Test
    void uneMoitieDeReglage_neDoitRienFaireTaire() {
        // Deviner la borne manquante ferait taire des notifications sur une
        // intention supposée, et dans un sens qui ne se remarque pas.
        assertThat(QuietHours.of(22, null).silences(at(23, 0), PARIS)).isFalse();
        assertThat(QuietHours.of(null, 7).silences(at(3, 0), PARIS)).isFalse();
    }

    @Test
    void deuxBornesEgales_neDoiventRienFaireTaire() {
        // « 22 → 22 » se lit aussi bien « une minute » que « toute la journée ».
        // Le service refuse de l'enregistrer ; ici on garantit qu'une valeur
        // arrivée par un autre chemin ne fait pas taire une journée entière.
        QuietHours ambiguous = QuietHours.of(22, 22);

        assertThat(ambiguous.disabled()).isTrue();
        assertThat(ambiguous.silences(at(3, 0), PARIS)).isFalse();
        assertThat(ambiguous.silences(at(22, 0), PARIS)).isFalse();
    }

    /** Un instant correspondant à cette heure locale parisienne. */
    private static Instant at(int hour, int minute) {
        return LocalDateTime.of(2026, 6, 15, hour, minute).atZone(PARIS).toInstant();
    }
}
