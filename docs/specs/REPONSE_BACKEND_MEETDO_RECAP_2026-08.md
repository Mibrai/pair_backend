# Réponse backend — la carte-souvenir de créneau (`slot_recaps`)

> Livré en entier, étapes 1 à 7 du §7 comprises. Les routes répondent : le
> drapeau `FeatureFlags.slotRecaps` peut passer à `true`.
>
> Trois écarts au document, tous assumés et détaillés en §2 : la migration est
> **V54** et non V50, le mécanisme de souvenir photo **n'existait pas** et a dû
> être créé, et `cityLabel` s'appuie sur une **nouvelle colonne** faute de
> géocodeur réel.

---

## 1. Ce qui est livré

| Étape §7 | État | Où |
|---|---|---|
| 1. Migration + entités | ✅ | `V54__slot_recap.sql`, `domain/recap/` |
| 2. Enum `SlotVibe` | ✅ | `domain/recap/SlotVibe.java` |
| 3. `SlotRecapService` | ✅ | création à la 1ʳᵉ contribution, présence réutilisée, fenêtre 7 j |
| 4. Contribution (vibes, consent, note) | ✅ | `SlotRecapController` |
| 5. Lecture + `nextSlot` + visibilité | ✅ | `SlotRecapService.get`, `ScheduleRepository.findNextOpenSlot` |
| 6. Garde-fou de publication | ✅ | `RECAP_NEEDS_ATTENDEE` |
| 7. `/api/recaps/feed` géolocalisé | ✅ | `RecapController`, `SlotRecapRepository.findPublicInRadius` |

Les trois classes de tests que le document donne comme définition du « livré »
existent et passent : `SlotRecapServiceTest` (15), `SlotRecapVisibilityTest`
(10), `SlotRecapDtoTest` (10).

---

## 2. Les trois écarts, et pourquoi

### 2.1 La migration est `V54`, pas `V50`

Flyway n'était pas à V49 : V50 à V53 existaient déjà au moment de la livraison
(dont le lot messagerie de programme). Le contenu est celui du §2, mot pour
mot, augmenté des deux points ci-dessous.

### 2.2 Le souvenir photo n'existait pas — il a fallu le créer

Le §2.1 dit « réutiliser `attendances.memory_photo_url` + `memory_is_public`
**s'il est déjà en place** ». Il ne l'était pas : ces colonnes n'existaient
nulle part dans le schéma, et rien ne reliait une présence à un fichier. Sans
elles, `photoUrls` serait resté vide et deux des tests attendus n'auraient rien
eu à vérifier.

Elles sont donc ajoutées sur `attendances` — pas de seconde table, conformément
au §2.1 — et rattachées par une route qui **n'est pas un chemin d'upload** :

```http
PATCH /api/slots/{scheduleId}/recap/photo   { "photoUrl": "/api/media/files/…", "isPublic": true }
```

Le fichier passe par `POST /api/media/upload/image` (multipart, champ `file`),
le seul chemin d'upload, inchangé. Cette route ne fait que rattacher une URL
déjà servie à une présence confirmée. `photoUrl: null` retire le souvenir, et
retirer la photo remet `memory_is_public` à faux — une image absente ne peut pas
rester publique.

Les `photoUrls` rendues sont donc de la forme `/api/media/files/**`,
authentifiées, celles que le client charge déjà via Dio.

### 2.3 `cityLabel` s'appuie sur une nouvelle colonne

Aucune ville n'était stockée : ni sur `schedules`, ni ailleurs
(`SearchResultDto.city` est câblé à `null` depuis toujours), et
`MapService.reverseGeocode` est un **bouchon** qui renvoie littéralement
`"Mock City"`. Déduire la ville de l'adresse aurait été une heuristique qui se
trompe en silence — et qui ne dit rien justement dans le cas privé, celui où
elle compte.

`schedules.city` est donc ajoutée, nullable, et renseignée par le client via
`city` sur `POST /programs/{id}/schedules` et `PATCH /schedules/{id}`. Absente,
`cityLabel` vaut `null` : la carte n'invente pas de lieu. Le jour où un
géocodeur réel est branché, il remplira cette colonne sans que le contrat bouge.

---

## 3. Les routes

Toutes authentifiées, corps en camelCase.

```http
GET    /api/slots/{scheduleId}/recap
POST   /api/slots/{scheduleId}/recap/vibes       { "vibes": ["RELAXED","FRIENDLY"] }
DELETE /api/slots/{scheduleId}/recap/vibes
PATCH  /api/slots/{scheduleId}/recap/consent     { "showIdentity": true }
PATCH  /api/slots/{scheduleId}/recap/photo       { "photoUrl": "…", "isPublic": true }
PATCH  /api/slots/{scheduleId}/recap/note        { "note": "…" }
PATCH  /api/slots/{scheduleId}/recap/visibility  { "visibility": "PUBLIC" }

GET    /api/recaps/feed?lat=&lng=&radiusMeters=
GET    /api/recaps/mine
```

Toutes les routes de contribution **créent la carte** si elle n'existe pas
encore, et **rendent le `SlotRecapDto` complet** — le client n'a pas à
enchaîner une lecture après chaque tap.

