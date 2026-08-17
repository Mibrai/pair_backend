# Réponse backend — abonnements : ce que nous livrons, et trois choses que le code dit autrement

> **Ce document précède la livraison.** Rien de ce qui suit n'est encore en
> production : c'est un engagement de contrat et un ordre de livraison, pas un
> relevé de ce qui est fait. Nous le publions maintenant parce que trois
> affirmations de votre demande ne tiennent pas contre le code, et que deux
> d'entre elles changent ce que vous devez prioriser.
>
> Audit mené sur la base du code, le 2026-08-17.

---

## 1. Trois constats d'audit, avant tout le reste

### 1.1 `NEARBY_PROGRAM` n'est jamais émis

Votre §1.3 décrit trois notifications pour une publication. Il y en a **deux**.

`NEARBY_PROGRAM` existe dans l'énumération `NotificationType`, et
`PushNotificationService` sait lui composer un titre et un corps. Mais aucun
appel `notify(..., NEARBY_PROGRAM, ...)` n'existe nulle part dans le code de
production. C'est un type déclaré et jamais émis — le vestige d'une intention,
pas une fonctionnalité.

Le doublon que vous observez est donc réel, mais il est **binaire** :
`AUTHOR_NEW_PROGRAM` et `ACTIVITY_NEW_PROGRAM`, tous deux émis dans la même
méthode `SubscriptionService.notifySubscribersOfNewProgram`, sur le même payload.
Idem côté activité : `AUTHOR_NEW_ACTIVITY` et `CATEGORY_NEW_ACTIVITY`.

Ce que cela change pour vous : la priorité à trois branches que vous demandez
n'a pas de troisième branche à départager. Nous implémentons l'ordre complet
malgré tout — le jour où la proximité géographique se met à notifier, la règle
sera déjà écrite et n'aura pas à être redécouverte. Mais si vous avez repoussé
d'autres travaux au motif que trois pushes partaient, le compte réel est de deux.

### 1.2 L'idempotence du §2.4 est déjà servie côté `POST`

Vous écrivez que le comportement d'un `POST` sur un abonnement existant « n'est
pas documenté ». Il n'est pas documenté, mais il est déterminé : les trois
méthodes d'abonnement lèvent `IllegalStateException`, et
`GlobalExceptionHandler` la traduit en **`409 CONFLICT`**, code d'erreur
`CONFLICT`. C'est la seconde des deux options que vous acceptez.

Aucune seconde ligne n'est écrite, et rien ne pourrait en écrire une : les trois
index uniques partiels de la migration V36 (`uq_sub_author`,
`uq_sub_user_activity`, `uq_sub_category`) l'interdisent en base. Le cas que
vous redoutez — deux lignes, notifications doublées, personne ne comprend — est
structurellement impossible.

Ce qui reste à corriger est l'autre moitié, celle que vous mentionnez en second :
`DELETE` sur un abonnement inexistant lève `ResourceNotFoundException` → `404`.
Nous le passons en `204`. Votre raisonnement sur le retrait optimiste est le bon.

Nous ajoutons un code métier propre, `ALREADY_SUBSCRIBED`, plutôt que le
`CONFLICT` générique actuel : vous demandiez « identifiable », et un code partagé
avec tous les autres conflits de l'API ne l'est pas.

### 1.3 Le marqueur de carte ne connaît pas l'organisateur — votre §1.4 n'est pas un champ

C'est le constat qui coûte, et il faut le poser avant que vous ne comptiez sur
une livraison simple.

Dans `MapService.getAllActivitiesForMap`, les créneaux sont groupés sur
`(activityId, lat arrondie, lng arrondie)`. **L'organisateur n'est pas dans la
clé.** Le `organizerId` que porte le marqueur — et que vous utilisez déjà pour
l'abonnement à l'auteur depuis l'Explorer — est celui d'un créneau *représentatif*
choisi par sa date : le prochain à venir du groupe, à défaut le premier.

