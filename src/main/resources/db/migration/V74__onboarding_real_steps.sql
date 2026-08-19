-- Le parcours d'accueil aligné sur les écrans réels (réponse mobile du 2026-08-19)
--
-- V60 a posé onboarding_step d'après la spécification, sans confrontation avec
-- l'application. L'équipe mobile a relevé que les deux listes ne partageaient que
-- deux valeurs, ACTIVITIES et LOCATION, et dans l'ordre inverse : le parcours
-- demande les activités d'abord, le contrat plaçait la position devant. Combiné à
-- la règle « un parcours ne recule pas », l'étape « position » était acceptée
-- puis ignorée, en 200 — un échec invisible des deux côtés, dont seule la
-- personne voyait la conséquence en reprenant au premier écran.
--
-- Le vocabulaire devient donc celui des écrans : ACTIVITIES, LEVELS, LOCATION,
-- PREVIEW. WELCOME n'a jamais eu d'écran, et la fin se lit déjà sur
-- onboarding_completed_at : ni l'un ni l'autre n'avait besoin d'une valeur.

-- Sans cette réécriture, la colonne casserait à la lecture et non à l'écriture :
-- @Enumerated(STRING) refuse une valeur absente de l'énumération, et toute
-- lecture d'un compte migré par V60 lèverait une exception à la désérialisation.
-- C'est la partie de cette migration qui n'est pas cosmétique.
UPDATE users SET onboarding_step = 'ACTIVITIES' WHERE onboarding_step = 'WELCOME';
UPDATE users SET onboarding_step = 'PREVIEW'    WHERE onboarding_step IN ('DISCOVERY', 'DONE');

-- Ce que cette réécriture ne peut pas rendre, et qu'il vaut mieux avoir écrit.
--
-- « DONE » devient « PREVIEW », le dernier écran, parce que ces comptes ont bien
-- traversé le parcours — leur onboarding_completed_at le dit. L'étape ne redit
-- donc rien de neuf pour eux, elle reste seulement cohérente.
--
-- « LOCATION » est conservé tel quel alors qu'il change de rang : deuxième étape
-- sur cinq hier, troisième sur quatre aujourd'hui. Quelqu'un arrêté là reprendra
-- après le choix du niveau au lieu d'avant. C'est une reprise imparfaite, pour
-- une population qui n'a jamais dépassé le développement, et le seul autre choix
-- — le renvoyer à ACTIVITIES — lui referait refaire un écran qu'il avait fait.
