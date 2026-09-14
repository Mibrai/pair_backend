# « Tout couper » : une transaction, cinq canaux, et ce que la coupure a révélé

**Date :** 2026-09-14
**Module :** [`tracabilite/`](.) — « Qui me voit »
**En réponse à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md) — P-MU-05, étapes 4 à 6 ; P-BL-05

> **En bref.**
>
> - **§2 — vos constats sont exacts, tous.** Nous les avons confirmés dans le code et par des tests.
>   Deux vont plus loin que ce que vous avez écrit (§4.2 et §4.5).
> - **§3.1 et §3.2 — `POST /api/users/me/visibility/cut-all` et `GET /api/users/me/visibility` sont
>   livrées** sous la forme que vous décrivez : une transaction, des booléens, aucun compte.
> - **§3.3 — le journal est un événement de compte**, relu par `GET …/visibility` (`recentCuts`).
> - **§3.4 — les deux corrections indépendantes sont livrées.** `deleteMessage` efface le point, et
>   `WatchDto` ne sert plus un lien révoqué (`publicLinkRevokedAt`).
> - **§4 — bloquer met fin aux partages de position** du fil `DIRECT`, dans les deux sens.
> - **§5.2 — non, `locationPublic` seul ne suffit pas.** La recherche de personnes par distance lit aussi
>   `showLocation` et `showOnMap`. La coupure éteint les trois.
> - **§5.5 — non, ce n'était pas voulu.** Corrigé ici, avec deux fuites de la même famille.

---

## 1. Le relevé, canal par canal

### 1.1 Statut live

Exact : `safety.liveAudience` est rangé, pas interprété, et aucune route ne sert un statut à autrui.

### 1.2 Affiches ⚠️

Exact. `AfficheService` ne lit aucune préférence, et une affiche déjà publiée restait visible après
« Couper mes affiches ». **Précision** : `publishedAt` n'est rafraîchi qu'à l'**ouverture** d'une
audience (`AfficheAudience.openness`). Une fermeture en masse ne rallume donc aucun anneau, et la
coupure le respecte.

### 1.3 Organisateur

Hors coupure, d'accord.

### 1.4 Liens de veille

Exact sur les trois points : 24 h après clôture, la veille close sort de `/watches/active`, et
`WatchDto` sert le jeton révoqué.

### 1.5 Positions relevées

Exact : `Watch` ne porte aucune coordonnée.

### 1.6 a — Position dans une conversation

Exact, correction du 14/09 comprise. **Le serveur reconnaît le message texte par votre marqueur**
`[meetdo:pos v1 lat=… lng=… exp=…]` (`LivePositionShare.encode`). Vous n'avez rien à ajouter : le
marqueur est déjà stable et versionné. **Ne le changez pas sans nous prévenir.** Un `v2` doit garder le
préfixe `[meetdo:pos `, ou le serveur ne le reconnaîtra plus.

Les pushes déjà remises ne se rappellent pas. `NEW_MESSAGE` est une push seule (`notifyPushOnly`) : le
texte n'est pas rangé dans `notifications`, mais la bannière reçue sur le téléphone reste ce qu'elle
était.

### 1.6 b — Présence sur la carte ⚠️

Exact. **Et plus large que vous ne l’écriviez** : voir §4.2 et §4.5.

## 2. Ce qui est livré

### 2.1 `POST /api/users/me/visibility/cut-all`

Authentifiée, sans corps, idempotente, **une seule transaction**. Si une étape échoue, rien n'a changé.

| Canal | Ce que la coupure fait |
|---|---|
| `AFFICHES` | toutes les affiches de l'appelant passent à `NOBODY`, **sans rafraîchir `publishedAt`** ; la préférence `affiche.audience` passe à `nobody`, la forme qu'écrit votre `AfficheAudience.toJson` |
| `WATCH_LINKS` | `publicTokenRevokedAt = now` sur toutes les veilles dont la page s'ouvrirait encore : jeton présent, non révoqué, veille non close **ou close depuis moins de 24 h**. **La veille elle-même continue** : rappels, escalade et SMS au proche ne sont pas touchés. Seule la page publique ferme |
| `CHAT_LOCATION` | (1) les points non échus posés par `POST /conversations/{id}/location` : coordonnées et échéance effacées, le message reste ; (2) les messages de l'appelant **porteurs du marqueur**, envoyés depuis moins de 30 min : traités comme une suppression, contenu remplacé par `[Message supprimé]`. Chaque message touché est diffusé sur `/queue/messages.edited` à tous les membres du fil |
| `MAP_PRESENCE` | `locationPublic`, `privacySettings.showLocation` **et** `privacySettings.showOnMap` passent à `false` (§5.2) |
| `LIVE_STATUS` | rien : aucun statut n'est servi |

Réponse `200` :

