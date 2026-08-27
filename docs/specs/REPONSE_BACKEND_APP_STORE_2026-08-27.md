# Réponse — ce que le serveur devait livrer pour l'App Store

> Réponse à `PROMPT_BACKEND_APP_STORE_2026-08-27.md`.
>
> **Les deux points serveur sont corrigés** : le `500` de `GET /api/reports/me`
> (1.1) et le contrat qui annonçait `200` là où le serveur rend `201` (1.2).
>
> **Une correction à votre diagnostic** : ce n'était pas le même défaut de
> sérialisation que celui des écritures. Ce n'était pas de la sérialisation du
> tout, et l'hypothèse méritait d'être écartée — elle aurait mené au mauvais
> endroit.
>
> **Vous n'aviez pas vu le plus coûteux**, et il était juste à côté : la file de
> modération était vide alors que six signalements y attendaient.

---

## 1. Le `500` de `GET /api/reports/me`

### La cause : deux vocabulaires pour une même colonne

`V9` a créé la table `reports` avec son propre jeu de mots :

```sql
status VARCHAR(20) NOT NULL DEFAULT 'OPEN'   -- valeurs : OPEN, RESOLVED, DISMISSED
```

L'entité `Report`, écrite plus tard, en déclare un autre :

```java
public enum ReportStatus { PENDING, REVIEWED, ACTIONED, DISMISSED }
```

Les deux ne se recouvrent que sur `DISMISSED`. Les seeds ont suivi celui de la
table. Le champ étant `@Enumerated(EnumType.STRING)`, chaque **lecture** appelle
`ReportStatus.valueOf("OPEN")` et lève :

```
java.lang.IllegalArgumentException:
  No enum constant org.program.pair.domain.report.ReportStatus.RESOLVED
```

Rien n'attrape cette exception, d'où votre `500 INTERNAL_ERROR` générique.

### Pourquoi l'écriture passait et la lecture non

C'est ce qui a fait ressembler le défaut à un problème de sérialisation.
`createReport` pose `PENDING` **en dur** : il n'y a jamais de conversion
entrante. L'écriture ne pouvait pas révéler le problème, quelle que soit la
qualité de vos essais. Le corps que vous avez obtenu en `201` était d'ailleurs
la preuve que l'entité se sérialise très bien — c'est la conversion **depuis**
la base qui échoue, pas vers le client.

### Le compte de Lena en avait deux, pas un

Vous écriviez « un compte qui possède exactement un signalement ». Il y en avait
un second, semé, et c'est lui qui faisait tomber la page :

```sql
-- V27__reset_and_seed_germany.sql, ligne 1151
('13100000-0000-0000-0000-000000000010',
 '00000000-0000-0000-0000-000000000002', 'USER', '…0007',
 'INAPPROPRIATE_CONTENT', 'Profilbio enthält anstößige Inhalte.',
 'OPEN', …)
```

Une seule ligne hors vocabulaire suffit : la page entière tombe avec elle.

### Ce que vous n'aviez pas vu, et qui pesait plus lourd

**La file de modération était invisible.** Les six signalements semés « ouverts »
n'étaient pas `PENDING` mais `'OPEN'` — donc `GET /api/reports/pending`, la
route des modérateurs, rendait une page **vide** alors que six signalements y
attendaient. Un `500` se remarque ; une file vide passe pour une file traitée.
Pour une application dont la modération est précisément ce qu'Apple regarde au
titre de la règle 1.2, c'était le vrai risque du lot.

**Votre critère « doublon → `409` » n'aurait tenu que par chance.** Le contrôle
d'unicité fait un `SELECT` qui hydrate la ligne existante. Rejoué sur le
signalement créé ce matin — statut `PENDING` — il rend bien `409`. Rejoué sur la
cible `…0007` de Lena, il serait tombé en `500` **avant** d'atteindre le
conflit. Le critère est maintenant vrai dans les deux cas, mais il ne l'était
pas pour la raison que vous pensiez.

### Le correctif

`V82__report_vocabulaire_statut.sql` normalise la table sur le vocabulaire de
l'enum :

| Valeur en base | Devient | Pourquoi |
|---|---|---|
| `'OPEN'` | `PENDING` | même sens — personne ne l'a encore regardé |
| `'RESOLVED'` | `REVIEWED` | et non `ACTIONED` : « clos » ne dit pas si une sanction a suivi |
| `'MISLEADING_INFORMATION'` | `OTHER` | ce motif n'a jamais existé dans `ReportReason` |

Deux arbitrages que nous signalons plutôt que de les enterrer :

> **`RESOLVED` → `REVIEWED`.** Parmi les lignes semées, certaines notes de
> résolution décrivent un avertissement, une autre conclut à l'absence de
> manquement. `REVIEWED` est le seul des deux qui ne prête aucune décision à des
> lignes qui n'en portent pas.

> **`MISLEADING_INFORMATION` → `OTHER`.** Le détail n'est pas perdu, il reste
> dans `description`. L'autre issue serait d'ajouter la valeur à `ReportReason`,
> mais elle change le contrat que votre application désérialise — à arbitrer
> ensemble, pas dans un correctif de données à trois jours d'une soumission.
> Dites-nous si le motif compte pour vous, il se rajoute en une ligne.

Le défaut de colonne passe de `'OPEN'` à `'PENDING'` — une ligne insérée sans
statut le recréait à elle seule — et deux contraintes `CHECK` interdisent
désormais à la base un mot que l'enum ignore. Sans elles, la normalisation
n'aurait valu que pour les lignes d'aujourd'hui.

**C'est une migration** : le correctif prend effet au prochain déploiement, il
n'y a rien à appeler ni à réinitialiser de votre côté.

