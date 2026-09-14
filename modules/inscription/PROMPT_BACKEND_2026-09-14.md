# Quatre questions restées ouvertes : les inscrits vus par l'hôte, et la recherche de personnes

**Date :** 2026-09-14
**Module :** `inscription` — deux des quatre questions portent sur ce que l'hôte et les inscrits
voient de la liste des inscrits, dont le dernier échange est ici (13/09) ; les deux autres viennent
de `mon-cercle/`, cité à chaque fois
**Audit :** P-BL-05 (blocage), P-MU-05 (« qui me voit »), P-MU-28 (P2b)
**Décision de l'utilisateur, 14/09/2026 :** relancer en **un seul document** les questions laissées
sans suite, plutôt que quatre relances dans quatre dossiers.

> **Pourquoi ce module.** Les questions (a) et (b) touchent la même surface : la liste des inscrits
> d'un créneau. Vous nous avez recommandé le 03/09 d'en faire « la seule surface » côté hôte, et
> vous l'avez remaniée le 13/09 (`/co-participants`) et le 14/09 (comptes fermés). Les questions (c)
> et (d) portent sur `GET /api/users?query=`, la recherche du module `mon-cercle`.
>
> **Ce qui a été vérifié avant d'écrire** : aucune des réponses du 14/09 (`badges`, `connexion`,
> `creneau-modifiable` et `-BIS`, `rappel`, `tracabilite`, `verification-email`) ne tranche l'une
> des quatre. La réponse `tracabilite` du 14/09 (§4.2) **touche** la question (d) sans la trancher :
> elle est gardée, et citée.
>
> **Ce qui vous appartient** : les quatre sont des changements de contrat ou de requête chez vous.
> Pour (a) et (b), c'est vous qui nous aviez renvoyé la décision : nous disons où nous penchons, et
> ce qu'il nous faut pour la prendre.

---

## 1. Relevé du 14/09/2026, 13 h 43 (heure de Paris)

Contrat `/v3/api-docs` de production (220 chemins, `build.time` `2026-09-14T11:39:59Z`) et clone
du serveur au commit `e09e9f2`, qui est très probablement ce qui tourne. **Aucune lecture HTTP
authentifiée n'a été rejouée** : ce relevé repose sur le contrat et le code.

### (a) « Vu par l'hôte » s'adresse encore par `watchId`

**Posée** par vous le 03/09 : `tracabilite/REPONSE_BACKEND_2026-09-03.md`, §3 (l. 212-232) et
récapitulatif l. 386, « **`seen-by-host` par `participationId`** — recommandation, votre décision ».
**Jamais répondue** : `tracabilite/SUITE_CLIENT_2026-09-03.md` ne reprend que le point 11 (« ne vaut
pas déclaration »).

État au 14/09 :
- `WatchController.java:88-97` : `POST /api/watches/{id}/seen-by-host`, `{id}` = la veille ;
  `204`, et `409 WATCH_NOT_OUTBOUND` hors `ARMED`/`EN_ROUTE` (`WatchService.java:404-421`).
- Côté app, le bouton « Je la vois » n'existe que devant les lignes de
  `GET /schedules/{id}/pending-arrivals` (`HostArrivalsController.java:36`), c'est-à-dire devant les
  seules personnes qui ont armé une veille (`slot_detail_page.dart:1156-1160`,
  `watch_providers.dart:239-244`).
- **Le défaut que vous décriviez tient donc toujours** : un hôte apprend qui se protège en regardant
  quels boutons son écran affiche. Et le `409` distingue en plus une veille close d'une veille en
  cours.

### (b) `GET /slots/{id}/participants` : `403` nommé, `404`, et ce que dit `/co-participants`

**Posée** par vous le 03/09 : même document, l. 234-238 et récapitulatif l. 387, « **`participants`
en 404 plutôt qu'en 403** — votre décision ». **Rendue plus pressante** le 13/09, quand
`/co-participants` a choisi l'autre voie (`REPONSE_BACKEND_2026-09-13.md` de ce module, §2).

État au 14/09 :

| Route | Appelant refusé | Réponse | Code |
|---|---|---|---|
| `GET /slots/{id}/participants` | tout non-hôte, **bloqué ou non** | `403 SLOT_PARTICIPANTS_HOST_ONLY` | `SlotService.java:683-690` |
| `GET /slots/{id}/co-participants` | hôte bloqué avec l'appelant | `404` | `SlotService.java:735-738` |
| `GET /slots/{id}/co-participants` | non-inscrit, `WAITLISTED`, `WITHDRAWN` | `403 SLOT_PARTICIPANTS_ENROLLED_ONLY` | `SlotService.java:739-743` |
| `GET /slots/{id}` | hôte bloqué, ou compte de l'hôte fermé | `404` « Créneau introuvable » | `creneau-modifiable/REPONSE_BACKEND_2026-09-14-BIS.md`, §3 |
| `GET /schedules/{id}/pending-arrivals`, `seen-by-host` | non-organisateur | `404` | `HostArrivalsController.java:37-38`, `WatchService.java:409-411` |

