# « Tout couper » : ce que l'app ne peut pas couper seule

**Date :** 2026-09-14
**Module :** `tracabilite` (rouvert : l'écran B4 « Qui me voit » est à lui)
**Audit :** P-MU-05, étapes 4 à 6 (`audit/PLAN_MOBILE_UX_DESIGN_2026-09-11.md`) ; à coordonner
avec P-BL-05 (`audit/PLAN_BACKEND_LOGIQUE_METIER_2026-09-11.md`)

> **Ce que l'app a déjà fait, sans vous** (commit `acd0c7c`, 12/09). Le bouton du bas de
> « Qui me voit » ne dit plus « Tout couper maintenant » mais « Couper mes affiches ». Chaque
> section qu'il ne coupe pas renvoie à la veille, où le geste existe. Un échec s'affiche au lieu
> d'être avalé. Le libellé « Tout couper maintenant » est traduit et attend, derrière un drapeau
> à écrire éteint (`whoSeesMeServerCutAll`).
>
> **Ce qui vous appartient** : une coupure **atomique** de tout ce par quoi quelqu'un peut
> observer la personne, et l'état de chaque canal pour que la page dise vrai. En relisant votre
> code pour écrire cette demande, nous avons trouvé que **même le canal que nous croyions couper
> ne l'est pas** (§2.2).

---

## 1. Le produit, en une phrase

« Rien ne peut observer quelqu'un sans figurer dans *Qui me voit* — la page est exhaustive, et
un bouton en bas coupe tout. » La personne qui appuie se sent suivie. Un bouton qui coupe
partiellement en disant « c'est coupé » est le pire mensonge de l'app, parce qu'il rassure.

## 2. Relevé du 14/09/2026, canal par canal

Contrat `/v3/api-docs` de production (217 chemins) et clone du serveur au commit `9908a69`
(13/09, 22:42). `GET /actuator/info` rend un `build.time` de `2026-09-13T20:42:30Z`, l'heure de ce
commit. Aucun chemin `cut-all`, ni rien d'approchant, n'est au contrat.

Nous n'avons **pas** pu rejouer ces constats en HTTP authentifié : la lecture avec le compte de
test n'a pas été autorisée de notre côté ce jour-là. Les §2.2 et §2.5 reposent donc sur la
lecture du code, et sont à confirmer chez vous.

### 2.1 Le statut live, ami par ami — le serveur ne le sert pas

- L'app range l'audience choisie dans `PUT /api/users/me/preferences/safety.liveAudience`
  (`live_visibility_store.dart:43`). La liste d'amis est rangée de la même façon.
- `UserPreferenceController.java:55` : « le serveur ne l'interprète pas ». Aucune route ne rend
  le statut d'une personne à une autre (aucun contrôleur ne lit `friend`, `starred` ni `live`).
- **Rien à couper chez vous aujourd'hui.** Le jour où un statut live sera servi, il entre dans la
  coupure du §3.

### 2.2 Les affiches — la préférence n'est pas la portée ⚠️

- « Couper mes affiches » écrit `PUT /api/users/me/preferences/affiche.audience` = `nobody`
  (`affiche_audience_store.dart:51,88-89` ; `who_sees_me_page.dart:373-384`).
- Or la portée **opposable** est une colonne **par affiche** : `affiches.audience`
  (`V104__affiches.sql:61,72-73`), fixée par `PUT /api/affiches/{scheduleId}`
  (`AfficheController.java:44-62`) et appliquée par `AfficheService.forUser` (`:182-199`) et
  `/affiches/updates`.
- `AfficheAudience.java:17-23` le dit : la préférence n'est pas cette portée. `AfficheService` ne
  lit aucune préférence.
- **Conséquence** : une affiche déjà publiée pour `SUBSCRIBERS` ou `EVERYONE` **reste visible**
  après le geste, que l'écran annonce comme fait (« C'est coupé. »). La préférence ne règle que
  la portée proposée à la prochaine publication.
- L'app **pourrait** le faire seule : lire `GET /api/users/{moi}/affiches`, puis republier
  chacune en `NOBODY` avec son motif et son `slotStartedAt` (une fermeture ne rafraîchit pas
  `publishedAt`, `AfficheService.java:144-148`). Mais ce serait N requêtes, non atomiques, et un
  échec au milieu laisserait une coupure partielle.

### 2.3 Ce que voit l'organisateur — une règle, pas un canal

L'arrivée, et rien d'autre (`HostArrivalsController`). La page l'affiche avec un cadenas. Hors
coupure, et c'est voulu.

### 2.4 Les liens hors meetDo — coupables un par un, et pas tous trouvables

- `POST /api/watches/{id}/revoke-link` (`WatchController.java:253-261`, « tous états ») pose
  `publicTokenRevokedAt` (`WatchService.java:560-565`).
- Mais le lien public **reste ouvert 24 h après la clôture** (`PublicWatchService.java:38`,
  `:106-117`).
- Or une veille `CLOSED` ou `RESOLVED` **sort de `GET /api/watches/active`** : seules
  `NOT_ARRIVED` et `NO_CONTACT` y restent un jour (`WatchService.java:109-110`,
  `WatchRepository.java:49-60`). Pour la retrouver, l'app devrait croiser `/watches/history`.
