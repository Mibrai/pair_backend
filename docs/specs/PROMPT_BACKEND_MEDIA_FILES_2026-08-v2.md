# Prompt à coller dans le Claude Code du dépôt backend

> **Relance de `BACKEND_MEDIA_500_PROMPT.md` (2026-07-22), toujours ouvert.** Ce
> document n'en répète pas le contenu : il apporte les mesures du 2026-08-11, qui
> **confirment sa première hypothèse** (fichier absent du volume de stockage) et
> ajoutent un second symptôme, plus grave parce qu'il empêche une action et pas
> seulement un affichage.

---

Deux symptômes, une seule cause probable : **les fichiers médias référencés en
base ne sont plus lisibles en production**.

## Mesure 1 — aucun média n'est lisible. Aucun.

Session mobile instrumentée du **2026-08-11, 09:22 → 09:27** (heure locale de
l'appareil, CEST = UTC+2), capturée par l'intercepteur de journal du client :

| `GET /api/media/files/**` | Nombre |
| --- | --- |
| Requêtes émises | **41** |
| Échecs | **41** |
| Succès | **0** |

Ce n'est donc pas un média corrompu isolé : sur cette session, **aucun fichier
uploadé n'a pu être relu**. Les avatars et les couvertures de programme ne
s'affichent nulle part dans l'app.

## Mesure 2 — la duplication d'un programme est impossible, et elle nomme la cause

```http
POST /api/programs/a1821526-7bc8-427f-910f-8f32f7cb1e82/duplicate
Authorization: Bearer <token valide>

→ 4xx  {"message":"File not found", …}
```

Deux tentatives, reproductibles, à 3 minutes d'intervalle :

| Horodatage local | `X-Request-Id` envoyé par le client | Durée |
| --- | --- | --- |
| 2026-08-11 09:23:20 | `6b979d51edb76727` | 1,30 s |
| 2026-08-11 09:26:15 | `19d74ff38b1c1840` | 1,39 s |

Le client envoie systématiquement un `X-Request-Id` : **ces deux identifiants
devraient permettre de retrouver la stacktrace côté serveur.** (C'est aussi
l'objet de la demande B8 du lot 7 — l'écho de cet en-tête dans la réponse.)

Ce que la réponse nous apprend, et qui manquait en juillet : le message est
**« File not found »**, cette fois sur un **4xx** et non un 500 masqué par le
`@ControllerAdvice`. Autrement dit, dans le chemin de duplication, l'absence du
fichier est *déjà* détectée et remontée telle quelle. C'est la même absence que
la lecture média transforme, elle, en `500 INTERNAL_ERROR`.

Comment nous savons qu'il s'agit d'un 4xx et pas d'un 5xx : le client choisit le
niveau d'alerte d'après le **type** d'exception (`noticeLevelForApiError`), et il
a affiché un niveau « attention », réservé aux refus `4xx`. Un 5xx aurait produit
un niveau « erreur » **et** notre propre libellé générique — l'utilisateur
n'aurait jamais vu le texte « File not found », qui ne peut venir que du serveur.

## Pourquoi ces deux symptômes sont le même bug

`POST /programs/{id}/duplicate` copie l'image **physiquement**, dans sa
transaction (contrat B4, `REPONSE_BACKEND_LOT7_2026-08.md`). Quand le fichier
source a disparu du stockage :

1. la copie du fichier échoue ;
2. la transaction étant tout-ou-rien, **aucune copie n'est créée** ;
3. l'auteur ne peut plus dupliquer **aucun** programme dont la couverture est
   référencée en base.

Et le piège qui nous a fait perdre du temps côté client, à noter : un programme
dont le fichier de couverture a disparu **ressemble, dans l'app, à un programme
sans couverture** — puisque la lecture du média échoue aussi, il n'y a rien à
afficher. Nous avons donc d'abord cru que la duplication échouait seulement sur
les programmes « avec image », avant de constater qu'elle échoue aussi sur ceux
qui *paraissent* en être dépourvus. Ils en ont une : c'est son fichier qui manque.

## Ta mission

1. **Retrouve la stacktrace** des deux `X-Request-Id` ci-dessus, et celle d'un
   `GET /api/media/files/**` de la même fenêtre. Confirme (ou infirme) que les
   deux butent sur la même absence de fichier.
2. **Rends le stockage persistant.** L'hypothèse n° 1 du prompt de juillet —
   volume Railway non monté ou réinitialisé à chaque redeploy — devient la plus
   probable : les uploads réussissent, la base garde l'URL, et les octets
   disparaissent. Un volume persistant monté au bon chemin, ou un stockage objet
   (S3/R2/GCS), selon ce que l'infra permet.
3. **Traite les références orphelines** que l'incident a laissées : une ligne qui
   pointe un fichier absent doit se résoudre proprement (image nulle) plutôt que
   de faire échouer chaque lecture — sinon la correction du stockage laissera
   derrière elle un parc de programmes indéfiniment cassés.
4. **Cesse de copier l'image, purement et simplement.** C'est la demande
   principale, et elle règle le bug comme effet secondaire.

   La raison n'est pas seulement le bug : c'est **le coût de stockage** (arbitrage
   produit du 2026-08-11). Copier l'image double les octets stockés pour une
   couverture que personne n'a choisie — un auteur qui duplique trois fois fait
   payer quatre fois le même fichier. La règle retenue côté mobile est donc :
   *une copie naît sans couverture, et son auteur en choisira une plus tard s'il
   le souhaite.* Aucun écran ne s'attend à ce qu'elle en ait une.

   L'app applique déjà cette règle par un détour, faute de pouvoir la demander
   (`DuplicateProgramRequest` n'a qu'un champ `title`) : elle appelle
   `DELETE /programs/{id}/image` sur la copie juste après. **Ce détour ne suffit
   pas pour l'objectif d'espace** — voir la question ci-dessous.

   Si la copie de fichier doit malgré tout être conservée, qu'elle sorte au moins
   de la transaction et devienne non bloquante (copie sans image + indication
   dans la réponse) : une image manquante ne doit jamais empêcher de copier des
   métadonnées et des créneaux.

   **Question, et elle décide de tout :** est-ce que
   `DELETE /programs/{id}/image` **efface le fichier sur le stockage**, ou
   vide-t-il seulement la référence en base ? Dans le second cas, notre
   contournement produit un **orphelin à chaque duplication** — des octets copiés
   que plus rien ne réclame —, soit l'inverse exact de l'économie recherchée. Si
   c'est le cas, dites-le nous : nous retirerions ce détour, et la seule issue
   serait que le serveur cesse de copier.
5. **Donne un `code` d'erreur** aux refus liés aux fichiers (par exemple
   `MEDIA_FILE_NOT_FOUND`). Aujourd'hui la réponse ne porte qu'un `message`
   anglais : le client, qui traduit par code, n'a d'autre choix que d'afficher
   « File not found » tel quel à un utilisateur francophone.
6. **Ajoute les tests qui manquaient**, dans le prolongement du point 4 du prompt
   de juillet :
   - dupliquer un programme dont le fichier de couverture est absent du stockage
     doit **réussir** (et rendre une copie sans image), pas échouer ;
   - la copie rendue par `POST /programs/{id}/duplicate` doit avoir
     `imageUrl == null`, et **aucun octet ne doit avoir été écrit** sur le
     stockage pour elle.

## Ce que le client fait déjà, pour éviter les doublons de travail

- La copie se voit retirer sa couverture juste après la duplication
  (`DELETE /programs/{id}/image`), et un échec de ce retrait n'annule pas la
  copie : il devient un simple avertissement à l'écran.
- Aucun contournement n'est possible côté mobile pour le bug lui-même : la
  transaction serveur ne produisant aucune copie, il n'y a rien à rattraper.
