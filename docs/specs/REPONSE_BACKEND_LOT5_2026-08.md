# Réponse backend au lot 5 — récurrences RFC 5545

> Demande 4 de `PROMPT_BACKEND_EVOLUTIONS_2026-08.md`, remontée avant la demande 2
> pour la raison exposée en tête du lot 4 : sans elle, `/activities/browse`
> masquait des activités vivantes.
>
> Fait suite aux lots 1 à 4.

---

## 1. Ce qui est livré

**Vous pouvez supprimer `schedule_occurrence.dart`.** `nextSessionAt` développe
désormais les récurrences, sur tous les chemins où vous le lisez :
`/map/activities`, `/activities/browse`, `ProgramDto.nextSessionAt`, et
`/slots/feed`.

### La correction est dans le modèle, pas dans les lectures

Nous n'avons pas ajouté un calcul de récurrence à chaque endroit qui lit une
date. Nous avons réparé ce qui produisait la date.

Un créneau récurrent n'a **qu'une seule occurrence bookable** dans ce modèle
(`starts_at` / `ends_at`) ; `RecurringSlotRolloverJob` l'avance quand elle est
passée. Ce job ajoutait **sept jours en dur, sans lire la règle**. Il lit
maintenant la RRULE. `starts_at` redevenant la prochaine occurrence réelle, tous
les chemins de lecture qui s'appuient dessus deviennent corrects sans être
touchés.

Deux bugs de fond disparaissent au passage, dont un que personne n'avait signalé :

| Cas | Avant | Maintenant |
|---|---|---|
| `FREQ=WEEKLY;BYDAY=MO,WE` posé un lundi | ne tombait **jamais** un mercredi | alterne lundi / mercredi |
| `FREQ=MONTHLY` | **déplacé** de 7 jours à chaque passage | reporté d'un mois |
| Série close par `UNTIL` ou `COUNT` | **ressuscitée** dans le futur | reste passée, donc expirée |

Le troisième est le plus gênant : une série terminée en mars réapparaissait
indéfiniment dans les feeds.

### Sémantique, telle que vous l'aviez spécifiée

- `nextSessionAt` = plus proche occurrence à venir, tous programmes et tous
  créneaux confondus, récurrences développées ;
- `isExpired = true` **uniquement** si l'entrée est datée — au moins un créneau —
  et qu'aucune occurrence future n'existe ;
- une entrée **sans aucun créneau** n'est jamais expirée ;
- `isExpired: true` ⇒ `nextSessionAt: null`, sans exception ;
- dates en UTC ISO 8601.

`FREQ`, `INTERVAL`, `BYDAY` multi-jours, `UNTIL`, `COUNT` sont couverts par
ical4j, une implémentation RFC 5545 complète. Une règle illisible ne fait pas
disparaître le créneau : on retombe sur le comportement d'avant, la séance
unique.

---

## 2. Trois limites, à connaître avant de supprimer votre moteur

### 2.1 Une fenêtre de dix minutes où une activité vivante paraît expirée

Le modèle « une occurrence bookable, avancée par un job » a un angle mort :
entre le passage d'une occurrence et l'exécution suivante du job, le créneau
reste daté dans le passé. Il est donc vu comme sans séance à venir.

Sur `/map/activities` c'est un `nextSessionAt` momentanément nul. Sur
`/activities/browse`, `includeExpired` valant `false` par défaut, **l'entrée
disparaît** pendant ce temps.

Nous avons resserré la cadence du job de soixante à **dix minutes**. La fenêtre
n'est pas nulle. La supprimer demanderait de développer la récurrence à la
lecture *et* de le faire dans le SQL qui pagine et compte, ce qui est un autre
chantier. Dites-nous si dix minutes vous posent un problème réel.

### 2.2 Le fuseau est unique pour toute l'application

`BYDAY=MO` désigne un lundi **local**, pas un lundi UTC : un créneau à 00h30 à
Paris tombe le dimanche 23h30 en UTC, et développer la règle en UTC le décalerait
d'un jour.

Les occurrences sont donc calculées dans `Europe/Paris` (paramétrable par
`pair.recurrence.zone`). **Un créneau organisé hors de ce fuseau, à une heure
proche de minuit, peut être développé sur le mauvais jour.** Le corriger demande
de stocker un fuseau par créneau — ce n'est pas le cas aujourd'hui, et c'est une
migration.