- `WatchDto` ne dit pas si le lien est révoqué : `publicToken` et `publicStatusUrl` restent servis
  après révocation (`WatchDto.java:67-72,97-99` ; contrat : aucun champ de révocation). La page
  ne peut donc pas afficher « aucun lien actif » sans le supposer.

### 2.5 Les positions relevées — rien n'est stocké

`Watch.java` ne porte aucune coordonnée. Le drapeau app `watchLocationPoints` est éteint par
décision produit (décision 2 du module). Rien à couper tant que cela tient.

### 2.6 Deux canaux qui observent et que la page ne liste pas

Ils ne sont pas dans la fiche P-MU-05. Nous les ajoutons côté app (la page doit être exhaustive),
et nous les demandons dans la coupure.

**a. La position partagée dans une conversation.**

> ⚠️ **Correction du 14/09/2026, après relecture du code de l'app.** L'app n'appelle **pas**
> `POST /api/conversations/{id}/location` : elle envoie le point **dans le texte d'un message
> ordinaire** (un lien de carte), et les « 30 minutes » ne sont qu'une règle d'affichage de l'app.
> Le message — lien compris — reste donc sur le serveur **et** dans la notification reçue, et
> aucun écran n'appelle `DELETE /api/messages/{id}`. Ce qui suit sur la route `/location` reste
> vrai pour un autre client, mais **ce qu'il faut couper pour meetDo, c'est le contenu du
> message** : voir §3.1 et §3.4, corrigés.

- Route existante, non utilisée par l'app : `POST /api/conversations/{id}/location` (`ChatController.java:183`) : un point servi 30 min au
  plus (`ShareLocationRequest.java:11-32`, `ChatService.java:482-510`).
- **Aucune route n'y met fin avant l'échéance.**
- Supprimer le message ne l'arrête pas : `deleteMessage` (`ChatService.java:1190-1219`) pose
  `deletedAt` et remplace le contenu, sans toucher `locationLat`, `locationLng` ni
  `locationExpiresAt`.
- `findLatest` (`MessageRepository.java:28-30`) ne filtre pas `deletedAt`, et `toMessageDto`
  (`ChatService.java:1252-1273`) sert les coordonnées tant que l'échéance n'est pas passée.
- **Un partage « supprimé » reste donc lisible jusqu'à 30 min.** C'est à corriger, même sans
  la route du §3.
- Et bloquer quelqu'un n'efface pas un partage déjà envoyé : `BlockService` ne touche aucune
  position (voir §4).

**b. La présence sur la carte.**
- `GET /api/map/users` et `GET /api/map/nearby/users` (`MapController.java:26,55`) rendent les
  comptes `location_public` (`UserRepository.java:35`), position floutée (`MapService.java:84-95`).
