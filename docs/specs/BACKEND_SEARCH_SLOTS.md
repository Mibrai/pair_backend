# Demande backend — indexer les créneaux dans `POST /api/search`

> À copier dans l'instance Claude Code ouverte sur le dépôt backend
> (`org.program.pair`). Complète `ios/docs/PROMPT_BACKEND_MEETDO.md`, qui traite
> les anomalies ; celle-ci est une **évolution fonctionnelle**.
>
> Contrat inspecté sur `/v3/api-docs` le 25 juillet 2026.

---

Tu travailles sur le backend meetDo. La recherche sémantique (`POST /api/search`)
ne renvoie aujourd'hui que des **personnes** et des **programmes**. Depuis
l'évolution meetDo, l'entité centrale du produit est le **créneau** — et elle
est invisible à la recherche. Il faut l'y ajouter.

---

## 1. Le problème, concrètement

L'app s'ouvre désormais sur un fil de créneaux, et la carte a un mode
« Créneaux ». Mais un utilisateur qui tape « du yoga demain soir » n'obtient
**aucun créneau** : ni le fil, ni la carte, ni la feuille de recherche
conversationnelle ne peuvent en proposer. Il reçoit des profils et des
programmes, c'est-à-dire des contenants, jamais l'occasion concrète de se
retrouver quelque part à une heure donnée.

C'est d'autant plus visible que **`SearchIntent` extrait déjà un `timeHint`** :

```jsonc
"parsedIntent": {
  "activityKeyword": "yoga",
  "timeHint": "demain soir",     // ← déjà extrait
  "suggestedRadius": 5000,
  ...
}
```

L'analyseur d'intention comprend donc le temps, mais **il n'a rien à quoi le
confronter** : ni `User` ni `Program` ne porte de date. Seul `Schedule` en a
une. Le travail d'analyse est fait, il manque la cible.

## 2. Ce qu'on demande

**Ajouter un troisième type de résultat, `slot`,** aux réponses de
`POST /api/search`.

### 2.1 Contrat de réponse

`SearchResultDto.resultType` vaut aujourd'hui `user` ou `program`. Ajouter la
valeur **`slot`**, et documenter l'énumération dans le schéma OpenAPI — elle est
actuellement typée `string` sans `enum`, ce qui oblige chaque client à deviner.

Pour un résultat `slot`, les champs existants du DTO se remplissent ainsi :

| Champ `SearchResultDto` | Source côté `Schedule` |
|---|---|
| `id` | `schedule.id` — c'est le `scheduleId` |
| `title` | titre du programme porteur |
| `description` | `welcomeNote` s'il existe |
| `activityName`, `categoryId`, `categoryName` | activité et catégorie du programme |
| `level`, `format` | du programme |
| `lat`, `lng`, `distanceMeters` | du créneau — **soumis à la règle de visibilité** (§2.3) |
| `city` | du créneau |
| `organizerId`, `organizerName`, `organizerAvatarUrl` | l'hôte |
| `enrolledCount` | `participantCount` (le total combiné) |
| `status` | `OPEN` / `FULL` / `CANCELLED` / `PAST` |
| `createdAt` | `startsAt` du créneau |

**Trois champs manquent** au DTO pour qu'un résultat `slot` soit exploitable
sans second appel. Merci de les ajouter (nullables, donc rétrocompatibles) :

```jsonc
"startsAt": "2026-08-01T07:00:00Z",   // obligatoire pour un slot
"endsAt":   "2026-08-01T08:00:00Z",   // optionnel
"maxParticipants": 8                  // pour afficher « 3 / 8 »
```

Sans `startsAt`, un créneau ne peut être ni trié, ni affiché, ni distingué d'un
programme — c'est le champ qui fait tout l'intérêt de l'entité. Réutiliser
`createdAt` serait un détournement qui piégera le prochain client.

### 2.2 Exploiter le `timeHint`

C'est le cœur de la demande. Quand `parsedIntent.timeHint` est renseigné, il
doit **borner la fenêtre temporelle** de la recherche de créneaux :

| `timeHint` | Fenêtre attendue |
|---|---|
| « ce soir » | aujourd'hui, 17 h → 23 h 59 |
| « demain », « demain soir » | J+1, journée entière ou plage du soir |
| « ce week-end » | prochain samedi 00 h → dimanche 23 h 59 |
| « cette semaine » | maintenant → dimanche |
| absent | maintenant → **+7 jours** (même défaut que `/api/slots/feed`) |

Les créneaux **passés ne sont jamais renvoyés**, quel que soit le `timeHint`.

