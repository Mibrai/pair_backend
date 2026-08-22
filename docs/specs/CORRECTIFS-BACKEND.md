# Backend — correctifs de performance

**Destinataire :** équipe backend (`Mibrai/pair_backend`)
**Origine :** campagne de tests de charge côté mobile, 22 août 2026
**Mesures brutes :** `load/RESULTATS-2026-08-22.md`

---

## Résumé

L'app met **6,3 secondes à s'ouvrir avec un seul utilisateur connecté**, sans
aucune charge concurrente. Le temps est intégralement du traitement serveur
(TCP+TLS = 30 ms, route statique = 116 ms).

La cause est identifiée et localisée : **un N+1 dans le mapping vers les DTO**.
Le temps de réponse suit le *nombre d'éléments rendus*, pas la surface
géographique fouillée.

| rayon | éléments rendus | temps |
|---:|---:|---:|
| 500 m | 0 | 1 011 ms |
| 2 000 m | 0 | 996 ms |
| 4 000 m | 2 | 5 003 ms |
| 6 000 m | 3 | 5 627 ms |
| 8 000 m | 4 | 7 149 ms |
| 12 000 m | 4 | 7 103 ms |
| 30 000 m | 4 | 7 158 ms |

Passé 8 km la base ne contient plus de créneau : le temps **cesse net**
d'augmenter alors que la zone balayée est multipliée par 14. Un défaut d'index
spatial produirait l'inverse. Droite mesurée : **~1,0 s fixe + ~1,5 s par
élément.**

**Urgence :** l'app abandonne au bout de 30 s (`api_client.dart:19`). À une
vingtaine de créneaux dans le rayon consulté, `/slots/feed` dépasse ce délai et
l'écran se vide sans message. Le seuil sera franchi par la croissance des
données, pas par la fréquentation.

### Temps de réponse mesurés (médiane, 4 passages, 1 utilisateur)

| Route | méd. |
|---|---:|
| `GET /programs?lat&lng&radius_km` | 5 260 ms |
| `GET /slots/feed` | 4 684 ms |
| `GET /map/users` | 2 737 ms |
| `POST /search` | 2 328 ms |
| `GET /conversations` | 1 799 ms |
| `GET /users/me` | 927 ms |
| `GET /notifications/unread-count` | 654 ms |

Aucune route ne descend sous 650 ms — y compris celles qui ne font que compter
des lignes. Cela suggère un coût fixe par requête SQL bien au-dessus du normal,
d'où la vérification n° 0 ci-dessous.

---

## 0. À vérifier en premier — 5 minutes, potentiellement un facteur 10

À ~1,5 s pour une quinzaine de requêtes SQL, chaque aller-retour vers la base
coûte de l'ordre de **100 ms**. Sur un réseau privé Railway, l'ordre de grandeur
attendu est de 1 à 5 ms.

```bash
railway variables | grep PGHOST
```

- `…​.railway.internal` → réseau privé, la latence n'est pas là, tout se joue
  dans les correctifs 1 à 6.
- `…​.proxy.rlwy.net` (ou une adresse publique) → **chaque requête sort et
  rentre du réseau**. Basculer sur l'hôte interne diviserait tous les temps par
  un facteur important, sans toucher une ligne de code.

Vérifier également que le service applicatif et la base sont dans la **même
région** (le service répond depuis `ams1`).

---

## 1. `GET /slots/feed` — le patron correct existe déjà dans le repo, il n'est pas appliqué

**Fichier :** `src/main/java/org/program/pair/domain/program/SlotService.java:74`

```java
List<Schedule> slots = scheduleRepository.findOpenSlotsInRadius(…);

return slots.stream()
    .filter(s -> !s.getProgram().getUserActivity().getUser().getId().equals(requesterId))
    .map(s -> toFeedItem(s, request.lat(), request.lng(), requesterId))
    .toList();
```

`findOpenSlotsInRadius` est une **requête native qui renvoie des entités**
(`ScheduleRepository.java:314`). Une requête native ne peut pas porter de
`JOIN FETCH` : toutes les associations restent paresseuses, et chaque élément
déclenche ensuite sa propre cascade.

Le commentaire du dépôt décrit pourtant déjà le bon patron, aux lignes 53-60 du
même fichier :

> *Ne renvoie que des ids : la reprise par `findWithActivityDetailsByIds`
> conserve les `LEFT JOIN FETCH` qui évitent le N+1 sur program → userActivity
> → activity → category. Une requête native renvoyant directement des entités
> les perdrait […] — l'inverse du but.*

Ce patron est appliqué à la carte, **pas au feed**.

**Correctif** — faire renvoyer des ids à la requête native (comme
`findOpenSlotIdsInRadius`), puis recharger avec les fetch joins existants en
**conservant l'ordre du SQL**, qui porte le tri par disponibilité et distance :

