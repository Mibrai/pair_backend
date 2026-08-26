# Signalement et recommandation : les trois routes d'écriture rendent 500

**Date** : 2026-08-26
**Demandeur** : chantier frontend Flutter (`pair_mobile`)
**Gravité** : **bloquant**. Deux fonctionnalités entières sont hors service pour tous les
utilisateurs, sur toutes les plateformes. L'une d'elles — le signalement — est la seule voie
de recours contre le harcèlement dans l'app.

---

## Les deux symptômes, remontés par l'usage

**Signaler quelqu'un.** Conversation → « Signaler » → un motif → « Envoyer le signalement ».
L'app affiche sa phrase de `5xx` : « Le serveur rencontre des difficultés. Réessaie dans un
instant. » Rien n'est enregistré — `GET /api/reports/me` reste vide.

**Recommander un co-participant.** Fil d'actualité → « Oui, j'y étais » → « Terminé » →
« Recommander ». Même phrase, même issue : `GET /api/recommendations/given` reste vide et
`can-recommend` continue de répondre `true` pour la personne qu'on vient d'essayer de
recommander.

## Les trois routes touchées

Relevé le 26/08/2026 contre la production (`pairbackend-production-35fe.up.railway.app`),
compte `loadtest-0@example.invalid`.

| route | corps envoyé | réponse |
|---|---|---|
| `POST /api/reports` | conforme à `CreateReportRequest` | **500 `INTERNAL_ERROR`** |
| `POST /api/programs/{programId}/report` | motif + description valides | **500 `INTERNAL_ERROR`** |
| `POST /api/recommendations` | `{"recommendedId": "…"}` | **500 `INTERNAL_ERROR`** |

Trois écritures, deux domaines, la même panne le même jour. Elles méritent d'être regardées
ensemble : si elles partagent un service, un intercepteur d'audit ou une émission de
notification, elles partagent probablement la cause.

---

## 1. `POST /api/reports` — le balayage complet

```bash
curl -s -X POST "$API/reports" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' --data-binary '{
    "reportedEntityType": "USER",
    "reportedEntityId": "120a4d43-6546-45ea-8f7d-fb372b647663",
    "reason": "SPAM",
    "description": "No additional details provided by the reporter."
  }'
```

```json
{"code":"INTERNAL_ERROR","message":"Une erreur est survenue.","timestamp":"2026-08-26T19:29:37Z"}
```

