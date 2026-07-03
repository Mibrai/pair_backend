-- Add privacy settings columns to users table
ALTER TABLE users
    ADD COLUMN profile_visibility VARCHAR(20) DEFAULT 'PUBLIC',
    ADD COLUMN show_age BOOLEAN DEFAULT TRUE,
    ADD COLUMN show_last_active BOOLEAN DEFAULT TRUE,
    ADD COLUMN show_location BOOLEAN DEFAULT FALSE,
    ADD COLUMN allow_messages VARCHAR(20) DEFAULT 'EVERYONE',
    ADD COLUMN show_on_map BOOLEAN DEFAULT FALSE;

-- Add constraint for profile_visibility
ALTER TABLE users
    ADD CONSTRAINT chk_profile_visibility
    CHECK (profile_visibility IN ('PUBLIC', 'FRIENDS', 'PRIVATE'));

-- Add constraint for allow_messages
ALTER TABLE users
    ADD CONSTRAINT chk_allow_messages
    CHECK (allow_messages IN ('EVERYONE', 'FRIENDS', 'NONE'));

-- Create index for privacy queries
CREATE INDEX idx_users_profile_visibility ON users(profile_visibility);
