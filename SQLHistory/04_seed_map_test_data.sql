-- =================================================================
-- Seed test data for map functionality
-- Creates multiple users with different locations in Paris area
-- =================================================================

\echo '=== Creating test users with locations ==='

-- User 1: Near Eiffel Tower
DO $$
DECLARE
    user_id UUID;
    ua_id UUID;
BEGIN
    -- Create user
    INSERT INTO users (email, password_hash, display_name, location, location_public, online_status_visible, blur_radius_m, last_active_at)
    VALUES (
        'tennis.player@paris.com',
        '$2a$12$abcdefghijklmnopqrstuvwxyz123456789',
        'Alex Tennis',
        ST_SetSRID(ST_MakePoint(2.2945, 48.8584), 4326),  -- Eiffel Tower area
        true,
        true,
        500,
        NOW() - INTERVAL '2 minutes'
    ) RETURNING id INTO user_id;

    -- Add Tennis activity
    INSERT INTO user_activities (user_id, activity_id, visible_on_map, level, format)
    VALUES (
        user_id,
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        true,
        'ADVANCED',
        'BOTH'
    );
END $$;

-- User 2: Near Louvre
DO $$
DECLARE
    user_id UUID;
BEGIN
    INSERT INTO users (email, password_hash, display_name, location, location_public, online_status_visible, blur_radius_m, last_active_at)
    VALUES (
        'football.fan@paris.com',
        '$2a$12$abcdefghijklmnopqrstuvwxyz123456789',
        'Marie Football',
        ST_SetSRID(ST_MakePoint(2.3364, 48.8606), 4326),  -- Louvre area
        true,
        true,
        300,
        NOW() - INTERVAL '1 minute'
    ) RETURNING id INTO user_id;

    INSERT INTO user_activities (user_id, activity_id, visible_on_map, level, format)
    VALUES (
        user_id,
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        true,
        'INTERMEDIATE',
        'GROUP'
    );
END $$;

-- User 3: Near Notre-Dame (also Tennis)
DO $$
DECLARE
    user_id UUID;
BEGIN
    INSERT INTO users (email, password_hash, display_name, location, location_public, online_status_visible, blur_radius_m, last_active_at)
    VALUES (
        'runner.paris@mail.com',
        '$2a$12$abcdefghijklmnopqrstuvwxyz123456789',
        'Pierre Runner',
        ST_SetSRID(ST_MakePoint(2.3488, 48.8530), 4326),  -- Notre-Dame area
        true,
        true,
        200,
        NOW() - INTERVAL '30 seconds'
    ) RETURNING id INTO user_id;

    -- Tennis + Running
    INSERT INTO user_activities (user_id, activity_id, visible_on_map, level, format)
    VALUES
        (user_id, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', true, 'INTERMEDIATE', 'SOLO'),
        (user_id, 'cccccccc-cccc-cccc-cccc-cccccccccccc', true, 'ADVANCED', 'SOLO');
END $$;

-- User 4: Montmartre (Yoga)
DO $$
DECLARE
    user_id UUID;
BEGIN
    INSERT INTO users (email, password_hash, display_name, location, location_public, online_status_visible, blur_radius_m, last_active_at)
    VALUES (
        'yoga.zen@paris.com',
        '$2a$12$abcdefghijklmnopqrstuvwxyz123456789',
        'Sophie Zen',
        ST_SetSRID(ST_MakePoint(2.3410, 48.8867), 4326),  -- Montmartre
        true,
        false,  -- Not showing online status
        400,
        NOW() - INTERVAL '10 minutes'
    ) RETURNING id INTO user_id;

    INSERT INTO user_activities (user_id, activity_id, visible_on_map, level, format)
    VALUES (
        user_id,
        'dddddddd-dddd-dddd-dddd-dddddddddddd',
        true,
        'BEGINNER',
        'GROUP'
    );
END $$;

-- User 5: Latin Quarter (Hidden - location_public = false)
DO $$
DECLARE
    user_id UUID;
BEGIN
    INSERT INTO users (email, password_hash, display_name, location, location_public, blur_radius_m, last_active_at)
    VALUES (
        'private.user@paris.com',
        '$2a$12$abcdefghijklmnopqrstuvwxyz123456789',
        'Hidden User',
        ST_SetSRID(ST_MakePoint(2.3450, 48.8500), 4326),
        false,  -- Not visible on map
        500,
        NOW()
    ) RETURNING id INTO user_id;

    INSERT INTO user_activities (user_id, activity_id, visible_on_map, level)
    VALUES (
        user_id,
        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        true,
        'INTERMEDIATE'
    );
END $$;

\echo ''
\echo '=== Map test data created! ==='
\echo 'Created 5 test users:'
\echo '  - 4 visible on map (location_public = true)'
\echo '  - 1 hidden (location_public = false)'
\echo '  - 3 online (last_active < 5min)'
\echo '  - Activities: Tennis, Football, Running, Yoga, Basketball'
\echo ''
\echo 'Test coordinates (Paris center):'
\echo '  lat: 48.8566, lng: 2.3522, radius: 5000m'
\echo ''
