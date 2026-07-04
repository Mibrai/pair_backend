-- Script SQL pour nettoyer les données de test Railway
-- Usage: psql $DATABASE_URL < cleanup-railway-data.sql

-- Désactiver temporairement les foreign key checks pour une suppression plus rapide
SET session_replication_role = 'replica';

-- Supprimer les données dans l'ordre inverse des dépendances

-- 1. User programs (inscriptions)
DELETE FROM user_programs
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 2. Program activities
DELETE FROM program_activities
WHERE user_program_id IN (
  SELECT up.id FROM user_programs up
  JOIN users u ON up.user_id = u.id
  WHERE u.email LIKE 'railway%@pair.app'
);

-- 3. Schedules
DELETE FROM schedules
WHERE program_id IN (
  SELECT p.id FROM programs p
  JOIN user_activities ua ON p.user_activity_id = ua.id
  JOIN users u ON ua.user_id = u.id
  WHERE u.email LIKE 'railway%@pair.app'
);

-- 4. Program media
DELETE FROM program_media
WHERE program_id IN (
  SELECT p.id FROM programs p
  JOIN user_activities ua ON p.user_activity_id = ua.id
  JOIN users u ON ua.user_id = u.id
  WHERE u.email LIKE 'railway%@pair.app'
);

-- 5. Programs
DELETE FROM programs
WHERE user_activity_id IN (
  SELECT ua.id FROM user_activities ua
  JOIN users u ON ua.user_id = u.id
  WHERE u.email LIKE 'railway%@pair.app'
);

-- 6. User activities
DELETE FROM user_activities
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 7. Messages
DELETE FROM messages
WHERE conversation_id IN (
  SELECT c.id FROM conversations c
  JOIN conversation_members cm ON c.id = cm.conversation_id
  WHERE cm.user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app')
);

-- 8. Conversation members
DELETE FROM conversation_members
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 9. Conversations (orphelines)
DELETE FROM conversations
WHERE id NOT IN (SELECT DISTINCT conversation_id FROM conversation_members);

-- 10. Notifications
DELETE FROM notifications
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 11. Notification prefs
DELETE FROM notification_prefs
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 12. Device tokens
DELETE FROM device_tokens
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 13. Badge awards
DELETE FROM badge_awards
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 14. Reviews (en tant que reviewer)
DELETE FROM reviews
WHERE reviewer_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 15. Peer recommendations (en tant que from_user ou to_user)
DELETE FROM peer_recommendations
WHERE from_user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app')
   OR to_user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 16. Reports (en tant que reporter ou target)
DELETE FROM reports
WHERE reporter_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app')
   OR (target_type = 'USER' AND target_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app'));

-- 17. Progression entries
DELETE FROM progression_entries
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 18. Search logs
DELETE FROM search_logs
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 19. Audit logs
DELETE FROM audit_logs
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'railway%@pair.app');

-- 20. Users (en dernier)
DELETE FROM users WHERE email LIKE 'railway%@pair.app';

-- Réactiver les foreign key checks
SET session_replication_role = 'origin';

-- Vérification
SELECT 'Cleanup terminé!' as status;
SELECT
  'Utilisateurs restants:' as info,
  COUNT(*) as count
FROM users
WHERE email LIKE 'railway%@pair.app';