**Ce que nous avons trouvé en relisant** : `getParticipants` ne regarde **ni le blocage ni le compte
de l'hôte avant** le contrôle d'hôte. Une personne bloquée par l'hôte, ou tout appelant sur le
créneau d'un compte fermé, reçoit `403 SLOT_PARTICIPANTS_HOST_ONLY` là où la fiche du même créneau
rend `404`. Le `403` confirme l'existence d'un créneau que la fiche dit introuvable.

Côté app, les deux `403` sont traduits (`exceptions.dart:673` et `:682`) ; un `404` s'y traite
comme « pas de donnée ».

### (c) La bio d'un profil privé reste cherchable

**Posée** par vous le 04/09 : `mon-cercle/REPONSE_BACKEND_2026-09-04.md`, §4 (l. 130-134) et
récapitulatif l. 173, « **La bio indexée sur un profil privé** — signalé, en attente de votre
avis ». **Jamais répondue** : ni `mon-cercle/SUITE_CLIENT_2026-09-04.md`, ni
`mon-cercle/REPONSE_BACKEND_2026-09-13.md` n'en parlent.

État au 14/09 :
- `UserRepository.java:231` : `unaccent(LOWER(u.bio)) LIKE …`, sans condition de visibilité.
- `UserService.java:504-509` et `:547` : la bio n'est **rendue** que si `profileVisibility` vaut
  `PUBLIC`, ou `FRIENDS` et que l'appelant est abonné.
- **Donc** un profil `PRIVATE` (ou `FRIENDS`, pour un non-abonné) remonte sur un mot de sa bio que
  l'appelant ne verra jamais. En essayant des mots, on lit la bio par oui ou non.

### (d) La recherche de personnes avec une position : un filtre de 50 km, pas un tri

**Posée** par nous le 04/09 : `mon-cercle/SUITE_CLIENT_2026-09-04.md`, §5 (l. 111-137), « soit que
`latitude`/`longitude` trient sans filtrer, soit qu'ils soient retirés de la route ». **Jamais
répondue.**

État au 14/09 :
- **La route est désormais au contrat** : `GET /api/users` avec `query`, `latitude`, `longitude`,
  `page`, `size`, tous facultatifs. Le paramètre ne porte aucune description.
- `UserService.java:405-406` fixe un rayon de **50 km**, et `UserRepository.java:241-245` **filtre**
  par `ST_DWithin` sur la **vraie** position `u.location` dès que les deux coordonnées sont là.
- **Une personne sans position enregistrée** (`u.location` nul) ne passe jamais ce filtre : dès
  qu'un point est envoyé, elle disparaît, même cherchée par son nom exact.
- Votre réponse `tracabilite/REPONSE_BACKEND_2026-09-14.md`, §4.2, le décrit sous le nom
  `GET /api/users/search`, qui n'existe pas au contrat : c'est cette route-ci. Elle y relève que la
  recherche « filtre sur la vraie position » et qu'« en déplaçant le centre et le rayon, on la
  retrouve ». La coupure « tout couper » éteint les trois réglages de présence, mais **la route reste
  une sonde** pour quiconque les a allumés.

**Une correction à notre document du 04/09**, que nous vous devons. Nous avions écrit « Berlin, où
elles vivent » et conclu à « un filtre qui n'apparie rien ». D'après la graine, Lena Müller est à
Munich (`V27__reset_and_seed_germany.sql:71`) et Anna Müller à Münster
(`V28__extend_seed_data.sql:151`), à plus de 50 km de Berlin : le `[]` s'expliquait par le rayon,
pas par un filtre cassé. Le défaut reste le même pour l'utilisateur (un paramètre facultatif,
non documenté, qui vide la réponse), mais notre diagnostic était faux.

L'app n'envoie aucune position depuis le 04/09 (`circle_find_repository.dart:16-25`, `:67-68`,
tenu par `test/circle_find/circle_find_repository_test.dart`).

## 2. La demande

