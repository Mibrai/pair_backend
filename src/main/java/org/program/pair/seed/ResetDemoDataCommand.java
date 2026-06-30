package org.program.pair.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Utility class to reset demo data in dev/staging environments.
 * This class is designed to remove demo users and all their associated data.
 *
 * IMPORTANT: This command is NOT called automatically at startup.
 * It is intended to be invoked manually or via an admin endpoint.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResetDemoDataCommand {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Resets all demo data by deleting demo users and their associated data.
     * This method enforces strict safety checks:
     * - CANNOT be executed in production environment
     * - Logs warnings before and after deletion
     * - Deletes data in proper order to respect foreign key constraints
     *
     * @throws IllegalStateException if attempted in production environment
     */
    @Transactional
    public void resetDemoData() {
        // SAFETY CHECK: Never allow this in production
        if ("prod".equalsIgnoreCase(activeProfile) || "production".equalsIgnoreCase(activeProfile)) {
            throw new IllegalStateException(
                "SECURITY VIOLATION: Cannot reset demo data in production environment! " +
                "Active profile: " + activeProfile
            );
        }

        log.warn("========================================");
        log.warn("RESET DEMO DATA - Starting deletion process");
        log.warn("Active profile: {}", activeProfile);
        log.warn("Target: All users with email like 'demo%@pair.app'");
        log.warn("========================================");

        try {
            // Delete in order of foreign key dependencies (child → parent)

            // 1. Delete messages (references conversations, users)
            int messagesDeleted = jdbcTemplate.update(
                "DELETE FROM messages WHERE sender_id IN (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} messages from demo users", messagesDeleted);

            // 2. Delete conversation_members (references conversations, users)
            int conversationMembersDeleted = jdbcTemplate.update(
                "DELETE FROM conversation_members WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} conversation memberships for demo users", conversationMembersDeleted);

            // 3. Delete conversations where all members were demo users
            // (This is for conversations that have no remaining non-demo members)
            int conversationsDeleted = jdbcTemplate.update(
                "DELETE FROM conversations WHERE id NOT IN " +
                "(SELECT DISTINCT conversation_id FROM conversation_members)"
            );
            log.warn("Deleted {} orphaned conversations", conversationsDeleted);

            // 4. Delete schedules (references programs)
            int schedulesDeleted = jdbcTemplate.update(
                "DELETE FROM schedules WHERE program_id IN " +
                "(SELECT p.id FROM programs p " +
                "JOIN user_activities ua ON p.user_activity_id = ua.id " +
                "JOIN users u ON ua.user_id = u.id " +
                "WHERE u.email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} schedules for demo users", schedulesDeleted);

            // 5. Delete program_media (references programs)
            int programMediaDeleted = jdbcTemplate.update(
                "DELETE FROM program_media WHERE program_id IN " +
                "(SELECT p.id FROM programs p " +
                "JOIN user_activities ua ON p.user_activity_id = ua.id " +
                "JOIN users u ON ua.user_id = u.id " +
                "WHERE u.email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} program media for demo users", programMediaDeleted);

            // 6. Delete programs (references user_activities)
            int programsDeleted = jdbcTemplate.update(
                "DELETE FROM programs WHERE user_activity_id IN " +
                "(SELECT ua.id FROM user_activities ua " +
                "JOIN users u ON ua.user_id = u.id " +
                "WHERE u.email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} programs for demo users", programsDeleted);

            // 7. Delete user_activities (references users)
            int userActivitiesDeleted = jdbcTemplate.update(
                "DELETE FROM user_activities WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')"
            );
            log.warn("Deleted {} user activities for demo users", userActivitiesDeleted);

            // 8. Finally, delete users
            int usersDeleted = jdbcTemplate.update(
                "DELETE FROM users WHERE email LIKE 'demo%@pair.app'"
            );
            log.warn("Deleted {} demo users", usersDeleted);

            log.warn("========================================");
            log.warn("RESET DEMO DATA - Completed successfully");
            log.warn("Summary: {} users, {} activities, {} programs, {} schedules, {} media, " +
                     "{} conversations, {} memberships, {} messages deleted",
                usersDeleted, userActivitiesDeleted, programsDeleted, schedulesDeleted,
                programMediaDeleted, conversationsDeleted, conversationMembersDeleted, messagesDeleted);
            log.warn("========================================");

        } catch (Exception e) {
            log.error("Error during demo data reset: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to reset demo data", e);
        }
    }
}
