package org.program.pair.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.repository.NotificationPrefRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * « No enum constant NotificationFrequency.DAILY » — ticket client du
 * 2026-08-25, §5.
 *
 * <p>La valeur était écrite par nos propres migrations de semis (V12, V13, V27)
 * et n'a jamais existé dans l'énumération. Le symptôme est muet : l'envoi étant
 * asynchrone, les comptes touchés cessent de recevoir leurs notifications sans
 * que rien ne remonte. Ces tests s'exécutent sur une base réellement migrée,
 * seule façon de vérifier une correction de données.
 */
class NotificationFrequencyMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationPrefRepository notificationPrefRepository;

    @Test
    @DisplayName("plus aucune ligne ne porte la valeur 'DAILY'")
    void plusAucunDaily() {
        Integer restants = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_prefs WHERE frequency = 'DAILY'", Integer.class);

        assertThat(restants)
            .as("V80 doit rattraper les semis V12, V13 et V27, qui s'appliquent aussi aux bases neuves")
            .isZero();
    }

    @Test
    @DisplayName("toute valeur en base se relit sans exception")
    void toutesLesValeursSeRelisent() {
        List<String> valeurs = jdbcTemplate.queryForList(
            "SELECT DISTINCT frequency FROM notification_prefs", String.class);

        // C'est exactement l'opération qui échouait en production, et l'échec
        // n'y était visible que dans les journaux.
        assertThat(valeurs).allSatisfy(v -> NotificationFrequency.valueOf(v));
        assertThat(notificationPrefRepository.findAll()).isNotNull();
    }

    @Test
    @DisplayName("la base refuse désormais une fréquence inconnue")
    void frequenceInconnueRefusee() {
        UUID userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users LIMIT 1", UUID.class);

        // Le défaut n'est jamais venu de l'application — @Enumerated(STRING) ne
        // peut écrire qu'un nom de constante. Il est venu du SQL, seul chemin
        // où rien ne vérifiait rien. C'est ce chemin que la contrainte ferme.
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO notification_prefs (user_id, notification_type, frequency) VALUES (?, ?, ?)",
            userId, "PROGRAM_REMINDER_TEST", "DAILY"))
            .hasMessageContaining("ck_notif_prefs_frequency");
    }
}
