# Réponse backend au lot 1 — `programCount`, maille d'arrondi, déploiement

> Réponse à `REPONSE_CLIENT_EVOLUTIONS_2026-08.md`, points 0 et 1 de votre
> tableau « Ce que nous attendons ensuite ».
>
> Fait suite à `REPONSE_BACKEND_EVOLUTIONS_2026-08.md`, qui reste la référence
> pour les demandes 1 à 5.

---

## 0. Déploiement — le SHA que vous demandez, et une mise au point

**SHA déployé le 4 août : `5bb9e0d9f5a0595e9471920e279ce211a7a3372d`**, poussé à
**18:14:25 +02:00**. C'est la tête de `feat/backend-evolutions-2026-08`, qui
porte les trois commits des demandes 6, 3(c) et 5 (options A et B).

Vos mesures correspondent exactement à ce commit — nous avons rejoué les vôtres
de notre côté et retrouvé vos chiffres au marqueur près : 69 sans rayon, 24 à
5 km, 13 clusters + 10 isolés au zoom 7 dont un premier à `count: 25` et
`categoryIcon: "dumbbell"`.

**Mais il faut que vous sachiez ceci** : au moment de vos mesures, ce commit
**n'était pas sur `master`**. `master` était encore à `23cfd6d`. La plateforme
déploie la branche poussée, pas la branche par défaut — autrement dit, chez nous
`git push` d'une branche de travail suffit à mettre en production.

La branche a depuis été fusionnée (`d090ead`), donc `master` et la production
coïncident à nouveau. Nous vous le signalons parce que cela change la valeur de
la règle que vous posez au §4 — « nous ne brancherons aucun code contre un
contrat que nous ne pouvons pas interroger en production ». Cette règle est
bonne, mais elle ne vous protège pas ici : sur ce dépôt, « interrogeable en
production » n'implique pas « fusionné », et une branche abandonnée pourrait
disparaître de la production sans que rien ne soit annulé côté `master`.

**Ce que nous vous proposons** : continuez à vérifier en production, mais
demandez-nous le SHA à chaque fois plutôt que de l'inférer du comportement. Nous
le donnerons systématiquement. C'est ce que fait ce document.

---

## 1. `programCount` — corrigé, et `scheduleCount` ajouté

### Le correctif

`MapService` comptait `locationSchedules.size()`, c'est-à-dire des **créneaux**.
Il compte désormais des programmes distincts :

```java
int programCount = (int) locationSchedules.stream()
    .map(s -> s.getProgram().getId())
    .distinct()
    .count();
```

Votre quatrième cas — la confirmation de suppression — était le bon argument. Un
compteur faux dans un dialogue destructif n'est pas un détail d'affichage, et
c'est celui qui a emporté la décision de ne pas temporiser.

### `scheduleCount`

**Oui, c'était trivial** : c'est littéralement la valeur qui alimentait
`programCount` par erreur, désormais exposée sous son vrai nom. Champ additif sur
`MapActivityMarkerDto`.

```jsonc
{
  "activityId": "…",
  "programCount": 1,     // programmes distincts à ce lieu
  "scheduleCount": 3,    // créneaux à ce lieu, tous programmes confondus
  …
}
```

Invariant garanti et testé : `scheduleCount >= programCount >= 1`. Votre
« 1 programme · 3 séances » est donc constructible, et votre badge de pin peut
choisir lequel des deux il affiche.

### Ce à quoi vous attendre

Vous aviez raison sur l'ampleur : sur les activités à créneau hebdomadaire — la
majorité — le compteur passe de 3, 5 ou « 9+ » à **1**. C'est la valeur juste.

---

## 2. La maille d'arrondi — figée, et un piège de portage

Vous demandez le nombre de décimales et le mode d'arrondi. Les voici, et la
règle est désormais documentée dans l'OpenAPI (`MapActivityMarkerDto`) plutôt que
laissée en détail d'implémentation.

**Clé de regroupement** : `(activityId, lat arrondie, lng arrondie)`.

| | Valeur |
|---|---|
| Décimales | **3** (~111 m en latitude) |
| Mode | **Au plus proche, demis vers +∞** |
| Formule exacte | `Math.round(v * 1000.0) / 1000.0`, soit `floor(v * 1000 + 0.5) / 1000` |

### Le piège

**« Demis vers +∞ » n'est pas « demis à l'opposé de zéro ».** Les deux règles
coïncident partout sauf sur les demis exacts, et sur ceux-là elles divergent dès
que la valeur est **négative** :

| `v` | `Math.round` (Java, nous) | `num.round()` (Dart, vous) |
|---|---|---|
| `2.3465` | `2.347` | `2.347` |
| `-2.3465` | **`-2.346`** | **`-2.347`** |

En latitude nord et longitude est, aucune divergence. En **longitude
occidentale** — donc partout à l'ouest de Greenwich, ce qui inclut une bonne
part de la France — et en latitude australe, votre clé s'écarterait de la nôtre
sur les coordonnées tombant pile sur un demi-millième de degré.

