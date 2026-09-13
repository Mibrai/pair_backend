-- P-BL-03 — la purge RGPD peut enfin effacer quelqu'un qui a rejoint un créneau,
-- et elle ne compte ses trente jours que depuis la demande (P-BL-01, décision D2).
--
-- ============================================================================
-- 1. LES DEUX SEULES CLÉS ÉTRANGÈRES VERS users QUI N'AVAIENT PAS D'ON DELETE
-- ============================================================================
--
-- Quarante contraintes référencent users(id). Relevées une par une contre le
-- catalogue d'une base à jour de V110 le 12/09, deux seulement portaient
-- confdeltype = 'a' (NO ACTION) :
--
--     slot_participations.slot_participations_user_id_fkey   (V40, l. 15)
--     attendances.attendances_user_id_fkey                   (V41, l. 6)
--
-- Les deux sont nées d'un REFERENCES en ligne, sans clause CONSTRAINT : c'est
-- PostgreSQL qui les a nommées, selon sa règle <table>_<colonne>_fkey. La fiche
-- d'audit devinait ces deux noms ; le catalogue les confirme, et c'est bien le
-- catalogue qui a tranché — un nom deviné qui tombe juste reste un nom deviné.
--
-- DROP CONSTRAINT sans IF EXISTS, délibérément. Si un nom ne correspondait pas,
-- IF EXISTS ne supprimerait rien et l'ADD qui suit créerait une SECONDE clé sur
-- la même colonne — PostgreSQL l'accepte — en laissant la première en NO ACTION.
-- La migration passerait au vert et la cascade resterait bloquée : exactement le
-- défaut qu'on ferme, rendu invisible. Mieux vaut une migration qui échoue fort.
--
-- Un seul ALTER TABLE par table, DROP puis ADD dans la même instruction : les
-- sous-commandes s'appliquent dans l'ordre, et la table n'est jamais, même un
-- instant, sans clé étrangère vers users.
--
-- Les index de tête existent déjà et ne sont pas à recréer — idx_slotpart_user
-- (V40) et idx_attendance_user_date, dont user_id est la PREMIÈRE colonne
-- (V41). C'est ce qu'exige db/ForeignKeysIndexedIntegrationTest, et c'est ce qui
-- fait que la cascade n'est pas un parcours séquentiel par ligne supprimée.

ALTER TABLE slot_participations
    DROP CONSTRAINT slot_participations_user_id_fkey,
    ADD  CONSTRAINT slot_participations_user_id_fkey
         FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- CASCADE et non SET NULL, et ce n'était pas un arbitrage libre : attendances.user_id
-- est NOT NULL depuis V41. SET NULL aurait donc supposé, d'abord, de rendre la
-- colonne nullable — c'est-à-dire d'accepter une présence sans personne, que tout
-- le calcul de fiabilité et les cartes-souvenir devraient alors savoir lire. La
-- décision D2 (option 1) choisit CASCADE ; la contrainte de schéma allait dans le
-- même sens. Conséquence assumée et attendue : le recalcul suivant fait baisser
-- distinct_partners_count et les présences d'une carte-souvenir chez les autres
-- participants du créneau.
ALTER TABLE attendances
    DROP CONSTRAINT attendances_user_id_fkey,
    ADD  CONSTRAINT attendances_user_id_fkey
         FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- ============================================================================
-- 2. LA DATE DE LA DEMANDE, ET POURQUOI ELLE N'EST PAS REMPLIE POUR LE PASSÉ
-- ============================================================================
--
-- La purge comptait ses trente jours depuis last_active_at. Or last_active_at
-- n'est écrit qu'à la connexion et à la mise à jour de position : un compte resté
-- ouvert des mois par jetons de rafraîchissement porte une date périmée. Depuis
-- que la route de suppression de l'app désactive réellement le compte (P-BL-01),
-- une telle demande serait devenue anonymisable la nuit même — sans les trente
-- jours de réversibilité que la décision D2 et l'écran de l'application
-- promettent. C'est le couplage de déploiement de ce lot : cette colonne et
-- P-BL-01 partent ensemble, ou la purge reste éteinte par
-- pair.gdpr.purge.enabled d'ici là.
--
-- La fiche d'audit prévoyait ici un
--     UPDATE users SET deactivated_at = COALESCE(last_active_at, updated_at, NOW())
--       WHERE is_active = false AND deactivated_at IS NULL;
-- Il n'est pas repris, pour deux raisons indépendantes :
--
--   · users n'a pas de colonne updated_at (relevé du 12/09 : 36 colonnes, aucune
--     de ce nom). La migration aurait échoué au déploiement, pas en revue ;
--   · et surtout, aucune de ces trois valeurs ne dit quand la suppression a été
--     demandée. last_active_at est justement la date dont on vient d'établir
--     qu'elle ne veut rien dire ici, et NOW() ferait repartir le délai à
--     l'instant du déploiement pour des comptes fermés il y a deux ans.
--
-- La colonne reste donc NULL pour tout compte désactivé avant cette migration,
-- et la purge IGNORE toute ligne sans date de demande (voir
-- UserRepository.findDeactivatedBefore). Ces comptes — dont les comptes de
-- démonstration que l'exploitation va fermer — ne seront jamais purgés
-- automatiquement. C'est voulu : leur effacement relève d'un runbook et d'un avis
-- produit, pas d'un job nocturne qui part à trois heures du matin sur une date
-- qu'il a lui-même inventée.

ALTER TABLE users ADD COLUMN deactivated_at TIMESTAMPTZ;

-- Partiel sur is_active = false : c'est la seule population que la purge lit, et
-- elle est minuscule devant la table. L'index ne porte donc que les lignes
-- réellement candidates. Le prédicat suit exactement le WHERE de la requête de
-- sélection, sans quoi le planificateur ne pourrait pas s'en servir.
CREATE INDEX idx_users_deactivated ON users (deactivated_at) WHERE is_active = false;

-- ============================================================================
-- 3. LE GARDE-FOU DE LA MIGRATION ELLE-MÊME
-- ============================================================================
--
-- Ce bloc ne modifie rien : il refuse de valider la migration s'il reste une
-- clé étrangère vers users qui empêcherait la suppression de la ligne parente.
-- Il double le test déclaratif de GdprPurgeIntegrationTest — le test tient la
-- porte pour les migrations futures, celui-ci tient la porte pour CELLE-CI, y
-- compris sur une base dont le schéma aurait dérivé de ce que le dépôt décrit.
--
-- 'a' = NO ACTION, 'r' = RESTRICT : les deux font échouer le DELETE. 'c', 'n' et
-- 'd' (CASCADE, SET NULL, SET DEFAULT) le laissent passer.
DO $$
DECLARE bloquantes text;
BEGIN
    SELECT string_agg(format('%s.%s', c.conrelid::regclass, c.conname), ', '
                      ORDER BY c.conname)
      INTO bloquantes
      FROM pg_constraint c
     WHERE c.contype = 'f'
       AND c.confrelid = 'users'::regclass
       AND c.connamespace = 'public'::regnamespace
       AND c.confdeltype IN ('a', 'r');

    IF bloquantes IS NOT NULL THEN
        RAISE EXCEPTION
            'Ces clés étrangères vers users empêchent encore la suppression du compte : %',
            bloquantes;
    END IF;
END $$;
