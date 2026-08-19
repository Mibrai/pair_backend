-- Signal de fiabilité (lot C3 de meetdo-v2)
--
-- Une seule colonne, là où la spécification en prévoyait deux.
--
-- confirmed_attendance_count aurait fait doublon avec attendance_count, présente
-- depuis V41 : celle-ci compte déjà exactement les présences confirmées
-- (COUNT des attendances où was_present = true) et est recalculée à chaque
-- confirmation par PracticeStatsService. Deux compteurs voisins sur la même
-- table finissent toujours par diverger, et celui-ci alimenterait un chiffre
-- montré à des gens.
--
-- Reste donc le dénominateur, qui manquait vraiment.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS joined_slots_count INTEGER NOT NULL DEFAULT 0;

-- Ce que ce compteur compte, et ce qu'il ne compte pas.
--
-- Les créneaux passés auxquels la personne était inscrite au moment où ils ont
-- eu lieu. Un désistement annoncé à l'avance n'y figure pas : se décommander
-- n'est pas manquer à sa parole, et le signal ne doit pas punir le geste honnête
-- — c'est l'inverse de ce qu'on veut encourager.
--
-- Aucun index : ce compteur ne sert qu'à décider s'il faut afficher un libellé,
-- jamais à trier ni à filtrer. Un index dirait le contraire de ce que la
-- spécification interdit, et inviterait le premier endpoint de classement venu.