```java
List<UUID> ids = scheduleRepository.findOpenSlotIdsInRadius(…);   // SELECT s.id …
if (ids.isEmpty()) return List.of();

Map<UUID, Schedule> parId = scheduleRepository.findWithActivityDetailsByIds(ids).stream()
    .collect(Collectors.toMap(Schedule::getId, Function.identity()));

List<Schedule> slots = ids.stream()          // l'ordre du ORDER BY natif est ici
    .map(parId::get)
    .filter(Objects::nonNull)
    .toList();
```

⚠️ `findWithActivityDetailsByIds` ne garantit aucun ordre : sans la reprise par
`ids`, le tri par disponibilité puis distance serait silencieusement perdu.

**Gain attendu :** 4 requêtes par élément supprimées (program, userActivity,
activity, category).

---

## 2. `toFeedItem` — un profil public complet rechargé par élément

**Fichier :** `SlotService.java`, méthode `toFeedItem`

```java
userService.getPublicProfile(userActivity.getUser().getId(), requesterId)
```

`UserService.getPublicProfile` → `findActiveUser` puis `toPublicDto`, qui
enchaîne par appel :

- `findActiveUser(targetId)` — 1 requête
- `subscriptionService.countAuthorSubscribers(...)` — 1 requête
- `subscriptionService.isSubscribedToAuthor(...)` — 1 requête
- `user.getPrivacySettings()` — association paresseuse, 1 requête
- `badgeAwardRepository.findByUserId(...)` — 1 requête
- puis **`award.getBadge().getCode()` — une requête par badge** (voir § 4)

Soit **6 requêtes au minimum, par créneau du feed**, alors que plusieurs
créneaux partagent très souvent le même hôte.

**Correctif** — calculer les profils **une fois** pour l'ensemble du lot, et
passer la table au mapping :

```java
Map<UUID, UserPublicDto> profils = slots.stream()
    .map(s -> s.getProgram().getUserActivity().getUser().getId())
    .distinct()
    .collect(Collectors.toMap(Function.identity(),
                              id -> userService.getPublicProfile(id, requesterId)));
```

`toFeedItem` reçoit alors `profils` et y lit l'entrée au lieu d'appeler le
service. Même remarque pour `getMySlots` (`SlotService.java:418`) et pour tous
les autres appelants de `toFeedItem` (lignes 109, 216, 310, 435).

---

## 3. Deux requêtes de participation par élément

Toujours dans `toFeedItem`, pour chaque créneau :

```java
SlotAddressVisibility.resolve(slot, requesterId, participationRepository)
// → participationRepository.existsByScheduleIdAndUserIdAndStatus(…)

participationRepository.findByScheduleIdAndUserId(slot.getId(), requesterId)
```

**Correctif** — une seule requête pour tout le lot :

```java
@Query("SELECT p FROM SlotParticipation p " +
       "WHERE p.user.id = :userId AND p.schedule.id IN :scheduleIds")
List<SlotParticipation> findByUserIdAndScheduleIdIn(@Param("userId") UUID userId,
                                                    @Param("scheduleIds") Collection<UUID> scheduleIds);
```

La `Map<UUID, SlotParticipation>` qui en résulte alimente à la fois le statut de
participation, la position en liste d'attente **et** `SlotAddressVisibility`
(dont la surcharge prendrait la participation déjà chargée plutôt que le dépôt).

---

## 4. Badges — un N+1 imbriqué dans le N+1

**Fichier :** `UserService.toPublicDto`, et
`repository/BadgeAwardRepository.java:16`

```java
List<BadgeAward> findByUserId(UUID userId);   // pas de fetch join
…
award.getBadge().getCode()                    // 1 requête PAR badge
```

**Correctif :**

```java
@Query("SELECT a FROM BadgeAward a JOIN FETCH a.badge WHERE a.user.id = :userId")
List<BadgeAward> findByUserIdWithBadge(@Param("userId") UUID userId);
```

C'est le multiplicateur le plus vicieux du lot : il croît avec le nombre de
badges gagnés, donc **il empire à mesure que les utilisateurs sont actifs**.

---

## 5. `GET /programs` — même défaut, route la plus lente mesurée

**Fichier :** `ProgramService.getNearbyPrograms`

```java
return programRepository.findVisibleNearScheduleOrOrganizer(lat, lng, radiusMeters, 100)
    .stream()
    .map(p -> toDto(p, requesterId))
    .collect(Collectors.toList());
```

Requête native renvoyant des entités, puis un `toDto` par élément : le schéma du
§ 1 à l'identique. C'est la route la plus lente mesurée (5 260 ms), et elle est
appelée à **chaque ouverture de l'app**.

