-- =================================================================
-- Add last_message_at column to conversations table
-- =================================================================

\echo '=== Adding last_message_at column to conversations ==='

ALTER TABLE conversations
ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_conv_last_message ON conversations(last_message_at);

\echo '=== Column added successfully! ==='
