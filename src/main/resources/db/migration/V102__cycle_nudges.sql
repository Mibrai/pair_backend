-- ============================================================
-- V102 — le registre des relances de cycle (CYCLE_NUDGE)
--
-- Le module « cycle » relance l'auteur d'un programme à trois moments : quand
-- son programme attend toujours sa date (étape 2), quand il est publié depuis
-- deux jours sans un seul inscrit (étape 4), et quand son cycle vient de se
-- refermer (étape 7).
--
-- Ce que cette table porte, et pourquoi ce n'est pas un drapeau sur programs.
--
-- 1. L'UNICITÉ, en base plutôt qu'en code. L'index unique (program_id, stage)
--    dit « une seule fois par programme et par étape » au seul endroit où deux
--    exécutions concurrentes du job ne peuvent pas se contredire. Le jour où
--    l'application tourne sur deux instances — ce qui n'est pas le cas
--    aujourd'hui et le sera peut-être — la règle tient sans qu'on ait eu à y
--    penser.
--
-- 2. LE PLAFOND PAR PROGRAMME DEVIENT STRUCTUREL. Le contrat demande « trois au
--    maximum pour un même programme sur toute sa vie ». Trois étapes, une ligne
--    par étape au plus : le plafond n'est plus une vérification qu'on peut
--    oublier d'écrire, c'est une propriété du schéma. Aucun code ne peut le
--    violer.
--
-- 3. LE PLAFOND PAR PERSONNE RESTE UN COMPTE, lui, parce qu'il porte sur une
--    fenêtre glissante de 48 h toutes étapes et tous programmes confondus.
--    D'où l'index (user_id, sent_at).
--
-- Pourquoi ne pas lire tout cela dans `notifications` — qui porte déjà le type,
-- le programme dans son payload jsonb, et n'est jamais purgée ? Parce que
-- NotificationService.notify est @Async : la ligne y est écrite APRÈS le retour
-- de l'appel. Un job qui relirait cette table pour se plafonner lui-même ne
-- verrait pas ce qu'il vient d'envoyer. Ici l'écriture est synchrone, dans la
-- transaction du job, et le plafond est exact dès la première relance.
--
-- Aucun remplissage rétroactif n'est nécessaire : les trois déclencheurs sont
-- bornés des DEUX côtés (voir CycleNudgeJob.NUDGE_WINDOW), si bien qu'un
-- programme dont le fait déclencheur est ancien n'est jamais candidat. C'est ce
-- qui évite la salve du premier jour — le mode d'échec le plus visible d'un
-- module de relance — sans avoir à inventer une date d'activation.
-- ============================================================

CREATE TABLE IF NOT EXISTS cycle_nudges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id  UUID        NOT NULL REFERENCES programs(id) ON DELETE CASCADE,

    -- L'auteur du programme, dénormalisé exprès : le plafond par personne se lit
    -- sur une fenêtre de 48 h toutes étapes confondues, et le faire passer par
    -- programs → user_activities → users à chaque passage du job coûterait trois
    -- jointures pour une question qui n'en demande aucune.
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    stage       SMALLINT    NOT NULL,

    -- L'horizon du programme au moment de la relance : la fin de sa dernière
    -- séance non annulée. Renseigné pour l'étape 7 seulement, et jamais relu par
    -- le code — il est là pour qu'on puisse répondre, dans six mois, à « pourquoi
    -- cette personne a-t-elle reçu ça ce jour-là ? ».
    horizon     TIMESTAMPTZ,

    sent_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_cycle_nudge_stage CHECK (stage IN (2, 4, 7))
);

-- « Une seule fois par programme et par étape ». Unique, donc opposable.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cycle_nudge_program_stage
    ON cycle_nudges (program_id, stage);

-- Le plafond glissant par personne.
CREATE INDEX IF NOT EXISTS idx_cycle_nudge_user_sent
    ON cycle_nudges (user_id, sent_at);
