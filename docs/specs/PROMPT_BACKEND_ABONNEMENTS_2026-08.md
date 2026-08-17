# Demande backend — abonnements : les rendre visibles, puis supportables

**Date** : 2026-08-17
**Demandeur** : chantier frontend Flutter (`pair_mobile`)
**Statut** : non bloquant pour l'existant, bloquant pour toute suite. Les abonnements
(migration V36) sont livrés et fonctionnels des deux côtés ; ce qui manque n'est pas
un correctif, c'est ce qui transforme un tuyau de notification en fonctionnalité.

---

## Pourquoi ce document existe

Trois types d'abonnement existent — `AUTHOR`, `USER_ACTIVITY`, `CATEGORY` — servis par
`POST|DELETE /users/{id}/subscription`, `/user-activities/{id}/subscription`,
`/categories/{id}/subscription`, et lus par `GET /users/me/subscriptions`. Le frontend les
expose partout où c'est pertinent : fiche programme, fiche activité, fiche de la carte,
carte de l'Explorer, et une page `/subscriptions` qui liste le tout.

Le constat, après usage : **l'abonnement est invisible des deux côtés**.

- L'abonné s'abonne et ne peut rien régler ensuite. Le seul recours contre le bruit est le
  désabonnement — on perd le lien alors que la personne voulait seulement moins de bruit.
- L'auteur ne sait pas qu'il a des abonnés. Aucun compteur, nulle part, sur aucun DTO.
  Personne ne s'abonne à un compte dont l'audience est invisible, et aucun organisateur ne
  se donne du mal pour une audience qu'il ne voit pas.
- Une notification n'indique pas **de quel abonnement** elle provient. L'utilisateur qui la
  reçoit ne peut ni comprendre ni couper la source.

Ce document demande deux lots. Le lot 1 rend l'abonnement visible ; le lot 2 le rend
supportable. Ils sont indépendants et peuvent se livrer dans cet ordre, mais le §1.3
(déduplication) devrait accompagner le lot 1 : c'est le défaut le plus visible aujourd'hui.

---

# Lot 1 — rendre l'abonnement visible

## 1.1 Compteurs d'abonnés, et l'état d'abonnement sur les DTO

### Le compteur

Aucun DTO ne dit combien de personnes suivent quoi que ce soit. Nous demandons un entier,
sur les trois cibles :

| DTO | Champ | Sens |
|---|---|---|
| `UserDto` / profil public (`GET /users/{id}`, `GET /users/me`) | `subscriberCount` | nombre d'abonnés `AUTHOR` |
| `BrowsedActivity` (`GET /activities/browse`) et la fiche d'activité | `subscriberCount` | nombre d'abonnés `USER_ACTIVITY` |
| `CategoryDto` (`GET /categories`) | `subscriberCount` | nombre d'abonnés `CATEGORY` |

Un compte, pas une liste : c'est ce qui s'affiche sous un nom d'auteur ou sur une carte
d'activité, et c'est bon marché à maintenir (compteur dénormalisé ou `COUNT` indexé sur
`(type, target_id)`).

### L'état d'abonnement — le point qui compte le plus

Aujourd'hui, pour savoir si le bouton doit dire « S'abonner » ou « Abonné », le client
charge **l'intégralité** de `GET /users/me/subscriptions` au démarrage et cherche la cible
dans cette liste en mémoire (`isSubscribedProvider`). Cela marche à dix abonnements. Cela ne
marchera pas à deux cents, et surtout cela **interdit de paginer cette route** (§2.4) : le
jour où elle rend une page, tous les boutons hors première page basculent à tort sur
« S'abonner », et un second clic tentera un `POST` déjà existant.

Nous demandons donc, sur les mêmes DTO que ci-dessus :

```
subscribed : boolean   // l'appelant est-il abonné à cette cible ?
```

Ces deux champs vont ensemble et devraient se livrer ensemble : le booléen est ce qui
rend la pagination possible, le compteur est ce qui rend l'abonnement désirable.

## 1.2 La liste des abonnés, pour l'auteur seul

```
GET /api/users/me/subscribers?page=&size=&type=
    200 → Page<SubscriberDto>
    type (optionnel) : AUTHOR | USER_ACTIVITY | CATEGORY — filtre sur le type
                       d'abonnement par lequel la personne est arrivée
```

```
SubscriberDto {
  userId       : string
  displayName  : string
  avatarUrl    : string | null
  type         : "AUTHOR" | "USER_ACTIVITY" | "CATEGORY"
  targetId     : string          // l'activité ou la catégorie concernée, null si AUTHOR
  targetName   : string | null
  subscribedAt : string          // ISO 8601 UTC
}
```

