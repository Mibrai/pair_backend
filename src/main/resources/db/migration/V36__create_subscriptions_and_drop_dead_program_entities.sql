-- Abonnements : auteur (User), activité personnelle (UserActivity), catégorie (Category)
CREATE TABLE IF NOT EXISTS subscriptions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type                     VARCHAR(20) NOT NULL, -- AUTHOR | USER_ACTIVITY | CATEGORY
    target_author_id         UUID REFERENCES users(id) ON DELETE CASCADE,
    target_user_activity_id  UUID REFERENCES user_activities(id) ON DELETE CASCADE,
    target_category_id       UUID REFERENCES categories(id) ON DELETE CASCADE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_subscription_target CHECK (
        (type = 'AUTHOR'        AND target_author_id IS NOT NULL AND target_user_activity_id IS NULL AND target_category_id IS NULL) OR
        (type = 'USER_ACTIVITY' AND target_user_activity_id IS NOT NULL AND target_author_id IS NULL AND target_category_id IS NULL) OR
        (type = 'CATEGORY'      AND target_category_id IS NOT NULL AND target_author_id IS NULL AND target_user_activity_id IS NULL)
    ),
    CONSTRAINT chk_subscription_not_self CHECK (subscriber_id <> target_author_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_author        ON subscriptions(subscriber_id, target_author_id)        WHERE type = 'AUTHOR';
CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_user_activity  ON subscriptions(subscriber_id, target_user_activity_id) WHERE type = 'USER_ACTIVITY';
CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_category       ON subscriptions(subscriber_id, target_category_id)      WHERE type = 'CATEGORY';

CREATE INDEX IF NOT EXISTS idx_sub_subscriber           ON subscriptions(subscriber_id);
CREATE INDEX IF NOT EXISTS idx_sub_target_author        ON subscriptions(target_author_id);
CREATE INDEX IF NOT EXISTS idx_sub_target_user_activity ON subscriptions(target_user_activity_id);
CREATE INDEX IF NOT EXISTS idx_sub_target_category      ON subscriptions(target_category_id);

-- Nettoyage : entités mortes (aucun repository, jamais utilisées en code)
DROP TABLE IF EXISTS program_progress;
DROP TABLE IF EXISTS program_enrollments;
