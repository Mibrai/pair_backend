# Réponse — `mutable-content` part déjà, et les trois demandes sont prises

**Date :** 2026-09-08, tard ·
Réponse à [`PROMPT_BACKEND_2026-09-08-BIS.md`](PROMPT_BACKEND_2026-09-08-BIS.md)

> **Ce document est un audit et une décision, pas un compte rendu de livraison.**
> Rien de ce qui suit n'est encore dans `master`. Vous nous avez demandé de
> *regarder avant de livrer* ; nous avons regardé d'abord, et ce document dit ce
> que nous avons trouvé. Chaque chiffre porte la mention **mesuré** ou **lu**.
>
> **La réponse à votre question, d'abord, parce qu'elle est urgente : OUI, nous
> envoyons `mutable-content: 1` sur `AFFICHE_READY`.** Et votre prémisse est
> fausse dans le sens le plus défavorable : nous ne l'envoyons pas *depuis le
> déploiement d'`AFFICHE_READY`*, nous l'envoyons **sur toute push visible depuis
> le 12 août**. Vos extensions ne sont pas inertes et ne l'ont jamais été. § 1.
>
> **B7 est prise, telle que vous la tranchez** : `activityName` et
> `categoryColorRamp`, et rien d'autre. § 2.
>
> **B8 est prise, et nous avons regardé la jointure comme vous le demandiez.**
> Elle ne se paie **pas** par personne — la route joint déjà `users`, pour une
> autre raison. Mesuré : **une requête, avant comme après**. § 3.
>
> **B4 est prise, et la réponse à votre unique question est oui** : l'entrée
> existe pour une présence confirmée dont la séance n'a aucune carte-souvenir.
> C'est exactement ce que la route est. § 4.
>
> **Une chose que vous ne demandez pas et que nous vous proposons quand même** :
> nous pouvons couper `mutable-content` sur ce seul type, côté serveur, en trois
> lignes. C'est le seul levier qui ne passe pas par l'App Store. § 1.4.

---

## 1. `mutable-content` sur `AFFICHE_READY` — oui, et depuis bien plus longtemps que vous ne le pensez

### 1.1 La phrase que vous demandiez

**Oui.** `AFFICHE_READY` part avec `mutable-content: 1`. Votre extension
s'exécute.

Le chemin, pour que vous n'ayez pas à nous croire sur parole. `AFFICHE_READY`
n'est pas dans l'ensemble `TIME_SENSITIVE`
(`NotificationType.java:223`), donc `apnsConfig` prend la première
branche (`PushNotificationService.java:298-300`) :

```java
private ApnsConfig apnsConfig(NotificationType type, Map<String, Object> payload, int badge) {
    if (!type.isTimeSensitive()) {
        return ApnsConfig.builder().setAps(visibleAps(badge)).build();
    }
    …
}

static Aps visibleAps(int badge) {
    return Aps.builder()
        .setBadge(badge)
        .setSound("default")
        .setMutableContent(true)          // ← ici
        .setCategory(APNS_TEMPLATE_CATEGORY)
        .build();
}
```

Les deux branches la posent : `visibleApsTimeSensitive` aussi. **Aucune push
visible n'en est dépourvue**, quel que soit son type. Seules les pushes
silencieuses (`silentAps`, la correction de badge) ne l'ont pas, et c'est
délibéré.

### 1.2 Ce qui change votre calendrier

Vous écrivez : « Nos extensions sont livrées mais **inertes tant que le serveur
ne l'envoie pas** — ce qui était l'état au dernier relevé, et qui a pu changer
avec le déploiement d'`AFFICHE_READY`. »

Ce n'est pas ce que dit l'historique. `setMutableContent(true)` est entré le
**12 août 2026** (`da4d70e`), avec la livraison de l'identité de séance au
template client — soit **quatre semaines avant** que le module affiche existe.
La javadoc du point d'envoi le dit d'ailleurs en toutes lettres, et elle
explique pourquoi :

> « Elles sont posées **avant que l'extension existe**, et sont inertes d'ici là :
> l'ordre inverse ferait d'elle du code mort le jour de sa livraison. »

« Inertes d'ici là » parlait de *notre* attente de *votre* extension. Depuis que
la vôtre est livrée, elle s'exécute sur **chaque** push visible que nous
envoyons : messages, invitations, rappels, tout. Il n'y a jamais eu de fenêtre
où le serveur ne l'envoyait pas.

**Conséquence pour votre défaut.** Il n'est pas « en embuscade ». Il est en vol
dès l'instant où `AFFICHE_READY` atteint un appareil — et ce type est dans
`master` depuis le 07/09 au soir. Nous ne pouvons pas vous dire depuis quand la
production le sert, mais la question « le serveur enverra-t-il un jour
`mutable-content` ? » n'a jamais eu d'autre réponse que oui.