À traiter avec le même patron : ids → rechargement avec fetch joins → mapping
sur des données déjà en mémoire.

---

## 6. Configuration — aucun garde-fou n'est posé

`src/main/resources/application.properties` ne contient **ni réglage Hikari, ni
`default_batch_fetch_size`, ni `open-in-view`**. Les valeurs par défaut
s'appliquent donc :

```properties
# Charge les associations paresseuses par paquets au lieu d'une par une.
# Filet de sécurité général : réduit tout N+1 restant d'un facteur ~30.
spring.jpa.properties.hibernate.default_batch_fetch_size=32

# Par défaut à true : la connexion à la base reste retenue pendant toute la
# sérialisation de la réponse. Avec un pool de 10 et des réponses à plusieurs
# secondes, c'est ce qui fera tomber le service en premier sous charge.
spring.jpa.open-in-view=false

# Par défaut : 10 connexions. À ajuster sous la limite du plan Postgres.
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=10000
```

Le pool à 10 est un point de rupture identifié côté mobile : **l'app émet 11
requêtes en parallèle à chaque ouverture** (`refresh_on_resume.dart`), soit plus
que le pool entier pour un seul utilisateur.

---

## 7. Comment chiffrer le gain

**Avant de corriger**, activer temporairement le comptage pour obtenir le nombre
exact de requêtes par appel — le présent document l'estime à 12-20 par élément,
la mesure le dira :

```properties
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.stat=DEBUG
```

Appeler `/api/slots/feed?lat=52.52&lng=13.405&radiusMeters=10000` et relever
`queries executed to database` dans le journal.

**Après correction**, la suite de tests côté mobile rejoue le même scénario en
une commande et sort un rapport comparable :

```bash
cd pair_mobile/load && ALLOW_PROD=1 ./run.sh smoke
```

Cible raisonnable : **`/slots/feed` sous 500 ms** pour une dizaine d'éléments,
avec un nombre de requêtes SQL **constant** (indépendant du nombre d'éléments).
C'est cette constance qui prouve que le N+1 est parti, pas le temps absolu.

---

## 8. Limiteurs de débit — bloquants pour les tests, à arbitrer

Deux limiteurs ont été rencontrés. Ils protègent correctement la production ;
ils rendent aussi toute campagne de charge impossible depuis une IP unique.

| Route | Comportement observé | Effet |
|---|---|---|
| `POST /auth/register` | **~2 inscriptions/heure/IP** — `RATE_LIMITED — "Trop d'inscriptions. Réessayez dans 1 heure."` | Constituer un pool de 200 comptes demanderait 4 jours. |
| `POST /auth/login` | **Blocage de 15 minutes** après ~8 connexions — `"Trop de tentatives. Réessayez dans 15 minutes."` | Un scénario de charge est coupé dès son démarrage. |

Le refus d'inscription est rendu en 40 ms contre 1 070 ms pour une inscription
réelle : le limiteur tranche avant tout traitement, et même avant de vérifier si
l'adresse existe déjà (une adresse déjà prise reçoit 429, pas 409).

**Demande :** une exemption d'IP, ou un profil de recette où ces limiteurs sont
relâchés. Sans cela, les scénarios de montée en charge resteront inexécutables.

---

## 9. Points mineurs relevés

- **Double inscription à un créneau → 422**, alors que 409 serait la réponse
  attendue pour un conflit d'état. Sans gravité, mais à confirmer comme
  volontaire : les clients écrits contre le contrat traitent 409.
- **`GET /programs` sans `lat`/`lng` renvoie *mes* programmes**, pas les
  programmes publics (`ProgramController.java:48-58`). C'est cohérent avec
  l'usage de l'app, mais le nom de la route ne le laisse pas deviner et le
  contrat OpenAPI ne le dit pas.
- **`radius_km` est plafonné à 100** (`MAX_RADIUS_KM`, réponse 400 au-delà).
  Comportement correct — mentionné parce que le plafond n'apparaît pas dans
  `/v3/api-docs`, et que le client mobile autorise des rayons plus larges sur
  d'autres routes (`/slots/feed` accepte jusqu'à 50 km).

---

## Ordre suggéré

1. **§ 0** — vérifier `PGHOST` (5 min, potentiellement un facteur 10).
2. **§ 7** — compter les requêtes avant correction, pour avoir un point de départ.
3. **§ 6** — la configuration : trois lignes, aucun risque, gain immédiat.
4. **§ 1 et § 5** — le patron ids + fetch joins sur le feed et sur `/programs`.
5. **§ 2, § 3, § 4** — mutualiser profils, participations et badges.
6. **§ 8** — l'exemption d'IP, pour que la campagne de charge puisse enfin avoir lieu.
