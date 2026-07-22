-- Assigns a topically relevant photo to every activity and program.
-- Images come from LoremFlickr (https://loremflickr.com/{w}/{h}/{tag}?lock=N), which serves
-- real Flickr photos matched by keyword tag; `lock` pins a deterministic photo for a given
-- tag+lock pair so the URL is stable across requests instead of resolving to a random photo.

-- One fixed representative photo per activity.
UPDATE activities a
SET image_url = 'https://loremflickr.com/640/400/' || t.tag || '?lock=1'
FROM (VALUES
    ('bachata', 'bachata'),
    ('basketball', 'basketball'),
    ('benevolat-environnement', 'volunteering'),
    ('benevolat-social', 'charity'),
    ('bjj', 'jiujitsu'),
    ('bouldern', 'bouldering'),
    ('calisthenics', 'calisthenics'),
    ('camping', 'camping'),
    ('ceramique', 'pottery'),
    ('chant', 'singing'),
    ('course-a-pied', 'running'),
    ('crossfit', 'crossfit'),
    ('cuisine-du-monde', 'cooking'),
    ('danse', 'dance'),
    ('echecs', 'chess'),
    ('ecriture', 'writing'),
    ('escalade', 'climbing'),
    ('football', 'soccer'),
    ('freistilschwimmen', 'swimming'),
    ('fussball', 'soccer'),
    ('futsal', 'futsal'),
    ('guitare', 'guitar'),
    ('hatha-yoga', 'yoga'),
    ('jam-session', 'livemusic'),
    ('jardinage', 'gardening'),
    ('jeux-de-role', 'roleplayinggame'),
    ('jeux-de-societe', 'boardgame'),
    ('jeux-video', 'videogames'),
    ('kettlebell', 'kettlebell'),
    ('kickboxen', 'kickboxing'),
    ('krafttraining', 'weightlifting'),
    ('langues', 'language'),
    ('laufen', 'running'),
    ('lecture', 'reading'),
    ('meditation', 'meditation'),
    ('mentorat', 'mentoring'),
    ('mountainbike', 'mountainbiking'),
    ('musculation', 'bodybuilding'),
    ('natation', 'swimming'),
    ('nordic-walking', 'nordicwalking'),
    ('oenologie', 'wine'),
    ('open-water', 'openwater'),
    ('outdoor-klettern', 'rockclimbing'),
    ('patisserie', 'baking'),
    ('peche', 'fishing'),
    ('peinture', 'painting'),
    ('photographie', 'photography'),
    ('piano', 'piano'),
    ('programmation', 'coding'),
    ('randonnee', 'hiking'),
    ('rennrad', 'cycling'),
    ('robotique', 'robotics'),
    ('rudern', 'rowingboat'),
    ('salsa', 'salsadancing'),
    ('skifahren', 'skiing'),
    ('snowboard', 'snowboarding'),
    ('tango-argentino', 'tango'),
    ('tennis', 'tennis'),
    ('trail', 'trailrunning'),
    ('trail-running', 'trailrunning'),
    ('triathlon-training', 'triathlon'),
    ('velo', 'cycling'),
    ('vinyasa-yoga', 'yoga'),
    ('vorstieg', 'climbing'),
    ('vtt', 'mountainbiking'),
    ('wasserball', 'waterpolo'),
    ('yin-yoga', 'yoga'),
    ('yoga', 'yoga')
) AS t(slug, tag)
WHERE a.slug = t.slug
  AND a.image_url IS NULL;

-- Each program gets a photo matching its activity's tag (read back from the URL just set
-- above), varied via `lock` so programs sharing an activity don't all show the same picture.
WITH program_activity AS (
    SELECT
        p.id AS program_id,
        regexp_replace(a.image_url, '^https://loremflickr\.com/640/400/([^?]+)\?.*$', '\1') AS tag,
        row_number() OVER (PARTITION BY a.id ORDER BY p.id) AS rn
    FROM programs p
    JOIN user_activities ua ON ua.id = p.user_activity_id
    JOIN activities a ON a.id = ua.activity_id
    WHERE a.image_url IS NOT NULL
)
UPDATE programs p
SET image_url = 'https://loremflickr.com/800/450/' || pa.tag || '?lock=' || (pa.rn + 1)
FROM program_activity pa
WHERE p.id = pa.program_id
  AND p.image_url IS NULL;
