-- Un index en tête pour chaque clé étrangère qui n'en avait pas (P-BA-08).
--
-- PostgreSQL indexe automatiquement la colonne *référencée* d'une clé étrangère
-- — c'est une clé primaire ou une contrainte d'unicité — mais jamais la colonne
-- *référençante*. Or c'est celle-là qu'on interroge, deux fois :
--
--   · à la lecture, quand l'application filtre « les lignes de ce parent » ;
--   · à la suppression du parent, où PostgreSQL émet lui-même
--     « SELECT 1 FROM enfant WHERE fk = ? FOR KEY SHARE » pour appliquer
--     ON DELETE CASCADE, SET NULL ou RESTRICT. Sans index, c'est un parcours
--     séquentiel complet de la table enfant, par ligne parente supprimée.
--
-- Vingt-et-une contraintes étaient dans ce cas, vérifiées une par une contre le
-- catalogue d'une base fraîchement migrée : les onze de la fiche d'audit, et dix
-- qu'elle n'avait pas vues. Les colonnes déjà couvertes par une clé primaire,
-- une contrainte d'unicité ou un index existant ne sont pas reprises ici —
-- recréer un index existant ferait échouer la migration.
--
-- ============================================================================
-- POURQUOI PAS « CONCURRENTLY », ALORS QUE LA FICHE LE DEMANDAIT
-- ============================================================================
--
-- La fiche prévoyait CREATE INDEX CONCURRENTLY, qui construit un index sans
-- bloquer les écritures. Écrit ainsi, et avec le fichier .sql.conf portant
-- « executeInTransaction=false », Flyway 12.4 accepte bien la migration et
-- l'annonce « [non-transactional] » : le mécanisme existe et fonctionne en
-- édition Community. Mais la migration ne finit jamais.
--
-- Mesuré le 12/09 contre une base neuve : Flyway prend son verrou d'exclusion
-- avec pg_try_advisory_xact_lock — un verrou *de transaction* — et laisse donc
-- une transaction ouverte sur une seconde connexion pendant toute la durée du
-- lot. Or CREATE INDEX CONCURRENTLY attend la fin de toutes les transactions
-- qui pourraient voir la table. Il attend donc Flyway, qui l'attend : la
-- migration reste bloquée sur « Lock / virtualxid », pg_blocking_pids désignant
-- la connexion de Flyway elle-même. Au démarrage du conteneur de test, cela
-- veut dire un contexte Spring qui ne démarre jamais.
--
-- Le contournement existe — « spring.flyway.postgresql.transactional.lock=false »
-- fait basculer Flyway sur pg_try_advisory_lock, un verrou de session, et la
-- migration passe alors en 0,2 s (vérifié). Mais c'est une propriété qui change
-- la façon dont Flyway se protège pour *toutes* les migrations, et cela ne se
-- décide pas dans le même geste qu'un ajout d'index.
--
-- D'où ce fichier : des CREATE INDEX simples, dans la transaction de Flyway.
-- C'est un arbitrage d'exploitation, et il faut le dire. CREATE INDEX prend un
-- verrou SHARE : les lectures passent, les écritures attendent la fin de la
-- construction. La migration étant transactionnelle, les vingt-et-un verrous
-- sont tenus jusqu'au COMMIT, donc les vingt-et-une tables bloquent leurs
-- écritures ensemble, le temps du lot. Sur ces tables — les plus grosses
-- comptent des milliers de lignes, pas des millions — cela se compte en
-- centaines de millisecondes, et le déploiement a lieu pendant que l'instance
-- redémarre. En contrepartie, le lot est atomique : soit les vingt-et-un index
-- existent, soit aucun, et il ne peut pas rester d'index INVALID derrière un
-- CONCURRENTLY interrompu — ce qui s'est produit lors de l'essai du 12/09 et
-- que IF NOT EXISTS, qui ne regarde que le nom, n'aurait pas réparé.
--
-- Le jour où une de ces tables deviendra vraiment grosse, la marche à suivre
-- est : poser la propriété transactional.lock=false, puis construire le nouvel
-- index en CONCURRENTLY dans une migration à lui, avec son .sql.conf.
--
-- Pour les colonnes NULLables (ON DELETE SET NULL, appartenance facultative),
-- l'index est partiel : « WHERE fk IS NOT NULL » exclut les lignes que la
-- recherche « WHERE fk = ? » ne pourra jamais atteindre, et le prédicat est
-- impliqué par cette recherche, donc l'index reste utilisable. Sur une table où
-- la colonne est majoritairement nulle, l'index ne pèse presque rien.

-- ---------------------------------------------------------------- messagerie

-- Suppression d'un compte : la cascade parcourait conversation_members en
-- entier. La clé primaire (conversation_id, user_id) ne sert pas cette
-- recherche, user_id n'y étant pas en tête.
CREATE INDEX IF NOT EXISTS idx_conv_members_user
    ON conversation_members (user_id);

-- Deux liens facultatifs d'une conversation vers son contexte. Tous deux
-- ON DELETE SET NULL : supprimer une activité ou un créneau déclenche une
-- recherche sur ces colonnes.
CREATE INDEX IF NOT EXISTS idx_conversations_activity
    ON conversations (activity_context_id) WHERE activity_context_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_schedule
    ON conversations (schedule_id) WHERE schedule_id IS NOT NULL;

