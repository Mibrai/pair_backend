-- ============================================================
-- V128 — Les tables progressions et progression_entries sont supprimées
-- ============================================================
-- Demande mobile badges du 14/09/2026 (QUATER), décision de l'utilisateur :
-- connaître le contenu, puis, faute d'usage réel, supprimer (minimisation, RGPD
-- art. 5.1.c). Le module /api/progressions est retiré depuis le 14/09 ; plus aucun
-- code ne lit ni n'écrit ces tables. Suppression confirmée par l'utilisateur le 15/09.
--
-- Relevé de production du 15/09/2026, en lecture seule et par agrégats :
--   progressions : 10 lignes, 10 comptes, 1 privée, plus récente le 2026-07-01 ;
--   progression_entries : 0 ligne.
-- Ces 10 lignes sont exactement la graine de V27 (10 insertions, une seule non
-- publique, datées de 35 à 8 jours avant son application) : aucune saisie réelle.

DROP TABLE IF EXISTS progression_entries;
DROP TABLE IF EXISTS progressions;
