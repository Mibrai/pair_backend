package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLog;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.gdpr.jobs.GdprPurgeJob;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-20 — le job de purge RGPD, appelé tel que le planificateur l'appelle.
 *
 * <p>{@code GdprPurgeIntegrationTest} éprouve le service compte par compte ; ce
 * qui manquait est le job lui-même — son drapeau, sa sélection, et la purge des
 * journaux d'audit, qu'aucun test ne citait.
 */
class GdprPurgeJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired GdprPurgeJob job;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void laPurge_supprimeUnCompteDontLaDemandeADepasseLeDelai_etGardeUnCompteActif() {
        User demande = compte(false, Instant.now().minus(40, ChronoUnit.DAYS));
        User actif = compte(true, null);

        job.purgeInactiveAccounts();

        assertThat(userRepository.findById(demande.getId())).as("demande de plus de 30 jours").isEmpty();
        assertThat(userRepository.findById(actif.getId())).isPresent();
    }

    @Test
    void lesJournauxDAuditPlusVieuxQueLaRetention_sontPurges_lesRecentsRestent() {
        UUID ancien = journal(Instant.now().minus(800, ChronoUnit.DAYS));
        UUID recent = journal(Instant.now().minus(10, ChronoUnit.DAYS));

        job.purgeOldAuditLogs();

        assertThat(auditLogRepository.findById(ancien)).as("au-delà de deux ans").isEmpty();
        assertThat(auditLogRepository.findById(recent)).isPresent();
    }

    private UUID journal(Instant date) {
        AuditLog ligne = auditLogRepository.save(AuditLog.builder()
            .actionType(AuditActionType.USER_LOGIN)
            .entityType("USER")
            .entityId(UUID.randomUUID())
            .build());
        // created_at n'est pas modifiable par l'entité (updatable = false) : la
        // date d'un journal ancien se pose en SQL, sur cette seule ligne.
        jdbcTemplate.update("UPDATE audit_logs SET created_at = ? WHERE id = ?",
            java.sql.Timestamp.from(date), ligne.getId());
        return ligne.getId();
    }

    private User compte(boolean actif, Instant demandeDeSuppression) {
        return userRepository.save(User.builder()
            .email(uniqueEmail("purge-job"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Purge")
            .isActive(actif)
            .deactivatedAt(demandeDeSuppression)
            .build());
    }
}
