-- T3 — l'annonce d'un nouveau programme se déplace à son premier créneau.
--
-- Elle partait de createProgram, qui sauve un programme en DRAFT et SANS aucun
-- créneau : CreateProgramRequest n'en porte pas, ils arrivent ensuite par
-- POST /schedules. Les abonnés recevaient donc une annonce qu'aucune date, aucun
-- lieu et aucun compte à rebours ne pouvaient accompagner — il n'y avait rien à
-- accompagner. L'annonce part désormais du premier créneau posé, où sessionAt,
-- placeName et scheduleId existent enfin.
--
-- La colonne est la mémoire d'unicité de cette annonce. Compter les créneaux du
-- programme aurait suffi presque toujours, mais pas quand l'unique créneau est
-- supprimé puis reposé : le suivant serait de nouveau « le premier », et les
-- abonnés recevraient une seconde annonce du même programme. Une date posée une
-- fois ne se redéfait pas.
ALTER TABLE programs ADD COLUMN IF NOT EXISTS subscribers_notified_at TIMESTAMPTZ;

COMMENT ON COLUMN programs.subscribers_notified_at IS
  'Instant où les abonnés (auteur, activité) ont été notifiés de ce programme. '
  'NULL = jamais annoncé, l''annonce partira au premier créneau posé.';

-- Les programmes existants ont déjà été annoncés à leur création, sous l'ancien
-- comportement. Sans ce marquage, le prochain créneau posé sur chacun d'eux
-- rejouerait l'annonce — une salve rétroactive sur toute la base au premier
-- créneau ajouté après la livraison.
UPDATE programs
   SET subscribers_notified_at = created_at
 WHERE subscribers_notified_at IS NULL;