`POST .../vibes` remplace la sélection de l'appelant, un tableau vide équivaut
au `DELETE`. `/api/recaps/feed` prend exactement les paramètres et les bornes de
`/api/slots/feed` (`radiusMeters` entre 500 et 50000) et rend un **tableau nu**,
plafonné à 100 cartes, les plus récemment publiées d'abord.

`PARTICIPANTS` est accepté sur `visibility` et traité comme `PRIVATE`.

---

## 4. Codes d'erreur

Les cinq codes demandés sont là, avec leurs statuts, et traduits dans les trois
catalogues (`messages.properties`, `_en`, `_de`).

| Situation | Statut | `code` |
|---|---|---|
| Publication sans participant non-hôte confirmé | `409` | `RECAP_NEEDS_ATTENDEE` |
| Fenêtre de 7 jours fermée | `409` | `RECAP_WINDOW_CLOSED` |
| L'appelant n'était pas présent | `403` | `RECAP_NOT_ATTENDEE` |
| L'appelant n'est pas l'hôte | `403` | `RECAP_NOT_HOST` |
| Plus de 2 vibes, ou valeur hors enum | `422` | `RECAP_INVALID_VIBES` |

Deux points d'implémentation qui vous concernent :

**Le `409` a demandé une exception neuve.** Aucun chemin de l'API ne produisait
jusqu'ici un `409` porteur d'un code métier — les seuls venaient d'une
`IllegalStateException`, qui ne sait pas en porter. `ConflictException` a été
ajoutée à côté des existantes ; elle est additive et ne change rien aux refus
déjà publiés.

**Les vibes sont reçues en `List<String>`, pas typées.** Une valeur inconnue
désérialisée directement en enum aurait échoué **avant** d'atteindre le service,
et vous auriez reçu un `400 INVALID_JSON` générique au lieu du `422
RECAP_INVALID_VIBES` traduisible. La validation est donc faite à la main, et
c'est délibéré.

---

## 5. Le DTO

Conforme au §6, sans un champ de plus. En particulier : **ni `lat`, ni `lng`,
ni adresse** — un test le verrouille par réflexion sur les composants du record,
pour qu'aucune évolution future ne les réintroduise par inadvertance.

Les trois plafonds sont appliqués côté serveur : `topVibes` ≤ 3 triées par
nombre de votes décroissant (à égalité, ordre alphabétique, pour qu'une même
carte rende toujours les mêmes trois), `photoUrls` ≤ 3, `visibleAttendees`
limitée aux consentants.

Deux précisions sur `visibleAttendees` : l'hôte n'y figure pas — il a son propre
champ `host`, et l'y répéter le ferait apparaître deux fois sur la carte — et
les comptes désactivés en sont exclus.

`nextSlot` cherche la prochaine séance **`OPEN` et à venir** du même programme,
la plus proche d'abord. `FULL` est exclu : proposer un créneau complet est un
cul-de-sac, et vous préférez proposer l'abonnement au programme. `alreadyJoined`
est vrai si l'appelant héberge ce créneau, l'a rejoint, ou suit le programme
dont il est la séance.

---

## 6. Ce qui a été réutilisé plutôt que réécrit

- **la vérification de présence** : `existsByScheduleIdAndUserIdAndWasPresentTrue`,
  la requête même qui sert la boucle de recommandation ;
- **la règle de lieu** : `SlotAddressVisibility` reste seule juge de l'adresse,
  la carte n'en expose simplement aucune ;
- **les filtres de visibilité du feed** : programme public, auteur actif,
  activité visible sur la carte — copiés de `findOpenSlotsInRadius`, moins ce
  qui n'a pas de sens pour un moment passé (statut du créneau, fenêtre de
  dates, programme encore `ACTIVE` : une carte est la trace de ce qui a eu
  lieu, pas une invitation à s'inscrire) ;
- **l'audience d'un créneau** : `SlotAudience` pour décider qui a le droit de
  lire une carte privée ;
- **le chemin d'upload** : inchangé, non doublé ;
- **le sanitizer** : `HtmlSanitizer`, comme sur `welcomeNote` et `joinMessage`.

Au passage, la convention « fin d'un créneau » (`endsAt`, sinon `startsAt + 2h`)
était écrite en trois exemplaires. La fenêtre de sept jours en aurait fait un
quatrième : elle est désormais dans `SlotTiming`, et les trois appelants
existants pointent dessus.

---

## 7. Deux comportements à connaître

**L'effectif est dénormalisé.** `slot_recaps.attendee_count` est tenu comme
`schedules.participant_count` : réaligné à chaque contribution, et aussi à
chaque confirmation de présence — sans quoi quelqu'un confirmant après la
dernière contribution ne serait jamais compté.

**La fenêtre de sept jours fige tout, y compris pour l'hôte.** Note et
visibilité comprises. « La carte se fige » a été pris au mot : passé sept
jours, une carte publiée ne peut plus changer sous les yeux de ceux qui l'ont
partagée.

---

## 8. Hors périmètre, comme demandé

L'image PNG partageable générée côté serveur n'est **pas** implémentée : la
raison invoquée (bibliothèque de rendu, image Docker, `Deploy Ran Out of
Memory`) tient, et la voie OpenGraph reste la sobre le jour venu.

Aucune des tentations du §9 n'a d'accroche dans le schéma : pas de colonne de
note, pas de compteur de réactions, pas de champ de performance. C'est
volontaire — ce qui n'existe pas au contrat ne réapparaît pas dans une demande
ultérieure.
