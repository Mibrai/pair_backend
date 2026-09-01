-- Pièce jointe optionnelle d'un incident (priorité 7).
--
-- Le volume de stockage est monté depuis le 31/08 ; la pièce jointe (photo) est
-- déposée par le pipeline média existant, et l'incident n'en garde que l'URL.

ALTER TABLE incidents ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(500);
