# Réponse backend au lot 2 — `Accept-Language`

> Demande 3 (a, b) de `PROMPT_BACKEND_EVOLUTIONS_2026-08.md`, avec les règles de
> repli confirmées dans `REPONSE_CLIENT_EVOLUTIONS_2026-08.md` § Q1.
>
> Le point (c), les codes d'erreur stables, était déjà livré — voir
> `REPONSE_BACKEND_EVOLUTIONS_2026-08.md`.
>
> Fait suite à `REPONSE_BACKEND_LOT1_2026-08.md` (`programCount`, maille
> d'arrondi).

---

## 1. Ce qui est livré

**Vous pouvez brancher votre intercepteur Dio.** `Accept-Language` est honoré sur
toutes les routes, avec vos deux règles.

| En-tête | Langue servie |
|---|---|
| `de`, `de-DE`, `de-DE, de;q=0.9, en;q=0.8` | allemand |
| `en`, `en-GB` | anglais |
| `fr`, `fr-CA` | français |
| `it` — présent, non supporté | **anglais** |
| `it-IT, de;q=0.9, en;q=0.8` | **allemand** (première langue supportée de la liste) |
| **absent** | **français** |
| présent mais illisible | anglais |

Le dernier cas est un choix que nous avons tranché seuls : un en-tête malformé
est un en-tête *présent*, donc pas un binaire historique. Dites-nous si vous
préférez le français.

Ce n'est pas le comportement par défaut de Spring — `AcceptHeaderLocaleResolver`
retombe sur la locale par défaut dans les deux cas, absent comme non supporté.
Votre réserve était juste, et il a fallu un resolver dédié.

### Textes traduits

| Texte | Route |
|---|---|
| `clarificationQuestion` | `POST /search` |
| `emptyStateActions[].label` — les 6 gabarits | `POST /search` |
| `message` des refus nommés — les 25 codes | toutes |

`suggestedAlternatives[]` suit automatiquement : il est dérivé des labels
(`SearchResponse.empty()`).

### Ce qui n'est pas traduit, délibérément

- **Les valeurs d'énumération** — `resultType`, `type`, `status`,
  `EmptyStateActionType`, et les codes d'erreur. Vous les parsez ; elles restent
  en anglais SCREAMING_SNAKE_CASE quelle que soit la langue. Testé.
- **Les refus sans code nommé.** La traduction passe par la clé
  `error.<CODE>` : un refus qui n'en a pas garde mot pour mot le message qu'il
  produisait. C'est ce qui rend le changement additif — pas d'en-tête ⇒ locale
  `fr` ⇒ mêmes chaînes qu'avant. Testé sur un `VALIDATION_ERROR`, qui reste en
  français même avec `Accept-Language: de`.

---

## 2. Deux points où nous nous écartons de votre demande

Les deux vont dans le sens de vos utilisateurs, mais vous devez les connaître
avant de brancher.

### 2.1 « En-tête absent ⇒ français » ne s'applique pas à la clarification

**Votre règle et votre exigence de non-régression se contredisent ici**, et nous
avons tranché pour la non-régression.

`clarificationQuestionFor` devine aujourd'hui la langue à partir **des mots de la
requête** (`RuleBasedIntentExtractor`, cf. notre réponse 10) :

```java
if (normalizedText.matches(".*\\b(ich|will|suche|langweilig)\\b.*")) → allemand
```

Un germanophone qui tape « ich will etwas tun » reçoit donc de l'allemand
**aujourd'hui, sans aucun en-tête**. Appliquer « absent ⇒ français » le ferait
basculer en français : une régression visible, chez des utilisateurs réels de
binaires déployés — exactement ce que votre point (d) cherche à éviter.

**Comportement retenu :**

| Requête | `Accept-Language` | Réponse |
|---|---|---|
| « ich will etwas tun » | absent | allemand *(comportement d'avant, préservé)* |
| « ich will etwas tun » | `en` | anglais *(l'en-tête fait autorité)* |
| « je m'ennuie » | absent | français |
| « je m'ennuie » | `de` | allemand |

Les quatre lignes sont testées. L'heuristique ne s'applique **qu'en l'absence
d'en-tête** : dès que votre intercepteur sera en place, elle ne s'exécutera plus
jamais et pourra être retirée. Dites-nous quand ce sera le cas.

### 2.2 Les refus d'inscription étaient en anglais — ils passent au français

C'est le seul endroit où « sans en-tête » ne rend pas exactement la chaîne
d'avant.

Les refus de `ProgramEnrollmentService` étaient rédigés **en anglais** dans une
API dont la langue par défaut est le français :

| Avant, sans en-tête | Maintenant, sans en-tête |
|---|---|
| `You are already enrolled in this program` | `Vous êtes déjà inscrit à ce programme.` |
| `You cannot join your own program` | `Vous ne pouvez pas rejoindre votre propre programme.` |
| `Program is not active and cannot accept new participants` | `Ce programme n'est pas actif et n'accepte plus de participants.` |
| `Progress percentage must be between 0 and 100` | `La progression doit être comprise entre 0 et 100.` |
| *(et 7 autres sur le même chemin)* | |

Nous avons jugé que servir de l'anglais à un utilisateur francophone était le
vrai défaut, et que le corriger valait mieux que le figer au nom de la
non-régression. Mais c'est **un changement visible sans en-tête**, contrairement
au reste de ce lot, et vous l'afficherez verbatim (`api_client.dart:258-262`).

Les codes n'ont pas changé — `PROGRAM_ALREADY_ENROLLED` reste
`PROGRAM_ALREADY_ENROLLED`. Si vous aviez branché quoi que ce soit sur le texte
anglais, c'est le moment de le dire.

---

## 3. Vérification

`AcceptLanguageIntegrationTest`, 13 tests, un par critère d'acceptation du §3 de
votre prompt initial :

- clarification en `de`, en `en`, sans en-tête (français), avec `it` (anglais),
  avec une liste qualifiée `it-IT, de;q=0.9, en;q=0.8` (allemand) ;
- l'heuristique par mots-clés préservée sans en-tête, et l'en-tête qui prime
  dessus ;
- `emptyStateActions[].label` dans la même langue que la clarification, et
  français sans en-tête ;
- `type` des actions jamais traduit ;
- `message` d'un refus dans les trois langues, `code` identique dans les cinq
  cas (`fr`, `en`, `de`, `it`, absent) ;
- un refus sans code nommé qui garde son message d'origine même en allemand.

Suite complète : **213 → 226 tests**, 11 échecs + 2 erreurs identiques avant et
après, tous préexistants.

Effet de bord assumé : `Messages` est branché sur les **vrais** bundles dans les
tests unitaires de `RuleBasedIntentExtractor`, pas sur un mock. Une clé absente
de `messages*.properties` fait donc échouer les tests au lieu de passer
inaperçue.

---

## 4. Ce que nous n'avons pas fait

**Les libellés de catégories et de niveaux** — le point (b.4) de votre demande,
que vous marquiez « souhaitable, à confirmer ». Ils viennent de la base
(`Category.name`), pas de fichiers de messages : les traduire suppose des
colonnes par langue ou une table de traductions, c'est-à-dire une migration et
un chantier de contenu, pas un `MessageSource`. Hors de ce lot. Dites-nous si
c'est un besoin réel et nous le chiffrerons séparément.

---

## 5. Suite

| | Attendu | État |
|---|---|---|
| 0 | SHA + horodatage, maille d'arrondi | ✅ lot 1 |
| 1 | `programCount` + `scheduleCount` | ✅ lot 1 |
| 2 | **Demande 3** — `Accept-Language` | ✅ ce document |
| 3 | `truncated` / `totalInBounds` sur `/map/bounds` | à faire, suivant |
| 4 | `truncated` sur `/programs` | ❌ impossible additivement, cf. lot 1 §3 |
| 5 | **Demande 1** — `/activities/browse` | à faire |
| 6 | **Demande 2** — pagination `/search` | à faire |
| 7 | **Demande 4** — RRULE | à faire |

Le SHA déployé de ce lot vous sera donné à la mise en production, comme convenu.