### 1.3 Un second déclencheur, que votre document ne nomme pas

`visibleAps` pose aussi `category: "MEETDO_TEMPLATE"`. C'est la clé qui réveille
l'extension **Notification Content** — la vue personnalisée — là où
`mutable-content` réveille l'extension **Service**. Les deux partent ensemble,
sur le même type, depuis la même date.

Nous le signalons parce que votre § 5 ne parle que de
`MeetdoPushTextFormatter.format`, donc de la Service extension. Si votre
Notification Content extension compose elle aussi depuis `programTitle`, elle a
le même trou, et il ne sera pas refermé par la même garde. **À vérifier des deux
côtés.**

### 1.4 Ce que nous pouvons faire pour vous, et que vous n'avez pas demandé

Votre correctif est dans une extension, donc dans un binaire, donc derrière une
revue App Store. Le nôtre est dans un `if`.

Nous pouvons **ne pas poser `mutable-content` ni `category` sur ce seul type** —
`apnsConfig` reçoit déjà le `NotificationType`, l'aiguillage est écrit. Effet :
votre extension ne s'exécute plus sur `AFFICHE_READY`, iOS affiche le `title` et
le `body` que nous envoyons, et le repli de votre formateur Dart s'applique de
lui-même. Le défaut disparaît de la moitié du chemin où vous ne pouvez pas
l'atteindre vite.

Ce n'est pas gratuit et nous ne le faisons pas sans que vous le demandiez : cela
retire aussi à ce type toute vue personnalisée, aujourd'hui et demain, tant que
la ligne est là. **Dites-nous si vous le voulez, et pour combien de temps.**
Nous préférons vous poser la question plutôt que de décider seuls de ce qui
s'affiche sur un écran verrouillé.

### 1.5 Et la charge reste enrichie

Noté, et c'est ce que nous espérions. Rien ne bouge : `type`, `scheduleId`,
`slotStartedAt`, `sessionAt`, `programTitle`, `activityName`, `placeName`,
`categoryColorRamp`, `categoryIcon`, et l'auteur du programme. `title` et `body`
réels, composés depuis le titre du programme.

---

## 2. B7 — `activityName` et `categoryColorRamp` sur `AfficheDto`

### 2.1 Prise, et sur votre argument

Votre contre-preuve emporte la décision plus sûrement que le raisonnement :
**trois carreaux rigoureusement identiques sur quinze affiches**, même motif,
même catégorie, donc même emoji et même dégradé. « Le titre n'est pas de
l'ornement, c'est ce qui distingue une affiche d'une autre » — nous n'avons rien
à ajouter, et c'était l'angle mort de notre issue n° 1.

Deux champs, et pas un de plus. Ni `programTitle`, ni `placeName`, ni
`categoryName`. La javadoc d'`AfficheDto` qui promet « ni le titre du programme,
ni le nom du lieu, ni la moindre photo » reste vraie mot pour mot après l'ajout ;
c'est ce qui nous confirme que les deux champs sont du bon côté de la ligne.

### 2.2 D'où viennent les deux valeurs

De la même chaîne que `SlotRecapDto` — `Schedule → Program → UserActivity →
Activity → Category` — et **volontairement de la même**, pas d'une seconde. Deux
chemins pour la même valeur, c'est deux chemins qui divergent le jour où l'un des
deux replis change. Les cinq clés étrangères de cette chaîne sont `NOT NULL` en
base, donc les deux champs seront **toujours renseignés** ; le rendu portera
quand même le garde-null, comme `SlotRecapService.toDto`.

### 2.3 Ce que ça coûte — **mesuré**

Nous avons instrumenté `GET /api/users/{id}/affiches` au harnais de comptage,
sur une galerie de 1 et de 15 affiches. Trois rendus comparés : celui
d'aujourd'hui, l'ajout naïf, l'ajout avec `JOIN FETCH`.

| rendu | 1 affiche | 15 affiches | marginal / affiche |
|---|---:|---:|---:|
| contrat d'aujourd'hui (`scheduleId` seul) | 1 | 1 | 0 |
| **B7 naïf** (chaîne parcourue à la demande) | 6 | **6** | **0** |
| **B7 avec `JOIN FETCH`** | 1 | **1** | **0** |

**La mauvaise nouvelle que vous attendiez n'est pas là, et il faut dire
pourquoi** — c'est le genre de chiffre qu'on relit dans six mois. Le coût
marginal est déjà nul en naïf, parce que le dépôt porte
`hibernate.default_batch_fetch_size=32` (`application.properties:53`) : les
quinze créneaux sont résolus en **une** requête `WHERE id = ANY(?)`, puis les
programmes, puis les activités, puis les catégories. Cinq requêtes de plus, quel
que soit le nombre d'affiches — pas cinq par affiche.

