# « Déjà pratiquée près de toi » : `GET /api/activities/practised-nearby`

**Date :** 2026-09-14
**Module :** [`rappel/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md) — P-MU-22, écart A1

> **En bref.**
>
> - **§2 — votre relevé est exact**, et vos trois raisons de ne pas détourner `/activities/suggested`
>   sont justes.
> - **§3 — la route est livrée telle que vous la décrivez** : authentifiée, 1 à 10 identifiants, une
>   entrée par identifiant connu dans l'ordre reçu, deux champs, aucun décompte.
> - **§4.1 — seuil de 3 personnes, position arrondie au centième de degré** (≈ 1 km). Les deux sont
>   écrits au contrat.
> - **§4.2 — les personnes, pas les programmes.**
> - **§4.3 — un plafond : 30 lectures par compte sur 10 minutes**, `429` avec `Retry-After`.
> - **Un ajout à votre §3.2 : une personne bloquée, dans un sens ou dans l'autre, ne compte pas.**

---

## 1. Le relevé, vérifié

Exact sur tous les points. `ActivityDto` n'a aucun champ de pratique, `GET /api/activities` est
public, et `findMostPractisedInRadius` exclut les activités de l'appelant, tronque à `limit` et compte.
Nous n'avons rien trouvé d'autre qui s'en approche.

## 2. La route

```
GET /api/activities/practised-nearby?lat=48.137&lng=11.575&activityIds={id},{id},{id}
Authorization: Bearer …
```

```json
[
  {"activityId": "3f1c…", "practisedNearby": true},
  {"activityId": "9a07…", "practisedNearby": false}
]
```

- **Une entrée par identifiant connu**, dans l'ordre reçu. Un doublon n'est rendu qu'une fois, à sa
  première place.
- **Un identifiant inconnu est omis.** Si aucun n'est connu, la réponse est `[]` avec un `200`.
- **Deux champs, aucun autre.** Aucun décompte n'est servi, ni dans le corps ni dans un en-tête. Le
  décompte ne sort même pas de la base : la requête rend seulement les identifiants qui passent le
  seuil (`HAVING COUNT(DISTINCT …) >= 3`).

### La règle du oui

Au moins **3 personnes distinctes**, **autres que l'appelant**, qui déclarent l'activité et remplissent
toutes ces conditions :

- l'activité est montrée sur la carte (`visible_on_map`) ;
- le compte est actif ;
- la position est rendue publique (`location_public`) ;
- la personne est à moins de **25 km** de la position reçue, **arrondie au centième de degré** ;
- **aucun blocage n'existe entre elle et l'appelant, dans un sens ou dans l'autre.**

La maille est celle de `findMostPractisedInRadius`, plus le blocage, que la carte applique déjà.
Une personne qui se cache ne compte pas, et une personne bloquée non plus.

### Les refus

| Cas | Réponse |
|---|---|
| Sans session | `401` |
| `lat` absente ou hors [-90, 90], `lng` absente ou hors [-180, 180] | `400 VALIDATION_ERROR` |
| `activityIds` absent ou vide, plus de 10 valeurs, ou une valeur qui n'est pas un UUID | `400 VALIDATION_ERROR` |
| Au-delà de 30 lectures en 10 minutes pour ce compte | `429 RATE_LIMITED`, `Retry-After` en secondes |

Les messages sont traduits (`REFUS_POSITION_HORS_BORNES`, `REFUS_IDENTIFIANTS_ACTIVITE`).

## 3. Les questions ouvertes

### 3.1 Le seuil : 3 personnes, et l'arrondi

Nous prenons votre proposition telle quelle. Le risque que vous décrivez est réel. Avec un oui dès une
personne, déplacer `lat`/`lng` par pas de quelques kilomètres dessine le disque de 25 km autour de
quelqu'un qui pratique seul. Nous posons trois protections :

- **le seuil de 3** rend la réponse collective. On ne peut plus isoler une personne, au mieux un groupe ;
- **l'arrondi à 0,01°** (≈ 1,1 km en latitude) retire l'intérêt d'un balayage fin : deux positions dans
  la même maille donnent la même réponse ;
- **le plafond de débit** (§3.3) borne le nombre de positions qu'un compte peut essayer.

Le seuil, le rayon et l'arrondi sont écrits dans la description de `practisedNearby` au contrat.

### 3.2 Personnes ou programmes : les personnes

Pour trois raisons :

- c'est la question que pose le bloc : « il y a déjà du monde derrière ». Un programme public avec une
  séance à venir répond à une autre question, « il y a une séance », et l'Explorer y répond déjà ;
- compter les programmes contournerait `location_public`, parce que le lieu d'une séance ne dépend pas
  de ce réglage. Le signal serait plus riche, mais il dirait où quelqu'un organise même s'il a choisi
  de ne pas être vu ;
- la réponse reste alignée sur `/activities/suggested` : une activité suggérée ici ne peut pas être
  dite « non pratiquée » là, sauf par l'effet du seuil.

### 3.3 Le débit : 30 lectures par compte sur 10 minutes

Votre usage (un appel dès 3 caractères, et seulement quand les résultats changent) consomme quelques
lectures par activité créée. Trente en dix minutes laissent une large marge. Au-delà, ce n'est plus
quelqu'un qui écrit un nom. Le budget est **par compte**, pas par adresse : la route exige une session,
et un budget par adresse punirait tout un réseau partagé.

## 4. Ce que nous n'avons pas changé

- `GET /api/activities/suggested` sert toujours `practitionersNearby` (un entier). Vous ne l'utilisez
  pas pour ce bloc. Si l'écran d'accueil ne l'affiche pas non plus, dites-le : nous le retirerons du
  contrat plutôt que de laisser un compte que personne ne lit.
- `ActivityDto` et `GET /api/activities` restent inchangés, pour les raisons de votre §3.

## 5. Vérification

- `PractisedNearbyIntegrationTest` (nouveau), sur des activités et un décor créés par le test :
  - trois personnes visibles dans le rayon donnent `true`, deux donnent `false` ;
  - un identifiant inconnu placé au milieu de la liste est omis, et l'ordre reçu est gardé ;
  - aucune entrée ne porte d'autre champ que `activityId` et `practisedNearby` ;
  - la même activité, lue depuis la mer du Nord, rend `false` ;
  - l'appelant, une personne à position cachée et une personne qui a bloqué l'appelant ne comptent
    pas ; deux personnes visibles de plus font passer la réponse à `true` ;
  - `401` sans jeton ; `400 VALIDATION_ERROR` pour une latitude à 91, une longitude à 181, une
    latitude absente, une liste vide ou absente, une valeur qui n'est pas un UUID, onze identifiants ;
    `[]` pour un identifiant seul et inconnu ;
  - la 31ᵉ lecture en dix minutes rend `429` avec `Retry-After` ;
  - `/v3/api-docs` porte la route, et `PractisedNearbyDto` n'a aucune propriété `integer` ni `number`.
- **Vos lectures réelles.** Autour de Munich, une activité courante devrait rendre `true` : les données
  de démonstration y déclarent des positions publiques. Nous ne l'avons pas rejoué en production, faute
  de déploiement. Si elle rend `false`, regardez d'abord combien de comptes de démonstration restent
  actifs depuis V116. Le seuil de 3 peut ne plus être atteint avec les seuls comptes de démonstration.
