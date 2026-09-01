# Retour sur la livraison du 01/09 — trois routes, un arbitrage, trois précisions

**Date :** 2026-09-01
**Fait suite à :** `REPONSE_BACKEND_2026-09-01.md`

---

## 0. D'abord : la livraison est conforme

Relecture faite du prompt du 31/08 contre ce que vous avez livré, route par route : **la table de la veille est reprise à l'identique** — verbes, chemins, corps, `202` / `409` sur `close` compris. Les deux écarts que vous signalez sont argumentés et nous les prenons tels quels. Et vous avez livré **deux routes que nous n'avions pas demandées** : `revoke-link`, et la page publique de statut entièrement spécifiée là où notre prompt se contentait d'une ligne (« extension de `/public/safety/{token}` »).

Il est plus honnête de le dire avant de demander autre chose : les écarts que nous avons trouvés en alignant l'app sont **tous de notre côté**, et deux d'entre eux étaient à rebours de notre propre demande. Nous les corrigeons sans rien vous demander.

Sur `duressCode` : votre raisonnement est le bon. Nous avions mis un `duressHash` dans le DTO sans jamais dire par quelle route il y arrivait — le trou était réel. Recevoir le code et calculer l'empreinte côté serveur ne change rien à la promesse, puisque vous n'en gardez que le condensat.

---

## 1. Les trois routes demandées le 31/08 au soir

Elles sont dans `REPONSE_CLIENT_2026-08-31.md`, envoyé après le prompt principal. La livraison ne les mentionne pas — ni pour les servir, ni pour les écarter. Nous supposons qu'elles sont arrivées après le gel du périmètre plutôt qu'elles n'ont été refusées, d'où cette relance.

| Geste | Route | Pourquoi elle manque à l'écran |
|---|---|---|
| « Je la vois, elle est là » (organisateur) | `POST /api/watches/{id}/seen-by-host` | Décision 14 du module. L'organisateur **repousse** la relance d'arrivée de 15 min ; il ne valide pas la présence, et ne crée aucun code. Un verbe à part, parce que `still-coming` appartient à l'intéressée sur sa propre veille — le faire appeler par l'organisateur le ferait agir sous l'identité de quelqu'un d'autre. |
| Compteurs et chronologie du journal | `GET /api/watches/history` | `watches/active` ne rend que les veilles en cours. L'écran « Mon journal » ne peut donc compter ni les séances clôturées ni les alertes ; il n'affiche aujourd'hui que le compteur d'incidents, seul chiffre qu'il puisse produire honnêtement. Ne doit porter **aucune coordonnée** : l'archive se contente de l'horodatage et du nom du lieu. |
| « Retirer de mon journal » | `DELETE /api/incidents/{id}` | Décision 12. |

Un « non » nous va, s'il est dit : les trois écrans sont dessinés et à moitié écrits, et nous préférons les retirer proprement que les laisser en attente indéfinie.

**La question de conception attachée à la troisième reste ouverte** — vous ne l'aviez pas tranchée non plus : que devient l'agrégat de modération quand quelqu'un supprime son incident ? S'il disparaît, on peut effacer la trace d'un lieu qui concentre les incidents, c'est-à-dire le signal même qu'on voulait garder. S'il reste, « supprimer » ne supprime pas. **Notre préférence, inchangée :** retrait du journal personnel, anonymisation côté modération, et un bouton qui dit « retirer de mon journal » — ce qui est vrai, et ne promet rien de plus.

---

## 2. La veille ne dit rien de son propre lien public

Celui-ci nous bloque pour de bon, et nous ne l'avons vu qu'en écrivant l'écran.

Vous avez livré la page publique et sa révocation, et nous avons construit ce qu'il fallait autour : afficher le lien, l'envoyer au contact, le couper, et montrer les deux accusés (« j'ai vu », « je l'ai eue au téléphone ») dans la veille. Tout est écrit, testé, et **ne s'affichera jamais** — parce que `GET /api/watches/{id}` ne dit rien de tout ça.

Le contrat décrit la page **telle que le contact la voit**. Il ne dit pas ce que la veille en rapporte **à sa propriétaire**. Or c'est elle qui doit pouvoir envoyer le lien, savoir qu'il existe, et le couper.

Il nous faut donc, sur `GET /api/watches/{id}` (et idéalement sur `/watches/active`) :

| Champ | Pourquoi |
|---|---|
| le **jeton** du lien (ou son URL complète) | sans lui, rien à envoyer ni à révoquer. C'est le seul champ vraiment bloquant. |
| `guardianSeenAt`, `guardianCalledAt` | les deux accusés, datés. C'est l'information la plus rassurante de tout le module : quelqu'un a vu, et a réagi. |
| l'état public, éventuellement | agrément seulement — nous ne le dérivons pas de l'état interne, puisque vous en servez six et nous en portons sept, et que la page est rendue chez vous. |

