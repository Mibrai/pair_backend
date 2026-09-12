-- ============================================================
-- V109 — la table de propriété des médias (fiche P-BS-01, partie B)
--
-- POURQUOI CETTE TABLE EXISTE. Jusqu'au lot 0, rien ne disait qui avait déposé
-- quoi sur le volume de stockage. LocalStorageService.store recevait bien un
-- identifiant, mais celui-ci ne servait qu'à écrire une ligne de journal — et,
-- à deux endroits sur quatre, ce n'était même pas un identifiant de personne :
-- ProgramController passait le programId, ActivityController l'activityId.
-- Une garde d'autorisation sur la suppression n'aurait donc eu personne à
-- comparer : c'est la raison pour laquelle la route générique
-- DELETE /api/media/files/** a été retirée au lot 0 (405) plutôt que gardée.
-- Cette table est ce qui permet de la remplacer par des suppressions ciblées.
--
-- LE CHEMIN EST LA CLÉ PRIMAIRE, pas un UUID de plus. Le chemin est déjà unique
-- (« user_avatar/<uuid>.jpg »), c'est lui qui circule dans les DTO et dans les
-- URL, et c'est donc lui que le serveur reçoit quand il doit décider. Une clé
-- technique obligerait à un index unique sur le chemin de toute façon, et
-- ajouterait une indirection à chaque décision d'autorisation.
--
-- UN FICHIER SANS LIGNE N'EST SUPPRIMABLE PAR PERSONNE, ET C'EST VOULU.
-- MediaFileService.supprimerSiAuteur ne touche au disque que si une ligne
-- existe ET que uploaded_by vaut l'appelant. Les fichiers que le rattrapage
-- ci-dessous ne sait pas rattacher — voir la liste des orphelins plus bas —
-- restent donc lisibles et servis, mais aucune route ne les effacera. C'est le
-- défaut fermé : mieux vaut de l'espace disque perdu qu'une preuve de
-- harcèlement effaçable par la personne signalée. Leur nettoyage, s'il devient
-- nécessaire, est une opération humaine sur le volume, pas une route.
--
-- uploaded_by EST NULLABLE, avec ON DELETE SET NULL. Un compte supprimé
-- (RGPD) laisse ses fichiers sans déposant : ils deviennent alors des
-- orphelins au sens ci-dessus, non supprimables par route. Un CASCADE aurait
-- effacé la ligne de propriété tout en laissant les octets sur le disque —
-- c'est-à-dire exactement l'état d'avant cette migration.
-- ============================================================

CREATE TABLE IF NOT EXISTS media_files (
    path        VARCHAR(300) PRIMARY KEY,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    purpose     VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- La lecture qui compte : « tous les fichiers de cette personne », pour le
-- jour où un export ou une purge RGPD en aura besoin. La décision
-- d'autorisation, elle, se fait par clé primaire.
CREATE INDEX IF NOT EXISTS idx_media_files_uploaded_by ON media_files(uploaded_by);

-- ============================================================
-- RATTRAPAGE DES FICHIERS EXISTANTS
--
-- Quatre colonnes portent aujourd'hui un chemin local — c'est-à-dire une URL
-- qui commence par « /api/media/files/ ». Le préfixe fait 17 caractères, d'où
-- le substring(... FROM 18). Les valeurs externes (seed loremflickr, CDN,
-- copies dénormalisées) ne sont PAS rattrapées : elles ne désignent aucun
-- fichier de notre volume.
--
-- Noms de colonnes relus dans les migrations, et deux écarts avec la fiche :
--
--   1. programs N'A PAS de colonne organizer_id. La fiche proposait de
--      rattacher programs.image_url à « l'auteur du programme » via une
--      colonne qui n'existe pas. L'hôte se rejoint par
--      programs.user_activity_id -> user_activities.user_id (V5 et V4).
--      Les colonnes programs.organizer_name et programs.organizer_avatar_url
--      existent bien (V24) mais sont DÉNORMALISÉES, sans clé étrangère : on ne
--      s'en sert pas ici. organizer_avatar_url en particulier est une copie du
--      users.avatar_url de l'hôte, donc déjà couverte par le premier INSERT —
--      et une copie périmée ne doit surtout pas décider d'une propriété.
--
--   2. La photo de souvenir est attendances.memory_photo_url (V54), portée par
--      la présence et non par slot_recaps : « c'est la personne présente qui
--      possède sa photo » (commentaire de V54). Son déposant est
--      attendances.user_id (V41).
--
-- ON CONFLICT DO NOTHING partout : la migration doit pouvoir être rejouée, et
-- un même chemin peut théoriquement apparaître dans deux colonnes. (Un doublon
-- à l'intérieur d'un même INSERT est également absorbé : « cannot affect row a
-- second time » ne concerne que DO UPDATE.)
--
-- Le garde de longueur sur chaque WHERE : les colonnes sources sont en
-- VARCHAR(500) et media_files.path en VARCHAR(300). Nos propres chemins font
-- une soixantaine de caractères — « <repertoire>/<uuid>.<ext> » — mais une
-- valeur anormalement longue ferait ÉCHOUER la migration entière plutôt que de
-- laisser un fichier de côté. Ce garde la fait retomber dans le cas des
-- orphelins décrit plus bas, qui est le défaut sûr.
-- ============================================================

-- 1. Avatars — users.avatar_url (V2), déposant users.id.
INSERT INTO media_files (path, uploaded_by, purpose)
SELECT substring(u.avatar_url FROM 18), u.id, 'AVATAR'
  FROM users u
 WHERE u.avatar_url LIKE '/api/media/files/%'
   AND length(substring(u.avatar_url FROM 18)) <= 300
ON CONFLICT DO NOTHING;

-- 2. Images de programme — programs.image_url (V37), déposant : l'hôte,
--    rejoint par user_activities (V5 -> V4). Voir l'écart 1 ci-dessus.
INSERT INTO media_files (path, uploaded_by, purpose)
SELECT substring(p.image_url FROM 18), ua.user_id, 'PROGRAM_IMAGE'
  FROM programs p
  JOIN user_activities ua ON ua.id = p.user_activity_id
 WHERE p.image_url LIKE '/api/media/files/%'
   AND length(substring(p.image_url FROM 18)) <= 300
ON CONFLICT DO NOTHING;

-- 3. Pièces jointes d'incident — incidents.attachment_url (V93), déposant
--    incidents.user_id (V90). Ce sont les lignes les plus sensibles de la
--    table : P-BS-11 réserve leur LECTURE à leur déposant.
INSERT INTO media_files (path, uploaded_by, purpose)
SELECT substring(i.attachment_url FROM 18), i.user_id, 'INCIDENT_ATTACHMENT'
  FROM incidents i
 WHERE i.attachment_url LIKE '/api/media/files/%'
   AND length(substring(i.attachment_url FROM 18)) <= 300
ON CONFLICT DO NOTHING;

-- 4. Photos de souvenir — attendances.memory_photo_url (V54), déposant
--    attendances.user_id (V41). Voir l'écart 2 ci-dessus.
INSERT INTO media_files (path, uploaded_by, purpose)
SELECT substring(a.memory_photo_url FROM 18), a.user_id, 'RECAP_PHOTO'
  FROM attendances a
 WHERE a.memory_photo_url LIKE '/api/media/files/%'
   AND length(substring(a.memory_photo_url FROM 18)) <= 300
ON CONFLICT DO NOTHING;

-- ============================================================
-- CE QUE LE RATTRAPAGE NE PEUT PAS RATTACHER — les orphelins assumés
--
--   activities.icon (V22) et activities.image_url (V38). La table activities
--   est un RÉFÉRENTIEL PARTAGÉ : elle n'a aucune colonne d'auteur (V3 :
--   parent_id, category_id, name, slug, description, embedding, created_at).
--   Une icône déposée par POST /activities/{id}/icon/upload n'a donc, pour les
--   fichiers déjà en place, aucun déposant connaissable — l'ancien code
--   passait l'activityId à store(), ce qui ne désigne personne. Ces fichiers
--   deviennent non supprimables par route, et c'est exactement la garantie que
--   demande l'étape 9 de la fiche : remplacer une icône ne doit pas effacer le
--   fichier d'un autre. Les icônes déposées APRÈS cette migration auront, elles,
--   leur ligne.
--
--   messages.image_url (V17) et program_media.url (V5). Les quatre seules
--   routes de DÉPÔT du serveur sont /api/media/upload/{image,avatar},
--   /programs/{id}/image/upload et /activities/{id}/icon/upload ; ces deux
--   colonnes reçoivent, elles, une URL fournie par le client et jamais
--   vérifiée (ChatService.uploadImage se contente de renvoyer la chaîne
--   reçue). Rattacher ces valeurs à messages.sender_id ou à un program_id
--   reviendrait à déclarer déposant quelqu'un qui a peut-être seulement recopié
--   le chemin d'un autre — précisément la confusion que cette table répare.
--   Ces deux surfaces réclament un attacher(...) à elles, hors de cette fiche.
--
--   Les fichiers présents sur le volume dont AUCUNE colonne ne porte plus
--   l'URL — un avatar remplacé avant le lot 0, une image de programme
--   détachée. Rien en base ne dit qui les a déposés ; ils resteront.
-- ============================================================
