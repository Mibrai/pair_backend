-- L'état NOT_ARRIVED : une non-arrivée se referme, et ne prévient plus personne.
--
-- Décision produit du 02/09 : quelqu'un qui n'a jamais validé son arrivée ne fait
-- plus prévenir son contact d'urgence. Le système note la non-arrivée, prévient
-- l'organisateur, et s'arrête là.
--
-- L'état neuf n'est pas cosmétique. ESCALATED voulait dire « un message est parti
-- à un tiers » ; sur cette branche il ne le voudra plus dire. Surtout, ESCALATED
-- est balayé par la boucle retour : une non-arrivée qui y restait aurait vu partir
-- l'alerte retour ② à l'échéance, une heure plus tard — précisément le message que
-- la décision retire. NOT_ARRIVED sort du champ de vision de cette boucle, et c'est
-- l'état, non un garde-fou, qui referme la porte.

ALTER TABLE watches DROP CONSTRAINT IF EXISTS watches_state_vocabulaire;

ALTER TABLE watches ADD CONSTRAINT watches_state_vocabulaire CHECK (state IN (
    'ARMED', 'EN_ROUTE', 'ON_SITE', 'REMINDING', 'ESCALATED', 'RESOLVED', 'CLOSED',
    'NOT_ARRIVED'));

-- « Mes veilles actives » rend désormais, en plus des veilles vivantes, les
-- non-arrivées refermées depuis moins de 24 h : c'est le seul endroit où la
-- personne concernée apprend que sa soirée a été classée perdue en chemin.
-- L'index sert ce prédicat sans balayer le journal entier.
CREATE INDEX IF NOT EXISTS idx_watches_user_not_arrived_recent
    ON watches (user_id, closed_at DESC)
    WHERE state = 'NOT_ARRIVED';

-- L'unicité « une seule veille vivante par créneau » énumérait les états terminaux
-- en SQL, à côté de WatchState.TERMINAUX qui les énumère en Java. Les deux listes
-- doivent dire la même chose : sans cette ligne, le service autorise le réarmement
-- après une non-arrivée — la veille est terminale pour lui — et la base le refuse,
-- ce qui rend un 500 à quelqu'un qui reprogramme une séance manquée.
DROP INDEX IF EXISTS uq_watches_active_par_creneau;

CREATE UNIQUE INDEX IF NOT EXISTS uq_watches_active_par_creneau
    ON watches (user_id, schedule_id)
    WHERE state NOT IN ('RESOLVED', 'CLOSED', 'NOT_ARRIVED');
