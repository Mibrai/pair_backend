-- Purge du signalement créé le 27/08 par le chantier mobile pour prouver, contre
-- la production, que le chemin d'écriture de POST /api/reports fonctionnait de
-- nouveau (voir PROMPT_BACKEND_APP_STORE_2026-08-27.md, « À nettoyer chez
-- vous »). C'est un auto-signalement du compte de démonstration Lena Müller,
-- description « Verification technique avant soumission App Store » : il n'a
-- aucune valeur de modération et n'a rien à faire dans la file.
--
-- Par migration et non par un DELETE manuel : la suppression part au prochain
-- déploiement, elle est datée, et elle laisse une trace lisible par quiconque
-- se demandera plus tard où est passée cette ligne.
--
-- La ligne n'existe qu'en production — aucun seed ne la pose. Ailleurs, cette
-- migration ne trouve rien et ne fait rien ; c'est le cas normal, pas un échec.
DO $$
DECLARE supprimes INT;
BEGIN
  -- L'identifiant seul suffirait à désigner la ligne. Le reporter_id est là pour
  -- que la migration dise ce qu'elle croit supprimer : si cet identifiant
  -- désignait autre chose dans un environnement que nous ne connaissons pas,
  -- elle ne touche rien plutôt que de supprimer à l'aveugle.
  DELETE FROM reports
  WHERE id = '063a0eb3-1839-42b9-bf91-90e6dd784454'
    AND reporter_id = '00000000-0000-0000-0000-000000000002'
    AND reported_entity_id = '00000000-0000-0000-0000-000000000002';

  GET DIAGNOSTICS supprimes = ROW_COUNT;
  IF supprimes = 0 THEN
    RAISE NOTICE 'V83 — signalement de vérification absent, rien à purger.';
  ELSE
    RAISE NOTICE 'V83 — signalement de vérification purgé.';
  END IF;
END $$;
