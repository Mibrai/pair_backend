-- P-MU-07 — le niveau affiché sur un créneau est celui du créneau.
--
-- Le défaut : `SlotFeedItemDto.level` et `SearchResultDto.level` rendaient le
-- niveau personnel de l'hôte pour l'activité (`user_activities.level`), déclaré
-- à l'onboarding. Un hôte « Avancé » en course affichait donc « Avancé » sur
-- tous ses créneaux de course, présenté aux participants comme une exigence
-- qu'il n'avait jamais formulée.
--
-- Nullable, et c'est le cas normal : nul veut dire « non précisé », et rien ne
-- s'affiche. Aucun rattrapage depuis `user_activities.level` : recopier le
-- niveau de l'hôte dans ses créneaux existants reconduirait exactement le
-- défaut, cette fois inscrit dans la ligne. Les créneaux existants perdent leur
-- puce, et c'est voulu.
--
-- VARCHAR(20) comme `user_activities.level` : même énumération (`ActivityLevel`),
-- même stockage par nom. Pas de contrainte CHECK, pour la même raison que
-- `user_activities` n'en a pas : c'est l'énumération Java qui fait foi.
ALTER TABLE schedules
    ADD COLUMN IF NOT EXISTS level VARCHAR(20);
