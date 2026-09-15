# La série de retours de veille : ne plus la servir

**Date :** 2026-09-15
**Module :** `tracabilite`
**Audit :** P-MU-25 étape 3
**Décision de l'utilisateur, 15/09/2026 :** retirer la carte « série de retours » de l'app, et ne
plus servir le compteur, par cohérence avec le retrait des séries de pratique (V122, V126) et du
module `/api/progressions` (14/09).

> **Ce que fait l'app** : à partir de la prochaine version, elle ne lit plus
> `consecutiveConfirmedReturns` et n'affiche plus de série sur l'écran de fin de veille
> (`watch_closed_page.dart`, `watchReturnStreakProvider`).
>
> **Ce qui vous appartient** : le compteur. Une série de retours confirmés « d'affilée » est une
> série à entretenir — la doctrine la refuse pour la veille comme pour la pratique — et elle mesure
> la régularité avec laquelle quelqu'un rentre seul.

---

## 1. Relevé du 15/09/2026

- `WatchDetailDto.consecutiveConfirmedReturns` (`WatchDetailDto.java:27`), calculé par
  `WatchService.retoursConfirmesDAffilee` (votre réponse badges TER du 14/09, §4, le cite).

## 2. La demande

1. Retirer `consecutiveConfirmedReturns` de `WatchDetailDto` et le calcul qui le produit. **Attention
   aux versions publiées** : les builds 16 et 17 lisent ce champ ; vérifiez qu'un champ absent y est
   toléré (l'app le lit en `int?` depuis le 02/09 — `REPONSE_BACKEND_2026-09-02-BIS.md` §3 ; nous
   confirmerons côté app avant votre retrait si vous le souhaitez).
2. Étendre `DoctrineContratSansDecompteTest` à ce champ (aucune propriété `consecutive`/`streak`).

## 3. Comment nous vérifierons

Relevé `/v3/api-docs` : `WatchDetailDto` sans `consecutiveConfirmedReturns`.