## 2. Le `201` de `POST /api/reports` au contrat

Vous aviez raison sur le fond — `201` est le bon choix, c'est la spec qui avait
tort. Mais la cause n'était pas un oubli de documentation.

`springdoc` lit la **signature** de la méthode, jamais son corps. Un statut posé
à l'exécution lui reste invisible :

```java
// avant — le 201 est dans le corps, donc absent du contrat
public ResponseEntity<Report> createReport(…) {
    return ResponseEntity.status(HttpStatus.CREATED).body(report);
}

// après — le 201 est dans la signature, donc dans /v3/api-docs
@ResponseStatus(HttpStatus.CREATED)
public Report createReport(…) { … }
```

C'est exactement la forme qu'emploie déjà `POST /api/programs/{programId}/report`,
et c'est pour cette raison qu'elle se documentait juste — vous l'aviez relevé
sans en tirer la cause.

Nous avons préféré cette correction à un `@ApiResponse(responseCode = "201")`
ajouté à la main : celui-ci n'aurait été qu'un second endroit à tenir à jour,
libre de rediverger le jour où le statut change. **Le comportement réseau ne
bouge pas** : même `201`, même corps. Un test de contrat verrouille désormais le
statut annoncé.

## 3. Ce que nous n'avons pas changé

**L'auto-signalement reste accepté** (votre 1.3). C'est la deuxième fois que la
question se pose — nous l'avions déjà signalée le 26/08 — et elle attend
toujours la même chose : une décision, pas un correctif. Notre avis, puisque
vous le demandez : `422 BUSINESS_RULE_VIOLATION` est le bon code, et le coût est
d'une ligne. Dites-le et c'est fait. Nous ne le posons pas de notre initiative
parce que cela change un contrat que votre application lit, et que ce lot-ci est
un lot de correction.

**Le signalement `063a0eb3-…` n'est pas purgé.** Il est en production, nous
n'écrivons pas dans la base de production depuis ce dépôt. Deux façons de le
faire, à votre convenance : un `DELETE` manuel, ou une migration `V83` d'une
ligne qui l'emporte au prochain déploiement — dites-nous laquelle et nous la
posons.

## 4. Vos points 1.4 et 1.5 : les cibles que vous demandiez

**1.5 — la paire éligible existe, et Lena en fait partie.** Le jeu de
démonstration est un cycle : chaque compte recommande le précédent, jamais le
suivant. Toutes les conversations ont donc un sens libre.

| Depuis | Vers | Preuve d'interaction |
|---|---|---|
| Lena Müller (`…0002`) | **Max Schmidt (`…0003`)** | conversation `70000000-…-0002` |

`GET /api/recommendations/can-recommend/00000000-0000-0000-0000-000000000003`
depuis le compte de Lena doit rendre `true`. Vous n'avez donc pas besoin d'une
nouvelle paire : celle-ci est dans le jeu que vous utilisez déjà. C'est bien
Seyd Njoya, le seul que vous aviez essayé, qui était le cas particulier —
`02 → 01` est justement l'arête du cycle qui existe.

**1.4 — le programme jetable.** Prenez
`40000000-0000-0000-0000-000000000008` (« Salsa Cubana Anfängerkurs Leipzig ») :
Lena ne l'a pas signalé, et il ne porte aucun signalement semé. Le signalement
créé sera à purger comme l'autre, à joindre au `DELETE` du point 3.

## 5. Vos demandes 2, 3 et 4

Elles ne sont pas dans ce dépôt — site vitrine et DNS — et nous les laissons
telles quelles. Une remarque tout de même, parce qu'elle porte sur votre propre
chemin critique et qu'elle vous appartient :

Vous avez raison d'écrire que les huit mentions non remplies de
`legal_placeholders.dart` sont le vrai blocage de la demande 2. Elles le sont
aussi pour la demande 3, qui réclame « une adresse de contact que quelqu'un
relève » — c'est la même mention. **Les demandes 2 et 3 partagent donc un unique
préalable**, et il ne se lève ni côté serveur ni côté mobile : il vient de
l'éditeur. Tant qu'il n'est pas levé, le travail de publication des pages est
prêt à être fait mais ne peut pas être fait.

---

## Vérification

- Suite complète au vert : **791 tests**, aucun échec (786 sur `master` avant ce
  lot, plus les cinq de ce correctif).
- `ReportVocabulaireIntegrationTest` — 4 méthodes :
  - toutes les lignes de la table se lisent dans l'enum Java ;
  - les signalements semés « ouverts » apparaissent bien dans la file d'attente ;
  - un compte qui a signalé obtient sa page en `200`, pas un `500` ;
  - la base refuse un statut hors vocabulaire.
- `OpenApiContractIntegrationTest` — une méthode de plus : `/api/reports` `post`
  annonce `201` et **pas** `200`.
- Les deux défauts ont été reproduits avant correction. Trois des quatre
  méthodes du premier test échouaient sur `No enum constant …ReportStatus.RESOLVED` ;
  le test de contrat échoue sur le contrôleur d'origine et passe sur le corrigé.

## Ce qu'il vous reste à faire

Rejouez vos quatre critères d'acceptation après le prochain déploiement. Les
deux premiers sont couverts par les tests ci-dessus ; les deux autres sont vos
écritures de production, avec les cibles du point 4.

Et répondez-nous sur deux points en attente, tous deux à un mot :
l'auto-signalement (`422` ou statu quo) et la purge (manuelle ou `V83`).

Pour OAuth, rien à signaler : il n'existe toujours pas côté serveur. Votre
drapeau reste éteint, et nous vous préviendrons — c'est noté.