```json
{
  "cutAt": "2026-09-14T18:02:11.123Z",
  "channels": [
    {"channel": "AFFICHES",      "cut": true},
    {"channel": "WATCH_LINKS",   "cut": true},
    {"channel": "CHAT_LOCATION", "cut": true},
    {"channel": "MAP_PRESENCE",  "cut": true},
    {"channel": "LIVE_STATUS",   "cut": true}
  ]
}
```

- **`cut: false` n'existe pas aujourd'hui.** La transaction rend tout à `true`, ou échoue en `500`
  sans rien changer. Nous gardons le champ pour le jour où un canal ne pourra pas être coupé en base
  (un tiers, par exemple).
- **L'ordre de l'énumération est stable.** Nous pourrons ajouter des canaux, jamais en retirer
  (`VisibilityChannel`).
- **Rejouer la coupure** rend la même réponse, avec un nouveau `cutAt`, et ajoute une entrée au journal.
- **Aucun `404`** : un compte sans affiche ni veille reçoit `200`, tout à `true`.

**Pourquoi les messages passent en dernier.** Ce sont les seuls à diffuser en WebSocket pendant la
transaction. Tout ce qui peut échouer (base, préférences, journal) échoue donc avant la moindre
diffusion.

**Pourquoi « suppression » plutôt que « modification » pour le message texte.** Vous demandiez que le
lien disparaisse, et que le changement soit diffusé comme une modification. C'est le cas : il part sur
`/queue/messages.edited`, avec le `MessageDto` à jour. Côté serveur, le message est en plus marqué
supprimé (`deletedAt`), comme par `DELETE /api/messages/{id}`. Il n'est donc plus modifiable, et
l'historique ne garde pas le lien dans `message_edit_history`. Une modification ordinaire l'y aurait
recopié.

### 2.2 `GET /api/users/me/visibility`

```json
{
  "channels": [
    {"channel": "AFFICHES",      "open": false},
    {"channel": "WATCH_LINKS",   "open": true},
    {"channel": "CHAT_LOCATION", "open": false},
    {"channel": "MAP_PRESENCE",  "open": false},
    {"channel": "LIVE_STATUS",   "open": false}
  ],
  "recentCuts": ["2026-09-14T18:02:11.123Z"]
}
```

`open` vaut `true` si la coupure aurait encore quelque chose à fermer : une affiche hors `NOBODY`, un
lien de veille ouvrable, un partage non échu ou marqué depuis moins de 30 min, l'un des trois réglages
de présence. **Toujours des booléens.**

### 2.3 Le journal : un événement de compte

Réponse au §5.3. Une coupure n'appartient à aucune veille, et l'accrocher à la veille active la ferait
disparaître de l'historique de la personne qui coupe sans veille en cours. Chaque coupure écrit une
ligne `audit_logs` de type `VISIBILITY_CUT_ALL`, **dans la transaction** : pas de coupure sans journal,
pas de journal sans coupure. `GET …/visibility` rend les dix dernières dans `recentCuts`, de la plus
récente à la plus ancienne. Elles figurent aussi dans l'export RGPD.

### 2.4 Indépendamment de la route (§3.4)

1. **`DELETE /api/messages/{id}`** efface désormais `locationLat`, `locationLng` et
   `locationExpiresAt`. Le contenu était déjà remplacé par `[Message supprimé]`, lien de carte compris.
   Le « partage supprimé encore lisible 30 minutes » est corrigé.
2. **`WatchDto`** : une fois le lien révoqué, `publicToken` et `publicStatusUrl` valent `null`, et le
   nouveau champ **`publicLinkRevokedAt`** donne l'instant. Nous avons pris les deux options : la
   première empêche de montrer un lien mort, la seconde vous permet de dire « lien coupé à 18 h 02 »
   plutôt que « aucun lien ».

## 3. Avec P-BL-05 : bloquer met fin aux partages

`ChatBlockEffects` écoute le blocage **après son commit**, comme `SlotBlockEffects`. Il applique la
primitive du canal `CHAT_LOCATION` au fil `DIRECT` des deux personnes, **pour chacune des deux** :
points effacés, messages marqués supprimés et diffusés. Si cette étape échoue, le blocage tient quand
même : le point échoit de lui-même sous 30 minutes.

Les fils de groupe ne sont pas touchés : on ne retire pas à tout un groupe une position partagée à tout
le groupe parce que deux de ses membres se sont bloqués.

## 4. Les questions ouvertes

### 4.1 Affiches : `NOBODY`