### 2.3 Règles de filtrage — identiques au feed

Un créneau ne doit apparaître dans la recherche que s'il apparaîtrait dans
`GET /api/slots/feed`. **Réutiliser la même requête de filtrage** plutôt que
d'en écrire une seconde, sinon les deux divergeront :

- `isOpenToPartners = true` ;
- statut `OPEN` ou `FULL` (jamais `CANCELLED` ni `PAST`) ;
- hôte actif, programme public et actif, activité non masquée de la carte ;
- **les créneaux de l'appelant lui-même sont exclus** ;
- rayon respecté à partir de `lat`/`lng`/`radiusMeters` de la requête.

**Règle de visibilité du lieu — à respecter strictement.** `lat`, `lng` et toute
adresse ne sont renvoyés que si le lieu est `PUBLIC`, ou `PRIVATE` avec
`showExactAddress = true`, ou si l'appelant a déjà une participation
`CONFIRMED`. Sinon ces champs sont `null` et seul le nom générique du lieu
subsiste. Un créneau privé non partagé **doit rester cherchable** — il ne doit
simplement pas divulguer sa position.

### 2.4 Classement

Un créneau qui a lieu dans deux heures vaut plus qu'un programme sans date. Nous
proposons, à pertinence sémantique comparable :

1. les créneaux correspondant au `timeHint`, **triés par date croissante** ;
2. puis les programmes ;
3. puis les personnes.

Si vous préférez un score unifié, faites au moins peser la **proximité
temporelle** dans `relevanceScore` pour les slots — sinon un créneau de samedi
prochain remontera au même niveau qu'un créneau dans une heure.

### 2.5 Actions d'état vide

`emptyStateActions` gagne du sens ici. Quand une requête résout une activité mais
qu'aucun créneau ne correspond, `CREATE_SLOT` doit être proposé avec
l'`activityId` résolu — c'est exactement le geste attendu (« sois le premier »).
Ce comportement existe déjà, il faut juste qu'il se déclenche aussi quand la
recherche portait sur des créneaux.

## 3. Point annexe — `SearchRequest` ignore ce que les clients envoient

En inspectant le schéma, `SearchRequest` ne contient que quatre champs :

```
query, lat, lng, radiusMeters
```

Or l'application envoie en plus `filters` (dont un filtre de localisation avec
rayon), `locale`, `page`, `pageSize`, ainsi que `sort_by` / `sort_order` en
snake_case. **Tout cela est silencieusement ignoré.**

Ce n'est pas bloquant — le rayon passe aussi par `radiusMeters` — mais deux
choses seraient utiles :

- **confirmer** que ces champs sont bien ignorés et non silencieusement mal
  interprétés ;
- si la pagination n'existe pas, le dire dans la description du schéma. Un
  client qui envoie `page: 2` croit aujourd'hui paginer.

Si vous ajoutez la pagination, `page` / `pageSize` sont déjà envoyés par l'app et
seraient pris en compte sans changement côté client.

## 4. Ce que ça change côté mobile

Rien tant que le champ n'apparaît pas : `SearchResultType.fromJson` est
**tolérant** — toute valeur inconnue retombe aujourd'hui sur `program`, donc un
`slot` s'afficherait comme un programme et le tap ouvrirait la mauvaise page.
Nous ajouterons le cas `slot` dès que le contrat est publié, avec navigation
vers `/slots/{id}`.

**Prévenez-nous avant la mise en production** de ce changement : c'est le seul
point où l'ordre de déploiement compte.

## 5. Critères d'acceptation

- [ ] `POST /api/search` avec « du yoga demain soir » renvoie au moins un
      résultat `resultType: "slot"` quand un créneau de yoga existe le
      lendemain dans le rayon.
- [ ] Chaque résultat `slot` porte `startsAt`, et `endsAt` / `maxParticipants`
      quand ils existent.
- [ ] Un créneau `CANCELLED`, `PAST`, fermé aux partenaires, ou appartenant à
      l'appelant, n'est **jamais** renvoyé.
- [ ] Un créneau `PRIVATE` sans `showExactAddress` est renvoyé **sans**
      `lat`/`lng` ni adresse.
- [ ] Sans `timeHint`, la fenêtre par défaut est « maintenant → +7 jours »,
      cohérente avec `/api/slots/feed`.
- [ ] Le schéma OpenAPI documente l'énumération complète de `resultType`
      (`user`, `program`, `slot`).
- [ ] Les résultats `user` et `program` existants sont inchangés — aucune
      régression sur la recherche actuelle.
