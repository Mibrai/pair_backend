-- Lot B des abonnements — l'annonce aux abonnés d'une CATÉGORIE change de moment.
--
-- Elle partait à la création de l'activité. Or une activité n'a pas de position
-- propre : elle emprunte celle de ses créneaux, et à sa création elle n'en a
-- aucun. Le rayon des abonnements CATEGORY (V58) n'avait donc rien à filtrer, et
-- la règle « pas de coordonnée, on notifie toujours » aurait été vraie à chaque
-- fois : un rayon stocké, lu, affiché, et sans le moindre effet.
--
-- L'annonce part désormais au premier créneau localisé de l'activité, où il y a
-- enfin quelque chose à situer. C'est exactement le motif déjà retenu pour
-- AUTHOR_NEW_PROGRAM à la V55 : « un programme naît en brouillon et sans
-- créneau, il n'y avait donc rien à situer au moment où l'annonce partait ».
--
-- Cette colonne porte l'unicité, et non un décompte des créneaux : le premier
-- créneau supprimé puis reposé ferait du suivant « le premier » une seconde
-- fois, et les abonnés d'une catégorie recevraient deux annonces de la même
-- activité. Même raisonnement, même forme que programs.subscribers_notified_at.
--
-- Nulle pour toutes les lignes existantes : les activités déjà créées ont déjà
-- été annoncées à la création, sous l'ancienne règle. Les laisser à NULL les
-- rendrait annonçables une seconde fois au prochain créneau ajouté — on les
-- horodate donc à la migration, ce qui les déclare « déjà annoncées ».
ALTER TABLE user_activities
    ADD COLUMN IF NOT EXISTS category_notified_at TIMESTAMPTZ;

UPDATE user_activities
   SET category_notified_at = created_at
 WHERE category_notified_at IS NULL;
