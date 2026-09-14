# « Déjà pratiquée près de toi » : un booléen, jamais un compte

**Date :** 2026-09-14
**Module :** `rappel`
**Audit :** P-MU-22, écart A1 (`audit/PLAN_MOBILE_UX_DESIGN_2026-09-11.md`)
**Suit :** `PLAN_IMPLEMENTATION_2026-09-03.md` §05, maquette `template/meetdo-rappel.html` (écran A1)

> **Ce que l'app a déjà fait, sans vous.** À la création d'une activité, le bloc « Ça existe
> déjà » propose au plus trois entrées du catalogue au fil de la frappe, insensible aux accents,
> sans appel réseau (`ActivityDuplicateHints`, lot 4 du module, livré le 05/09). La part A3 de la
> fiche est livrée (`4ae9e15`, 13/09). A2 — « Étape 1 sur 3 » et ses trois pastilles — ne vous
> demande rien : l'étape se déduit de `CycleStage`, déjà calculé côté app.
>
> **Ce qui vous appartient** : dire, pour une activité donnée et une position donnée, si elle
> **se pratique déjà** autour. Un oui ou un non. Sans lui, le bloc reste tel qu'il est : un nom et
> une catégorie.

---

## 1. Ce que la maquette voulait, et ce que nous refusons d'en garder

L'écran A1 de la maquette écrit sous chaque suggestion « 142 personnes · 9 programmes autour de
toi ». Nous n'afficherons **aucun chiffre** : la doctrine produit refuse les classements et
préfère un booléen à un compte partout où il suffit (consignes du dépôt mobile, « jamais un compte là où un
booléen suffit »). La phrase retenue est « Déjà pratiquée près de toi » — ou rien.

Ce qu'elle doit produire chez la personne : choisir l'entrée existante plutôt que créer
« Course à pie », parce qu'elle apprend qu'il y a déjà du monde derrière.

## 2. Relevé du 14/09/2026

Contrat `/v3/api-docs` de production (217 chemins) et clone du serveur au commit `4c3d38b`
(13/09, 22:42). `GET /actuator/info` rend un `build.time` de `2026-09-13T20:42:30Z`, soit l'heure
de ce commit : le clone relu est très probablement ce qui tourne. Aucune lecture HTTP
authentifiée n'a été rejouée ce jour-là : ce relevé repose sur le contrat et le code.

**Rien ne sert ce booléen aujourd'hui.**

- `ActivityDto` (contrat) : `id`, `name`, `slug`, `description`, `icon`, `imageUrl`, `parentId`,
  `category`. Aucun champ de pratique.
- `GET /api/activities` (`ActivityController.java:137-145`) est **public**
  (`SecurityConfig.java:132`, `permitAll`) et ne reçoit aucune position.

**Ce qui s'en approche, et pourquoi l'app ne peut pas s'en servir.**

- `GET /api/activities/suggested?lat&lng&limit` (`ActivityController.java:96-103`, au contrat,
  livrée avec V60 le 19/08) rend `SuggestedActivityDto.practitionersNearby` (`int64`,
  `SuggestedActivityDto.java:18-23`).
- Le décompte vient de `ActivityRepository.findMostPractisedInRadius` (`:175-225`) : personnes
  distinctes, activité `visible_on_map`, compte actif, `location_public`, dans 25 km
  (`SuggestedActivityService.java:44`).
- Trois raisons de ne pas la détourner :
  1. elle **écarte les activités que l'appelant déclare déjà** (`:211-214`) ;
  2. elle ne rend que les `limit` plus pratiquées, 50 au plus : **l'absence d'une activité ne
     veut pas dire qu'elle ne se pratique pas** ;
  3. elle rend un **compte**, que nous aurions à rabattre en booléen chez nous — le chiffre
     transiterait quand même, et finirait affiché par quelqu'un.
- Son repli national (`fallback: true`) vaut 0 et ne dit rien du voisinage.

