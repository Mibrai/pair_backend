# « Reporter » a une fin, et la veille ne devine plus son heure limite

**Date :** 2026-09-13
**Module :** [`tracabilite/`](.) — veille de retour
**Fiches d'audit :** P-BL-22, P-BL-15 étape 1
**Décisions :** produit, 13/09
**Migration :** `V118__veille_reports_plafonnes.sql`

> **En bref.**
>
> - **§1 — au plus trois reports**, et jamais plus de deux heures cumulées. Le quatrième rend
>   `409 WATCH_SNOOZE_LIMIT`.
> - **§2 — `WatchDto.snoozesLeft`**, additif, pour griser le bouton.
> - **§3 — le message au contact dit combien de fois l'heure a été repoussée.**
> - **§4 — armer sans heure limite sur un créneau sans fin : `422 WATCH_DEADLINE_REQUIRED`.**

---

## 1. Plafond des reports

`POST /api/watches/{id}/snooze` repousse toujours l'heure limite de **30 minutes** et réarme les
rappels. Au-delà de **3 reports** (ou de **2 h cumulées**), il rend **`409 WATCH_SNOOZE_LIMIT`**,
l'heure limite ne bouge plus, et l'escalade suit son cours. Message traduit dans les trois bundles.

Les reports déjà faits sur une veille en cours sont comptés depuis la chronologie : le déploiement ne
rend pas trois reports de plus.

## 2. `snoozesLeft`

`WatchDto.snoozesLeft` (entier, 3 → 0). À `0`, le bouton se grise ; l'app 1.1.0+16 l'ignore et
affiche le refus générique au quatrième appui.

## 3. Message au contact

Le SMS et l'e-mail d'alerte de retour ajoutent, quand il y a eu au moins un report : « L'heure limite
a été repoussée une fois. » / « … N fois. ». Rien sinon.

## 4. Heure limite exigée sur un créneau sans fin

`POST /api/watches` **sans `deadlineAt`** sur un créneau **sans fin déclarée** rend **`422
WATCH_DEADLINE_REQUIRED`**. Avec `deadlineAt`, la veille s'arme comme avant. Après la reprise V119,
tous les créneaux ont une fin : ce refus ne devrait se voir que sur une ligne écrite hors de l'API.
Voir [`../passe/REPONSE_BACKEND_2026-09-13.md`](../passe/REPONSE_BACKEND_2026-09-13.md).

## 5. Ce que nous vous suggérons

- Griser « reporter » à `snoozesLeft == 0`, avec une phrase qui dit pourquoi.
- Sur `WATCH_DEADLINE_REQUIRED`, proposer le choix de l'heure limite.
