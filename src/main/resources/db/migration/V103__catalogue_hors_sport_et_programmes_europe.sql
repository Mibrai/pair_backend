-- ============================================================
-- V103 — sortir le catalogue du tout-sport, et lui donner des programmes
--        vivants en France, en Belgique et en Allemagne.
--
-- POURQUOI CETTE MIGRATION EXISTE
--
-- meetDo n'est pas une application de sport, mais la base de test le laissait
-- croire : sur les 68 activités présentes, la grande majorité venait des seeds
-- sportifs (V27, V28), et les catégories non sportives héritées de
-- seed/data/activities.json restaient à moitié vides. Un jeune qui ouvrait la
-- carte voyait du footing et de l'escalade, et rien de ce qui l'occupe le reste
-- de la semaine.
--
-- Ce que la migration ajoute :
--   * 7 catégories manquantes (cinéma, mode, culture urbaine, médias,
--     entrepreneuriat, sciences, voyage) ;
--   * 50 activités hors sport, du beatmaking au tandem linguistique, chacune
--     avec une image qui la montre vraiment ;
--   * 1 programme par utilisateur actif — soit une activité nouvelle chacun —
--     réparti sur 25 villes de France, de Belgique et d'Allemagne ;
--   * 2 séances par programme, espacées d'une semaine ;
--   * chaque utilisateur inscrit sur une séance de chacun des autres.
--
-- LES HORAIRES NE SE CHEVAUCHENT JAMAIS
--
-- La dernière ligne est celle qui contraint tout le reste : si deux séances de
-- deux organisateurs différents tombaient en même temps, l'utilisateur qui
-- s'inscrit aux deux serait à deux endroits à la fois, et le jeu de test
-- décrirait une situation impossible.
--
-- Plutôt que de choisir les inscriptions en évitant les collisions au cas par
-- cas — fragile, et faux dès qu'on ajoute une séance — les 100 séances sont
-- posées de façon à ne JAMAIS se recouvrir, quelle que soit la répartition :
--
--   séance 1 du programme i : jour  1 + i, à 10 h, 14 h ou 18 h
--   séance 2 du programme i : jour  8 + i, à 12 h, 16 h ou 20 h
--
-- Les séances 1 sont sur 50 jours distincts, les séances 2 aussi. Une séance 1
-- et une séance 2 ne partagent un jour que si i = j + 7, et l'arithmétique des
-- heures (10 + 4·((i-1) mod 3) contre 12 + 4·(j mod 3)) place alors la seconde
-- exactement deux heures après la première. Les séances durant 90 minutes, il
-- reste une demi-heure entre les deux. Aucune collision n'est donc possible,
-- et la vérification en fin de fichier le prouve plutôt que de l'affirmer.
--
-- Le passage à l'heure d'hiver ne casse pas ce raisonnement : les deux séances
-- d'un même jour sont calculées en heure locale de Berlin et basculent
-- ensemble, et deux séances de jours différents restent séparées d'au moins
-- 23 heures.
-- ============================================================

