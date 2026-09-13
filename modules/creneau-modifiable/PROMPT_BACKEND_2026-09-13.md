# Le niveau affiché sur un créneau est celui de l'hôte, pas celui du créneau

**Date :** 2026-09-13
**Module :** `creneau-modifiable`
**Audit :** P-MU-07 (`audit/PLAN_MOBILE_UX_DESIGN_2026-09-11.md`)

> **Ce que l'app a déjà fait, sans vous** (commit `8cdefc1`) : elle n'affiche plus
> aucun niveau sur la fiche d'un créneau, et la création d'un créneau n'invente plus
> « Débutant ». Le formulaire qui laisse l'hôte **dire** le niveau attendu est écrit,
> derrière un drapeau éteint, `slotDeclaredLevel`.
>
> **Ce qui vous appartient** : un niveau porté par le créneau lui-même. Sans lui, le
> drapeau reste éteint, et aucun niveau ne s'affiche.

---

## 1. Le défaut, relevé le 13/09/2026

`SlotFeedItemDto.level` et `SearchResultDto.level` existent au contrat
(`/v3/api-docs`). Mais leur valeur est **le niveau personnel de l'hôte** pour
l'activité, déclaré à l'onboarding :

- `SlotService.java:784` — `userActivity.getLevel()` ;
- `SemanticSearchService.java:365` et `:564` — même lecture.

Un hôte qui s'est déclaré « Avancé » en course voit donc « Avancé » sur **tous** ses
créneaux de course, présenté aux participants comme une exigence qu'il n'a jamais
formulée. Et jusqu'au 13/09, l'app créait ce `UserActivity` en `BEGINNER` en dur
depuis le formulaire de créneau : un « Débutant » inventé, ensuite affiché partout.

Côté écriture, ni `CreateScheduleRequest` ni `UpdateScheduleRequest` ne portent de
niveau.

## 2. La demande

1. **Une colonne `level` sur `schedules`**, nullable — `null` veut dire « non
   précisé », et l'app n'affiche alors rien. Migration Flyway suivante (V113 au
   moment du relevé, à ajuster).
2. **Acceptée** par `POST /api/programs/{programId}/schedules` et
   `PUT /api/programs/{programId}/schedules/{scheduleId}`, clé JSON `level`.
   Valeurs utilisées par le formulaire : `ANY`, `BEGINNER`, `ADVANCED` (sous-ensemble
   de votre `ActivityLevel`). Une autre valeur de l'énumération doit rester lisible.
3. **Rendue** par `SlotFeedItemDto.level` et `SearchResultDto.level` depuis le
   créneau, **et plus depuis `userActivity`**. Un créneau existant, sans niveau
   déclaré, rend `null` — c'est voulu : ces créneaux perdent leur puce.
4. Si un autre DTO de créneau porte un niveau (`NextSlotDto`, vue publique), même
   règle.

## 3. Une question de contrat à trancher avant la bascule

Le `PUT` d'un créneau **remplace-t-il l'objet entier** ? Si oui, et si la colonne
existe alors que notre drapeau est encore éteint, chaque modification faite depuis
l'app partira **sans** `level` et l'effacera. Deux issues :

- un `PUT` qui ignore une clé absente (sémantique de fusion pour `level`) ;
- ou nous allumons le drapeau le jour même du déploiement.

Dites-nous laquelle.

## 4. Comment nous vérifierons

Relevé de `/v3/api-docs` **et** création réelle avec le compte de test : créer un
créneau « Débutants bienvenus », le relire par `/slots/feed` et `/search`, le
modifier sans toucher au niveau, le relire encore. Puis bascule dans un commit
séparé. Le drapeau reste ensuite en place : c'est l'interrupteur si le serveur
régresse.
