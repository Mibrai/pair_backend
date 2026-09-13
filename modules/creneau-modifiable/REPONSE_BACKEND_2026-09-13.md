# Le niveau d'un créneau est désormais celui du créneau — et un `PUT` sans `level` ne l'efface pas

**Date :** 2026-09-13
**Module :** [`creneau-modifiable/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-13.md`](PROMPT_BACKEND_2026-09-13.md) — **P-MU-07**
**Migration :** `V113__schedules_level.sql`

> **En bref.**
>
> - **§1 — votre relevé est exact**, et il en manquait deux : la recherche plein texte et le
>   **filtre de niveau** de `/search` lisaient aussi le profil de l'hôte.
> - **§2 — tout ce que vous demandez est livré** : colonne `schedules.level` nullable, acceptée par
>   `POST` et `PUT`, rendue par `SlotFeedItemDto`, `SearchResultDto` et, en plus, `ScheduleDto`.
> - **§3 — la question du contrat est tranchée par l'existant : le `PUT` est déjà une fusion.**
>   Clé absente ou `null` : le niveau reste en place. **Vous n'avez pas à allumer
>   `slotDeclaredLevel` le jour du déploiement.**
> - **§4 — trois changements que vous n'avez pas demandés**, dont un visible : `SearchResultDto.level`
>   vaut **toujours `null` sur un résultat `program`**.

---

## 1. Le relevé, vérifié

Les trois lectures que vous citez sont bien là où vous le dites (`SlotService.java:784`,
`SemanticSearchService.java:365` et `:564`). Deux autres avaient la même source :

- **`FullTextSearchService`** — le chemin de repli et la couche taxonomique de `/search` —
  sélectionnait `ua.level` pour ses résultats `program` ;
- **le filtre de niveau de `/search`** (« tennis débutant ») ne gardait que les résultats dont
  `level` valait exactement le niveau demandé. Il écartait donc un programme parce que **son
  organisateur** s'était déclaré avancé. Voir §4.2 : c'est le point où corriger l'affichage sans
  toucher au filtre aurait créé une régression.

Côté écriture, ni `CreateScheduleRequest` ni `UpdateScheduleRequest` ne portaient de niveau :
exact.

`NextSlotDto` (carte-souvenir) et la page publique d'un créneau (`PublicSlotView`) **ne portent
aucun niveau**. Rien à y faire.

---

## 2. Ce qui est livré

### 2.1 La colonne

`schedules.level VARCHAR(20) NULL` — même type et même stockage par nom que
`user_activities.level`. **`null` veut dire « non précisé ».**

**Aucun rattrapage** : recopier le niveau de l'hôte dans ses créneaux existants inscrirait le
défaut dans la ligne. Tous les créneaux existants rendent `null` et perdent leur puce, comme vous
l'avez voulu.

### 2.2 L'écriture

| Route | Clé `level` |
|---|---|
| `POST /api/programs/{programId}/schedules` | absente, `null` ou `""` → non précisé ; sinon un nom d'`ActivityLevel` |
| `PUT /api/programs/{programId}/schedules/{scheduleId}` | absente ou `null` → **inchangé** ; `""` → retiré ; sinon un nom d'`ActivityLevel` |
| `POST /api/quick-slots` | le `level` qu'elle portait déjà part **aussi** sur le créneau (§4.1) |

- **Toute valeur d'`ActivityLevel` est acceptée**, pas seulement `ANY`, `BEGINNER`, `ADVANCED` :
  `INTERMEDIATE` et `EXPERT` s'écrivent et se relisent. La casse est tolérée (`beginner`).
- **Une valeur inconnue est refusée en `400 VALIDATION_ERROR`**, sur les deux routes, et ne
  modifie rien.
- La clé est une **chaîne** et non l'énumération, pour une seule raison : permettre `""` en
  modification, comme `primaryLanguage` dans la même requête. Le schéma OpenAPI la déclare avec
  ses `allowableValues`.
- **Dupliquer un programme** recopie le niveau de chaque créneau.

### 2.3 La lecture

| DTO | Routes | `level` |
|---|---|---|
| `SlotFeedItemDto` | `/api/slots/feed`, `/api/slots/{id}`, `/api/slots/mine`, `/api/quick-slots`… | le niveau du créneau, ou `null` |
| `SearchResultDto`, `resultType="slot"` | `POST /api/search` | le niveau du créneau, ou `null` |
| `SearchResultDto`, `resultType="program"` | `POST /api/search` | **toujours `null`** (§4.2) |
| `ScheduleDto` — **nouveau champ** | réponses de `POST` et `PUT …/schedules` | le niveau du créneau, ou `null` |

`ScheduleDto.level` n'était pas demandé. Nous l'avons ajouté pour que la réponse de votre `PUT`
vous dise directement ce qui a été enregistré, sans relire le fil.

**Le niveau personnel de l'hôte n'est plus lu par aucune de ces routes.**

---

## 3. La question du `PUT` : fusion, déjà