-- ============================================================
-- 1. LES CATÉGORIES QUI MANQUAIENT
--
-- ON CONFLICT sur le nom : ReferenceDataSeeder crée les catégories de
-- seed/data/categories.json à chaque démarrage en testant existsByName, et
-- cette migration doit pouvoir tourner sur une base qui les a déjà.
-- ============================================================
INSERT INTO categories (name, icon, color_ramp) VALUES
  ('Cinéma & Audiovisuel', 'clapperboard', 'indigo-violet'),
  ('Mode & Style', 'shirt', 'rose-pink'),
  ('Culture urbaine', 'spray-can', 'lime-green'),
  ('Podcast & Médias', 'mic', 'amber-orange'),
  ('Entrepreneuriat & Finances', 'trending-up', 'emerald-teal'),
  ('Sciences & Découverte', 'telescope', 'sky-blue'),
  ('Voyage & Culture', 'globe', 'cyan-blue')
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- 2. LES ACTIVITÉS
--
-- Les images sont des photographies réelles, vérifiées une à une : chaque URL
-- répond 200 et montre bien l'activité. Unsplash quand une photo juste
-- existait, loremflickr — la convention déjà en place sur les activités les
-- plus récentes — pour les quatre sujets où elle manquait.
--
-- La jointure passe par le NOM de la catégorie et non par un identifiant en
-- dur : les catégories peuvent avoir été créées par ReferenceDataSeeder, avec
-- des UUID que cette migration ne connaît pas.
-- ============================================================
INSERT INTO activities (category_id, name, slug, description, icon, image_url)
SELECT c.id, v.nom, v.slug, v.description, v.icone, v.image
FROM (VALUES
    ('dessin-manga', 'Dessin manga & BD', 'Arts & Création',
     'draw', 'Planches, character design et encrage, du croquis au chapitre fini.',
     'https://images.unsplash.com/photo-1613376023733-0a73315d9b06?w=800'),
    ('illustration-numerique', 'Illustration numérique', 'Arts & Création',
     'brush', 'Illustration sur tablette : composition, couleur et rendu.',
     'https://images.unsplash.com/photo-1626785774573-4b799315345d?w=800'),
    ('theatre-impro', 'Théâtre d''improvisation', 'Arts & Création',
     'theater_comedy', 'Matchs et exercices d''impro, sans texte et sans filet.',
     'https://images.unsplash.com/photo-1503095396549-807759245b35?w=800'),
    ('calligraphie', 'Calligraphie & lettering', 'Arts & Création',
     'edit_note', 'Tracé à la plume et au brush pen, des pleins et des déliés.',
     'https://images.unsplash.com/photo-1455390582262-044cdead277a?w=800'),
    ('beatmaking', 'Beatmaking & MAO', 'Musique',
     'graphic_eq', 'Fabrication d''instrus au sampleur et en home studio.',
     'https://images.unsplash.com/photo-1598488035139-bdbb2231ce04?w=800'),
    ('dj-mix', 'DJ & mix', 'Musique',
     'album', 'Mix aux platines, calage tempo et construction de set.',
     'https://images.unsplash.com/photo-1544785349-c4a5301826fd?w=800'),
    ('batterie', 'Batterie', 'Musique',
     'music_note', 'Groove, indépendance des membres et jeu en groupe.',
     'https://images.unsplash.com/photo-1519892300165-cb5542fb47c7?w=800'),
    ('esport', 'Esport & tournois', 'Jeux',
     'sports_esports', 'Entraînement en équipe et tournois amateurs, tous jeux.',
     'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=800'),
    ('escape-game', 'Escape game', 'Jeux',
     'lock_open', 'Énigmes en équipe contre la montre, en salle ou en extérieur.',
     'https://images.unsplash.com/photo-1614064641938-3bbee52942c7?w=800'),
    ('street-food', 'Street food', 'Cuisine',
     'lunch_dining', 'Cuisine de rue du monde entier, à préparer et à goûter ensemble.',
     'https://images.unsplash.com/photo-1555939594-58d7cb561ad1?w=800'),
    ('cuisine-vegetale', 'Cuisine végétale', 'Cuisine',
     'eco', 'Cuisine sans produits animaux, du quotidien au festif.',
     'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=800'),
    ('barista', 'Barista & café de spécialité', 'Cuisine',
     'local_cafe', 'Extraction, latte art et dégustation de cafés de spécialité.',
     'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=800'),
    ('mixologie', 'Mixologie & cocktails', 'Cuisine',
     'local_bar', 'Cocktails avec ou sans alcool, équilibre et dressage.',
     'https://images.unsplash.com/photo-1514362545857-3bc16c4c7d1b?w=800'),
    ('club-debat', 'Club de débat', 'Apprentissage',
     'forum', 'Débats à format court : argumenter, écouter, convaincre.',
     'https://images.unsplash.com/photo-1524178232363-1fb2b075b655?w=800'),
    ('prise-de-parole', 'Prise de parole en public', 'Apprentissage',
     'record_voice_over', 'Poser sa voix, structurer un propos et tenir une salle.',
     'https://images.unsplash.com/photo-1475721027785-f74eccf877e2?w=800'),
    ('cafe-philo', 'Café philo', 'Apprentissage',
     'psychology', 'Une question, deux heures, un café : la philo sans jargon.',
     'https://images.unsplash.com/photo-1543269865-cbf427effbad?w=800'),
    ('geocaching', 'Geocaching', 'Plein air',
     'explore', 'Chasse au trésor au GPS, en ville comme en forêt.',
     'https://images.unsplash.com/photo-1551632811-561732d1e306?w=800'),
    ('observation-oiseaux', 'Observation des oiseaux', 'Plein air',
     'visibility', 'Sorties jumelles au marais, en forêt et sur les toits.',
     'https://images.unsplash.com/photo-1444464666168-49d633b86797?w=800'),
    ('cueillette-sauvage', 'Cueillette sauvage', 'Plein air',
     'local_florist', 'Reconnaître et cueillir plantes et champignons comestibles.',
     'https://loremflickr.com/640/400/blackberry,picking?lock=2'),
    ('stand-up-paddle', 'Stand-up paddle', 'Stand-Up-Paddling',
     'surfing', 'Balade et gainage sur la planche, lac, canal ou rivière.',
     'https://images.unsplash.com/photo-1517176118179-65244903d13c?w=800'),
    ('cybersecurite-ctf', 'Cybersécurité & CTF', 'Tech & Numérique',
     'security', 'Capture the flag, rétro-ingénierie et défense en équipe.',
     'https://images.unsplash.com/photo-1550751827-4bd374c3f58b?w=800'),
    ('creation-jeu-video', 'Création de jeux vidéo', 'Tech & Numérique',
     'videogame_asset', 'Game jams et prototypes : game design, code et pixel art.',
     'https://images.unsplash.com/photo-1552820728-8b83bb6b773f?w=800'),
    ('impression-3d', 'Impression 3D & making', 'Tech & Numérique',
     'precision_manufacturing', 'Modélisation, impression et bricolage en fablab.',
     'https://images.unsplash.com/photo-1611117775350-ac3950990985?w=800'),
    ('atelier-ia', 'Atelier IA & prompting', 'Tech & Numérique',
     'smart_toy', 'Prendre en main les modèles génératifs et leurs limites.',
     'https://images.unsplash.com/photo-1677442136019-21780ecad995?w=800'),
    ('plogging', 'Plogging & ramassage de déchets', 'Bénévolat',
     'recycling', 'Courir ou marcher en ramassant : la ville repart propre.',
     'https://images.unsplash.com/photo-1532996122724-e3c354a0b15b?w=800'),
    ('refuge-animaux', 'Bénévolat en refuge animalier', 'Bénévolat',
     'pets', 'Sorties, soins et socialisation des animaux du refuge.',
     'https://images.unsplash.com/photo-1450778869180-41d0601e046e?w=800'),
    ('sophrologie', 'Sophrologie', 'Bien-être',
     'spa', 'Relaxation dynamique, respiration et visualisation.',
     'https://images.unsplash.com/photo-1545205597-3d9d02c29597?w=800'),
    ('bain-froid', 'Respiration & bain froid', 'Bien-être',
     'ac_unit', 'Exercices respiratoires puis immersion froide encadrée.',
     'https://images.unsplash.com/photo-1541411438265-4cb4687110f2?w=800'),
    ('journaling', 'Journaling', 'Bien-être',
     'menu_book', 'Écriture quotidienne guidée : clarté, gratitude, recul.',
     'https://images.unsplash.com/photo-1517842645767-c639042777db?w=800'),
    ('cine-club', 'Ciné-club', 'Cinéma & Audiovisuel',
     'movie', 'Projection suivie d''une discussion, un film par séance.',
     'https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?w=800'),
    ('montage-video', 'Montage vidéo', 'Cinéma & Audiovisuel',
     'video_settings', 'Dérushage, montage et étalonnage de bout en bout.',
     'https://images.unsplash.com/photo-1574717024653-61fd2cf4d44d?w=800'),
    ('court-metrage', 'Tournage de court-métrage', 'Cinéma & Audiovisuel',
     'videocam', 'Écrire, tourner et monter un court à plusieurs.',
     'https://images.unsplash.com/photo-1485846234645-a62644f84728?w=800'),
    ('couture', 'Couture & retouches', 'Mode & Style',
     'content_cut', 'Patron, machine et finitions : coudre et réparer ses vêtements.',
     'https://loremflickr.com/640/400/sewing,machine?lock=3'),
    ('upcycling', 'Upcycling & friperie', 'Mode & Style',
     'checkroom', 'Chiner, transformer et rendre portable ce qui allait au rebut.',
     'https://images.unsplash.com/photo-1489987707025-afc232f7ea0f?w=800'),
    ('custom-sneakers', 'Custom sneakers', 'Mode & Style',
     'styler', 'Peinture cuir, pochoirs et lacets : la paire devient unique.',
     'https://images.unsplash.com/photo-1552346154-21d32810aba3?w=800'),
    ('skateboard', 'Skateboard', 'Culture urbaine',
     'skateboarding', 'Street et park, du premier ollie aux lignes complètes.',
     'https://images.unsplash.com/photo-1547447134-cd3f5c716030?w=800'),
    ('breakdance', 'Breakdance', 'Culture urbaine',
     'sports_gymnastics', 'Toprock, footwork et power moves, cyphers et battles.',
     'https://images.unsplash.com/photo-1535525153412-5a42439a210d?w=800'),
    ('graffiti', 'Graffiti & fresque murale', 'Culture urbaine',
     'format_paint', 'Lettrage et fresques sur murs libres, bombe et rouleau.',
     'https://loremflickr.com/640/400/graffiti,streetart?lock=2'),
    ('rap-freestyle', 'Rap & freestyle', 'Culture urbaine',
     'mic_external_on', 'Écriture, flow et freestyle en cercle, sur instrus ouvertes.',
     'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=800'),
    ('parkour', 'Parkour', 'Culture urbaine',
     'directions_run', 'Franchissement, réception et lecture du mobilier urbain.',
     'https://loremflickr.com/640/400/parkour?lock=4'),
    ('podcast', 'Podcast', 'Podcast & Médias',
     'podcasts', 'Concevoir, enregistrer et monter un épisode à deux voix.',
     'https://images.unsplash.com/photo-1478737270239-2f02b77fc618?w=800'),
    ('streaming-live', 'Streaming & création de contenu', 'Podcast & Médias',
     'live_tv', 'Direct, montage court et régie : tenir une chaîne.',
     'https://images.unsplash.com/photo-1598550476439-6847785fcea6?w=800'),
    ('side-project', 'Side project & startup weekend', 'Entrepreneuriat & Finances',
     'rocket_launch', 'Passer d''une idée à un prototype en un week-end.',
     'https://images.unsplash.com/photo-1519389950473-47ba0277781c?w=800'),
    ('finances-perso', 'Finances personnelles', 'Entrepreneuriat & Finances',
     'savings', 'Budget, épargne et investissement, sans conseil personnalisé.',
     'https://images.unsplash.com/photo-1554224155-6726b3ff858f?w=800'),
    ('pitch-networking', 'Pitch & networking', 'Entrepreneuriat & Finances',
     'handshake', 'S''entraîner au pitch court et rencontrer d''autres porteurs de projet.',
     'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=800'),
    ('astronomie', 'Astronomie', 'Sciences & Découverte',
     'nights_stay', 'Observation du ciel à l''œil nu et au télescope.',
     'https://images.unsplash.com/photo-1419242902214-272b3f66ee7a?w=800'),
    ('sciences-participatives', 'Sciences participatives', 'Sciences & Découverte',
     'science', 'Relevés de terrain qui alimentent de vrais programmes de recherche.',
     'https://images.unsplash.com/photo-1532094349884-543bc11b234d?w=800'),
    ('tandem-linguistique', 'Tandem linguistique', 'Voyage & Culture',
     'translate', 'Une heure dans sa langue, une heure dans celle de l''autre.',
     'https://images.unsplash.com/photo-1523240795612-9a054b0db644?w=800'),
    ('musee-expo', 'Sorties musées & expos', 'Voyage & Culture',
     'museum', 'Visites d''expositions suivies d''un débrief au café.',
     'https://images.unsplash.com/photo-1554907984-15263bfd63bd?w=800'),
    ('urbex-patrimoine', 'Exploration urbaine & patrimoine', 'Voyage & Culture',
     'apartment', 'Marches d''architecture et lieux oubliés, en sécurité et en règle.',
     'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800')
  ) AS v(slug, nom, categorie, icone, description, image)
