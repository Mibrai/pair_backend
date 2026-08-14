-- Fil de diffusion d'un programme.
--
-- 1. La colonne type était un VARCHAR(10), assez pour DIRECT et GROUP mais pas
--    pour PROGRAM_BROADCAST, qui fait 17 caractères. Sans cet élargissement,
--    tout enregistrement d'un fil de diffusion échouerait à l'insertion.
--
-- 2. Un seul fil par programme. L'unicité est partielle : elle ne porte que sur
--    les fils de diffusion, car program_id sert aussi de contexte aux
--    conversations directes (V51), où plusieurs conversations partagent
--    légitimement le même programme.
--
-- Aucune donnée à reprendre : les fils de diffusion naissent à la première
-- diffusion, pas à la création du programme.

ALTER TABLE conversations
    ALTER COLUMN type TYPE VARCHAR(30);

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversations_program_broadcast
    ON conversations(program_id)
    WHERE type = 'PROGRAM_BROADCAST';