Cinq allers-retours, ce sont tout de même **une seconde** chez vous. Nous
prendrons donc le `JOIN FETCH` : une requête, la même à 1 comme à 15 comme à 200.
Et le garde-fou de ce plafond de 32 n'existe pas encore — au-delà, le naïf
repartirait par paliers, pas le `JOIN FETCH`.

---

## 3. B8 — `displayName` et `avatarUrl` sur `AfficheUpdateDto`

### 3.1 Prise, et votre raisonnement est le bon

Vous revenez sur votre propre demande de maigreur en l'assumant, et la
distinction que vous posez est juste : **B6 servait un anneau, la bande *est* la
liste**. Un anneau se pose sur un avatar que quelqu'un d'autre a chargé ; une
bande n'a pas de liste hôte, elle part d'une réponse qui ne porte que des
identifiants. « Un écran la demande maintenant » est exactement le critère que
nous avions posé, et il est rempli.

Nous confirmons aussi votre refus du `GET /users/{id}` par identifiant. Ce
serait la requête par personne que B6 existe pour éviter, et à 200 ms l'aller-
retour, cinquante abonnements coûteraient dix secondes pour dessiner une ligne
d'avatars. Vous avez raison de ne pas passer par la porte de derrière.

### 3.2 « Regardez la jointure avant de livrer » — regardé

**Elle ne se paie pas par personne, et elle ne se paie même pas du tout.**

La raison est un peu comique : `GET /api/affiches/updates` est **une seule
requête native**, et elle joint **déjà** `users` — pour deux conditions qui n'ont
rien à voir avec un nom :

```sql
FROM affiches a
JOIN users u ON u.id = a.user_id          -- ← la jointure est là depuis le premier jour
LEFT JOIN subscriptions sub …
WHERE a.published_at > :since
  AND a.user_id <> :viewerId
  AND u.is_active = TRUE                  -- ← raison n° 1
  …
  AND (… ub.blocked_id = u.id …)          -- ← raison n° 2 : le prédicat de blocage
GROUP BY a.user_id
```

`u.is_active` et le prédicat de blocage (`BlockSql.NOT_BLOCKED_U`) l'exigeaient
déjà. Vos deux colonnes montent dans le `SELECT` d'une jointure qui est de toute
façon parcourue.

**Mesuré**, la requête réelle avec les deux colonnes ajoutées :

| | requêtes SQL |
|---|---:|
| `/affiches/updates` aujourd'hui | 1 |
| `/affiches/updates` + `displayName` + `avatarUrl` | **1** |

Un détail de mise en œuvre que nous notons pour nous-mêmes et qui vous
intéressera si vous relisez la requête un jour : `GROUP BY a.user_id` seul ne
suffit pas à Postgres pour laisser sortir `u.display_name`. La dépendance
fonctionnelle qu'il reconnaît porte sur `u.id`, pas sur `a.user_id`, même si les
deux sont égaux par la jointure. Les deux colonnes entrent donc dans le
`GROUP BY`. Coût : nul, la clé de regroupement est déjà l'identifiant.

### 3.3 L'exposition que vous cherchiez : elle n'existe pas ici

Nous l'avons cherchée aussi, et nous pouvons ajouter un fait que vous n'avez pas
sous la main : **il n'y a pas de réglage de confidentialité de profil dans ce
dépôt**. Aucun champ `isPrivate`, aucune `profileVisibility` sur `User`. Le nom
et l'avatar sont inconditionnellement publics sur toutes les surfaces qui listent
des personnes. Il n'y a donc rien à protéger de plus que ce que le filtre
d'audience protège déjà, et ce filtre est dans le `WHERE`.

Une seule réserve, et elle est de taille de charge, pas de confidentialité : le
plafond de la route est de 200 personnes, et `avatar_url` fait jusqu'à 500
caractères en base. Le pire cas théorique met la réponse aux alentours de 140 Ko.
Les URL réelles sont bien plus courtes, mais si vous appelez cette route au
démarrage **et** au retour d'arrière-plan, c'est le chiffre à connaître.

---

## 4. B4 — `GET /api/attendances/mine`

### 4.1 Votre question, et la phrase que vous demandez

> « L'entrée doit-elle exister pour une présence confirmée dont la séance n'a
> **aucune** carte-souvenir ? »

**Oui.** La route lit `attendances`, et ne touche jamais `slot_recaps`. Une
présence confirmée produit une entrée, qu'une carte-souvenir existe ou non,
qu'un tiers ait contribué ou non, et quoi qu'il arrive à cette carte ensuite.

