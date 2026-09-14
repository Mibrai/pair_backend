package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.gdpr.GdprService;
import org.program.pair.domain.gdpr.dto.GdprExportDto;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le module {@code /api/progressions} est retiré (14/09), ses tables pas encore :
 * l'export RGPD doit toujours rendre à la personne les progressions qu'elle a
 * écrites, et seulement les siennes.
 */
class GdprExportProgressionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired GdprService gdprService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;

    @Test
    void lExport_doitRendreLesProgressionsDeLaPersonne_etSeulementLesSiennes() {
        User organisateur = compte("gdpr-prog-orga");
        User auteur = compte("gdpr-prog-auteur");
        UUID programme = programmeDe(organisateur);

        jdbcTemplate.update("INSERT INTO progressions (program_id, user_id, title, content) VALUES (?, ?, ?, ?)",
            programme, auteur.getId(), "Ma séance", "Dix longueurs.");
        jdbcTemplate.update("INSERT INTO progressions (program_id, user_id, title, content) VALUES (?, ?, ?, ?)",
            programme, organisateur.getId(), "Celle de l'organisateur", "Autre chose.");

        GdprExportDto export = gdprService.exportUserData(auteur.getId());

        assertThat(export.getProgressions()).hasSize(1);
        assertThat(export.getProgressions().get(0).getLabel()).isEqualTo("Ma séance");
        assertThat(export.getProgressions().get(0).getValue()).isEqualTo("Dix longueurs.");
        assertThat(export.getStatistics().get("progressions")).isEqualTo(1L);
    }

    private User compte(String prefixe) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefixe))
            .passwordHash("x")
            .displayName("Testeur")
            .build());
    }

    private UUID programmeDe(User organisateur) {
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(organisateur).activity(activityRepository.findAll().get(0)).build());
        Program program = programRepository.save(Program.builder()
            .userActivity(ua).title("Export " + UUID.randomUUID().toString().substring(0, 6))
            .status(ProgramStatus.ACTIVE).isPublic(true).build());
        return program.getId();
    }
}
