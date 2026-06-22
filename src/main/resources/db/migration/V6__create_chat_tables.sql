-- Create conversations table
CREATE TABLE conversations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                VARCHAR(10) NOT NULL DEFAULT 'DIRECT',
    activity_context_id UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_at     TIMESTAMPTZ,
    CONSTRAINT fk_conversations_activity FOREIGN KEY (activity_context_id) REFERENCES activities(id) ON DELETE SET NULL
);

CREATE INDEX idx_conv_last_message ON conversations(last_message_at);

-- Create conversation_members table
CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_at    TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_conv_members_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_conv_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Create messages table
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    sender_id       UUID NOT NULL,
    content         VARCHAR(4000) NOT NULL,
    status          VARCHAR(15) NOT NULL DEFAULT 'SENT',
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at         TIMESTAMPTZ,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_sender ON messages(sender_id);
CREATE INDEX idx_messages_sent_at ON messages(sent_at);
