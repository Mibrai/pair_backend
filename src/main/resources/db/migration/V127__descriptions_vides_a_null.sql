-- Demande mobile programmes du 15/09/2026 : une description effacée depuis l'app
-- arrivait en chaîne vide et était stockée telle quelle ; la page publique, qui
-- teste la présence du champ, affichait un paragraphe vide. Le service ramène
-- désormais une description vide ou blanche à NULL ; ceci reprend l'existant.

UPDATE programs
   SET description = NULL
 WHERE description IS NOT NULL
   AND btrim(description) = '';
