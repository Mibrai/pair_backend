# Réponse backend — les trois lots sont livrés, et deux d'entre eux ne font pas ce que nous avions écrit

> Les neuf points de la demande sont traités. Sept le sont comme annoncé ; deux
> ont changé de forme en cours de route, parce que le code a dit autre chose que
> nos deux documents.
>
> **Le §2.2 change le moment d'une notification** (§3 ci-dessous), et **le §1.2
> refuse un filtre que vous aviez demandé** (§4). Ce sont les deux seuls endroits
> où lire ce document avant de brancher vos écrans vous fera gagner du temps.
>
> S'y ajoute un élargissement que nous n'avions pas annoncé : le réglage
> « qui peut me suivre » couvre désormais aussi les abonnements aux activités
> (§7). C'était un trou, pas une extension de périmètre.
>
> Livré et vérifié sur la base du code, le 2026-08-17.

---

## 1. Ce qui est en place, en un tableau

| # | Objet | État | À lire |
|---|---|---|---|
| 1.1 | `subscriberCount` + `subscribed` | livré | §5 — deux champs sont nullables |
| 1.2 | `GET /users/me/subscribers` | livré | §4 — `type=CATEGORY` est refusé |
| 1.3 | Déduplication des notifications | livré | §2 |
| 1.4 | `userActivityId` sur le marqueur | **non livré, à votre demande** | §8 |
| 2.1 | `level` par abonnement | livré | §2 |
| 2.2 | Rayon sur abonnement `CATEGORY` | livré, **autrement** | §3 |
| 2.3 | Provenance dans le payload | livré | §2 |
| 2.4 | Pagination, tri, idempotence | livré | §6 |
| 2.5 | Confidentialité | livré, **élargi** | §7 |

---

## 2. L'émission : un fait, une notification

Trois règles s'appliquent, et leur ordre est le fond du sujet :

1. le **niveau** de chaque abonnement décide si un envoi existe ;
2. la **portée** géographique écarte ce qui est hors zone ;
3. la **déduplication** ne garde, par destinataire, que l'envoi le plus délibéré.

Nous filtrons avant de dédupliquer, jamais l'inverse. C'est le piège de ce lot et
il mérite d'être dit : quelqu'un dont l'abonnement à l'auteur est en `MUTED` mais
dont l'abonnement à l'activité est en `ALL` **reçoit `ACTIVITY_NEW_PROGRAM`**.
Dédupliquer d'abord ferait gagner la branche auteur, qui se tairait ensuite, et
cette personne ne recevrait rien — alors qu'elle avait explicitement demandé à
suivre l'activité. Ce cas a son test.

**Priorité retenue**, avec la justification inscrite dans le code en toutes
lettres pour qu'un lecteur futur ne l'inverse pas par bon sens apparent :

```
AUTHOR_NEW_PROGRAM  >  ACTIVITY_NEW_PROGRAM  >  NEARBY_PROGRAM
AUTHOR_NEW_ACTIVITY >  CATEGORY_NEW_ACTIVITY
```

La branche `NEARBY_PROGRAM` est écrite bien que ce type ne soit émis nulle part,
comme convenu. La structure tolère un candidat **sans abonnement source** : la
proximité n'étant pas un abonnement, un tel envoi n'a aucune ligne à nommer, et
sa provenance est simplement absente du payload plutôt que fabriquée.

**`level`** se comporte comme spécifié. `NEW_ONLY` retient `ACTIVITY_UPDATED` et
laisse passer les quatre créations. `MUTED` ne coupe **que** l'émission :
`subscribed` reste `true`, la cible reste dans vos listes, et le bouton doit
continuer de dire « Abonné ».

**La provenance** (`subscriptionId`, `subscriptionType`, `subscriptionLabel`)
désigne la ligne qui a **gagné la déduplication**. C'est la seule réponse qui
rende votre appui long honnête : mettre en sourdine ce qui est nommé à l'écran
doit faire taire ce qu'on vient de recevoir. Le libellé est copié, jamais relu.

Un effet de bord que vous verrez : les deux annonces d'activité partagent enfin
un contexte unique. La branche catégorie omettait `authorId` et `authorName`, si
bien que le payload d'un même fait dépendait de l'abonnement par lequel on
l'apprenait. **Clés ajoutées, aucune retirée.**

---

## 3. §2.2 — le rayon fonctionne, mais l'annonce a changé de moment

**À lire avant de brancher quoi que ce soit sur `CATEGORY_NEW_ACTIVITY`.**