Conséquence : deux personnes proposant le même yoga au même lieu (à 111 m près)
produisent **un seul marqueur**, portant le nom, l'avatar et l'identifiant de
l'une des deux, arbitrairement. Ce n'est pas une régression que nous
introduirions ; c'est l'état actuel, et il est déjà visible dans votre app —
sur les zones denses, s'abonner à « l'auteur » depuis la carte peut abonner à
quelqu'un d'autre que celui qu'on croit suivre.

Ajouter `userActivityId` tel que vous le demandez reviendrait à propager cette
ambiguïté à un second champ, en lui donnant l'apparence de la précision : un
identifiant d'activité-utilisateur *paraît* désigner exactement une entrée, là
où `organizerId` avoue au moins qu'il désigne une personne.

Nous voyons deux issues, et nous ne trancherons pas seuls :

| Option | Ce qu'elle donne | Ce qu'elle coûte |
|---|---|---|
| **A — la maille change** : `userActivityId` entre dans la clé de groupement | Un marqueur par `(activité-utilisateur, lieu)`. `organizerId` et `userActivityId` deviennent tous deux exacts. | La cardinalité des marqueurs augmente sur les zones denses. Votre déduplication client, documentée sur `MapActivityMarkerDto`, doit intégrer le nouveau champ dans sa clé. |
| **B — le champ suit l'existant** : `userActivityId` du créneau représentatif | Livrable immédiatement, cohérent avec `organizerId` (les deux désignent la même entrée représentative). | Le bouton « suivre cette activité » peut abonner à l'activité d'un autre organisateur, dans le cas exact où deux personnes se superposent. |

Notre préférence est **A** — un marqueur qui ment n'est pas moins gênant parce
qu'il ment depuis longtemps — mais c'est votre rendu qui absorbe le changement
de cardinalité, donc c'est votre appel. **Nous attendons votre réponse sur ce
point ; il est le seul du document à être bloqué sur vous.** Le reste part sans
attendre.

---

## 2. L'ordre de livraison, et pourquoi il ne suit pas vos lots

Vous proposez lot 1 puis lot 2. Nous livrons en trois temps découpés
**par zone de code touchée**, parce que vos §1.3, §2.1, §2.2 et §2.3 modifient
tous la même chose : les trois boucles de fan-out de `SubscriptionService`. Les
livrer dans l'ordre du document reviendrait à réécrire ce bloc quatre fois, avec
quatre occasions de le casser.

**Lot A — le modèle et les champs de lecture.**
Une migration unique : `level` et `lat` / `lng` / `radius_meters` sur
`subscriptions`. Puis `subscribed` + `subscriberCount` sur les trois DTO, le
`PATCH`, le `DELETE` en `204`, et `ALREADY_SUBSCRIBED`.
→ couvre **§1.1**, **§2.1**, **§2.2** (partie modèle et écriture), **§2.4**
(idempotence).

Nous posons les colonnes du §2.2 dès maintenant bien qu'elles ne servent qu'au
lot B : deux migrations sur la même table à trois jours d'intervalle, sur une
table déjà en production, se paient et ne s'expliquent pas.

**Lot B — l'émission, réécrite une seule fois.**
Déduplication, respect de `level`, filtre par rayon, provenance dans le payload.
→ couvre **§1.3**, **§2.1** (partie effet), **§2.2** (partie effet), **§2.3**.

**Lot C — les listes.**
→ couvre **§1.2** et **§2.4** (pagination et tri).

Votre contrainte d'ordre est respectée par construction : `subscribed` part au
lot A, la pagination au lot C.

**Le §1.4 sort de cette séquence** — non par priorité, mais parce qu'il attend
votre arbitrage du §1.3 ci-dessus.

---

## 3. Le contrat, point par point

### §1.1 — `subscriberCount` et `subscribed`

Trois précisions sur les noms et un écart.

**Le DTO du profil public s'appelle `UserPublicDto`, pas `UserDto`.** Il est servi
par `GET /users/{id}`, et `UserService.getPublicProfile` reçoit déjà
l'identifiant de l'appelant : `subscribed` n'y coûte qu'une requête d'existence.

