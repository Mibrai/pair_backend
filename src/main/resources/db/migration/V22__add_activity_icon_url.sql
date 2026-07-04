ALTER TABLE activities
    ADD COLUMN icon_url VARCHAR(500) DEFAULT '/api/media/files/activity_icon/default.png' NOT NULL;
