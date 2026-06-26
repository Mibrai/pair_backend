-- =================================================================
-- Create Chat tables: conversations, messages, conversation_members
-- Phase 1 Step 7: Chat en temps réel
-- =================================================================

\echo '=== Creating Conversations Table ==='
CREATE TABLE IF NOT EXISTS conversations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                    VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    activity_context_id     UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_conv_activity FOREIGN KEY (activity_context_id)
        REFERENCES activities(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_conversations_type ON conversations(type);
CREATE INDEX IF NOT EXISTS idx_conversations_activity ON conversations(activity_context_id);

\echo '=== Creating Conversation Members Table ==='
CREATE TABLE IF NOT EXISTS conversation_members (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL,
    user_id             UUID NOT NULL,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_at        TIMESTAMPTZ,
    CONSTRAINT fk_member_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_conversation_user UNIQUE (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_members_conversation ON conversation_members(conversation_id);
CREATE INDEX IF NOT EXISTS idx_members_user ON conversation_members(user_id);

\echo '=== Creating Messages Table ==='
CREATE TABLE IF NOT EXISTS messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL,
    sender_id           UUID NOT NULL,
    content             TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'SENT',
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at             TIMESTAMPTZ,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id);

\echo ''
\echo '=== Chat Tables Created Successfully! ==='
\echo 'Tables:'
\echo '  - conversations'
\echo '  - conversation_members'
\echo '  - messages'
\echo ''
