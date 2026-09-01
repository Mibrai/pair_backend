-- Le jeton de la page de statut publique d'une veille (priorité 4, préparé pour 5).
--
-- Le lien d'urgence naît AVEC l'alerte, pas à l'armement : s'il partait à
-- l'armement, le contact verrait chaque soirée de quelqu'un, et la veille
-- deviendrait un mouchard. Le jeton est donc posé au moment de l'escalade, et le
-- gabarit ② le porte. La page qu'il ouvre est construite à la priorité 5.

ALTER TABLE watches ADD COLUMN IF NOT EXISTS public_token VARCHAR(22);

-- Retrouver une veille par son jeton public, quand la page sera servie.
CREATE UNIQUE INDEX IF NOT EXISTS uq_watches_public_token
    ON watches(public_token) WHERE public_token IS NOT NULL;