Tant que le produit reste sur l'Europe de l'Ouest continentale, l'écart est nul.

### 2.3 `EXDATE` n'est pas géré

Vous le mentionniez comme souhaitable « si vous les gérez ». Nous ne les gérons
pas : la colonne ne stocke qu'une `RRULE`, il n'y a pas de champ d'exceptions.
Une séance annulée ponctuellement se fait en passant le créneau à `CANCELLED`,
pas en excluant une date.

---

## 3. Deux choses trouvées en chemin, qui méritent d'être écrites

### 3.1 La méthode de convenance d'ical4j reproduit votre bug

Nous avons pris ical4j plutôt que d'écrire un moteur — un moteur partiel étant
exactement ce que vous nous demandiez de remplacer. Sa méthode `getNextDate()`
est pourtant fausse pour notre cas :

```
FREQ=WEEKLY;BYDAY=MO,WE, évalué un mardi
  getNextDate(seed, from)     → lundi 10 août      ← saute le mercredi
  getDates(seed, from, +10j)  → mercredi 5, lundi 10, mercredi 12
```

`getNextDate` avance par périodes entières depuis la graine : le même défaut que
`schedule_occurrence.dart`, dans une bibliothèque de référence. Nous énumérons
donc les dates et prenons la première, par fenêtres élargies pour ne pas produire
730 dates là où une suffit.

Si vous cherchez un jour une bibliothèque équivalente côté Dart, méfiez-vous de
la même méthode.

### 3.2 Un de vos tests, et un des nôtres, encodaient le bug

Notre `RecurringSlotRolloverJobIntegrationTest` a échoué à la première exécution.
Vérification faite : sa graine tombe un **mardi**, sa règle dit `BYDAY=MO`, et il
asseyait le report sur « un multiple de 7 jours depuis la graine » — c'est-à-dire
qu'il exigeait qu'un créneau « du lundi » reste un mardi pour toujours.

Le test a été corrigé, pas contourné. Nous le signalons parce que c'est le
symptôme d'un piège partagé : tant qu'on raisonne en « +7 jours », l'assertion
paraît juste.

---

## 4. Vérification

**11 tests unitaires** sur le moteur, un par critère d'acceptation : hebdomadaire
passé remontant une occurrence future, `BYDAY=MO,WE` évalué un mardi donnant le
mercredi, `UNTIL` dépassé et `COUNT` épuisé donnant `null`, `INTERVAL=2`
respecté, séance unique passée et future, mensuel avançant d'un mois et non de
sept jours, règle illisible ne faisant pas disparaître le créneau, préfixe
`RRULE:` toléré, et un lundi local près de minuit ne basculant pas au dimanche.

**5 tests d'intégration** sur le job : créneau hebdomadaire avancé, `BYDAY`
multi-jours pouvant atterrir sur le second jour, série close par `UNTIL` laissée
en l'état, durée du créneau préservée, créneau non récurrent non touché.

Suite complète : **244 → 260 tests**, 11 échecs + 2 erreurs identiques avant et
après, tous préexistants.

---

## 5. Une dépendance nouvelle, et son effet de bord

`org.mnode.ical4j:ical4j:4.0.4`, sans dépendance transitive ajoutée.

À savoir : ical4j enregistre son propre `ZoneRulesProvider` via le ServiceLoader
de la JVM. C'est un effet de bord global au processus, pas cantonné au moteur de
récurrence. Nous n'avons observé aucune régression — les 260 tests le disent —
mais c'est le genre de chose qu'on préfère apprendre d'un document que d'un
incident.

---

## 6. Suite

| | Attendu | État |
|---|---|---|
| 0-3 | SHA, maille, `programCount`, `Accept-Language`, `/map/bounds` | ✅ lots 1 à 3 |
| 4 | `truncated` sur `/programs` | ❌ impossible additivement |
| 5 | **Demande 1** — `/activities/browse` | ✅ lot 4 |
| 6 | **Demande 4** — RRULE | ✅ ce document |
| 7 | **Demande 2** — pagination `/search` | à faire — **la dernière** |

Il ne reste que la pagination de `POST /search`, avec l'arbitrage (a) que vous
avez validé : plafonds relevés, fusion, tri, découpe, `totalCount` exact dans la
limite du plafond.
