-- P-BL-03, étape 8 : la purge RGPD échouait pour toute personne qui avait écrit.
--
-- GdprAccountEraser anonymise avant de supprimer la ligne users : les messages
-- (sender = NULL, contenu « [Message supprimé] »), les avis (reviewer = NULL) et
-- les recommandations (recommender = NULL), pour que le fil de l'autre personne
-- et la page d'un programme gardent leur forme (décision D2, fiche P-BL-03).
-- Or les trois colonnes étaient NOT NULL depuis V6 et V7 : l'anonymisation levait
-- une violation de contrainte, et le compte n'était jamais effacé. En pratique,
-- presque tout compte réel avait écrit au moins un message.
--
-- Les clés passent aussi de CASCADE à SET NULL. En CASCADE, la suppression de la
-- ligne users emporterait les messages d'une personne partie par n'importe quel
-- autre chemin qu'une anonymisation préalable — et avec eux des trous dans la
-- conversation de quelqu'un d'autre. SET NULL dit la règle au niveau du schéma :
-- ce qu'une personne a écrit à d'autres survit à son compte, sans elle.
--
-- Les contraintes sont retrouvées par colonne et non par nom : V18 a renommé
-- from_user_id en recommender_id, et un nom figé ici serait un pari.

DO $$
DECLARE
    cible RECORD;
    contrainte TEXT;
BEGIN
    FOR cible IN
        SELECT * FROM (VALUES
            ('messages', 'sender_id', 'fk_messages_sender'),
            ('reviews', 'reviewer_id', 'fk_reviews_reviewer'),
            ('peer_recommendations', 'recommender_id', 'fk_peer_rec_from')
        ) AS t(tbl, col, nom)
    LOOP
        FOR contrainte IN
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'f'
              AND c.conrelid = cible.tbl::regclass
              AND c.confrelid = 'users'::regclass
              AND a.attname = cible.col
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', cible.tbl, contrainte);
        END LOOP;

        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I DROP NOT NULL', cible.tbl, cible.col);
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES users(id) ON DELETE SET NULL',
            cible.tbl, cible.nom, cible.col);
    END LOOP;
END $$;