Aucune fiche des plans backend (`audit/PLAN_BACKEND_*.md`) ne couvre ce besoin.

## 3. La demande

1. **Une route authentifiée**
   `GET /api/activities/practised-nearby?lat={lat}&lng={lng}&activityIds={id},{id},{id}`.
   - Au plus **10** identifiants. Le bloc n'en montre que 3 (`kActivityHintMaxResults`,
     `activity_name_match.dart:44`).
   - Réponse : `[{"activityId": "…", "practisedNearby": true}]`, une entrée par identifiant
     connu, dans l'ordre reçu.
   - Aucun autre champ. **Aucun décompte**, ni ici ni dans un en-tête.
2. **La règle du oui**, la même maille que `findMostPractisedInRadius` : au moins une personne
   **autre que l'appelant**, activité `visible_on_map`, compte actif, `location_public`, dans le
   rayon de l'Explorer (25 km). Une personne qui se cache ne compte pas, comme ailleurs.
3. **Les refus** :
   - `401` sans session ;
   - `400 VALIDATION_ERROR` pour une latitude ou une longitude hors bornes, plus de 10
     identifiants ou une liste vide ;
   - un identifiant inconnu est **omis**, jamais un `404` pour toute la requête.
4. **Au contrat**, avec la règle du §3.2 écrite dans la description du champ.

Pourquoi une route et pas `ActivityDto.practisedNearby` sur `GET /api/activities` : cette route
est publique et rend un référentiel. Y ajouter `lat`/`lng` l'ouvrirait au sondage anonyme, et
`ActivityDto` est lu par la version publiée de l'app. Si vous préférez le champ, nous le prenons,
à deux conditions : `lat`/`lng` facultatifs, et `practisedNearby` **absent ou `null`** sans
position ou sans session.

## 4. Les questions de contrat ouvertes

1. **Le seuil.** Un « oui » à partir d'**une** personne permet, en déplaçant `lat`/`lng`, de
   trianguler quelqu'un qui pratique seul une activité rare. Nous proposons un seuil de **3
   personnes distinctes**, et d'arrondir la position reçue (par exemple au kilomètre) avant de
   compter. Vous tranchez ; nous voulons seulement le seuil écrit au contrat.
2. **Personnes ou programmes ?** « Se pratique ici » pourrait aussi compter un programme public
   avec une séance à venir dans le rayon, ce qui ne dépend pas de `location_public`. Le §3.2 s'en
   tient aux personnes pour rester aligné sur `/activities/suggested`. Dites-nous si l'autre
   maille vous semble plus juste.
3. **La limite de débit.** La route serait appelée au fil de la frappe. Aujourd'hui le bloc n'a
   aucun délai de frappe, puisqu'il ne fait aucun appel. Nous en ajouterons un : un appel dès 3
   caractères, et seulement quand les résultats changent. Faut-il un plafond ? Il rendrait un
   `429` avec `Retry-After`, comme les autres.

## 5. Comment nous vérifierons

- **Relevé** `/v3/api-docs` : route, paramètres, schéma de réponse, **aucun champ entier**.
- **Lectures HTTP réelles** avec le compte de test : autour de Munich, où vivent les données de
  démonstration, une activité courante rend `true`. Au milieu de la mer du Nord, `false`. Une
  activité que seul l'appelant pratique, `false`. Un identifiant inconnu est omis. Sans jeton,
  `401`.
- **Côté app**, derrière un drapeau éteint, `activityPractisedNearby`. Il entre par le
  constructeur de l'écran hôte, `create_activity_sheet.dart`, qui garde déjà le point d'insertion
  des indices. Le dépôt est injectable, et le test déclaratif
  `test/cycle/duplicate_hints_no_count_test.dart` vérifie « le bloc de doublon ne rend aucun
  chiffre ».
- **Sans position autorisée**, l'app n'appelle pas la route et n'affiche rien.
- **Bascule** dans un commit séparé. Le drapeau reste ensuite : c'est l'interrupteur si la route
  régresse.