Deux points à trancher côté serveur, et à documenter :

- **Visibilité.** Cette liste est celle de l'appelant, et de personne d'autre. Pas de
  `GET /users/{id}/subscribers` : savoir qui suit un tiers n'a aucun usage produit ici et
  crée une exposition dont nous ne voulons pas (voir §2.5).
- **Les abonnés d'une activité.** Un auteur voudra, à terme, écrire aux abonnés d'une
  activité précise. Le paramètre `type` + `targetId` ci-dessus suffit si vous préférez une
  route unique ; sinon `GET /api/user-activities/{id}/subscribers`, réservé au propriétaire
  (403 sinon). L'un ou l'autre, pas les deux.

> **Note de contexte.** Cette route est techniquement la jumelle de la « liste des
> participants » demandée de longue date et jamais livrée : même besoin, même forme, même
> contrainte de confidentialité. Si un socle commun de « listes de personnes rattachées à un
> objet » vous paraît juste, les deux en sortiraient d'un coup.

## 1.3 Une publication, une notification

C'est le défaut fonctionnel le plus voyant aujourd'hui.

Un utilisateur abonné à Lena **et** à son activité « Course du dimanche » reçoit, quand elle
publie un programme :

```
AUTHOR_NEW_PROGRAM      (parce qu'il suit Lena)
ACTIVITY_NEW_PROGRAM    (parce qu'il suit l'activité)
NEARBY_PROGRAM          (si le programme tombe dans son rayon)
```

Trois notifications, trois pushes, un seul fait. Le client sait les rendre visuellement
comme une seule carte, mais regrouper à l'affichage ne répare pas trois notifications
poussées sur le téléphone à une seconde d'intervalle.

Nous demandons une **déduplication à l'émission**, côté serveur, sur la clé :

```
(destinataire, programId)      —  fenêtre : la publication
```

et, en cas de collision, une priorité explicite :

```
AUTHOR_NEW_PROGRAM  >  ACTIVITY_NEW_PROGRAM  >  NEARBY_PROGRAM
```

La raison de cet ordre : plus le lien est délibéré, plus il doit gagner. Suivre une personne
est un acte ; être géographiquement à proximité n'en est pas un.

Le même raisonnement vaut pour la création d'activité :
`AUTHOR_NEW_ACTIVITY > CATEGORY_NEW_ACTIVITY`, clé `(destinataire, userActivityId)`.

Si la déduplication vous paraît coûteuse à ce niveau, le repli acceptable est de la faire au
niveau du **push** seulement (une seule notification poussée, les trois entrées conservées
en base) — mais nous préférons franchement la première solution : trois entrées pour un fait
polluent aussi la liste.

## 1.4 `userActivityId` sur les marqueurs de la carte

Résidu d'un manque déjà signalé, et en grande partie résolu depuis : `/activities/browse`
sert bien `organizerId`, ce qui a débloqué l'abonnement à l'auteur depuis l'Explorer. Le
marqueur de carte, lui, porte `organizerId` mais **pas `userActivityId`** — le client le
reconstitue par recoupement avec un programme apparié, ce qui échoue exactement dans le cas
qui nous intéresse : une activité sans programme.

Conséquence concrète : sur la fiche ouverte depuis la carte, le bouton « suivre cette
activité » ne peut pas s'afficher tant que l'auteur n'a pas créé de programme. C'est un
champ à ajouter, rien de plus.

---

# Lot 2 — rendre l'abonnement supportable

## 2.1 Un niveau par abonnement

C'est la fonction qui manque le plus du point de vue de l'abonné. Aujourd'hui le réglage est
binaire : abonné, ou pas. Quelqu'un qui reçoit trop se désabonne — et le lien est perdu pour
de bon, alors qu'il voulait seulement baisser le volume.

```
PATCH /api/users/{userId}/subscription
PATCH /api/user-activities/{userActivityId}/subscription
PATCH /api/categories/{categoryId}/subscription
      body : { "level": "ALL" | "NEW_ONLY" | "MUTED" }
      200  → SubscriptionDto (enrichi du champ level)
      404  si l'appelant n'a pas d'abonnement sur cette cible
```

Sémantique demandée :

| `level` | Ce qui est notifié |
|---|---|
| `ALL` (défaut) | tout : créations **et** modifications (`ACTIVITY_UPDATED`, changements de créneau) |
| `NEW_ONLY` | les créations seules — nouveau programme, nouvelle activité. Les mises à jour ne notifient pas |
| `MUTED` | rien n'est poussé. L'abonnement reste, la cible reste dans « Mes abonnements » et alimente le flux consultable, mais elle n'interrompt plus |

