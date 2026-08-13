-- Autorisation des messages de participants, programme par programme.
--
-- L'auteur choisit s'il accepte de recevoir des messages de ses participants.
-- S'il refuse, ceux-ci restent en lecture seule sur le fil du programme.
--
-- Défaut à TRUE, et c'est un choix : le produit met des gens en relation, un
-- programme muet par défaut prendrait tout le monde à contre-pied. L'auteur
-- restreint, il n'ouvre pas — d'où NOT NULL DEFAULT TRUE plutôt qu'une colonne
-- nullable dont l'absence de valeur resterait à interpréter à chaque lecture.
--
-- Les programmes existants héritent donc de TRUE : personne ne perd une
-- conversation en place à cause de cette migration.

ALTER TABLE programs
    ADD COLUMN IF NOT EXISTS allow_participant_messages BOOLEAN NOT NULL DEFAULT TRUE;