Votre demande suppose qu'une activité a une position. Elle n'en a pas.
`UserActivity` ne porte ni latitude, ni longitude, ni type de lieu : elle
**emprunte** la position de ses créneaux, exactement comme dans l'Explorer.

Or `CATEGORY_NEW_ACTIVITY` partait à la **création** de l'activité — instant où
elle n'a ni programme, ni créneau, donc aucune position. Implémenté à la lettre,
votre §2.2 aurait donc été un no-op garanti : la règle « pas de coordonnée, on
notifie toujours » aurait été vraie à chaque appel. Le rayon aurait été stocké,
lu, affiché sur votre écran, et sans le moindre effet. Vous auriez livré un
réglage qui ne règle rien, et personne ne s'en serait aperçu avant longtemps.

**L'annonce part désormais au premier créneau localisé de l'activité**, où il y a
enfin quelque chose à situer. C'est le motif que nous avions déjà retenu pour
`AUTHOR_NEW_PROGRAM` : un programme naît en brouillon et sans créneau, il n'y
avait rien à situer au moment où l'annonce partait.

Trois conséquences, toutes assumées :

- **`AUTHOR_NEW_ACTIVITY` ne bouge pas.** Il part toujours à la création : un
  abonné à l'auteur n'a pas de rayon, il n'a rien à situer. Les deux annonces se
  séparent donc dans le temps, et c'est voulu.
- **Une activité qui n'obtient jamais de programme n'atteint jamais les abonnés
  de sa catégorie.** Cohérent avec ce qu'ils demandent — être prévenus de ce qui
  se passe près d'eux — et une activité sans séance n'est pas quelque chose à
  quoi se rendre.
- **L'annonce arrive plus tard qu'avant**, du délai que met l'auteur à poser son
  premier créneau.

Le reste est conforme : rayon en **mètres**, corps absent = aucune contrainte, et
une activité **à distance** (`REMOTE`, `ONLINE`) notifie toujours quel que soit
le rayon — un filtre qui écarte ce qui n'a pas de géographie n'est pas un filtre,
c'est une perte. La distance est évaluée à l'émission et ne se rejoue pas si
l'activité déménage ensuite.

---

## 4. §1.2 — `type=CATEGORY` est refusé, et c'est votre propre règle qui l'exige

`GET /api/users/me/subscribers?page=&size=&type=&targetId=` rend
`Page<SubscriberDto>` comme spécifié. Une seule route, avec `type` et `targetId`
en paramètres — vous demandiez l'un ou l'autre, c'est celle-ci.

`targetId` ne desserre pas la règle de visibilité, **il la resserre** : l'appelant
doit être l'auteur de l'activité demandée, `403` sinon.

**Mais `type=CATEGORY` rend `403`, pour tout le monde.** Votre §1.2 le liste parmi
les valeurs du filtre ; votre §2.5 interdit exactement cette exposition — « suivre
une catégorie n'est pas un acte neutre : selon le référentiel, c'est une donnée de
santé ou de situation personnelle ». Les deux paragraphes se contredisent, et
nous avons fait gagner la confidentialité.

S'y ajoute un fait de modèle qui rend la question sans objet : `Category` ne porte
ni propriétaire ni créateur. C'est un référentiel partagé — aucune catégorie
n'appartenant à personne, il n'existe personne à qui cette liste pourrait
légitimement revenir.

Un refus plutôt qu'une page vide : une page vide répondrait « vous n'avez aucun
abonné par catégorie » à une question qui n'a de réponse pour personne.

En clair, pour votre écran : **un abonné arrive par votre profil ou par l'une de
vos activités, jamais par une catégorie.** Si vous vouliez afficher « N personnes
suivent la catégorie de cette activité », dites-le-nous — ce serait un compteur,
pas une liste, et un compteur n'expose personne.

---

## 5. §1.1 — deux champs sont nullables, et c'est délibéré

`subscriberCount` et `subscribed` sont servis sur `UserPublicDto` (nommé ainsi, et
non `UserDto`), `BrowsedActivityDto` et `CategoryDto`. `UserPrivateDto`
(`GET /users/me`) ne porte que le compteur — on ne s'abonne pas à soi-même.

Nous nous écartons ici de ce que nous vous avions écrit (« `subscriberCount` est
servi dans tous les cas ») :