**Sur `GET /users/me`, `subscribed` est absent — pas `false`.** On ne peut pas
s'abonner à soi-même : la contrainte `chk_subscription_not_self` de la V36
l'interdit en base. Servir un booléen toujours faux inviterait à afficher un
bouton « S'abonner » sur son propre profil. `subscriberCount` y est servi, lui :
c'est précisément le chiffre qu'un auteur veut voir.

**Sur `CategoryDto`, `subscribed` peut être `false` sans que ce soit une réponse.**
`GET /api/categories` est une route **publique** (`permitAll` dans
`SecurityConfig`), et `CategoryDto` est par ailleurs imbriqué dans `ActivityDto`.
Pour un appelant anonyme, `subscribed` vaut `false` faute d'identité — ce qui est
correct pour l'affichage, mais ne doit pas servir de source de vérité une fois
l'utilisateur connecté. `subscriberCount`, lui, est servi dans tous les cas :
c'est une donnée publique.

**Sur `BrowsedActivityDto`**, nous n'ajoutons pas les deux champs à la requête
native de `UserActivityRepository.browse` — elle est déjà lourde et son mapping
par alias casse silencieusement quand on la touche. L'enrichissement se fait
après coup dans `ActivityBrowseService`, en deux requêtes bornées à la taille de
la page : un `COUNT … GROUP BY` pour les compteurs, un `IN` pour l'ensemble des
cibles auxquelles l'appelant est abonné. Coût constant par page, quelle que soit
la taille du catalogue.

Compteurs calculés par `COUNT` indexé, pas dénormalisés : les index
`idx_sub_target_author`, `idx_sub_target_user_activity` et
`idx_sub_target_category` existent depuis la V36 et rendent le calcul assez bon
marché pour ne pas justifier un compteur à maintenir — donc à désynchroniser.

### §1.2 — `GET /api/users/me/subscribers`

Livré tel que spécifié : `Page<SubscriberDto>`, paramètres `page`, `size`, `type`.
`targetId` et `targetName` nuls pour un abonnement `AUTHOR`, conformément à
votre commentaire (votre bloc de types dit `targetId : string` puis « null si
AUTHOR » — nous retenons le commentaire, le champ est nullable).

**Une route unique**, avec `type` et `targetId` en paramètres, plutôt que
`GET /user-activities/{id}/subscribers`. Vous demandiez l'un ou l'autre : c'est
celle-ci. Une seule route à autoriser est une seule route à ne pas se tromper
d'autorisation — et la liste reste, dans les deux cas, celle de l'appelant.

Sur votre note de contexte : oui, c'est la jumelle de la liste des participants,
et non, nous ne construisons pas le socle commun maintenant. Deux usages ne
suffisent pas à dessiner une abstraction juste, et une abstraction fausse coûte
plus cher que la duplication qu'elle évite. Nous reverrons la question au
troisième.

### §1.3 — Une publication, une notification

Déduplication **à l'émission**, pas au push : nous prenons votre solution
préférée, pas le repli. Trois entrées pour un fait polluent la liste, vous avez
raison, et une notification que l'utilisateur ne peut pas relier à un acte est
une notification qu'il finit par ignorer en bloc.

Clé `(destinataire, programId)` pour les programmes, `(destinataire, userActivityId)`
pour les activités, fenêtre = la publication. Priorité retenue :

```
AUTHOR_NEW_PROGRAM  >  ACTIVITY_NEW_PROGRAM  >  NEARBY_PROGRAM
AUTHOR_NEW_ACTIVITY >  CATEGORY_NEW_ACTIVITY
```

Votre justification — « plus le lien est délibéré, plus il doit gagner » — est
celle que nous inscrivons dans le code, en toutes lettres. C'est le genre de
règle qu'un lecteur futur inversera par bon sens apparent si personne ne lui dit
pourquoi elle est dans ce sens.

