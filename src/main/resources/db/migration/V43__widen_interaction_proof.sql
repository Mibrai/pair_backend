-- meetDo: élargit la preuve d'interaction exigée pour recommander/évaluer
-- quelqu'un. Une double confirmation de présence sur le même créneau
-- (SHARED_ATTENDANCE) est désormais une preuve valide, au même titre qu'une
-- conversation directe (CONVERSATION).
--
-- interaction_proof_id devient nullable des deux côtés : une preuve
-- SHARED_ATTENDANCE n'a pas d'identifiant unique à stocker (contrairement à
-- une conversation). La preuve elle-même reste obligatoire pour créer une
-- recommandation ou une review — c'est interaction_proof_type qui devient la
-- source de vérité sur la présence d'une preuve, pas la nullabilité de l'id.

ALTER TABLE peer_recommendations
    ALTER COLUMN conversation_id DROP NOT NULL,
    ADD COLUMN interaction_proof_type VARCHAR(20);

UPDATE peer_recommendations SET interaction_proof_type = 'CONVERSATION' WHERE conversation_id IS NOT NULL;

ALTER TABLE reviews
    ALTER COLUMN interaction_proof_id DROP NOT NULL,
    ADD COLUMN interaction_proof_type VARCHAR(20);

UPDATE reviews SET interaction_proof_type = 'CONVERSATION' WHERE interaction_proof_id IS NOT NULL;
