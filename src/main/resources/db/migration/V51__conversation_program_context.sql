-- Contexte de conversation : programme et créneau.
--
-- La colonne activity_context_id existait depuis V6 mais n'a jamais été écrite
-- (ChatService jetait CreateConversationRequest.activityContextId). L'en-tête de
-- conversation du client a besoin de trois choses — le programme, l'activité et
-- la date du créneau — dont deux manquaient au modèle.
--
-- schedule_id porte la séance qui lie les deux personnes : c'est la date que le
-- client compare à maintenant pour griser un fil dont le créneau est passé.
-- Sans elle la règle n'a aucun déclencheur, et le nom d'activité seul ne suffit
-- pas à désigner la bonne séance dès qu'une personne suit deux programmes de la
-- même activité.
--
-- ON DELETE SET NULL, comme activity_context_id : la disparition d'un programme
-- ou d'un créneau retire le contexte, elle n'emporte pas la conversation ni son
-- historique.

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS program_id  UUID,
    ADD COLUMN IF NOT EXISTS schedule_id UUID;

ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_program
        FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE SET NULL;

ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_program ON conversations(program_id);