JOIN categories c ON c.name = v.categorie
ON CONFLICT (slug) DO NOTHING;

-- ============================================================
-- 3. LE CANEVAS DES PROGRAMMES
--
-- Une table temporaire plutôt qu'une liste VALUES répétée : les mêmes 50 lignes
-- servent aux programmes, aux séances et aux inscriptions, et les recopier
-- trois fois ouvrirait trois occasions de les faire diverger.
--
-- « langue » vaut fr ou de. Les programmes français sont en français ; ceux
-- d'Allemagne et de Belgique alternent français et allemand, comme demandé.
-- ============================================================
CREATE TEMP TABLE seed_v103_canevas (
    idx            INTEGER PRIMARY KEY,
    activite_slug  TEXT NOT NULL,
    langue         TEXT NOT NULL,
    titre          TEXT NOT NULL,
    description    TEXT NOT NULL,
    objectifs      TEXT NOT NULL,
    prerequis      TEXT NOT NULL,
    pays           TEXT NOT NULL,
    ville          TEXT NOT NULL,
    lieu           TEXT NOT NULL,
    adresse        TEXT NOT NULL,
    lng            DOUBLE PRECISION NOT NULL,
    lat            DOUBLE PRECISION NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_v103_canevas VALUES
  (1, 'dessin-manga', 'fr',
   'Atelier manga du samedi',
   'On dessine ensemble : character design, storyboard et encrage, avec un exercice par séance. Chacun repart avec une planche avancée et les retours du groupe.',
   'Terminer une planche complète et tenir un rythme de dessin régulier.',
   'Aucun niveau requis. Apporte tes crayons et un carnet.',
   'FR', 'Paris', 'Le Carreau du Temple',
   '4 Rue Eugène Spuller, 75003 Paris', 2.3614, 48.8656),
  (2, 'illustration-numerique', 'de',
   'Digitale Illustration am Holzmarkt',
   'Wir zeichnen auf dem Tablet: Komposition, Farbe und Rendering, jede Sitzung mit einer konkreten Übung. Am Ende gibt es Feedback in der Runde.',
   'Eine eigene Illustration von der Skizze bis zum fertigen Rendering bringen.',
   'Tablet oder Laptop mitbringen. Vorkenntnisse sind nicht nötig.',
   'DE', 'Berlin', 'Holzmarkt 25',
   'Holzmarktstraße 25, 10243 Berlin', 13.429, 52.511),
  (3, 'theatre-impro', 'de',
   'Impro-Theater in Schaerbeek',
   'Kurze Szenen ohne Text und ohne Netz: zuhören, annehmen, weiterspielen. Wir wärmen gemeinsam auf und spielen danach in wechselnden Paaren.',
   'Ohne Vorbereitung eine Szene tragen können.',
   'Keine Bühnenerfahrung nötig. Bequeme Kleidung mitbringen.',
   'BE', 'Bruxelles', 'Les Halles de Schaerbeek',
   'Rue Royale Sainte-Marie 22, 1030 Bruxelles', 4.369, 50.863),
  (4, 'calligraphie', 'fr',
   'Calligraphie et lettering aux Subs',
   'Pleins et déliés à la plume, puis au brush pen, sur des exercices courts. On termine chaque séance par une composition à emporter.',
   'Maîtriser une main d''écriture et composer une affiche lisible.',
   'Débutants bienvenus. Le matériel de base est prêté sur place.',
   'FR', 'Lyon', 'Les Subsistances',
   '8 bis Quai Saint-Vincent, 69001 Lyon', 4.818, 45.7716),
  (5, 'beatmaking', 'de',
   'Beatmaking-Session im Oberhafen',
   'Vom Sample zum fertigen Beat: Drums, Bassline und Arrangement in einer Sitzung. Wir hören die Ergebnisse gemeinsam ab und tauschen Projekte.',
   'Einen Beat von der Idee bis zum Export fertigstellen.',
   'Laptop und Kopfhörer mitbringen. Jede DAW ist willkommen.',
   'DE', 'Hamburg', 'Oberhafen Quartier',
   'Stockmeyerstraße 43, 20457 Hamburg', 10.013, 53.542),
  (6, 'dj-mix', 'de',
   'DJ-Workshop in De Studio',
   'An den Decks: Beatmatching, Übergänge und der Aufbau eines Sets. Jede Sitzung endet mit einem kurzen Set vor der Gruppe.',
   'Ein sauberes Set von zwanzig Minuten spielen können.',
   'Keine Vorkenntnisse. Kopfhörer mitbringen, die Geräte stehen bereit.',
   'BE', 'Antwerpen', 'De Studio',
   'Maarschalk Gerardstraat 4, 2000 Antwerpen', 4.411, 51.218),
  (7, 'batterie', 'fr',
   'Batterie collective à la Friche',
   'Groove, indépendance des membres et jeu à plusieurs, sur des morceaux choisis ensemble. Une heure de technique, une demi-heure de jeu en groupe.',
   'Tenir un groove stable et jouer un morceau du début à la fin.',
   'Baguettes personnelles conseillées. Niveau débutant accepté.',
   'FR', 'Marseille', 'Friche la Belle de Mai',
   '41 Rue Jobin, 13003 Marseille', 5.3906, 43.308),
  (8, 'esport', 'de',
   'Esport-Training im Werksviertel',
   'Teamtraining und interne Turniere: Kommunikation, Rollen und Review der eigenen Runden. Aufzeichnungen schauen wir gemeinsam an.',
   'Als Team abgestimmt spielen und die eigenen Fehler erkennen.',
   'Eigene Peripherie mitbringen. Der Rang spielt keine Rolle.',
   'DE', 'München', 'Werksviertel Mitte',
   'Atelierstraße 1, 81671 München', 11.603, 48.129),
  (9, 'escape-game', 'de',
   'Rätselabend in De Krook',
   'Rätsel im Team gegen die Uhr, jede Sitzung mit einem neuen Szenario. Danach besprechen wir, woran die Gruppe hängen geblieben ist.',
   'Im Team methodisch suchen statt nebeneinander zu raten.',
   'Keine Vorbereitung nötig. Kommt in Gruppen von vier bis sechs.',
   'BE', 'Gent', 'De Krook',
   'Miriam Makebaplein 1, 9000 Gent', 3.728, 51.05),
  (10, 'street-food', 'fr',
   'Street food du monde à Darwin',
   'On cuisine un plat de rue par séance, d''un continent à l''autre, puis on partage la table. Les recettes repartent avec le groupe.',
   'Savoir refaire seul trois plats de rue de bout en bout.',
   'Aucun prérequis. Contenants réutilisables bienvenus.',
   'FR', 'Bordeaux', 'Darwin Écosystème',
   '87 Quai des Queyries, 33100 Bordeaux', -0.5546, 44.8452),
  (11, 'cuisine-vegetale', 'de',
   'Pflanzliche Küche bei Odonien',
   'Alltagstaugliche Gerichte ganz ohne tierische Produkte, von der Vorbereitung bis zum gemeinsamen Essen. Jede Sitzung stellt ein Grundnahrungsmittel in den Mittelpunkt.',
   'Eine Woche pflanzlich kochen, ohne lange nachzudenken.',
   'Keine Erfahrung nötig. Schürze und Behälter mitbringen.',
   'DE', 'Köln', 'Odonien',
   'Hornstraße 85, 50823 Köln', 6.941, 50.956),
  (12, 'barista', 'de',
   'Barista-Kurs in der Cité Miroir',
   'Extraktion, Milchschaum und Latte Art an der Maschine, mit Verkostung im Anschluss. Wir vergleichen Röstungen und Mahlgrade.',
   'Einen Espresso sauber ziehen und Milch richtig aufschäumen.',
   'Keine Vorkenntnisse. Bohnen und Geräte sind vorhanden.',
   'BE', 'Liège', 'La Cité Miroir',
   'Place Xavier Neujean 22, 4000 Liège', 5.572, 50.641),
  (13, 'mixologie', 'fr',
   'Mixologie du jeudi soir',
   'Cocktails avec et sans alcool : équilibre sucre-acide, dilution et dressage. Chaque séance se termine par une dégustation commentée.',
   'Composer trois cocktails équilibrés sans recette sous les yeux.',
   'Réservé aux majeurs. Aucun matériel à apporter.',
   'FR', 'Lille', 'Gare Saint Sauveur',
   '17 Boulevard Jean-Baptiste Lebas, 59800 Lille', 3.07, 50.63),
  (14, 'club-debat', 'de',
   'Debattierclub in der Naxoshalle',
   'Kurze Formate mit zwei Seiten und einer Jury, das Thema wird vor Ort gezogen. Nach jeder Runde gibt es strukturiertes Feedback.',
   'Eine Position in fünf Minuten klar und belegt vertreten.',
   'Keine Erfahrung nötig, nur Lust zu widersprechen.',
   'DE', 'Frankfurt am Main', 'Naxoshalle',
   'Waldschmidtstraße 19, 60316 Frankfurt am Main', 8.701, 50.12),
  (15, 'prise-de-parole', 'de',
   'Frei sprechen im OPEK',
   'Stimme, Haltung und Aufbau einer Rede, in kurzen Übungen vor der Gruppe. Jede Einheit wird gefilmt und gemeinsam ausgewertet.',
   'Zehn Minuten frei vor Publikum sprechen.',
   'Keine Vorkenntnisse. Wer mag, bringt ein eigenes Thema mit.',
   'BE', 'Leuven', 'OPEK',
   'Vaartkom 4, 3000 Leuven', 4.7, 50.885),
  (16, 'cafe-philo', 'fr',
   'Café philo du Quai des Savoirs',
   'Une question par séance, deux heures et un café : on argumente sans jargon. Un participant introduit, le groupe déroule.',
   'Construire un raisonnement tenable et écouter celui d''en face.',
   'Aucune lecture préalable exigée.',
   'FR', 'Toulouse', 'Quai des Savoirs',
   '39 Allées Jules Guesde, 31000 Toulouse', 1.45, 43.596),
  (17, 'geocaching', 'de',
   'Geocaching rund um den Schlossgarten',
   'Schatzsuche per GPS quer durch die Stadt, jede Sitzung mit einer neuen Route. Wir laufen in kleinen Gruppen und treffen uns am Ende wieder.',
   'Eigene Caches finden und selbst eine Route legen.',
   'Smartphone mit GPS und feste Schuhe.',
   'DE', 'Stuttgart', 'Schlossgarten',
   'Schillerstraße, 70173 Stuttgart', 9.184, 48.786),
  (18, 'observation-oiseaux', 'de',
   'Vogelbeobachtung am Bois du Cazier',
   'Frühe Runden mit dem Fernglas, Bestimmung nach Gesang und Silhouette. Die Beobachtungen notieren wir gemeinsam.',
   'Zwanzig heimische Arten sicher erkennen.',
   'Ferngläser werden gestellt. Leise Kleidung hilft.',
   'BE', 'Charleroi', 'Le Bois du Cazier',
   'Rue du Cazier 80, 6001 Charleroi', 4.437, 50.391),
  (19, 'cueillette-sauvage', 'fr',
   'Cueillette sauvage sur l''Île de Nantes',
   'Reconnaître, cueillir et cuisiner ce qui pousse en ville et en lisière. On termine par une dégustation de ce qui a été ramassé.',
   'Identifier sans hésiter dix plantes comestibles et leurs sosies toxiques.',
   'Panier et couteau conseillés. Rien ne se cueille sans validation.',
   'FR', 'Nantes', 'Île de Nantes',
   'Quai des Antilles, 44200 Nantes', -1.561, 47.205),
  (20, 'stand-up-paddle', 'de',
   'Stand-up-Paddling am Rheinpark',
   'Balance, Paddeltechnik und ruhige Touren auf dem Wasser. Wir starten mit Trockenübungen am Ufer.',
   'Eine Stunde stehend paddeln, ohne zu wackeln.',
   'Schwimmen können ist Pflicht. Board und Weste werden gestellt.',
   'DE', 'Düsseldorf', 'Rheinpark Golzheim',
   'Rotterdamer Straße, 40474 Düsseldorf', 6.758, 51.244),
  (21, 'cybersecurite-ctf', 'fr',
   'CTF du mardi à La Coop',
   'Capture the flag en équipe : web, forensic et rétro-ingénierie, sur des épreuves montées pour la séance. Correction commentée à la fin.',
   'Résoudre seul une épreuve web de niveau intermédiaire.',
   'Portable avec une machine virtuelle Linux. Bases de la ligne de commande.',
   'FR', 'Strasbourg', 'La Coop',
   '5 Rue de Rathsamhausen, 67100 Strasbourg', 7.759, 48.572),
  (22, 'creation-jeu-video', 'de',
   'Game Jam in der Baumwollspinnerei',
   'Vom Prototyp zum spielbaren Build: Game Design, Code und Pixel Art in kleinen Teams. Am Ende jeder Sitzung wird angespielt.',
   'Einen spielbaren Prototypen in zwei Sitzungen abliefern.',
   'Laptop mitbringen. Die Engine ist frei wählbar.',
   'DE', 'Leipzig', 'Baumwollspinnerei',
   'Spinnereistraße 7, 04179 Leipzig', 12.32, 51.335),
  (23, 'impression-3d', 'fr',
   'Impression 3D à la Halle Tropisme',
   'Modélisation, réglages d''impression et post-traitement, sur un objet choisi par chacun. Les impressions ratées sont analysées en groupe.',
   'Modéliser et imprimer une pièce fonctionnelle sans assistance.',
   'Aucun prérequis. Le filament est fourni.',
   'FR', 'Montpellier', 'Halle Tropisme',
   '121 Rue Fontcouverte, 34070 Montpellier', 3.86, 43.606),
  (24, 'atelier-ia', 'de',
   'KI-Werkstatt im Kraftwerk Mitte',
   'Generative Modelle in der Praxis: Prompting, Grenzen und was man ihnen nicht glauben sollte. Pro Sitzung bauen wir ein kleines Werkzeug.',
   'Ein eigenes Werkzeug bauen und seine Fehler benennen können.',
   'Laptop mitbringen. Programmieren hilft, ist aber kein Muss.',
   'DE', 'Dresden', 'Kraftwerk Mitte',
   'Wettiner Platz 7, 01067 Dresden', 13.728, 51.053),
  (25, 'plogging', 'de',
   'Plogging auf der Wöhrder Wiese',
   'Laufen und dabei sammeln: eine Runde, zwei Säcke, ein sauberer Park. Am Ende wiegen wir, was zusammengekommen ist.',
   'Jede Woche eine Runde laufen und dabei etwas zurückgeben.',
   'Handschuhe und Säcke werden gestellt. Jedes Tempo ist recht.',
   'DE', 'Nürnberg', 'Wöhrder Wiese',
   'Wöhrder Wiese, 90489 Nürnberg', 11.093, 49.456),
  (26, 'refuge-animaux', 'fr',
   'Bénévolat au refuge, dimanche matin',
   'Rendez-vous au parc, puis départ groupé pour le refuge : sorties, soins et socialisation des animaux. Chaque séance commence par un point sur les arrivants.',
   'Devenir bénévole autonome sur les sorties et les soins simples.',
   'Majeur et à l''aise avec les chiens. Chaussures fermées obligatoires.',
   'FR', 'Paris', 'Parc de la Villette',
   '211 Avenue Jean Jaurès, 75019 Paris', 2.393, 48.8938),
  (27, 'sophrologie', 'fr',
   'Sophrologie au Tempelhofer Feld',
   'Relaxation dynamique, respiration et visualisation, en plein air quand la météo le permet. Chaque séance se termine par un temps de retour au calme.',
   'Disposer d''une routine de dix minutes utilisable n''importe où.',
   'Aucun prérequis. Prévois un tapis et une couche chaude.',
   'DE', 'Berlin', 'Tempelhofer Feld',
   'Tempelhofer Damm, 12101 Berlin', 13.402, 52.475),
  (28, 'bain-froid', 'fr',
   'Respiration et bain froid au Cinquantenaire',
   'Exercices respiratoires puis immersion froide encadrée, progressive et jamais forcée. On sort ensemble et on se réchauffe en marchant.',
   'Tenir trois minutes en eau froide en gardant une respiration calme.',
   'Certificat médical demandé. Serviette et bonnet indispensables.',
   'BE', 'Bruxelles', 'Parc du Cinquantenaire',
   'Parc du Cinquantenaire, 1000 Bruxelles', 4.393, 50.841),
  (29, 'journaling', 'fr',
   'Journaling du matin au Parc de la Tête d''Or',
   'Vingt minutes d''écriture guidée, puis un partage volontaire de ce qu''on veut bien lire. Les consignes changent à chaque séance.',
   'Écrire chaque jour sans se relire ni se juger.',
   'Un carnet et un stylo. Rien d''autre.',
   'FR', 'Lyon', 'Parc de la Tête d''Or',
   'Place Général Leclerc, 69006 Lyon', 4.8547, 45.7772),
  (30, 'cine-club', 'fr',
   'Ciné-club francophone de Hambourg',
   'Un film par séance, projeté puis discuté à chaud pendant une heure. La programmation est votée par le groupe.',
   'Regarder autrement et savoir dire pourquoi un film tient.',
   'Aucun prérequis. Les films sont sous-titrés.',
   'DE', 'Hamburg', 'Planten un Blomen',
   'Marseiller Straße, 20355 Hamburg', 9.98, 53.559),
  (31, 'montage-video', 'fr',
   'Montage vidéo à Het Bos',
   'Dérushage, rythme et étalonnage, sur des rushes fournis ou les tiens. On compare deux montages du même matériau.',
   'Monter un sujet de trois minutes qui tienne debout.',
   'Portable avec un logiciel de montage déjà installé.',
   'BE', 'Antwerpen', 'Het Bos',
   'Ankerrui 5-7, 2000 Antwerpen', 4.406, 51.23),
  (32, 'court-metrage', 'fr',
   'Tournage de court-métrage au Prado',
   'Écrire, tourner, monter : un court par cycle, en équipe réduite. Chaque séance est un tournage réel avec un plan de travail.',
   'Sortir un court de six minutes prêt à être projeté.',
   'Aucun matériel exigé. Être disponible sur les deux séances.',
   'FR', 'Marseille', 'Plage du Prado',
   'Avenue Pierre Mendès France, 13008 Marseille', 5.376, 43.262),
  (33, 'couture', 'fr',
   'Couture et retouches à Munich',
   'Patron, machine et finitions : on répare, on ajuste, on transforme. Chacun vient avec un vêtement à sauver.',
   'Poser une fermeture éclair et ajuster un vêtement sans aide.',
   'Apporte un vêtement à retoucher. Les machines sont sur place.',
   'DE', 'München', 'Werksviertel Mitte',
   'Atelierstraße 1, 81671 München', 11.603, 48.129),
  (34, 'upcycling', 'fr',
   'Upcycling et friperie à Gand',
   'Chiner puis transformer : teinture, découpe et assemblage de pièces de seconde main. On termine par un essayage collectif.',
   'Transformer trois pièces chinées en vêtements portables.',
   'Apporte deux vêtements dont tu ne fais plus rien.',
   'BE', 'Gent', 'De Krook',
   'Miriam Makebaplein 1, 9000 Gent', 3.728, 51.05),
  (35, 'custom-sneakers', 'fr',
   'Custom sneakers à Darwin',
   'Peinture cuir, pochoirs et lacets : une paire par cycle, du dégraissage au vernis. Les couleurs se choisissent en début de séance.',
   'Customiser une paire propre et durable.',
   'Apporte une paire claire dont tu acceptes de te séparer un peu.',
   'FR', 'Bordeaux', 'Darwin Écosystème',
   '87 Quai des Queyries, 33100 Bordeaux', -0.5546, 44.8452),
  (36, 'skateboard', 'fr',
   'Skate au Rheinpark',
   'Street et park, du premier ollie aux lignes complètes, avec des ateliers par niveau. Les chutes se travaillent aussi.',
   'Enchaîner trois tricks dans une même ligne.',
   'Planche personnelle. Casque obligatoire pour les débutants.',
   'DE', 'Köln', 'Rheinpark',
   'Sachsenbergstraße, 50679 Köln', 6.974, 50.945),
  (37, 'breakdance', 'fr',
   'Breakdance à la Boverie',
   'Toprock, footwork et power moves, puis cypher en fin de séance. On travaille la musicalité autant que la technique.',
   'Tenir un passage de trente secondes en cypher.',
   'Aucun niveau requis. Genouillères conseillées.',
   'BE', 'Liège', 'Parc de la Boverie',
   'Parc de la Boverie 3, 4020 Liège', 5.581, 50.628),
  (38, 'graffiti', 'fr',
   'Fresque murale à la Citadelle',
   'Lettrage, remplissage et fresque collective sur mur libre, à la bombe et au rouleau. Le mur change à chaque cycle.',
   'Poser un lettrage lisible et tenir sa place dans une fresque.',
   'Masque et gants fournis. Vêtements à sacrifier.',
   'FR', 'Lille', 'Parc de la Citadelle',
   'Avenue du 43e Régiment d''Infanterie, 59000 Lille', 3.045, 50.641),
  (39, 'rap-freestyle', 'fr',
   'Freestyle au Günthersburgpark',
   'Écriture courte puis cercle de freestyle sur instrus ouvertes. Personne ne passe deux fois sans avoir écouté.',
   'Tenir seize mesures sans décrocher.',
   'Rien à apporter. Les instrus sont fournies.',
   'DE', 'Frankfurt am Main', 'Günthersburgpark',
   'Comeniusstraße, 60389 Frankfurt am Main', 8.698, 50.13),
  (40, 'parkour', 'fr',
   'Parkour au Stadspark de Louvain',
   'Franchissement, réception et lecture du mobilier urbain, en progression encadrée. On échauffe longtemps et on saute peu au début.',
   'Franchir en sécurité les obstacles courants d''un parcours urbain.',
   'Chaussures plates. Aucune acrobatie exigée.',
   'BE', 'Leuven', 'Stadspark Leuven',
   'Stadspark, 3000 Leuven', 4.706, 50.879),
  (41, 'podcast', 'fr',
   'Podcast au Quai des Savoirs',
   'Concevoir, enregistrer et monter un épisode à deux voix, du pitch au mixage. Chaque cycle produit un épisode publiable.',
   'Publier un premier épisode de vingt minutes.',
   'Un casque. Les micros sont prêtés.',
   'FR', 'Toulouse', 'Quai des Savoirs',
   '39 Allées Jules Guesde, 31000 Toulouse', 1.45, 43.596),
  (42, 'streaming-live', 'fr',
   'Stream et création de contenu aux Wagenhallen',
   'Régie, direct et formats courts : on monte une chaîne de A à Z. Une séance sur deux se termine par un direct réel.',
   'Tenir un direct d''une heure sans casser la régie.',
   'Portable et compte de diffusion. Le reste est sur place.',
   'DE', 'Stuttgart', 'Wagenhallen',
   'Innerer Nordbahnhof 1, 70191 Stuttgart', 9.178, 48.8),
  (43, 'side-project', 'fr',
   'Side project au Rockerill',
   'De l''idée au prototype en deux séances : cadrage, maquette, test auprès de vrais gens. On coupe ce qui n''est pas indispensable.',
   'Sortir un prototype testé par cinq personnes extérieures.',
   'Une idée, même vague. Portable conseillé.',
   'BE', 'Charleroi', 'Rockerill',
   'Rue de la Providence 134, 6030 Charleroi', 4.45, 50.413),
  (44, 'finances-perso', 'fr',
   'Finances personnelles au Lieu Unique',
   'Budget, épargne et bases de l''investissement, sur des cas concrets et anonymisés. Aucun conseil personnalisé n''est donné.',
   'Tenir un budget mensuel et se constituer une épargne de précaution.',
   'Aucun prérequis, rien à apporter et aucun démarchage.',
   'FR', 'Nantes', 'Le Lieu Unique',
   '2 Rue de la Biscuiterie, 44000 Nantes', -1.545, 47.214),
  (45, 'pitch-networking', 'fr',
   'Pitch et networking au Zakk',
   'Trois minutes pour convaincre, puis retours du groupe et rencontres libres. On change de binôme à chaque tour.',
   'Avoir un pitch de trois minutes qui tient sans notes.',
   'Viens avec un projet, même à l''état d''idée.',
   'DE', 'Düsseldorf', 'Zakk',
   'Fichtenstraße 40, 40233 Düsseldorf', 6.8, 51.22),
  (46, 'astronomie', 'fr',
   'Observation du ciel à l''Orangerie',
   'Repérage à l''œil nu puis observation au télescope, selon la météo. Chaque séance vise un objet précis du ciel de saison.',
   'S''orienter seul dans le ciel et pointer cinq objets au télescope.',
   'Vêtements chauds. Les instruments sont partagés.',
   'FR', 'Strasbourg', 'Parc de l''Orangerie',
   'Avenue de l''Europe, 67000 Strasbourg', 7.7715, 48.5895),
  (47, 'sciences-participatives', 'fr',
   'Sciences participatives au Clara-Zetkin-Park',
   'Relevés de terrain qui alimentent de vrais programmes de recherche : protocole, mesure, saisie. Les données partent le soir même.',
   'Réaliser un relevé complet et fiable en autonomie.',
   'Aucun prérequis scientifique. Carnet et smartphone utiles.',
   'DE', 'Leipzig', 'Clara-Zetkin-Park',
   'Anton-Bruckner-Allee, 04107 Leipzig', 12.361, 51.329),
  (48, 'tandem-linguistique', 'fr',
   'Tandem linguistique au Parc Montcalm',
   'Une heure dans sa langue, une heure dans celle de l''autre, en binômes tirés au sort. Les sujets sont donnés pour éviter les blancs.',
   'Tenir une heure de conversation sans repasser par sa langue.',
   'Un niveau A2 minimum dans la langue visée.',
   'FR', 'Montpellier', 'Parc Montcalm',
   'Rue Marioge, 34000 Montpellier', 3.853, 43.607),
  (49, 'musee-expo', 'fr',
   'Sorties musées et expos à Dresde',
   'Une exposition par séance, visitée ensemble puis débriefée au café d''à côté. Le choix tourne entre les membres du groupe.',
   'Regarder une exposition en entier et savoir en parler.',
   'Billets à la charge de chacun. Les tarifs réduits sont partagés.',
   'DE', 'Dresden', 'Kraftwerk Mitte',
   'Wettiner Platz 7, 01067 Dresden', 13.728, 51.053),
  (50, 'urbex-patrimoine', 'fr',
   'Marches patrimoine au Z-Bau',
   'Architecture, friches et lieux oubliés, toujours avec autorisation et en sécurité. Le parcours est reconnu avant chaque sortie.',
   'Lire une ville par ses bâtiments et ses usages successifs.',
   'Chaussures fermées. Aucune intrusion, aucune exception.',
   'DE', 'Nürnberg', 'Z-Bau',
   'Frankenstraße 200, 90461 Nürnberg', 11.081, 49.434);

-- ============================================================
-- 4. QUI ORGANISE QUOI
--
-- Un programme par utilisateur actif, dans l'ordre d'ancienneté du compte. Le
-- modulo n'est pas décoratif : sur une base qui compte moins de 50 comptes —
-- un environnement de test fraîchement monté, par exemple — il fait retomber
-- les canevas restants sur les premiers utilisateurs plutôt que de les perdre
-- en silence. Avec les 50 comptes actuels, la correspondance est exactement
-- un pour un.
--
-- Le NULLIF protège le cas limite d'une base sans aucun compte actif : le
-- modulo lèverait une division par zéro et ferait échouer un déploiement pour
-- un jeu de données de test. Il rend la jointure vide, la migration ne crée
-- rien, et les vérifications de la section 10 restent vraies pour zéro
-- programme.
-- ============================================================
CREATE TEMP TABLE seed_v103_hotes ON COMMIT DROP AS
SELECT u.id AS user_id,
       u.display_name,
       u.avatar_url,
       row_number() OVER (ORDER BY u.created_at, u.id) AS rn
FROM users u
WHERE u.is_active IS TRUE;

CREATE TEMP TABLE seed_v103_creation ON COMMIT DROP AS
SELECT c.*,
       h.user_id,
       h.display_name,
       h.avatar_url,
       a.id  AS activity_id,
       a.image_url,
       gen_random_uuid() AS program_id
FROM seed_v103_canevas c
JOIN seed_v103_hotes h
  ON h.rn = ((c.idx - 1) % NULLIF((SELECT count(*) FROM seed_v103_hotes), 0)) + 1
JOIN activities a ON a.slug = c.activite_slug;

-- ============================================================
-- 5. LES ACTIVITÉS PRATIQUÉES
-- ============================================================
INSERT INTO user_activities (user_id, activity_id, visible_on_map, custom_description,
                             level, format, created_at)
SELECT c.user_id, c.activity_id, TRUE,
       CASE c.langue
         WHEN 'de' THEN 'Ich leite dieses Programm und suche Leute, die regelmäßig dabei sind.'
         ELSE 'J''anime ce programme et je cherche des partenaires réguliers.'
       END,
       'ANY', 'GROUP', NOW() - INTERVAL '30 days'
FROM seed_v103_creation c
ON CONFLICT (user_id, activity_id) DO NOTHING;

-- ============================================================
-- 6. LES PROGRAMMES
--
-- subscribers_notified_at est renseigné volontairement (V55) : laissé à NULL,
-- l'annonce partirait au premier créneau posé et 50 programmes de test
-- réveilleraient les abonnés pour rien.
-- ============================================================
INSERT INTO programs (id, user_activity_id, title, description, status, is_public,
    organizer_name, organizer_avatar_url, image_url,
    duration_weeks, sessions_per_week, session_duration_minutes,
    preferred_time, max_participants, privacy, goals, prerequisites,
    location_type, created_via, subscribers_notified_at, created_at, updated_at)
SELECT c.program_id, ua.id, c.titre, c.description, 'ACTIVE', TRUE,
       c.display_name, c.avatar_url, c.image_url,
       2, 1, 90,
       CASE (c.idx - 1) % 3 WHEN 0 THEN 'MORNING' WHEN 1 THEN 'AFTERNOON' ELSE 'EVENING' END,
       60, 'PUBLIC', c.objectifs, c.prerequis,
       'IN_PERSON', 'FULL',
       NOW() - INTERVAL '20 days', NOW() - INTERVAL '20 days', NOW() - INTERVAL '5 days'
FROM seed_v103_creation c
JOIN user_activities ua
  ON ua.user_id = c.user_id AND ua.activity_id = c.activity_id;

-- ============================================================
-- 7. LES SÉANCES
--
-- Deux par programme, à une semaine d'intervalle, aux horaires démontrés
-- sans recouvrement en tête de fichier. Le calcul se fait en heure locale de
-- Berlin puis revient en instant absolu, pour que 18 h veuille dire 18 h sur
-- place des deux côtés du changement d'heure.
-- ============================================================
INSERT INTO schedules (program_id, place_name, place_type, location, address_public, city,
    show_exact_address, starts_at, ends_at, max_participants,
    is_open_to_partners, status, participant_count, welcome_note,
    primary_language, created_at)
SELECT c.program_id, c.lieu, 'PUBLIC',
       ST_SetSRID(ST_MakePoint(c.lng, c.lat), 4326),
       c.adresse || ' — ' || c.pays, c.ville, TRUE,
       debut.instant,
       debut.instant + INTERVAL '90 minutes',
       60, TRUE, 'OPEN', 0,
       CASE c.langue
         WHEN 'de' THEN 'Komm einfach vorbei: Die ersten fünf Minuten gehören den Neuen.'
         ELSE 'Viens comme tu es : les cinq premières minutes sont pour les nouveaux.'
       END,
       c.langue, NOW() - INTERVAL '20 days'
FROM seed_v103_creation c
CROSS JOIN LATERAL (VALUES (1), (2)) AS s(no)
CROSS JOIN LATERAL (
    SELECT (
        date_trunc('day', NOW() AT TIME ZONE 'Europe/Berlin')
        + make_interval(days  => CASE s.no WHEN 1 THEN 1 + c.idx ELSE 8 + c.idx END,
                        hours => CASE s.no
                                   WHEN 1 THEN 10 + 4 * ((c.idx - 1) % 3)
                                   ELSE        12 + 4 * ( c.idx      % 3)
                                 END)
    ) AT TIME ZONE 'Europe/Berlin' AS instant
) AS debut;

-- La date de prochaine séance est dénormalisée sur le programme : elle sert au
-- tri de la recherche et de la carte, et resterait nulle sans ce report.
UPDATE programs p
SET next_session_at = (SELECT min(s.starts_at) FROM schedules s WHERE s.program_id = p.id)
WHERE p.id IN (SELECT program_id FROM seed_v103_creation);

-- ============================================================
-- 8. LES INSCRIPTIONS
--
-- Chaque utilisateur rejoint une séance de chacun des autres organisateurs :
-- 50 × 49 = 2 450 inscriptions confirmées, soit une vingtaine par séance.
-- Laquelle des deux séances ? Celle que désigne la parité de (rang de
-- l'inscrit + numéro du programme), ce qui répartit le monde à peu près
-- également sur les deux dates sans jamais faire de choix aléatoire — une
-- migration doit donner le même résultat partout où elle passe.
--
-- Le plafond des séances est fixé à 60 places, au-dessus des 49 inscriptions
-- possibles : à 8 ou 10 places comme dans les seeds sportifs, tous les créneaux
-- basculeraient FULL et la base de test ne montrerait plus jamais un créneau
-- ouvert.
-- ============================================================
INSERT INTO slot_participations (schedule_id, user_id, status, join_message, created_at)
SELECT k.id, h.user_id, 'CONFIRMED',
       CASE c.langue
         WHEN 'de' THEN 'Bin dabei, freue mich drauf!'
         ELSE 'Je serai là, avec plaisir !'
       END,
       NOW() - INTERVAL '10 days'
FROM seed_v103_creation c
JOIN (
    SELECT s.id, s.program_id,
           row_number() OVER (PARTITION BY s.program_id ORDER BY s.starts_at) AS no
    FROM schedules s
    WHERE s.program_id IN (SELECT program_id FROM seed_v103_creation)
) k ON k.program_id = c.program_id
JOIN seed_v103_hotes h ON h.user_id <> c.user_id
WHERE k.no = ((h.rn + c.idx) % 2) + 1
ON CONFLICT (schedule_id, user_id) DO NOTHING;

-- Même règle que ScheduleRepository.countConfirmedParticipants et que V100 :
-- inscriptions actives au programme, plus RSVP confirmés sur la séance.
UPDATE schedules s
SET participant_count =
      (SELECT count(*) FROM user_programs up
        WHERE up.schedule_id = s.id AND up.status = 'ACTIVE')
    + (SELECT count(*) FROM slot_participations sp
        WHERE sp.schedule_id = s.id AND sp.status = 'CONFIRMED')
WHERE s.program_id IN (SELECT program_id FROM seed_v103_creation);

UPDATE schedules
SET status = 'FULL'
WHERE program_id IN (SELECT program_id FROM seed_v103_creation)
  AND status = 'OPEN'
  AND max_participants IS NOT NULL
  AND participant_count >= max_participants;

-- ============================================================
-- 9. LES VISUELS DES PROGRAMMES
-- ============================================================
INSERT INTO program_media (program_id, url, media_type, sort_order, created_at)
SELECT c.program_id, c.image_url, 'IMAGE', 0, NOW() - INTERVAL '20 days'
FROM seed_v103_creation c;

-- ============================================================
-- 10. VÉRIFICATIONS
--
-- Une migration de données qui se contente d'insérer ne dit rien de ce qu'elle
-- a produit. Ces trois contrôles échouent la migration plutôt que de laisser
-- passer un jeu de test faux — en particulier le deuxième, qui est la seule
-- preuve que l'absence de collision tient vraiment.
-- ============================================================
DO $$
DECLARE
    nb_programmes INTEGER;
    nb_seances    INTEGER;
    nb_collisions INTEGER;
    nb_absents    INTEGER;
BEGIN
    SELECT count(*) INTO nb_programmes FROM seed_v103_creation;
    SELECT count(*) INTO nb_seances
      FROM schedules WHERE program_id IN (SELECT program_id FROM seed_v103_creation);

    IF nb_seances <> nb_programmes * 2 THEN
        RAISE EXCEPTION 'V103 : % séances pour % programmes, attendu %',
            nb_seances, nb_programmes, nb_programmes * 2;
    END IF;

    -- Deux séances se chevauchent-elles ? On compare tous les couples de
    -- séances nouvellement posées : aucun intervalle [début, fin) ne doit en
    -- croiser un autre.
    SELECT count(*) INTO nb_collisions
    FROM schedules a
    JOIN schedules b
      ON b.id > a.id
     AND a.starts_at < b.ends_at
     AND b.starts_at < a.ends_at
    WHERE a.program_id IN (SELECT program_id FROM seed_v103_creation)
      AND b.program_id IN (SELECT program_id FROM seed_v103_creation);

    IF nb_collisions > 0 THEN
        RAISE EXCEPTION 'V103 : % chevauchements d''horaires entre séances', nb_collisions;
    END IF;

    -- Chaque utilisateur actif doit être inscrit sur une séance de chacun des
    -- autres organisateurs, soit (nb_programmes - 1) inscriptions chacun quand
    -- la correspondance utilisateur/programme est un pour un.
    SELECT count(*) INTO nb_absents
    FROM seed_v103_hotes h
    JOIN seed_v103_creation c ON c.user_id <> h.user_id
    LEFT JOIN slot_participations sp
      ON sp.user_id = h.user_id
     AND sp.schedule_id IN (SELECT s.id FROM schedules s WHERE s.program_id = c.program_id)
    WHERE sp.id IS NULL;

    IF nb_absents > 0 THEN
        RAISE EXCEPTION 'V103 : % couples (utilisateur, programme) sans inscription', nb_absents;
    END IF;

    RAISE NOTICE 'V103 : % programmes, % séances, aucune collision horaire.',
        nb_programmes, nb_seances;
END $$;
