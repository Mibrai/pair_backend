-- Une recommandation entre pairs est un geste binaire (je recommande ou je ne
-- fais rien), jamais une note comparative : rating et comment deviennent
-- facultatifs. Pas de valeur par défaut appliquée ici — les lignes existantes
-- gardent leur note/commentaire réels, seules les futures recommandations
-- pourront omettre ces champs.

ALTER TABLE peer_recommendations
    ALTER COLUMN rating DROP NOT NULL,
    ALTER COLUMN comment DROP NOT NULL;