- sur **`CategoryDto`**, les deux champs sont `null` **hors de
  `GET /api/categories`**. Ce DTO est imbriqué ailleurs, dans `ActivityDto`
  notamment, et les calculer y coûterait deux requêtes par activité rendue ;
- sur **`UserPublicDto`**, ils sont `null` quand le DTO sert de fiche d'identité
  hors contexte d'abonnement — un membre de conversation, par exemple.

`null` dit « non calculé ici ». Un `0` dirait « aucun abonné » et un `false`
« pas abonné », ce qui serait faux. Votre §4.2 lit déjà ces champs comme une
valeur à trois cas ; c'est exactement la lecture qu'il faut.

Rappel utile : sur `GET /api/categories`, qui est une route **publique**,
`subscribed` vaut `false` pour un appelant anonyme — faute d'identité, pas faute
d'abonnement.

Les compteurs sont calculés par `COUNT` indexé, pas dénormalisés, et par requêtes
groupées **par page** — jamais une par entrée. Sur `/activities/browse` nous
n'avons pas touché à la requête native : l'enrichissement se fait après coup, en
deux requêtes bornées à la taille de la page.

---

## 6. §2.4 — pagination, tri, idempotence

**Rupture de contrat, celle que vous aviez acceptée.**
`GET /api/users/me/subscriptions` rendait un tableau, il rend désormais une
enveloppe `Page`, dans la forme que sert déjà `/activities/browse` — le total est
sous la clé `page`, pas à la racine.

Paramètres : `page`, `size` (plafonnée à 50, comme `/notifications`), `type`, et
`direction` (`desc` par défaut, `asc` accepté) sur `createdAt`.

**Nous n'exposons pas `targetName,asc` et nous n'avons posé aucune colonne
dénormalisée**, comme vous l'avez demandé à votre §4.4.

**Idempotence.** `POST` sur un abonnement existant rend `409` avec le code
**`ALREADY_SUBSCRIBED`**, et non plus le `CONFLICT` générique — vous pouvez donc
le traiter comme un succès, ce que votre §4.3 prévoit. Aucune seconde ligne n'est
possible : les trois index uniques partiels de la V36 l'interdisent en base.
`DELETE` sur un abonnement inexistant rend `204`.

---

## 7. §2.5 — confidentialité, et un trou que nous avons trouvé en chemin

Les trois décisions sont tenues : pas de `GET /users/{id}/subscribers`, pas de
`GET /users/{id}/subscriptions`, et `allowSubscriptions` (`OPEN` | `NOBODY`) posé
dans `PrivacySettingsDto`, servi et modifié par `GET|PUT /users/me/privacy` — pas
de route ni d'écran nouveaux.

**Ce que nous vous avions annoncé était incomplet.** Nous avions écrit que le
réglage porterait sur les abonnements de type `AUTHOR`. Vérification faite, cela
en aurait fait un réglage décoratif : quelqu'un qui ferme son profil restait
suivable par **n'importe laquelle de ses activités**, et l'abonné ainsi arrivé
recevait bien ses nouveaux programmes. Le réglage aurait affiché « fermé » sans
l'être.

**Le refus vaut donc sur les deux chemins** : le profil et chacune de ses
activités. Suivre ce que quelqu'un propose, c'est le suivre. Les catégories y
échappent délibérément — elles n'appartiennent à personne, et s'abonner à
« Yoga » n'est pas suivre quelqu'un.

Corollaire pour votre écran : `POST /api/user-activities/{id}/subscription` peut
désormais rendre `403 SUBSCRIPTIONS_NOT_ALLOWED`, ce qui n'était pas prévu dans
notre document. Même code, même traitement que sur la route auteur.

Au passage, nous avons fermé un second trou : on pouvait s'abonner à sa **propre**
activité. La contrainte de base qui l'interdit sur un profil ne pouvait pas
s'étendre à une activité — elle supposerait une jointure, ce qu'un `CHECK` ne sait
pas faire.

---

## 8. §1.4 — non livré, et le défaut vous survit

Nous n'avons pas ajouté `userActivityId` au marqueur, et nous n'avons pas touché
à la clé de groupement : c'est ce que vous avez demandé une fois la fiche carte
passée sur le pin de programme.

Nous avons en revanche suivi votre suggestion, qui était la bonne : le défaut est
maintenant **écrit dans le code de groupement** de `MapService`, et sur le schéma
d'`organizerId`, à l'intention du prochain consommateur de la route. Il y est dit
que la maille ignore l'organisateur, que deux personnes superposées se fondent en
un marqueur, et que le corriger suppose de prévenir les clients avant, pas après.