| charge envoyée | réponse |
|---|---|
| `reason` = chacune des 7 valeurs de l'énumération | **500** pour les 7 |
| `description` de 10 à 500 caractères | **500** |
| `reportedEntityType` = `USER` / `PROGRAM` / `MESSAGE` | **500** dans les trois cas |
| auto-signalement (`reportedEntityId` = l'appelant) | **500** |
| `reportedEntityId` inexistant mais bien formé | **500** *(un `404` était attendu)* |
| `description` de 5 caractères | 400 `VALIDATION_ERROR` — correct |
| `description` absente | 400 `VALIDATION_ERROR` — correct |
| `reportedEntityId` non-UUID | 400 `INVALID_JSON` — correct |
| `reportedEntityType` hors énumération | 400 `INVALID_JSON` — correct |

Deux enseignements :

1. **La validation du corps fonctionne**, et proprement. La panne est donc **après** elle,
   dans le service.
2. **Rien ne la fait varier** : ni le type d'entité, ni le motif, ni l'existence de la cible.
   Un identifiant qui ne désigne personne rend `500` là où le contrat annonce `404` — le code
   casse donc **avant** d'avoir cherché l'entité signalée, ou en cassant sur son absence.

Ce n'est pas un cas limite : c'est le chemin nominal qui ne s'exécute pas.

## 2. `POST /api/recommendations` — la panne est derrière le contrôle métier

Cette route-là commence par vérifier la preuve d'interaction, et **ce contrôle, lui,
fonctionne** :

```bash
# cible sans conversation ni présence partagée
curl -s -X POST "$API/recommendations" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' --data-binary '{"recommendedId":"<sans preuve>"}'
```
```json
{"code":"BUSINESS_RULE_VIOLATION",
 "message":"Vous devez avoir échangé des messages ou partagé une présence confirmée avec cet utilisateur avant de pouvoir le recommander"}
```
→ `422`, ce qui est juste.

Avec une cible **valide** — `GET /recommendations/can-recommend/{id}` répond `true`, une
conversation directe existe — la même requête rend :

```json
{"code":"INTERNAL_ERROR","message":"Une erreur est survenue.","timestamp":"2026-08-26T19:49:28Z"}
```

Et après cet appel : `GET /recommendations/given` toujours vide, `can-recommend` toujours
`true`. Rien n'a été écrit, pas même partiellement.

Variantes essayées, toutes en `500` : `recommendedId` seul, `recommendedId` + `rating: 5`,
`recommendedId` + `rating` + `comment`. **Ce n'est donc pas un champ facultatif du contrat
posé sur une colonne `NOT NULL`** — l'hypothèse la plus naturelle est écartée.

Autrement dit, sur cette route, le `422` court-circuite la panne : le crash est **après** le
contrôle de preuve, sur le chemin qui écrit.

---

## Ce que nous demandons

1. **Les traces d'exception**, horodatées ci-dessus : `2026-08-26T19:29:37.440Z` pour le
   premier signalement, `2026-08-26T19:49:28.962Z` pour la recommandation. Elles nomment la
   cause en une ligne ; nous n'avons rien à en deviner d'ici.

2. **Le correctif, puis la vérification par les routes de lecture.** Une charge valide doit
   rendre `2xx` **et** apparaître : dans `GET /api/reports/me` pour un signalement, dans
   `GET /api/recommendations/given` pour une recommandation (et `can-recommend` doit alors
   basculer à `false`). Les deux routes de lecture répondent déjà `200` avec une page vide —
   elles serviront de contrôle.

3. **Rétablir le `404` documenté** sur `POST /api/reports` pour un `reportedEntityId` qui ne
   désigne aucune entité. L'app en dépend : elle en tire « cette personne n'est plus
   joignable » plutôt qu'une panne (`lib/features/safety/domain/report_failure.dart`), et un
   `500` à sa place transforme un cas normal en incident.

4. **Regarder les trois routes ensemble**, pour la raison dite plus haut.

5. **Nommer le refus « déjà recommandé ».** Nous n'avons jamais pu l'observer, faute d'une
   seule écriture réussie. Le contrat annonce `409` et `422` sans dire lequel porte quel sens ;
   l'app traite aujourd'hui le `409` comme « déjà fait » et le `422 BUSINESS_RULE_VIOLATION`
   comme un refus de droit. Une ligne de confirmation nous suffira.

---

## Ce que l'app change, et ce qu'elle ne change pas

**Le signalement : rien.** Le corps envoyé est conforme à `CreateReportRequest`, la validation
du serveur le confirme, et aucune variante ne passe. Il n'y a pas de contournement client à
écrire, seulement une route à réparer.

> Le seul écart connu de l'app avec le contrat : quand la personne n'écrit pas de commentaire,
> nous envoyons `"No additional details provided by the reporter."`, parce que `description`
> est requise (10 caractères minimum). Cette phrase n'est **pas** en cause — le `500` tombe
> aussi bien avec un vrai texte de 60 caractères. La demande de rendre `description`
> facultative reste ouverte par ailleurs.

**La recommandation : deux défauts côté app, trouvés en cherchant celui-ci, et corrigés.** Ils
ne causaient pas le `500`, mais ils se seraient vus le jour même de votre correctif :

- la feuille offrait un bouton « Recommander » à **tous** les co-participants du créneau, sans
  jamais appeler `GET /recommendations/can-recommend/{id}`. Or avoir confirmé sa présence ne
  suffit pas : vous exigez la réciprocité (`SHARED_ATTENDANCE`), et vous refusez ce qui a déjà
  été recommandé. Elle interroge désormais la route, et n'affiche que les gestes qui
  aboutissent ;
- un `422` était compté avec le `409` comme « déjà recommandé », et affichait un « Recommandé »
  vert. Sur le `BUSINESS_RULE_VIOLATION` que vous renvoyez, c'était l'exact contraire de la
  vérité.
