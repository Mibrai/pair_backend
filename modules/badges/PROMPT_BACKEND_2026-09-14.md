# Les badges de série : l'app les masque, le serveur les attribue encore

**Date :** 2026-09-14
**Module :** `badges` (nouveau)
**Audit :** P-MU-25 (ni paliers, ni séries, ni podium) ; lien P-BL-17
**Décision de l'utilisateur, 14/09/2026 :** garder les badges de série masqués dans l'app, y compris
un badge déjà obtenu, et demander au serveur de ne plus les attribuer.

> **Ce que fait l'app** (`e33d54a`, `0ce40d0`) : aucun palier, aucune série, aucune progression
> affichés ; les badges de série que le serveur sert sont **écartés** de l'écran des badges et du
> profil.
>
> **Ce qui vous appartient** : cesser de les produire. Un badge qu'on attribue sans jamais le
> montrer est une donnée sur la régularité de quelqu'un que personne n'a demandée.

---

## 1. Relevé du 14/09/2026 (code serveur `9908a69`, production le 13/09)

- `BadgeConditionType` déclare `PROGRESSION_STREAK`, `STREAK_DAYS` et `WEEKLY_STREAK`
  (`trust/BadgeConditionType.java:7,20,25`).
- `BadgeService` les évalue encore (`BadgeService.java:74` `checkProgressionStreak`, `:79`
  `checkWeeklyStreak`).
- Le catalogue en production servait **six badges de série** sur 31 le 13/09/2026, dont un obtenu
  par le compte de test (« 1 mois de régularité », icône flamme) ; la graine historique en porte
  aussi (`V27__reset_and_seed_germany.sql:953`, `STREAK_DAYS`).
- P-BL-17 relève par ailleurs que `STREAK_MILESTONE` est déclaré sans être émis.

## 2. La demande

1. **Ne plus attribuer** de badge dont la condition est une série (`PROGRESSION_STREAK`,
   `STREAK_DAYS`, `WEEKLY_STREAK`).
2. **Retirer ces badges du catalogue servi** (`GET /api/badges`) et **des attributions servies**
   (`GET /api/badges/me`) — ou nous dire pourquoi il faut les garder.
3. **Les attributions existantes** : les supprimer, ou les garder en base sans jamais les servir ?
   La doctrine penche pour la suppression (une série est une stat d'effort) ; dites-nous ce que vous
   retenez, et si une migration est nécessaire.
4. Profiter du passage pour les deux points relevés le 13/09 : des **chiffres dans les noms servis**
   (« 10 séances partagées », « 5 partenaires différents ») et des **paliers déguisés en codes**
   (`TRUSTED_5` / `TRUSTED_20`) — mêmes questions.

## 3. Comment nous vérifierons

Relevé de `GET /api/badges` et `GET /api/badges/me` avec le compte de test : aucun badge de série,
aucun palier. Côté app, le filtre des séries reste en place : c'est l'interrupteur si un badge de
série revenait.