D'accord. L'affiche reste chez son auteur et se rouvre d'un geste. La dépublication (`DELETE
/api/affiches/{id}`) reste un geste distinct.

### 4.2 La carte : `locationPublic` seul ne suffit pas

**Toutes les lectures qui servent une position de personne exigent `location_public = true`** :
`/map/users`, `/map/nearby/users`, les groupes de `/map/clusters`, `/map/nearby/activities`,
`/map/nearby/programs`, `/activities/suggested` et la nouvelle `/activities/practised-nearby`.

**Une lecture fait exception : la recherche de personnes** (`GET /api/users/search` avec `lat`, `lng`,
rayon). Elle retient une personne si **l'un des trois** réglages est vrai : `location_public`,
`show_location` ou `show_on_map` (`UserRepository.SEARCH_USERS_BODY`). Et elle filtre sur la **vraie**
position. Elle ne la rend pas, mais en déplaçant le centre et le rayon, on la retrouve. Avec
`locationPublic = false` seul, quelqu'un qui avait activé « Afficher sur la carte » dans l'écran de
confidentialité restait trouvable par distance. **La coupure éteint donc les trois.**

`visible_on_map` sur chaque activité n'est pas touché, et c'est inutile : aucune lecture ne l'emploie
sans exiger aussi l'un des trois réglages ci-dessus. Vous avez raison, on ne saurait pas restaurer ces
réglages.

### 4.3 Le journal

Voir §2.3.

### 4.4 Rouvrir : aucun obstacle

La coupure ne mémorise pas l'état d'avant. Rouvrir, c'est refaire chaque geste à son écran : publier
une affiche, rallumer la présence, partager une position.

### 4.5 La position non floutée : une fuite, corrigée

**Ce n'était pas voulu.** En relisant, nous en avons trouvé deux autres de la même famille :

| Route | Défaut | Corrigé |
|---|---|---|
| `GET /api/map/nearby/activities` | position **exacte** de la première personne qui pratique | floutée, par le rayon de flou de la personne, comme `/map/users` |
| `GET /api/map/nearby/programs` | position **exacte** du domicile déclaré de l'organisateur | floutée de même |
| `GET /api/map/clusters` (personnes) | un groupe d'**une** personne rendait son centre et ses bornes à 11 m près ; à fort zoom, la cellule de la grille en disait presque autant | le flou s'applique **avant** le regroupement, à la cellule comme aux bornes |

Reste ouvert, et hors de cette demande : `/map/nearby/activities` et `/map/nearby/programs`
n'appliquent pas le blocage, contrairement à `/map/users`. Nous en faisons une fiche.

## 5. Ce que nous vous suggérons

- **Le libellé « Tout couper maintenant »** peut s'allumer derrière `whoSeesMeServerCutAll`. Chaque
  section de la page a un canal, et chaque canal est coupable.
- **Lire `GET …/visibility` à l'ouverture de la page** plutôt que de supposer l'état, et après chaque
  coupure pour l'afficher.
- **Dire à la personne que la veille continue** quand `WATCH_LINKS` est coupé pendant une veille
  active : son proche est toujours prévenu, mais la page qu'il a peut-être ouverte ne s'affiche plus.
- **Garder le marqueur `[meetdo:pos v1 `** tel quel (§1.6 a).

## 6. Vérification

`ToutCouperIntegrationTest` (nouveau) rejoue votre §6 à deux comptes :

1. **Préparation.** Une affiche `EVERYONE` et une `SUBSCRIBERS`, une veille active et une veille close
   il y a deux heures, chacune avec un lien public. Dans un fil `DIRECT`, un partage par `/location`, un
   message au marqueur et un message ordinaire. Les trois réglages de présence sont allumés.
2. **Avant la coupure, depuis le second compte :** l'affiche se lit, et les deux pages de veille rendent
   `200`. `GET …/visibility` rend quatre canaux ouverts, et `LIVE_STATUS` fermé.
3. **La coupure** rend cinq canaux, dans l'ordre, tous à `cut: true`, sans autre champ.
4. **Après la coupure, depuis le second compte :**
   - plus aucune affiche, et `published_at` est inchangé ; la préférence vaut `nobody` ;
   - les deux pages de veille rendent `404` ;
   - aucun message ne porte de coordonnées ; le message au marqueur ne contient plus le lien, et le
     message ordinaire est intact ;
   - les trois réglages de présence sont à `false`.
5. **L'état et le rejeu :** `GET …/visibility` rend tout à `open: false` et une coupure au journal.
   Rejouer rend tout à `true`, et deux coupures au journal.

Et à part :

- un compte vide reçoit `200`, tout à `true` ; `401` sans session sur les deux routes ;
- `DELETE /api/messages/{id}` sur un partage de position efface le point en base et dans la lecture du
  fil ;
- bloquer efface les points **des deux personnes** et le message au marqueur de l'autre ;
- après `revoke-link`, `GET /api/watches/{id}` ne rend plus ni `watch.publicToken` ni `watch.publicStatusUrl`, et
  rend `watch.publicLinkRevokedAt`.
