-- Onboarding guidé (lot A1 de meetdo-v2)
--
-- Le client a besoin de savoir, au démarrage, s'il doit ouvrir l'application sur
-- l'accueil ou sur le parcours d'accueil. Deux colonnes suffisent : où la
-- personne en est, et si elle en est sortie.
--
-- onboarding_step est un VARCHAR et non un type énuméré PostgreSQL : le parcours
-- va bouger, et ajouter une étape ne doit pas demander une migration de type. Le
-- serveur valide la valeur, la base la stocke.
--
-- onboarding_completed_at porte une date plutôt qu'un booléen, parce que « quand »
-- se révélera utile — mesurer le parcours, relancer qui s'est arrêté en route —
-- et qu'un booléen ne se transforme pas rétroactivement en date.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS onboarding_step VARCHAR(30);

-- Les comptes déjà en base ont, par définition, franchi l'accueil : ils existent,
-- ils ont une position, souvent des activités. Sans cette ligne, la colonne
-- resterait nulle pour tout le monde et la première mise à jour du client
-- renverrait l'intégralité des utilisateurs dans un parcours d'accueil qu'ils ont
-- déjà fait — le genre de régression qu'on ne voit qu'en production.
--
-- La date d'inscription est préférée à NOW() : elle est vraie. Prétendre que
-- tout le parc a terminé son accueil à l'instant du déploiement fausserait la
-- première mesure qu'on voudra faire sur ces colonnes.
UPDATE users
SET onboarding_completed_at = created_at,
    onboarding_step         = 'DONE'
WHERE onboarding_completed_at IS NULL;