**Le `PUT` d'un créneau ne remplace pas l'objet.** Il l'est depuis longtemps : chaque champ de
`UpdateScheduleRequest` suit la règle « `null` veut dire ne touche pas » (`placeName`, `city`,
`welcomeNote`, `accessibilityTags`…). `level` suit la même règle.

Concrètement, pour vous :

- **une app avec `slotDeclaredLevel` éteint** envoie un `PUT` sans `level` → **le niveau déclaré
  est conservé** ;
- **vous pouvez donc déployer, vérifier, puis basculer** dans un commit séparé, comme prévu ;
- **pour qu'un hôte revienne à « non précisé »**, envoyez `"level": ""`. Envoyer `"level": null`
  ne retire rien.

Si votre sérialiseur Dart émet `null` pour un champ non renseigné, ça ne pose aucun problème : le
niveau est laissé tel quel. Il faut seulement que « Retirer le niveau » envoie bien la chaîne vide.

---

## 4. Ce que nous avons changé sans que vous le demandiez

### 4.1 Le créneau rapide

`QuickSlotRequest.level` était documenté comme « niveau attendu », mais il n'écrivait que le profil
(`UserActivity`), et seulement quand l'activité y était ajoutée. Sans changement, un créneau rapide
publié « Débutants » serait désormais sorti **sans puce**.

Il écrit maintenant **aussi le créneau**. Absent, le créneau reste non précisé. Pour le profil,
rien ne change : `ANY` par défaut à la création, et un profil existant n'est jamais réécrit.

### 4.2 `/search` : plus de niveau sur un programme, et un filtre qui n'écarte plus sur un silence

**Un programme ne déclare pas de niveau** : seul un créneau en porte un. Pour un résultat
`program`, la seule valeur disponible était celle de l'organisateur, c'est-à-dire le défaut même
que vous signalez à `:564`. **`SearchResultDto.level` vaut donc `null` pour tout résultat
`program`**, dans les deux moteurs (sémantique et plein texte).

Le filtre de niveau suivait une égalité stricte. Laissé tel quel, il aurait écarté **tous** les
programmes de toute recherche contenant un niveau (« yoga débutant » → zéro programme). **Il
n'écarte désormais que ce qui déclare un autre niveau.** Un niveau non précisé, ou `ANY`, reste
visible. C'est le même principe que la langue d'un créneau : l'absence d'information ne fait pas
exclure. Un test vérifie ce cas, et nous avons contrôlé qu'il échoue quand on remet l'égalité
stricte.

Aujourd'hui, ce filtre ne s'applique qu'aux programmes, qui ne portent jamais de niveau. **En
pratique, il n'écarte donc plus rien.** Faire filtrer les **créneaux** de `/search` sur leur niveau
déclaré est possible, mais vous ne l'avez pas demandé, et nous ne l'avons pas fait.

### 4.3 Ce que nous avons laissé, et qui relève de la même famille

`GET /activities/browse` lit toujours `user_activities.level` à deux endroits : la facette et le
filtre `levels`, ainsi que `BrowsedActivityDto.programs[].level`.

- La **facette et le filtre** portent sur des **activités déclarées par des personnes**. Le niveau
  de la personne y a un sens, nous n'y touchons pas.
- **`BrowsedProgramDto.level`**, en revanche, est le même défaut sous forme de résumé de
  programme. **Hors du périmètre de ce lot.** Si vous l'affichez comme une exigence, dites-le, et
  il suivra la règle du §4.2.

---

## 5. Vérification

**Côté serveur** — `SlotDeclaredLevelIntegrationTest`, 6 tests. Tous les hôtes y sont déclarés
**`ADVANCED`** sur leur profil : aucune assertion ne peut passer si ce niveau ressort.

- un créneau déclaré `BEGINNER` se relit `BEGINNER` par `POST` (réponse), `/slots/{id}`,
  `/slots/feed` et `/search` (`resultType="slot"`). Le résultat `program` du même programme rend
  `null` ;
- un créneau sans niveau rend `null` partout, jamais `ADVANCED` ;
- `PUT` sans la clé → conservé ; `"level": null` → conservé ; `"EXPERT"` → `EXPERT` ;
  `""` → `null` ;
- `"PRO"` → `400` en création comme en modification, et le niveau en place ne bouge pas ;
- créneau rapide avec `BEGINNER` → `BEGINNER` sur le créneau ; sans niveau → `null` ;
- « yoga debutant » : l'intention est bien `BEGINNER`, et le programme sans niveau reste dans les
  résultats.

**Suite entière** : **1595 tests, 198 classes, 0 échec** (15 min 05).

**Côté vous**, votre protocole du §4 s'applique tel quel : relevé de `/v3/api-docs`, puis créneau
« Débutants bienvenus » créé, relu par `/slots/feed` et `/search`, modifié sans toucher au niveau,
relu. **Ajoutez une étape** : remettre le niveau à « non précisé » avec `"level": ""`, et vérifier
qu'il revient `null`.
