# Réponse du 14/09 — les quatre questions tranchées : « je la vois » par inscription, introuvable d'abord, la bio et la position sortent de la recherche

**Date :** 2026-09-14
**Module :** `inscription`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14.md`

> **Votre relevé est exact sur les quatre points**, et nous vous devions ces réponses depuis le
> 03/09 et le 04/09. Les quatre sont livrées, dans le sens où vous penchiez.
>
> - **(a)** `POST /api/schedules/{scheduleId}/arrivals/{participationId}/seen` : `202` pour tout
>   inscrit du créneau, jamais `409`. L'ancienne forme par veille reste servie, dépréciée.
> - **(b)** **Introuvable d'abord.** Si la fiche rend `404` à l'appelant, `/participants` et
>   `/co-participants` rendent `404` avant tout contrôle de rôle. Sinon, les deux `403` nommés restent.
> - **(c)** La bio n'est cherchable que là où elle serait rendue.
> - **(d)** `latitude` et `longitude` sont **retirés** de `GET /api/users`.

---

## 1. (a) « Je la vois », adressé à l'inscrit

**L'offre du 03/09 tient.** Nous retenons un chemin voisin de celui que vous proposiez, pour qu'il se
range à côté du geste de validation qui existe déjà sur le même modèle :

```
POST /api/schedules/{scheduleId}/arrivals/{participationId}/seen      → 202
POST /api/schedules/{scheduleId}/arrivals/{participationId}/confirm   → 202  (inchangé)
```

| Situation | Réponse | Effet |
|---|---|---|
| Inscription du créneau, avec une veille `ARMED` ou `EN_ROUTE` | `202` | la relance d'arrivée recule de 15 min, événement `SEEN_BY_HOST` inscrit, comme par la veille |
| Inscription du créneau, **sans veille**, ou veille sortie du trajet aller | `202` | aucun |
| Créneau qui n'est pas celui de l'appelant | `404` | aucun |
| Inscription d'un **autre** créneau présentée sur le sien | `404` | aucun |

**Jamais de `409`.** Le `participationId` est celui que porte chaque ligne de
`GET /api/slots/{id}/participants`. Le bouton peut donc passer devant chaque inscrit, comme vous
le prévoyez derrière `hostSeenByParticipation`.

**L'ancienne forme** `POST /api/watches/{id}/seen-by-host` **reste servie**, sans changement de
comportement, marquée `deprecated` au contrat. **Nous ne la retirerons pas avant que vous nous
écriviez** qu'aucune version encore utilisée ne l'appelle. C'est vous qui voyez les versions
installées, pas nous.

## 2. (b) Une règle unique pour les deux listes

**Votre règle est retenue telle quelle**, et le cas du blocage est corrigé :

1. **Introuvable d'abord**, avant tout contrôle de rôle. Si la fiche rend `404` à l'appelant, les
   deux listes aussi : créneau inexistant, hôte bloqué dans un sens ou dans l'autre, compte de l'hôte
   fermé (sauf un créneau annulé, dont la fiche reste ouvrable depuis P-BL-18).
2. **Sinon, les `403` nommés restent** : `SLOT_PARTICIPANTS_HOST_ONLY` sur `/participants`,
   `SLOT_PARTICIPANTS_ENROLLED_ONLY` sur `/co-participants`.

La règle de la fiche est désormais **écrite une fois** (`SlotService.introuvableSiFicheIllisible`)
et appelée par les trois routes. Une divergence entre elles, qui était le défaut que vous avez
relevé, ne peut plus s'écrire en ne touchant qu'une route. Les descriptions des deux listes au
contrat commencent par ce `404`.

## 3. (c) La bio hors de portée de qui ne la verrait pas

La bio n'entre dans la recherche que si elle serait **rendue** à l'appelant : profil `PUBLIC`
(ou sans réglage), ou `FRIENDS` et appelant abonné à la personne. C'est la condition de
`UserService.toPublicDto`, recopiée dans la requête SQL pour que le total de la page reste d'accord
avec la page.

**Restent cherchables pour tous** : le nom affiché, et les titres des programmes publics actifs.

Nous n'avons pas touché à **qui est trouvable** : un compte ne remonte toujours que s'il a allumé
l'un des trois réglages de présence (`location_public`, `show_location`, `show_on_map`).

## 4. (d) Plus de position dans la recherche de personnes

`latitude` et `longitude` **sont retirés** de `GET /api/users`, avec le rayon de 50 km et le tri par
distance. Nous ne connaissons pas d'autre client que votre app, qui ne les envoie plus. L'ordre des résultats est
désormais seulement stable (`u.id`), ce qui était déjà le cas pour vous, qui n'envoyiez pas de
point.

**Un client qui les envoie encore** n'obtient pas d'erreur : Spring ignore les paramètres inconnus,
et la réponse est la même qu'avec le seul `query`. La route porte maintenant une description au
contrat.

**Votre correction sur Berlin est notée** : le `[]` venait du rayon, pas d'un filtre cassé.

## 5. Vérification

**Tests** :

- `ArrivalTwoStepIntegrationTest` (3 nouveaux) :
  - la relance recule de 15 min sur un inscrit qui a armé ;
  - `202` sur un inscrit sans veille, et sur une veille sortie du trajet aller ;
  - `404` hors de son créneau, et pour l'inscription d'un autre créneau.
- `InscritsEtRechercheIntegrationTest` (6) :
  - **(b)** bloqué : fiche, `/participants` et `/co-participants` en `404` ; hôte au compte fermé :
    `/participants` en `404` ; fiche lisible : les deux `403` nommés ;
  - **(c)** profil `PRIVATE` absent sur un mot de sa bio, présent sur son nom ; profil `FRIENDS`
    trouvé par un abonné, pas par un inconnu ;
  - **(d)** une personne sans position remonte sur son nom, et un point envoyé ne change pas la
    réponse.

Rejoué sans le correctif, `InscritsEtRechercheIntegrationTest` échoue sur 5 de ses 6 cas. Le
sixième, qui vérifie que les `403` nommés restent, passe dans les deux cas, comme attendu.

**Votre protocole du §4 tient tel quel** après déploiement, avec un chemin corrigé pour (a) :
`POST /api/schedules/{scheduleId}/arrivals/{participationId}/seen`. Ce lot part dans le même
déploiement que la réponse `badges` BIS.
