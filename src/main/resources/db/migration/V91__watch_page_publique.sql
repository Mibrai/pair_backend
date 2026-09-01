-- La page de statut publique d'une veille (priorité 5 du cahier, §5).
--
-- On garde trace de deux choses : la première ouverture de la page — le « le
-- principal a ouvert » qui décide si l'on prévient le contact de secours — et une
-- révocation par le propriétaire, qui éteint le lien avant son expiration
-- naturelle.

ALTER TABLE watches
    ADD COLUMN IF NOT EXISTS public_viewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS public_token_revoked_at TIMESTAMPTZ;