`MUTED` mérite d'exister à part entière : c'est la soupape qui évite le désabonnement. Un
abonnement en sourdine reste un signal d'intérêt exploitable ; un désabonnement ne l'est
plus.

`SubscriptionDto` gagne le champ `level` (défaut `ALL` pour les lignes existantes — pas de
migration de données nécessaire au-delà de la valeur par défaut).

## 2.2 Un rayon sur les abonnements `CATEGORY`

Les catégories sont un référentiel **partagé et mondial**. Un abonnement `CATEGORY` sans
contrainte géographique notifie donc un utilisateur de Paris quand une activité de yoga est
créée à Berlin. C'est le même défaut de portée que celui déjà corrigé sur la carte et la
recherche, transposé aux notifications — et il rend le type `CATEGORY` inutilisable en
pratique dès que le catalogue grossit.

Nous demandons que la souscription de type `CATEGORY` porte un point et un rayon :

```
POST /api/categories/{categoryId}/subscription
     body (optionnel) : { "lat": 48.8566, "lng": 2.3522, "radiusMeters": 20000 }
```

- corps absent → comportement actuel, aucune contrainte géographique ;
- corps présent → `CATEGORY_NEW_ACTIVITY` n'est émis que si l'activité créée est
  géolocalisée **dans** le rayon ;
- une activité **en ligne** (`REMOTE`) ou sans coordonnée notifie toujours, quel que soit le
  rayon. Nous appliquons déjà exactement cette règle dans l'Explorer : ces entrées ne sont
  pas filtrées par la distance, elles sont reléguées en fin de tri. Les exclure ici serait
  incohérent avec ce que l'utilisateur voit ailleurs.

Les mêmes trois champs, en lecture, sur `SubscriptionDto` (`lat`, `lng`, `radiusMeters`,
nullables), pour que l'écran puisse afficher et modifier la portée. Le `PATCH` du §2.1 les
accepte également.

L'unité est le **mètre**, comme `/search` et `/slots/feed` — le client a unifié ses quatre
rayons sur cette unité, et `/programs` en `radius_km` est la seule exception restante.

## 2.3 La provenance, dans le payload de la notification

Une notification d'abonnement ne dit pas pourquoi elle arrive. L'utilisateur ne peut ni
comprendre ni couper la source, sauf à retrouver de tête la cible dans `/subscriptions`.

Nous demandons trois clés dans le `payload` des types issus d'un abonnement
(`AUTHOR_NEW_ACTIVITY`, `AUTHOR_NEW_PROGRAM`, `ACTIVITY_UPDATED`, `ACTIVITY_NEW_PROGRAM`,
`CATEGORY_NEW_ACTIVITY`) :

```
subscriptionId    : string    // la ligne exacte qui a causé cet envoi
subscriptionType  : "AUTHOR" | "USER_ACTIVITY" | "CATEGORY"
subscriptionLabel : string    // « Lena Müller », « Course du dimanche », « Yoga »
```

Ce que le frontend en fait, dès que c'est servi : une ligne « Vous suivez **Lena Müller** »
sous la notification, et un appui long qui propose « Mettre en sourdine » / « Se désabonner »
sans quitter l'écran. C'est le geste qui remplace la désinstallation quand le volume
devient pénible — et il n'est pas réalisable en devinant la source côté client, puisque
trois abonnements différents peuvent produire le même texte.

`subscriptionLabel` évite un aller-retour : le client ne peut pas résoudre un identifiant en
nom sans charger la cible, et une notification doit se rendre entière hors ligne.

## 2.4 `GET /users/me/subscriptions` : pagination, tri, idempotence

**Pagination.** La route rend aujourd'hui tout, d'un coup. Nous demandons l'enveloppe
`Page<T>` de Spring, comme `/notifications` et `/activities/browse` — le client sait déjà la
lire (`PagedResponse`) :

```
GET /api/users/me/subscriptions?page=&size=&type=&sort=
    200 → Page<SubscriptionDto>
    type (optionnel) : AUTHOR | USER_ACTIVITY | CATEGORY
    sort : createdAt,desc  (défaut) | createdAt,asc | targetName,asc
```

Rappel du §1.1 : **la pagination n'est sûre qu'une fois `subscribed` servi sur les DTO de
cible.** Livrée seule, elle casse silencieusement l'état de tous les boutons d'abonnement de
l'application. Si un seul des deux doit partir en premier, que ce soit `subscribed`.