---

## 9. Vos deux questions du §5

**5.1 — le code du `403`.** Nous prenons votre nom : **`SUBSCRIPTIONS_NOT_ALLOWED`**.

**5.2 — les abonnements existants quand un profil passe en `NOBODY`.** Votre
lecture est retenue : **ils restent et continuent de notifier.** Le réglage ferme
la porte, il ne vide pas la pièce.

La raison n'est pas seulement que c'est le moins de travail. La troisième option —
garder mais taire — est la pire des trois : l'abonné conserverait `subscribed:
true` sans plus jamais rien recevoir, sans aucun moyen de comprendre pourquoi,
pendant que `subscriberCount` continuerait d'annoncer une audience que rien
n'atteint. Et la suppression est irréversible : rebasculer en `OPEN` ne rendrait
pas ses abonnés.

**Conséquence pour votre libellé**, puisque vous nous demandiez de quoi l'écrire :
le réglage porte sur les abonnements **à venir**. Il doit donc dire « empêcher de
nouveaux abonnements » et non « personne ne peut me suivre », qui promettrait un
effet rétroactif que nous n'implémentons pas.

---

## 10. Un ajout à la demande, et un seul

`PATCH …/subscription` accepte un champ **`clearScope`** (booléen) qui retire la
portée géographique.

Sans lui, retirer une portée était inexprimable : en JSON, un champ absent et un
champ explicitement `null` arrivent tous deux à `null`, rien ne les distingue. Et
l'alternative — un `PATCH` qui remplacerait la portée en bloc à chaque appel —
aurait fait qu'un simple changement de niveau efface silencieusement un rayon
réglé. Un drapeau explicite ne se déclenche pas par accident.

`clearScope` et `lat`/`lng`/`radiusMeters` dans le même appel sont refusés :
poser et retirer une portée d'un même geste n'a pas de sens, et le silence sur
l'ordre d'application serait un piège.

---

## 11. Vérification

**37 tests unitaires** et **20 tests d'intégration** contre un PostgreSQL réel.

Ce que seule une vraie base pouvait établir : les contraintes `CHECK` des
migrations V58 et V59, le fait qu'un abonnement en sourdine reste un abonnement,
que le `403` d'un profil fermé laisse intacts les abonnés d'avant, et que les
deux requêtes de listes filtrent bien ce qu'elles prétendent filtrer.

Deux points méritent d'être signalés :

- **Le fan-out n'était couvert par aucun test avant ce lot**, ni ici ni ailleurs :
  les tests voisins vérifiaient seulement qu'il était *appelé*. Son comportement
  n'était donc retenu par rien, dans un sens comme dans l'autre. Il l'est
  maintenant par treize cas, dont le piège d'ordre du §2.
- **Une régression nous a échappé le temps d'un commit**, et la façon dont elle
  s'est révélée vaut d'être dite : un champ ajouté au mauvais endroit d'une entité
  avait désactivé l'horodatage automatique d'une colonne voisine, et toute
  création d'activité échouait. Aucun test d'abonnement ne pouvait l'attraper —
  seules deux classes sans rapport, qui créent des activités en chemin pour
  arriver à ce qu'elles testent, l'ont fait tomber. C'est l'argument pour lancer
  la suite entière plutôt que les seules classes touchées.

---

## 12. Ce qui vous attend concrètement

Par ordre de ce qui casse si vous ne le lisez pas :

1. **`GET /users/me/subscriptions` rend une `Page`** — enveloppe imbriquée, total
   sous `page.totalElements`.
2. **`CATEGORY_NEW_ACTIVITY` arrive plus tard**, au premier créneau localisé, et
   jamais pour une activité sans programme (§3).
3. **`POST /user-activities/{id}/subscription` peut rendre `403`** (§7).
4. **`type=CATEGORY` sur `/users/me/subscribers` rend `403`** (§4).
5. `subscriberCount` et `subscribed` sont nullables selon le contexte (§5).
6. `409 ALREADY_SUBSCRIBED` et `204` sur un `DELETE` déjà effectué (§6).

Tout le reste est additif et n'exige rien de vous.

---

*Demande initiale : `docs/specs/PROMPT_BACKEND_ABONNEMENTS_2026-08.md`.
Contrat annoncé : `REPONSE_BACKEND_ABONNEMENTS_2026-08.md`.
Votre réponse : `REPONSE_CLIENT_ABONNEMENTS_2026-08.md`.*
