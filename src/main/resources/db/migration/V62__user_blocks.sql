-- Blocage d'utilisateur (lot A3 de meetdo-v2) — bloquant pour les stores
--
-- Bloquer quelqu'un, dans meetDo, veut dire : ne plus le voir, ne plus en
-- entendre parler, et qu'il ne puisse plus rien déclencher qui me concerne. La
-- table ne porte que le fait ; ce sont les surfaces qui portent la conséquence.
--
-- Le blocage est enregistré dans un seul sens — qui a bloqué qui — mais il
-- s'applique dans les deux. C'est délibéré : une ligne par sens obligerait à en
-- écrire deux à chaque blocage et à les garder synchronisées, alors que
-- l'asymétrie du fait (« c'est moi qui ai bloqué ») est justement ce qu'il faut
-- conserver pour pouvoir débloquer.
--
-- reason est facultatif et n'est jamais montré à la personne bloquée : il
-- servira à la modération, pas à l'échange.

CREATE TABLE IF NOT EXISTS user_blocks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason       VARCHAR(30),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_block UNIQUE (blocker_id, blocked_id),
    CONSTRAINT chk_no_self_block CHECK (blocker_id <> blocked_id)
);

-- Les deux sens sont interrogés à chaque filtrage, et toujours ensemble : le
-- prédicat bilatéral des requêtes de visibilité fait un NOT EXISTS qui teste
-- (moi → lui) OR (lui → moi). Un seul des deux index laisserait la moitié de la
-- condition sans plan.
CREATE INDEX IF NOT EXISTS idx_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX IF NOT EXISTS idx_blocks_blocked ON user_blocks(blocked_id);