1. **(a) — dites-nous si l'offre du 03/09 tient**, et sous quelle forme : `seen-by-host` adressé par
   `participationId` (par exemple `POST /api/schedules/{scheduleId}/participants/{participationId}/seen`),
   **`202` pour tout inscrit `CONFIRMED` du créneau de l'hôte**, qu'il ait armé ou non, sans effet
   quand il n'y a pas de relance à repousser, et **jamais `409`** ; `404` hors de ses créneaux. Nous
   penchons pour l'accepter : c'est la seule façon que `NONE` ne dise vraiment rien. Il nous faut
   de vous : le chemin retenu, et combien de temps l'ancienne forme par `watchId` reste servie pour
   les versions installées.
2. **(b) — une règle unique pour les deux listes**, que nous vous proposons ainsi :
   - **« introuvable » d'abord** : si la fiche du créneau rend `404` à l'appelant (hôte bloqué dans
     un sens ou dans l'autre, compte de l'hôte fermé, créneau inexistant), `/participants` et
     `/co-participants` rendent **`404`**, avant tout contrôle de rôle ;
   - **sinon, le `403` nommé reste** sur les deux routes (`SLOT_PARTICIPANTS_HOST_ONLY`,
     `SLOT_PARTICIPANTS_ENROLLED_ONLY`) : la fiche étant lisible, il ne révèle rien, et l'app sait
     le dire.
   Nous penchons pour cette règle plutôt que pour le `404` partout : la donnée de veille que porte
   `/participants` (`arrival`) n'atteint jamais un non-hôte, qui est refusé avant toute lecture ; ce
   que le `403` lui apprend (« tu n'es pas l'hôte ») il le sait déjà ; et un `404` sur un créneau
   dont il vient de lire la fiche ferait croire à une panne. Si vous tenez au `404` partout, dites pourquoi :
   c'est une rupture de contrat que nous prendrons derrière un drapeau.
   Dans les deux cas, **le cas du blocage est un défaut à corriger** (§1 b).
3. **(c) — la bio sort de l'index** quand elle ne serait pas rendue à l'appelant : même règle que
   `UserService.java:508-509`, écrite une fois (`PUBLIC`, ou `FRIENDS` et abonné). Le nom affiché et
   les titres de programmes publics restent cherchables. Un test : un profil `PRIVATE` dont la bio
   contient un mot unique ne remonte pas sur ce mot, et remonte toujours sur son nom.
4. **(d) — retirer `latitude` et `longitude` de `GET /api/users`**, ce que nous préférons : un nom
   n'a pas de position, l'app ne les envoie plus, et la route cesse d'être une sonde de position. Si
   un autre client en a besoin, alors **un tri sans filtre**, les personnes sans position en fin de
   liste, et le rayon documenté au contrat. Dans les deux cas, un test : cherchée par son nom exact,
   une personne sans position remonte.

## 3. Côté app, ensuite

- **(a)** derrière un drapeau éteint, `hostSeenByParticipation`, par le constructeur de la fiche
  créneau : le bouton « Je la vois » passe devant **chaque** inscrit de `/participants`, jamais
  seulement devant les lignes de `pending-arrivals`. `pending-arrivals` cesse d'alimenter l'écran.
  Les deux branches testées, bascule dans un commit séparé.
- **(b)** si la règle proposée est retenue, rien à coder : un `404` sur ces listes est déjà « pas de
  donnée ». Un test de dépôt fige les deux codes nommés et le `404`.
- **(c)** rien à coder.
- **(d)** rien à coder ; le cartouche de `circle_find_repository.dart` et le document
  `mon-cercle/SUITE_CLIENT_2026-09-04.md` seront corrigés sur place (Berlin, le rayon de 50 km).

## 4. Comment nous vérifierons

- **Relevé** `/v3/api-docs` : (a) la nouvelle route et ses réponses, sans `409` ; (b) les
  descriptions des deux listes portent le `404` d'abord ; (d) `GET /api/users` sans `latitude` ni
  `longitude`, ou avec un rayon décrit.
- **HTTP réel avec les deux comptes de test**, sur
  un état remis en place ensuite :
  - (a) l'hôte appelle « vu » sur un inscrit **sans** veille : `202`, rien ne change ; sur un inscrit
    avec veille armée : `202`, la relance est repoussée ;
  - (b) le second compte bloque l'hôte : la fiche, `/participants` et `/co-participants` rendent
    tous `404` ; débloqué et non inscrit : `403` nommés ;
  - (c) un profil passé `PRIVATE`, cherché sur un mot de sa bio : absent ; sur son nom : présent ;
    remis `PUBLIC` ensuite ;
  - (d) `GET /api/users?query=muller&latitude=52.52&longitude=13.40` rend les mêmes personnes que
    sans point ; et une personne sans position remonte sur son nom exact.