Le tri par `createdAt` décroissant nous sert directement : `SubscriptionDto.createdAt` existe
déjà et n'est aujourd'hui affiché nulle part, faute d'ordre garanti.

**Idempotence.** Le comportement d'un `POST` sur un abonnement déjà existant n'est pas
documenté et nous ne l'avons pas éprouvé. Deux appuis rapides sur le bouton, ou un rejeu
réseau, sont des cas normaux en mobile. Nous demandons que ce soit **explicite** :
`200` avec la souscription existante nous convient parfaitement, `409` avec un code métier
identifiable aussi. Ce qu'il faut éviter est une seconde ligne en base, qui doublerait les
notifications sans que personne ne comprenne pourquoi.

Symétriquement, `DELETE` sur un abonnement inexistant devrait rendre `204` plutôt qu'une
erreur : le client fait un retrait optimiste, et un 404 sur un désabonnement déjà effectué le
ferait revenir en arrière à tort.

## 2.5 Confidentialité — à trancher maintenant, pas après

Trois décisions qui coûtent peu maintenant et cher plus tard :

- **La liste des abonnés n'est visible que de l'auteur** (§1.2). Pas de route tierce.
- **Les abonnements d'un utilisateur ne sont pas publics.** `GET /users/{id}/subscriptions`
  ne doit pas exister pour un tiers. Suivre une catégorie n'est pas un acte neutre : selon le
  référentiel, c'est une donnée de santé ou de situation personnelle. Nous n'en voulons ni
  l'affichage ni le transport.
- **Un réglage « qui peut me suivre »** (`OPEN` / `NOBODY`, sur le profil) : aujourd'hui
  l'abonnement est subi, on ne peut ni le refuser ni même le constater. Ce n'est pas urgent
  au même titre que le reste, mais c'est le genre de champ qu'il vaut mieux poser avec le
  modèle qu'ajouter après coup, quand des lignes existent déjà.

---

## Récapitulatif de ce qui est demandé

| # | Objet | Forme | Priorité |
|---|---|---|---|
| 1.1 | `subscriberCount` + `subscribed` | champs sur `UserDto`, `BrowsedActivity`, `CategoryDto` | **haute** |
| 1.2 | `GET /users/me/subscribers` | route paginée + `SubscriberDto` | haute |
| 1.3 | Déduplication des notifications de publication | logique d'émission | **haute** |
| 1.4 | `userActivityId` sur le marqueur de carte | champ | moyenne |
| 2.1 | `level` par abonnement | `PATCH …/subscription` + champ | **haute** |
| 2.2 | Rayon sur abonnement `CATEGORY` | corps de `POST` + champs | haute |
| 2.3 | Provenance dans le payload | 3 clés de `payload` | moyenne |
| 2.4 | Pagination, tri, idempotence de `/users/me/subscriptions` | route | moyenne, **après 1.1** |
| 2.5 | Confidentialité | décisions de modèle | à trancher |

## Ce que le frontend livre de son côté, sans rien attendre

Pour situer le partage : ces points-là ne sont pas dans la demande, ils sont déjà à notre
charge et ne dépendent d'aucune de vos routes.

- La page `/subscriptions` : regroupement par type, désabonnement en masse, affichage de la
  date d'abonnement.
- La proposition « suivre cet auteur ? » après une inscription à un programme.
- Le filtre « Mes abonnements » dans l'Explorer, sur les identifiants déjà connus du client.
- Le rendu de tous les champs ci-dessus, nullable par nullable : chacun est ignoré tant qu'il
  n'arrive pas, aucun ne casse l'app par son absence. Vous pouvez livrer dans l'ordre qui
  vous arrange, à la seule exception de `subscribed` **avant** la pagination (§2.4).

## Questions ouvertes, de notre côté

1. Le `level` du §2.1 doit-il s'articuler avec les préférences de notification globales
   (`NotificationPref`, `UpdatePreferenceRequest`) ou rester indépendant ? Notre lecture :
   indépendant, la préférence globale coupe un **type**, le `level` coupe une **cible** ; les
   deux se composent en « le plus restrictif gagne ». À confirmer.
2. Le compteur `subscriberCount` d'une activité doit-il inclure les abonnés de son auteur
   (qui seront eux aussi notifiés de ses nouveaux programmes) ? Notre lecture : non, un
   compteur par type, sans agrégation — deux nombres qui se recouvrent partiellement sont
   plus trompeurs qu'un seul, exact et étroit.