-- ------------------------------------------------------- partage de sécurité

-- La table n'avait qu'un index sur share_token, le seul chemin de lecture de la
-- page publique. Les deux cascades — compte et créneau — la parcouraient.
CREATE INDEX IF NOT EXISTS idx_safety_shares_user
    ON slot_safety_shares (user_id);

CREATE INDEX IF NOT EXISTS idx_safety_shares_schedule
    ON slot_safety_shares (schedule_id);

-- ------------------------------------------------------------- invitations

-- idx_invitations_inviter existait ; les deux autres clés, toutes deux
-- ON DELETE SET NULL, n'avaient rien.
CREATE INDEX IF NOT EXISTS idx_invitations_schedule
    ON slot_invitations (schedule_id) WHERE schedule_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_invitations_invitee
    ON slot_invitations (invitee_id) WHERE invitee_id IS NOT NULL;

-- ------------------------------------------------------- carte-souvenir

CREATE INDEX IF NOT EXISTS idx_vibe_votes_user
    ON recap_vibe_votes (user_id);

-- Même cas que conversation_members : clé primaire (recap_id, user_id), donc
-- rien pour user_id.
CREATE INDEX IF NOT EXISTS idx_recap_consents_user
    ON recap_participant_consents (user_id);

-- ----------------------------------------------------------- veille retour

-- guardians portait un index sur owner_id et un unique partiel
-- (owner_id, member_id) : member_id n'était en tête d'aucun des deux.
CREATE INDEX IF NOT EXISTS idx_guardians_member
    ON guardians (member_id) WHERE member_id IS NOT NULL;

-- watches avait (user_id, state) et l'unique partiel (user_id, schedule_id) :
-- schedule_id n'était jamais en tête, et la suppression d'un créneau récurrent
-- parcourait la table.
CREATE INDEX IF NOT EXISTS idx_watches_schedule
    ON watches (schedule_id);

CREATE INDEX IF NOT EXISTS idx_incidents_watch
    ON incidents (watch_id) WHERE watch_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_incidents_schedule
    ON incidents (schedule_id) WHERE schedule_id IS NOT NULL;

-- La file de sortie est la table la plus écrite du module, et
-- OutboxMessageRepository l'interroge par veille (findByWatchId,
-- existsByWatchIdAndChannel). La clé est ON DELETE SET NULL : la fermeture d'une
-- veille ne doit pas effacer la trace des messages partis.
CREATE INDEX IF NOT EXISTS idx_outbox_watch
    ON outbox_messages (watch_id) WHERE watch_id IS NOT NULL;

-- ------------------------------------------------------------ récompenses

-- Absente de la fiche, et pourtant sur un chemin de lecture : la clé primaire
-- est (badge_id, user_id), alors que BadgeAwardRepository ne lit jamais que
-- « les badges de cette personne » (findByUserId, findByUserIdWithBadge,
-- findByUserIdsWithBadge). Chaque affichage de profil parcourait la table.
CREATE INDEX IF NOT EXISTS idx_badge_awards_user
    ON badge_awards (user_id);

-- --------------------------------------------------------------- avis et
-- recommandations

-- Les critères d'un avis : cascade depuis reviews, et lecture systématique par
-- avis.
CREATE INDEX IF NOT EXISTS idx_review_criteria_review
    ON review_criteria (review_id);

-- Ces deux clés pointent vers conversations en ON DELETE RESTRICT. RESTRICT
-- n'est pas moins coûteux que CASCADE : PostgreSQL doit prouver qu'aucune ligne
-- ne référence la conversation supprimée, donc la parcourir en entier. C'est la
-- purge RGPD (P-BL-03) qui le paierait.
CREATE INDEX IF NOT EXISTS idx_reviews_interaction
    ON reviews (interaction_proof_id) WHERE interaction_proof_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_peer_rec_conversation
    ON peer_recommendations (conversation_id) WHERE conversation_id IS NOT NULL;

-- ------------------------------------------------------- créneaux, affiches
-- et catalogue

-- Qui a annulé la séance : ON DELETE SET NULL vers users, renseigné sur une
-- poignée de lignes. L'index partiel ne porte donc que les séances annulées.
CREATE INDEX IF NOT EXISTS idx_schedules_cancelled_by
    ON schedules (cancelled_by) WHERE cancelled_by IS NOT NULL;

-- affiches n'avait que (user_id, published_at) et l'unique (user_id,
-- occurrence) : la cascade depuis un créneau parcourait la table.
CREATE INDEX IF NOT EXISTS idx_affiches_schedule
    ON affiches (schedule_id);

-- Hiérarchie du catalogue d'activités, auto-référencée et ON DELETE CASCADE.
-- Aucune ligne ne l'utilise aujourd'hui : l'index partiel est donc vide, il ne
-- coûte rien, et il sera juste le jour où une sous-activité apparaîtra. C'est
-- moins cher qu'une exception à documenter puis à surveiller.
CREATE INDEX IF NOT EXISTS idx_activities_parent
    ON activities (parent_id) WHERE parent_id IS NOT NULL;
