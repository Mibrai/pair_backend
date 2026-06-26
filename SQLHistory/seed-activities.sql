-- Insert categories
INSERT INTO categories (id, name, icon, color_ramp) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Sport', '⚽', 'blue'),
    ('22222222-2222-2222-2222-222222222222', 'Musique', '🎵', 'purple'),
    ('33333333-3333-3333-3333-333333333333', 'Art', '🎨', 'pink'),
    ('44444444-4444-4444-4444-444444444444', 'Jeux', '🎮', 'green')
ON CONFLICT (id) DO NOTHING;

-- Insert activities for Sport category
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'Tennis', 'tennis', 'Sport de raquette'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'Football', 'football', 'Sport collectif avec un ballon'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'Running', 'running', 'Course à pied'),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111', 'Yoga', 'yoga', 'Pratique corporelle et spirituelle'),
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111', 'Basketball', 'basketball', 'Sport collectif avec un panier')
ON CONFLICT (slug) DO NOTHING;

-- Insert activities for Music category
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222', 'Guitare', 'guitare', 'Instrument à cordes'),
    ('gggggggg-gggg-gggg-gggg-gggggggggggg', '22222222-2222-2222-2222-222222222222', 'Piano', 'piano', 'Instrument à clavier'),
    ('hhhhhhhh-hhhh-hhhh-hhhh-hhhhhhhhhhhh', '22222222-2222-2222-2222-222222222222', 'Chant', 'chant', 'Art vocal')
ON CONFLICT (slug) DO NOTHING;

-- Insert activities for Art category
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('iiiiiiii-iiii-iiii-iiii-iiiiiiiiiiii', '33333333-3333-3333-3333-333333333333', 'Peinture', 'peinture', 'Art visuel'),
    ('jjjjjjjj-jjjj-jjjj-jjjj-jjjjjjjjjjjj', '33333333-3333-3333-3333-333333333333', 'Photographie', 'photographie', 'Art de capturer des images')
ON CONFLICT (slug) DO NOTHING;

-- Insert activities for Games category
INSERT INTO activities (id, category_id, name, slug, description) VALUES
    ('kkkkkkkk-kkkk-kkkk-kkkk-kkkkkkkkkkkk', '44444444-4444-4444-4444-444444444444', 'Échecs', 'echecs', 'Jeu de stratégie'),
    ('llllllll-llll-llll-llll-llllllllllll', '44444444-4444-4444-4444-444444444444', 'Poker', 'poker', 'Jeu de cartes')
ON CONFLICT (slug) DO NOTHING;
