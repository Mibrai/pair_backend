# Réponse du 15/09 (BIS) — la série de retours de veille n'est plus servie

**Date :** 2026-09-15
**Module :** `tracabilite`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15-BIS.md`
**Audit :** P-MU-25 étape 3

> **Livré.** `consecutiveConfirmedReturns` quitte `WatchDetailDto`, son calcul et la requête qui
> l'alimentait sont retirés, et le test de doctrine couvre ce schéma.

---

## 1. Ce qui est retiré

- **`WatchDetailDto.consecutiveConfirmedReturns`**. `GET /api/watches/{id}` rend `watch`, `timeline`
  et `alertDelivery`, sans autre changement.
- **`WatchService.retoursConfirmesDAffilee`**, et la requête `WatchRepository.issuesDesVeilles` qui
  ne servait qu'à lui.
- **Les cinq tests** qui vérifiaient la série sont remplacés par un seul, qui vérifie son absence de
  la réponse. Les tests de l'annonce de retour au contact restent.

## 2. Les versions publiées

Les builds 16 et 17 lisent ce champ en `int?` depuis le 02/09. Vous nous l'aviez écrit dans
`PROMPT_BACKEND_2026-09-02-BIS.md`, §3 : « une méthode qui rend un `int?` et **jamais** la veille ». Un
champ absent y est lu comme `null`, et la carte de série ne s'affiche simplement pas. Nous n'avons pas
attendu de confirmation supplémentaire, puisque c'est votre propre relevé. Si une version lit
autrement, dites-le avant votre prochaine sortie.

## 3. Le test de doctrine

`DoctrineContratSansDecompteTest` :

- `consecutiveConfirmedReturns` n'apparaît **nulle part** au contrat ;
- `WatchDetailDto` rejoint `PracticeStatsDto`, `SuggestedActivityDto` et `PractisedNearbyDto` dans la
  liste des schémas sans propriété de série. Le motif interdit s'étend à `consecutive`.

## 4. Vérification

Relevé `/v3/api-docs` après déploiement : `WatchDetailDto` sans `consecutiveConfirmedReturns`.
