-- Disponibilités habituelles (lot D3 de meetdo-v2)
--
-- « Je suis généralement libre le mardi soir. » Sert à faire remonter ce qui
-- tombe bien, jamais à écarter ce qui tombe mal.

CREATE TABLE IF NOT EXISTS user_availability (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- 1 = lundi … 7 = dimanche, la numérotation ISO — celle que PostgreSQL rend
    -- par EXTRACT(ISODOW), donc aucune conversion nulle part.
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),

    -- MORNING | AFTERNOON | EVENING. Trois tranches et pas douze : on cherche à
    -- savoir quand quelqu'un est généralement libre, pas à remplir un agenda.
    time_slot   VARCHAR(20) NOT NULL,

    PRIMARY KEY (user_id, day_of_week, time_slot)
);

-- Le fil interroge « cette personne est-elle libre à ce moment-là ? », donc
-- toujours par utilisateur. La clé primaire suffit et aucun index de plus n'est
-- utile.

-- Ce que cette table ne fait pas, et pourquoi.
--
-- Elle ne filtre rien. Une disponibilité déclarée est une habitude, pas un
-- engagement : quelqu'un qui a mis « mardi soir » peut très bien vouloir un
-- samedi matin, et masquer les créneaux hors de ses cases lui cacherait
-- exactement ce qu'il cherchait ce jour-là. La pondération vit dans l'ORDER BY
-- du fil, jamais dans son WHERE.
--
-- Elle ne porte pas non plus de fuseau. Le rapprochement entre un starts_at en
-- UTC et une case « mardi soir » se fait dans le fuseau applicatif
-- (pair.recurrence.zone), le même qui sert au développement des récurrences et
-- aux titres auto-générés. C'est une approximation assumée : le seul fuseau que
-- le système connaisse réellement est celui de l'appareil (device_tokens), qui
-- n'est pas disponible au moment de la requête et peut différer d'un appareil à
-- l'autre pour la même personne.