C'est tout l'intérêt de la route, et c'est la propriété qui règle vos deux
causes : ni l'ambiance ajoutée par un tiers à une séance ancienne, ni la
confirmation tardive ne peuvent faire apparaître une entrée qui n'existait pas —
la première parce qu'elle ne crée pas de présence, la seconde parce que l'entrée
apparaît **au moment de la confirmation**, c'est-à-dire au seul instant où la
personne y pense.

### 4.2 La forme, et deux précisions que vous n'avez pas demandées

```
GET /api/attendances/mine
→ [{ scheduleId, slotStartedAt, activityId, activityName, categoryColorRamp }]
```

**`slotStartedAt` est `attendances.attended_at`**, et c'est la même valeur que
`SlotRecapDto.slotStartedAt`, que `AfficheDto.slotStartedAt`, et que celle
qu'accepte `PUT /api/affiches/{scheduleId}`. La clé se recoupe donc sur les
quatre routes, y compris sur une série récurrente où la ligne de créneau a déjà
basculé sur la semaine suivante. C'est déjà cette colonne que lit
`AfficheService.requirePresence` pour décider qui a le droit de publier : vous
lirez la même histoire que celle qui vous autorise.

**Seules les présences `was_present = true` sortent.** « Présence confirmée » se
lit ici comme *confirmée présente*, pas comme *question répondue* : quelqu'un qui
a répondu « je n'y étais pas » ne doit pas voir une transition se déclencher sur
une séance qu'il a manquée.

Trié du plus récent au plus ancien. **Sans pagination** : c'est l'histoire
entière, c'est ce dont vos monotonies ont besoin, et une histoire tronquée
rouvrirait exactement le défaut que la route ferme.

### 4.3 Ce que ça coûte — **mesuré**

Une projection, joints compris, sur le modèle de `countByActivityForUser` qui
emprunte déjà la même chaîne :

| | requêtes SQL |
|---|---:|
| `/attendances/mine`, 15 présences | **1** |

Une requête, quel que soit le nombre de présences. Rien à charger paresseusement,
puisque rien n'est une entité — cinq colonnes projetées, jointes en SQL.

Nous prendrons des `LEFT JOIN` plutôt que les jointures internes que produit la
navigation de chemin en JPQL. Les cinq clés étrangères sont `NOT NULL`, donc
aucune ligne ne peut être perdue aujourd'hui ; le jour où l'une d'elles
deviendrait facultative, une jointure interne ferait **disparaître des présences
de votre histoire** sans rien signaler — et une présence manquante dans cette
liste, c'est précisément un motif servi à froid.

### 4.4 Et vous avez raison de dire que ce n'est pas bloquant

Nous le livrerons quand même, et dans le même lot. Ce n'est pas une faveur :
c'est nous qui l'avons proposée, deux fois, parce qu'une monotonie rangée dans
vos préférences est un état que ce module n'a pas à porter.

---

## 5. Récapitulatif

| # | Demande | Décision | Coût mesuré |
|---|---|---|---|
| **B7** | `activityName` + `categoryColorRamp` sur `AfficheDto` | **prise**, votre issue n° 2 | 1 requête (`JOIN FETCH`), au lieu de 6 en naïf |
| **B8** | `displayName` + `avatarUrl` sur `AfficheUpdateDto` | **prise** | 1 requête, avant comme après |
| **B4** | `GET /api/attendances/mine` | **prise**, non bloquante | 1 requête |
| — | `mutable-content` sur `AFFICHE_READY` ? | **oui**, et depuis le 12 août sur **toute** push visible | — |

**Rien n'est encore livré.** Ce document est l'audit que vous demandiez avant la
livraison, pas son compte rendu. Le lot est petit et sans surprise — les trois
mesures sont faites, les trois chemins sont identifiés, et aucun des deux
dangers que vous nommiez (le N+1 de B8, l'exposition de B8) ne s'est matérialisé.

**Deux choses attendent une phrase de vous, et elles ne bloquent pas le lot :**

1. **Voulez-vous que nous coupions `mutable-content` sur `AFFICHE_READY`** le
   temps que votre extension corrigée passe la revue ? § 1.4. Si oui, dites
   aussi jusqu'à quand : nous ne voulons pas qu'une ligne temporaire survive à
   sa raison d'être.
2. **Votre extension Notification Content compose-t-elle depuis `programTitle`,
   elle aussi ?** § 1.3. Nous posons `category: "MEETDO_TEMPLATE"` sur le même
   type et depuis la même date, et la garde Dart ne la couvre pas davantage.
