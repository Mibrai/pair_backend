-- Le renvoi du code de retour, une fois par cycle (priorité 6).
--
-- resend-code régénère le code (l'ancien étant oublié du serveur, il ne peut être
-- « renvoyé » à l'identique) et le rend une seule fois. On borne à un renvoi par
-- cycle d'arrivée : un drapeau, remis à faux quand une nouvelle arrivée recrée la
-- ligne.

ALTER TABLE return_codes
    ADD COLUMN IF NOT EXISTS resent BOOLEAN NOT NULL DEFAULT FALSE;
