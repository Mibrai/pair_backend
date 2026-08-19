-- Filtres d'accessibilité (lot D2 de meetdo-v2)
--
-- Ce que l'organisateur annonce sur les conditions d'accueil de sa séance.
-- Déclaratif, jamais vérifié — le contrat d'API le dit, et l'interface doit le
-- dire aussi.

CREATE TABLE IF NOT EXISTS schedule_accessibility_tags (
    schedule_id UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,

    -- Étiquette de l'énumération AccessibilityTag. VARCHAR et non type énuméré :
    -- la liste s'allongera, et une migration de type pour chaque ajout serait
    -- coûteuse là où le serveur valide déjà la valeur.
    tag         VARCHAR(40) NOT NULL,

    PRIMARY KEY (schedule_id, tag)
);

-- Le filtre entre par l'étiquette — « montre-moi ce qui est accessible » — et
-- non par le créneau.
CREATE INDEX IF NOT EXISTS idx_schedule_accessibility_tag
    ON schedule_accessibility_tags(tag);

-- Note de conception, à ne pas perdre.
--
-- Ce filtre est RESTRICTIF, à l'inverse de celui des langues posé en V71.
--
-- Une langue non déclarée veut dire « on ne sait pas », et exclure faute
-- d'information punirait ceux qui n'ont rien rempli. Une étiquette
-- d'accessibilité non déclarée veut dire « rien ne permet de l'affirmer », et
-- afficher quand même le créneau enverrait quelqu'un en fauteuil vers un lieu
-- dont personne n'a dit qu'il était accessible. Le coût de l'erreur n'est pas
-- du même ordre dans les deux sens.
--
-- Plusieurs étiquettes demandées se cumulent : qui filtre « accessible en
-- fauteuil » ET « sans alcool » a besoin des deux, pas de l'une ou l'autre.