- `GET /api/map/nearby/activities` (`MapService.java:511-535`) rend, pour une activité
  `visible_on_map`, la position **non floutée** de la personne (`:533-534`, pas d'`applyBlur`).
- Les interrupteurs existent (`PUT /api/users/me` `locationPublic`, `UserService.java:190-191` ;
  `PUT /api/users/me/privacy` `showOnMap`, `:362-363` ; `PATCH
  /api/users/me/activities/{id}/visibility`), mais ils sont dispersés.

## 3. La demande

### 3.1 `POST /api/users/me/visibility/cut-all`

Authentifiée, sans corps, **idempotente** et **atomique** : une transaction. Si elle échoue,
rien n'a changé.

| Canal (`channel`) | Ce que la coupure fait |
|---|---|
| `AFFICHES` | toutes les affiches de l'appelant passent à `NOBODY`, sans rafraîchir `publishedAt` ; la préférence `affiche.audience` passe à `NOBODY` |
| `WATCH_LINKS` | `publicTokenRevokedAt = now` sur **toutes** ses veilles au lien encore ouvrable, closes depuis moins de 24 h comprises |
| `CHAT_LOCATION` | ses messages porteurs d'une position envoyés depuis moins de 30 min voient leur **contenu** remplacé (le lien de carte disparaît), comme une suppression, et le changement est diffusé comme une modification de message ; les partages par la route `/location`, s'il en existe, sont échus (coordonnées et échéance effacées). Dites-nous comment vous reconnaissez un message porteur d'une position envoyé en texte — l'app peut le marquer si c'est nécessaire |
| `MAP_PRESENCE` | `locationPublic = false` (voir la question §5.2 pour `visible_on_map`) |
| `LIVE_STATUS` | rien aujourd'hui (§2.1) ; le canal figure dans la réponse pour que l'app n'ait pas à le deviner le jour où il existera |

**Réponse `200`**, sans aucun compte :

```json
{
  "cutAt": "2026-09-14T18:02:11Z",
  "channels": [
    {"channel": "AFFICHES",      "cut": true},
    {"channel": "WATCH_LINKS",   "cut": true},
    {"channel": "CHAT_LOCATION", "cut": true},
    {"channel": "MAP_PRESENCE",  "cut": true},
    {"channel": "LIVE_STATUS",   "cut": true}
  ]
}
```

- `cut: true` veut dire « plus rien ne passe par ce canal », qu'il ait été ouvert ou non.
- Un canal que le serveur ne sait pas couper rend `cut: false` : l'app affichera « Coupé, sauf :
  … ». Mais une transaction atomique ne devrait jamais en
  produire. Si ce cas existe, dites-nous lequel.
- Un canal inconnu de l'app sera affiché sous son nom brut, jamais passé sous silence. Vous
  pouvez en ajouter sans prévenir, mais n'en retirez jamais.
- Refus : `401` sans session. Aucun `404` : une personne sans affiche ni veille reçoit `200` et
  tous les canaux à `true`.

### 3.2 `GET /api/users/me/visibility`

Même forme, `{"channel": "…", "open": bool}`, pour que la page affiche l'état réel de chaque
section au lieu de le supposer (§2.4). Toujours des booléens : « un lien actif », jamais « 2
liens ».

### 3.3 Un journal lisible

Chaque coupure écrit un événement daté que la personne relit dans son historique. Où ? Voir la
question §5.3.

### 3.4 Indépendamment de la route

1. `deleteMessage` efface les coordonnées d'un partage de position (§2.6 a) ; et une suppression
   de message efface aussi, côté serveur, le texte qui portait le lien de carte (c'est la voie que
   l'app emprunte).
2. `WatchDto` expose la révocation : `publicToken` et `publicStatusUrl` nuls une fois révoqués,
   ou un champ `publicLinkRevokedAt`.

## 4. Avec P-BL-05

P-BL-05 est livré dans votre commit `149501d` (13/09) : un bloqué ne peut plus écrire ni partager
sa position dans un fil commun (`ChatService.java:197`, appelé par `shareLocation`).

Ce qui reste, et qui partage la même primitive : **un partage de position envoyé avant le
blocage** reste servi à la personne bloquée jusqu'à son échéance, `BlockService` ne touchant
aucune position. La primitive « échoir mes partages non échus » du canal `CHAT_LOCATION` devrait
aussi être appelée par `block`, dans les deux sens, sur le fil `DIRECT` des deux personnes.

## 5. Les questions de contrat ouvertes

1. **Affiches : `NOBODY` ou dépublication ?** Nous demandons `NOBODY` : l'affiche reste chez son
   auteur et se rouvre d'un geste. Une dépublication détruirait ce que la personne a composé.
2. **La carte : `locationPublic` seul, ou aussi `visible_on_map` sur chaque activité ?**
   `locationPublic = false` suffit à sortir de `findVisibleUsersInRadius` et de
   `UserActivityRepository.findVisibleInRadius`, sans effacer des réglages d'activité qu'on ne
   saurait pas restaurer. Confirmez qu'aucun autre chemin ne sert la position sans cette
   condition.
3. **Le journal.** L'historique de traçabilité (`/watches/history`) est par veille. Une coupure
   n'appartient à aucune veille. Préférez-vous un événement de compte, ou l'ajouter à la veille
   active s'il y en a une ?
4. **Et « rouvrir » ?** La coupure ne mémorise pas l'état d'avant, et c'est voulu : rien ne se
   rouvre d'un geste que la personne ne ferait pas en connaissance de cause. Dites-nous si vous y
   voyez un obstacle.
5. **Le §2.6 b, position non floutée** sur `/map/nearby/activities` : est-ce voulu ? Sinon, cela
   relève d'une fiche de sécurité à ouvrir chez vous, hors de cette demande.

## 6. Comment nous vérifierons

- **Relevé** `/v3/api-docs` : les deux routes, l'énumération `channel`, aucun champ entier.
- **HTTP réel à deux comptes**, sur un compte de test qu'on remet en état ensuite :
  1. publier une affiche en `SUBSCRIBERS`, armer une veille jusqu'au lien public, partager une
     position dans un fil ;
  2. vérifier depuis le second compte que les trois se voient ;
  3. `POST /users/me/visibility/cut-all` ;
  4. relire depuis le second compte : `GET /users/{id}/affiches` vide, `/public/watch/{token}` en
     `404`, `locationLat` nul, absent de `/map/users` ;
  5. `GET /users/me/visibility` tout à `open: false` ; rejouer la coupure, même réponse.
- **Côté app**, écrit éteint : `WhoSeesMeRepository(this._dio, {bool? serverCutAll})` et
  `WhoSeesMePage({this.serverCutAll = FeatureFlags.whoSeesMeServerCutAll})`, les deux branches
  testées, et le test déclaratif « le mot *tout* n'apparaît dans le libellé que si chaque section
  listée est coupable ». Bascule dans un commit séparé. Le drapeau reste : si la route régresse,
  le bouton redevient « Couper mes affiches ».
- **D'ici là**, le §2.2 se corrige sans vous, et c'est à nous de le décider : « Couper mes
  affiches » peut republier en `NOBODY` chaque affiche déjà publiée, et dire laquelle a échoué.
  Ce palliatif n'enlève rien à la demande : il n'est pas atomique.