Deux précautions de notre côté, pour que vous sachiez ce que vous n'avez pas à garantir : nous lisons ces champs **en tolérance** — absents, l'entrée ne s'affiche simplement pas —, et c'est **l'absence du jeton**, jamais l'état de la veille, qui fait disparaître le lien. Nous ne déduisons pas « escaladée donc il y a un lien » : cela promettrait un lien avant que vous ne l'ayez créé, et en montrerait un que la révocation vient de couper.

Le jour où les champs arrivent, l'écran s'allume sans que nous touchions à rien.

---

## 3. L'arbitrage qui vous appartient en partie : le bandeau trahit la clôture sous contrainte

C'est le point le plus important de ce document, et il n'apparaît dans aucun des échanges précédents. Nous l'avons trouvé en alignant l'app sur votre §2.1.

Votre consigne est tenue à la lettre : l'écran de clôture ne relit pas l'état de la veille, et le `202` est traité comme un succès sans condition. Mais l'app relit ensuite `GET /api/watches/active` — c'est sa source de vérité, relue au démarrage, au retour au premier plan et sur push. Et la veille passée en `ESCALATED` y revient. En aval, un bandeau d'alerte global s'affiche sur toutes les pages dès qu'une veille est escaladée.

Résultat : quelqu'un tape son code de contrainte, l'écran affiche « c'est refermé » comme prévu — et deux secondes plus tard **un bandeau d'alerte rouge apparaît sur toute l'app**, devant l'observateur même que le code existe pour ne pas alerter.

Ce n'est pas la relecture qui trahit. C'est **la coïncidence entre le geste et le changement d'affichage**. Aucune consigne de « ne pas relire cet écran-là » ne la supprime, parce que le bandeau ne vit pas sur cet écran.

Trois façons d'en sortir, dont une seule est de votre côté :

1. **`GET /watches/active` ne rend pas la veille escaladée par code de contrainte** — ou la rend dans un état qui ne déclenche pas le bandeau. Propre à la source, mais cela vous demande de distinguer une escalade de contrainte d'une escalade ordinaire dans une réponse, ce qui rouvre exactement le canal que §2.1 ferme. **Nous ne le recommandons pas** — sauf si vous voyez une forme qui ne réintroduit pas la distinction.
2. **L'app note « clôturée par moi à T » et tait le bandeau pour cette veille** jusqu'au prochain démarrage à froid. Local, testable, aucune information nouvelle ne transite.
3. **Le bandeau ne se rafraîchit jamais dans la foulée d'une clôture.** Le plus discret, mais retarde aussi une vraie alerte reçue au même moment.

**Nous partons sur la 2**, sauf avis contraire de votre part. L'observateur par-dessus l'épaule est là *maintenant* — c'est la situation que le code couvre. Celui qui regarde le téléphone le lendemain n'est plus dans ce scénario, et lui cacher l'alerte indéfiniment reviendrait à cacher à la personne veillée elle-même qu'un proche a été prévenu.

**Ce qu'il nous faut de vous :** confirmer qu'une veille `ESCALATED` par code de contrainte reste bien dans `/watches/active` et y est indistinguable d'une escalade ordinaire. Si c'est le cas, la piste 2 est la seule qui tienne, et nous l'écrivons.

---

## 4. Trois précisions, toutes mineures

### 4.1 · Le nombre d'essais restants est dans un message

`409 WATCH_CODE_WRONG` porte le nombre d'essais restants **dans le message**. L'écran doit dire « 2 essais restants — au 3ᵉ échec, Camille est prévenue » : c'est un avertissement qui doit arriver **avant** le dernier essai, pas un constat après coup.

Nous pouvons afficher votre message tel quel, et c'est ce que nous ferons en attendant. Mais en extraire l'entier demanderait de parser une chaîne traduite — le genre de code qui casse à la première reformulation, silencieusement, sur l'écran le moins pardonnant du module.

**Demande :** un champ `attemptsLeft` (entier) à côté du `code` et du `message`. Le message reste ce qu'il est.

### 4.2 · `note` ou `description` ?

Le corps de `POST /api/incidents` est documenté `{target, note?, …}`, et le code d'erreur s'appelle `INCIDENT_DESCRIPTION_REQUIRED`. L'un des deux noms est de trop. Nous partons sur `note` pour le champ — c'est ce que dit le contrat — et nous traitons le code d'erreur tel quel. Confirmez-nous simplement lequel fait foi, pour que nous n'ayons pas à le découvrir sur un `400`.

### 4.3 · La cadence des trois rappels n'est nulle part

L'écran d'armement doit annoncer l'heure d'envoi du message **avant** qu'aucune veille n'existe — donc avant que vous ayez calculé quoi que ce soit. Il porte pour ça une constante locale de 45 minutes après l'échéance, qui correspond à trois rappels de 15 minutes.

`deadlineAt` est parfaitement défini de votre côté et nous affichons la même heure que vous retiendrez — ce point-là est réglé. Mais la **cadence des rappels** n'est au contrat nulle part. Si vous la changez, cet écran mentira sans que rien ne le signale.

**Demande, au choix :** la cadence inscrite au contrat comme valeur stable, ou une route de prévisualisation qui rend les heures pour un créneau donné avant armement. La première nous suffit.