La déduplication porte sur le **destinataire résolu**, après application des
`level` du §2.1 : quelqu'un dont l'abonnement `AUTHOR` est en `MUTED` mais dont
l'abonnement `USER_ACTIVITY` est en `ALL` doit recevoir `ACTIVITY_NEW_PROGRAM`,
et non rien du tout au motif que la branche prioritaire a gagné puis a été tue.
C'est le piège de l'ordre d'évaluation, et c'est la raison technique pour
laquelle §1.3 et §2.1 partent ensemble au lot B.

### §1.4 — `userActivityId` sur le marqueur

Voir §1.3 de ce document. En attente de votre arbitrage.

### §2.1 — `level`

Les trois `PATCH` tels que spécifiés, `404` si l'appelant n'a pas d'abonnement
sur la cible, `SubscriptionDto` enrichi en retour. Colonne `level` avec défaut
`ALL` : aucune reprise de données au-delà du défaut, comme vous l'anticipiez.

Sémantique retenue sans écart :

| `level` | Émis |
|---|---|
| `ALL` | `AUTHOR_NEW_ACTIVITY`, `AUTHOR_NEW_PROGRAM`, `ACTIVITY_NEW_PROGRAM`, `CATEGORY_NEW_ACTIVITY`, `ACTIVITY_UPDATED` |
| `NEW_ONLY` | les quatre premiers ; `ACTIVITY_UPDATED` est retenu |
| `MUTED` | aucun |

`MUTED` ne coupe que l'**émission**. La ligne reste, la cible reste dans
`/users/me/subscriptions`, et `subscribed` continue de valoir `true` sur les
DTO de cible : le bouton doit dire « Abonné », pas « S'abonner ». Nous
insistons parce que c'est le point où une implémentation naïve trahit
l'intention de `MUTED` — un abonnement en sourdine qui s'affiche comme absent
sera recliqué, et le second `POST` rendra `409`.

### §2.2 — Rayon sur les abonnements `CATEGORY`

Corps optionnel sur le `POST`, trois champs nullables en lecture sur
`SubscriptionDto`, acceptés aussi par le `PATCH`. Unité : le **mètre**.

Nous reprenons votre règle sur les activités sans position, et pour votre
raison : une activité `REMOTE` ou sans coordonnée notifie toujours. Un filtre
géographique qui écarte ce qui n'a pas de géographie n'est pas un filtre, c'est
une perte.

Un point que vous ne soulevez pas et que nous tranchons : le rayon s'applique à
l'**activité créée**, évaluée au moment de l'émission. Il ne se rejoue pas si
l'activité déménage ensuite. Une notification est un fait daté ; la rejouer
contre un état ultérieur produirait des annonces sans événement.

### §2.3 — Provenance dans le payload

Les trois clés sur les cinq types listés. `subscriptionLabel` est résolu à
l'émission — nom d'auteur, nom d'activité, nom de catégorie — et **copié** dans
le payload, non relu. Votre argument de l'affichage hors ligne est le bon, et il
en implique un second : un label copié ne change plus quand la cible est
renommée. C'est voulu. Une notification doit dire ce qu'elle disait le jour où
elle est partie.

Sur les types issus de plusieurs abonnements possibles, `subscriptionId` désigne
**la ligne qui a gagné la déduplication du §1.3** — celle dont le type figure en
tête de la priorité. C'est la seule réponse qui rende votre appui long cohérent :
mettre en sourdine l'abonnement que l'utilisateur voit nommé doit effectivement
faire taire la notification qu'il vient de recevoir.

### §2.4 — Pagination, tri, idempotence

Enveloppe `Page<SubscriptionDto>`, paramètres `page`, `size`, `type`, `sort`
avec `createdAt,desc` par défaut et `createdAt,asc` / `targetName,asc` acceptés.

