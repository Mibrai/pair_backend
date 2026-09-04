-- ============================================================
-- V100 — remettre schedules.participant_count en accord avec la réalité
--
-- POURQUOI CETTE MIGRATION EXISTE
--
-- Le compteur est une valeur dénormalisée : la vérité est la somme des
-- inscriptions actives (user_programs) et des RSVP confirmés
-- (slot_participations), telle que la calcule
-- ScheduleRepository.countConfirmedParticipants.
--
-- Jusqu'au 02/09, deux chemins d'écriture oubliaient de la rafraîchir
-- (joinProgram, leaveProgram) et un troisième la remettait franchement à zéro
-- (le rollover récurrent). Le commit 1e13317 a réparé les chemins — un seul
-- écrivain, ParticipantCounter — mais n'a rien fait des lignes déjà fausses.
-- Elles le sont restées, et le resteront jusqu'à la prochaine écriture sur le
-- créneau : le compteur ne se répare qu'en étant touché.
--
-- C'est ce que le client a observé le 04/09 sans pouvoir l'expliquer, et sa
-- prudence était fondée. Sur un créneau lu avant toute écriture, il relevait
-- participant_count = 0, puis 3 après son inscription, puis 2 après son
-- désistement. Aucun join ne compte pour trois : le 0 était la valeur périmée,
-- et 3 puis 2 sont la vérité que sa propre inscription a fait recalculer. Le
-- compteur n'est pas revenu à sa valeur de départ parce que sa valeur de départ
-- était fausse.
--
-- Ce n'est pas cosmétique. Trois écrans décident sur ce chiffre, dont le filtre
-- « masquer les créneaux complets » qui le compare à max_participants : figé
-- trop bas, il laisse s'inscrire au-delà du plafond que l'organisateur a posé.
--
-- Même règle, mot pour mot, que countConfirmedParticipants et que le recalcul
-- de V44.
-- ============================================================

UPDATE schedules s
SET participant_count =
    (SELECT COUNT(*) FROM user_programs up
      WHERE up.schedule_id = s.id AND up.status = 'ACTIVE')
  + (SELECT COUNT(*) FROM slot_participations sp
      WHERE sp.schedule_id = s.id AND sp.status = 'CONFIRMED')
WHERE s.participant_count <>
    (SELECT COUNT(*) FROM user_programs up
      WHERE up.schedule_id = s.id AND up.status = 'ACTIVE')
  + (SELECT COUNT(*) FROM slot_participations sp
      WHERE sp.schedule_id = s.id AND sp.status = 'CONFIRMED');

-- Le statut suit le compteur, et seulement entre OPEN et FULL — la même borne
-- que ParticipantCounter.refresh. CANCELLED et PAST disent quelque chose que le
-- nombre de places ne sait pas : un créneau annulé qui se viderait ne
-- redeviendrait pas ouvert.
--
-- Sans ces deux ordres, un créneau dont le compteur était figé trop bas
-- resterait OPEN alors qu'il est plein — c'est-à-dire exactement la porte que la
-- correction du compteur était censée refermer.

UPDATE schedules
SET status = 'FULL'
WHERE status = 'OPEN'
  AND max_participants IS NOT NULL
  AND participant_count >= max_participants;

UPDATE schedules
SET status = 'OPEN'
WHERE status = 'FULL'
  AND (max_participants IS NULL OR participant_count < max_participants);