C'est rare, ce n'est pas nul, et l'effet serait exactement celui que vous
craignez : un lieu que nous distinguons et que vous fusionnez, ou l'inverse.

**Formule à utiliser côté Dart**, qui reproduit la nôtre exactement :

```dart
double _cellKey(double v) => ((v * 1000) + 0.5).floorToDouble() / 1000;
```

`floor(x + 0.5)` est la définition littérale de `Math.round` en Java.
**N'utilisez pas `.round()`.**

### Un point qui vous évitera une passe de débogage

`lat` et `lng` du marqueur sont les coordonnées **exactes** d'un des créneaux du
groupe, pas la valeur arrondie. Ce n'est pas un problème : arrondir la valeur
émise avec la règle ci-dessus redonne bien la clé du groupe, puisque tous les
membres du groupe arrondissent vers la même cellule. Mais ne vous étonnez pas de
voir des `lat` à sept décimales dans la réponse.

---

## 3. Vos questions restantes, répondues

### `truncated` sur `GET /programs` — non, pas additivement

Nous avons regardé. `GET /api/programs` renvoie un **`List<ProgramDto>` nu**
(`ProgramController.java:47-57`), pas une enveloppe. Y ajouter `truncated` et
`totalCount` suppose de passer d'un tableau JSON à un objet — c'est exactement la
rupture que vous redoutiez, et elle casserait tout client déployé qui itère la
réponse.

Nous retenons donc votre repli, qui est aussi notre préférence : **`/activities/browse`
naîtra correctement paginé** (enveloppe `PagedModel { content, page }`, cf.
réponse 5 du document précédent), et vous abandonnerez `/programs` pour l'écran
Explorer. Cela ne laisse aucun trou : d'ici là, le plafond de 100 est le même
qu'aujourd'hui, ni pire ni meilleur.

Si vous avez besoin d'un signal intermédiaire avant `/activities/browse`, la
seule option non cassante est un **en-tête de réponse** (`X-Total-Count`,
`X-Truncated`). Dites-le et nous l'ajoutons — mais notre avis est que ça ne vaut
pas le coup pour une route que vous allez quitter.

### `permitAll()` sur `/map/activities` — nous tranchons, plus tard

Merci pour les trois faits vérifiables : votre `AuthInterceptor` sans liste
d'exclusion, le routeur qui ne déclare que deux routes publiques, et la
confirmation du 200 sans jeton. C'est précisément ce qui manquait.

Vous ne dépendez donc ni du `permitAll()` ni de son retrait. La décision est
chez nous, et nous ne la prenons **pas dans ce lot** : c'est un choix produit
(carte publique assumée ou non), pas un correctif, et le mélanger à un lot de
correctifs le ferait passer inaperçu.

Nous notons votre avis — la route expose adresses, noms d'organisateurs et
avatars d'utilisateurs qui ont coché `visibleOnMap` pour les membres, pas pour un
scraper anonyme — et nous le trouvons juste. En attendant,
`MapActivitiesIntegrationTest.shouldRequireAuthentication` **continue d'échouer**,
et nous le laissons échouer sciemment plutôt que de l'aligner sur un
`permitAll()` que nous n'avons pas encore validé. Un test rouge qui pose une
question ouverte vaut mieux qu'un test vert qui l'enterre.

### Votre bug de déduplication

Rien à faire de notre côté : la maille est documentée (§2), et le
`(activityId, lat arrondie, lng arrondie)` que vous décrivez est la bonne clé.
Attention seulement à la formule d'arrondi.

---

## 4. Vérification

Trois tests d'intégration ajoutés à `MapActivitiesBoundingIntegrationTest`, qui
passe de 17 à 20 :

- un programme unique à trois créneaux au même lieu → `programCount: 1`,
  `scheduleCount: 3` — le cas exact qui produisait « 3 programmes » ;
- deux programmes coexistant au même lieu (2 créneaux + 1) → `programCount: 2`,
  `scheduleCount: 3` ;
- l'invariant `scheduleCount >= programCount >= 1` sur **tous** les marqueurs
  renvoyés, données de seed comprises.

---

## 5. Suite

Inchangée, et nous enchaînons dans cet ordre :

| | Attendu | État |
|---|---|---|
| 0 | SHA + horodatage | ✅ ce document, §0 |
| 0 | Maille d'arrondi | ✅ ce document, §2 — figée dans l'OpenAPI |
| 1 | `programCount` + `scheduleCount` | ✅ ce document, §1 |
| 2 | **Demande 3** — `Accept-Language`, `it → en` / absent → `fr` | à faire, suivant |
| 3 | `truncated` / `totalInBounds` sur `/map/bounds` | à faire |
| 4 | `truncated` sur `/programs` | ❌ impossible additivement, §3 |
| 5 | **Demande 1** — `/activities/browse` | à faire |
| 6 | **Demande 2** — pagination `/search` | à faire |
| 7 | **Demande 4** — RRULE | à faire |

Nous vous donnerons le SHA déployé de chaque lot.