`targetName,asc` demande un tri sur un champ qui n'existe pas en base : le nom
est dans `users`, `activities` ou `categories` selon le type. Nous le servons
par un tri applicatif sur la page, ce qui trie **la page et non la collection**.
Sur un tri alphabétique paginé, la nuance est visible. Si vous en avez besoin
sur la collection entière, dites-le et nous passerons par une colonne dénormalisée
— nous préférons ne pas la poser tant que l'écran n'en dépend pas.

Idempotence : voir §1.2 de ce document.

### §2.5 — Confidentialité

Les trois décisions sont prises, dans votre sens.

- **Pas de `GET /users/{id}/subscribers`.** La liste du §1.2 est celle de
  l'appelant.
- **Pas de `GET /users/{id}/subscriptions`.** Votre argument sur la donnée de
  santé n'est pas théorique : le référentiel contient des catégories dont
  l'abonnement révèle un état, et rien dans le produit ne justifie de le
  transporter.
- **« Qui peut me suivre »** trouve sa place tout de suite, et sans route
  nouvelle : `PrivacySettingsDto` existe déjà, servi par `GET|PUT /users/me/privacy`,
  et porte déjà `allowMessages` — exactement le même geste, sur le même écran.
  Nous y ajoutons `allowSubscriptions : "OPEN" | "NOBODY"`, défaut `OPEN`. Un
  `POST` d'abonnement sur un profil en `NOBODY` rendra `403`.

  Nous suivons votre raisonnement : c'est le genre de champ qui coûte une colonne
  aujourd'hui et une reprise de données dans six mois.

---

## 4. Vos deux questions ouvertes

**1. `level` et `NotificationPref` — indépendants, et composés en « le plus
restrictif gagne ».** Votre lecture est la nôtre, et le modèle la porte déjà :
`NotificationPref` est unique par `(user_id, notification_type)` — c'est bien
un réglage par **type**, sans notion de cible. `level` est par **ligne
d'abonnement**, donc par cible, sans notion de type. Les deux axes sont
orthogonaux, et aucun ne peut exprimer l'autre.

L'ordre d'évaluation à l'émission : `level` d'abord (il décide *si* la
notification existe), `NotificationPref` ensuite (il décide *par quel canal*
elle sort — `emailEnabled`, `pushEnabled`, `frequency`). Une cible en `MUTED` ne
produit rien, quels que soient les canaux ouverts ; un type dont le push est
coupé produit une entrée en base sans push, quel que soit le `level`.

**2. `subscriberCount` d'une activité n'inclut pas les abonnés de son auteur.**
Votre lecture, encore. Un compteur par type, exact et étroit. Deux nombres qui
se recouvrent partiellement sont effectivement plus trompeurs qu'un seul — et
celui-ci serait invérifiable par l'auteur lui-même, qui ne pourrait rapprocher
le chiffre d'aucune liste.

Corollaire à garder à l'esprit côté rendu : la somme des `subscriberCount` d'un
auteur et de ses activités **n'est pas** le nombre de personnes qu'une
publication touchera. La déduplication du §1.3 fait que le second nombre est plus
petit. Si un écran affiche un jour « votre publication touchera N personnes », ce
N devra être calculé, pas additionné — dites-le nous et nous le servirons.

---

## 5. Ce que nous ne faisons pas

- **Pas de compteur dénormalisé.** `COUNT` indexé tant que la mesure ne dit pas
  le contraire. Un compteur à maintenir est un compteur à resynchroniser.
- **Pas de socle « listes de personnes rattachées à un objet ».** Voir §1.2.
- **Pas de reprise de données.** Ni pour `level`, ni pour le rayon, ni pour
  `allowSubscriptions` : trois valeurs par défaut suffisent, et elles préservent
  toutes le comportement actuel.
- **Pas de digest ni de regroupement temporel.** Votre §1.3 déduplique un fait
  entre plusieurs sources ; il ne demande pas de regrouper plusieurs faits dans
  le temps, et nous ne l'anticipons pas.

---

*Demande initiale : `docs/specs/PROMPT_BACKEND_ABONNEMENTS_2026-08.md`.*
