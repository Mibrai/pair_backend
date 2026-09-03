-- L'arrivée à deux temps, et la veille qui n'a personne à prévenir.
--
-- Deux décisions produit du 03/09, portées par la même migration parce qu'elles
-- touchent la même table et le même vocabulaire d'états.
--
-- 1. L'ARRIVÉE SE DÉCLARE, PUIS SE VALIDE
--
-- Jusqu'ici « j'y suis » faisait naître le code de retour. Désormais la personne
-- déclare (arrival_claimed_at), et l'hôte valide (arrival_confirmed_at, qui
-- existait déjà). Le code naît après la validation, et sur demande de la personne
-- seule.
--
-- arrival_claimed_at est une COLONNE ET NON UN ÉTAT, à la demande du client et
-- pour la raison qu'il a apprise avec NOT_ARRIVED : WatchState.parse rend ARMED
-- sur tout état inconnu, donc un état neuf ferait retomber les app anciennes sur
-- « armée, en attente d'arrivée » — ce qui est faux dès qu'une déclaration
-- existe. Un champ inconnu, lui, est simplement ignoré.
--
-- 2. UNE VEILLE PEUT S'ARMER SANS CONTACT
--
-- guardian_id perd son NOT NULL. « Une veille qui ne prévient personne n'est pas
-- une veille » était vrai du point de vue de l'alerte, et faux du point de vue du
-- premier soir : le bouton était éteint pour quiconque n'avait pas encore de
-- contact accepté, c'est-à-dire pour tout le monde au moment où l'on en a le plus
-- besoin. Restent les relances, le journal, et la validation de présence.
--
-- NO_CONTACT est l'état terminal de ces veilles-là. Il ne remplace pas ESCALATED,
-- il l'évite : ESCALATED veut dire « un message est parti à un tiers » dans tout
-- le code et dans le bandeau corail du client. Sur une veille sans contact, ce
-- serait la phrase la plus fausse que l'app puisse écrire.
--
-- C'est le même raisonnement que NOT_ARRIVED (V96), et le même piège : les états
-- terminaux sont énumérés à TROIS endroits — WatchState.TERMINAUX en Java, la
-- contrainte de vocabulaire ci-dessous, et l'index d'unicité. Les trois doivent
-- dire la même chose, sans quoi le service autorise un réarmement que la base
-- refuse, et rend un 500 à quelqu'un qui reprogramme une séance.

-- ------------------------------------------------------------------ 1. l'arrivée

ALTER TABLE watches ADD COLUMN IF NOT EXISTS arrival_claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN watches.arrival_claimed_at IS
    'Quand la personne a déclaré son arrivée. La validation, elle, est arrival_confirmed_at.';

-- Le balayage de la validation automatique : les veilles déclarées et non encore
-- validées. Partiel, parce que c''est une poignée de lignes à tout instant et que
-- le job passe chaque minute.
CREATE INDEX IF NOT EXISTS idx_watches_arrivee_declaree_non_validee
    ON watches (arrival_claimed_at)
    WHERE arrival_claimed_at IS NOT NULL AND arrival_confirmed_at IS NULL;

-- ------------------------------------------------- 2. la veille sans contact

-- Le contact devient facultatif. Aucune donnée à rétro-remplir : les veilles
-- existantes en ont toutes un, et continuent de fonctionner à l'identique.
ALTER TABLE watches ALTER COLUMN guardian_id DROP NOT NULL;

ALTER TABLE watches DROP CONSTRAINT IF EXISTS watches_state_vocabulaire;

ALTER TABLE watches ADD CONSTRAINT watches_state_vocabulaire CHECK (state IN (
    'ARMED', 'EN_ROUTE', 'ON_SITE', 'REMINDING', 'ESCALATED', 'RESOLVED', 'CLOSED',
    'NOT_ARRIVED', 'NO_CONTACT'));

-- L'unicité « une seule veille vivante par créneau » doit compter NO_CONTACT
-- comme terminal, exactement comme NOT_ARRIVED : sans cette ligne, une personne
-- dont la veille sans contact s'est refermée ne pourrait plus jamais réarmer sur
-- ce créneau.
DROP INDEX IF EXISTS uq_watches_active_par_creneau;

CREATE UNIQUE INDEX IF NOT EXISTS uq_watches_active_par_creneau
    ON watches (user_id, schedule_id)
    WHERE state NOT IN ('RESOLVED', 'CLOSED', 'NOT_ARRIVED', 'NO_CONTACT');

-- « Mes veilles actives » rend les veilles sans contact refermées depuis moins de
-- 24 h, pour la même raison que les non-arrivées : c'est le seul endroit où la
-- personne apprend que sa soirée s'est refermée sans réponse. Personne n'a été
-- prévenu — c'est précisément ce qu'elle avait accepté — mais elle doit le lire.
CREATE INDEX IF NOT EXISTS idx_watches_user_no_contact_recent
    ON watches (user_id, closed_at DESC)
    WHERE state = 'NO_CONTACT';