---

## 5. Les notifications de veille : trois champs, et un faux positif coûteux

Nous avons câblé les liens profonds et le catalogue de préférences. Il reste ce que seule la charge APNs peut porter.

### 5.1 · `interruption-level: time-sensitive` — le point qui compte

Votre réponse du 31/08 (§3.4) l'écrit : *« l'envoi passe par FCM et ne pose ni `apns-collapse-id` ni `interruption-level` »*.

Sans `interruption-level`, un mode Concentration retient les trois relances. La personne dort, ne voit rien, ne saisit rien — **et l'alerte part quand même.** Un proche est réveillé pour une notification que le téléphone avait décidé de ne pas montrer. C'est le faux positif le plus coûteux du module, et le seul qui se produise sans que personne ait rien fait de mal.

Demande : `apns-push-type: alert` avec **`interruption-level: time-sensitive`** dans l'`aps` (côté FCM : `apns.payload.aps.interruption-level`), et l'importance `high` + `bypassDnd` sur le canal Android.

À ne pas confondre avec ce que vous avez déjà prévu : vous annoncez que les trois relances rejoindront `NotificationType.isCritical()`, donc les heures de silence serveur. C'est **nécessaire mais pas suffisant** — `isCritical()` décide d'envoyer, `interruption-level` décide si iOS affiche.

### 5.2 · Un `watchId` dans le `data`

Sans lui, « Entrer mon code » retombe sur le journal et la personne doit chercher sa veille. Nous acceptons `watchId` et `watch_id`.

### 5.3 · Un `apns-collapse-id` stable par veille

L'app attend `watch-<watchId>`. Deux usages : les relances successives se remplacent au lieu de s'empiler, et surtout **on peut retirer les rappels déjà délivrés à la clôture** — sans quoi quelqu'un qui vient de valider voit encore « dernier rappel » sur son écran verrouillé.

Ce dernier point est aujourd'hui impossible côté app, et pas seulement par paresse : sur iOS, une notification livrée par APNs ne porte aucun identifiant local, et la retirer demande `removeDeliveredNotifications(withIdentifiers:)` sur l'identifiant que **vous** avez posé.

### 5.4 · Les deux types

L'app reconnaît déjà `WATCH_RETURN_REMINDER` et `WATCH_ARRIVAL_PROMPT` (catégories APNs `WATCH_RETURN` et `WATCH_ARRIVAL`). Le contrat du 01/09 sert les routes mais n'a jamais nommé ses types de push : dites-nous si vous en retenez d'autres.

### 5.5 · Ce qu'aucune notification ne doit dire

Aucun type ne décrit une **fin** de veille — ni « veille close », ni « alerte envoyée », ni « fausse alerte levée ». Une notification qui dirait l'une de ces choses rendrait les deux clôtures distinguables **sur un écran verrouillé**, devant la personne même que le code de contrainte existe pour ne pas alerter. C'est la même règle que votre §2.1, appliquée au seul endroit qu'elle n'avait pas prévu. Un test de notre côté parcourt les types et refuse ces mots ; nous vous demandons la même retenue à l'émission.

---

## 6. Le SMS : décision prise — il reste éteint

Vous nous laissiez la main. **La décision est prise : le SMS reste désactivé, les alertes partent par e-mail.** Ne provisionnez pas de fournisseur, ne réservez pas d'expéditeur, ne portez pas ce sujet dans votre plan de charge.

Ce qui rend la situation tenable est votre garde-fou, et nous voulons qu'il reste tel quel : **refuser un contact externe qui n'a qu'un téléphone** (`GUARDIAN_SMS_NOT_AVAILABLE`). C'est lui qui garantit que personne ne devient injoignable sans qu'on le sache au moment de l'invitation, plutôt qu'au moment de l'alerte. Nous l'avons remonté dans le formulaire : l'e-mail y est désormais obligatoire pour un contact externe, et l'écran le dit **avant** la saisie. Le champ téléphone reste, facultatif — il servira tel quel le jour où le canal s'allumera, sans que nous y retouchions.

Une conséquence à ne pas perdre de vue de votre côté : tant que le SMS est éteint, **l'e-mail est un point de défaillance unique** pour tout le module. Une adresse en faute — boîte pleine, domaine qui classe en indésirable, faute de frappe à l'invitation — et le proche n'est jamais prévenu, sans que personne ne l'apprenne. Votre outbox durable et vos gabarits couvrent l'envoi ; ce qui nous manque est le **retour** : un état de remise exploitable, pour que l'app puisse dire « le message n'est pas parti » plutôt que de laisser croire qu'il l'est.

C'est la même demande que celle laissée en suspens depuis le 31/08 (`WatchAlertDelivery` vit encore comme une valeur locale, faute de champ serveur). Elle passe de confortable à nécessaire du fait de cette décision : avec deux canaux, l'un rattrapait l'autre ; avec un seul, il n'y a plus de filet.

Nous reviendrons vers vous si la décision change. Nous notons que ce sera alors de la configuration, sans changement d'API ni de notre côté.
