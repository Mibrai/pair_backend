# Les inscrits voient les prénoms des autres inscrits — par une route à eux

**Date :** 2026-09-13
**Module :** [`inscription/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-13.md`](PROMPT_BACKEND_2026-09-13.md) — P-MU-28 (P2b), P-BL-05

> **En bref.**
>
> - **§1 — une route distincte**, `GET /api/slots/{scheduleId}/co-participants`, plutôt que la même
>   route aux deux formes.
> - **§2 — réservée aux inscrits `CONFIRMED`**, prénom et avatar seulement, blocage dans les deux sens.
> - **§3 — la liste de l'hôte** ne sert plus que les `CONFIRMED`, et applique aussi le blocage.
> - **§4 — « qui me voit »** : une ligne à ajouter côté app, pas de réglage de retrait.

---

## 1. Pourquoi une route distincte

Vous demandiez un DTO distinct « pour que la spec le dise ». Une même route qui rendrait deux schémas
selon l'appelant ne se décrit en OpenAPI que par un `oneOf`, qu'aucun client généré ne lit sans ambiguïté. Deux routes,
deux schémas : `/participants` reste l'écran de l'hôte, `/co-participants` est celui d'un inscrit.

## 2. `GET /api/slots/{scheduleId}/co-participants`

- **Qui** : un inscrit `CONFIRMED` du créneau. Non-inscrit, `WAITLISTED` ou `WITHDRAWN` → **`403
  SLOT_PARTICIPANTS_ENROLLED_ONLY`** (message traduit). Un hôte bloqué avec l'appelant → `404`, comme
  la fiche.
- **Quoi** : les **autres** `CONFIRMED`, l'appelant exclu, comptes actifs seulement.
- **Blocage, dans les deux sens** : une personne bloquée par l'appelant ou qui l'a bloqué n'apparaît
  pas, et l'appelant n'apparaît pas chez elle.
- **Réponse** : `SlotCoParticipantDto[]` —

| Champ | Contenu |
|---|---|
| `userId` | identifiant |
| `firstName` | prénom déduit du nom affiché (le nom entier s'il n'a qu'un mot) |
| `avatarUrl` | nullable |

Ni `bio`, ni `activities`, ni `isOnline`, ni `reliabilitySignal`, ni `joinMessage`, ni `arrival`, ni
`status`. Disponible **avant** la séance.

## 3. `GET /api/slots/{scheduleId}/participants` (hôte)

Toujours réservée à l'hôte (`403 SLOT_PARTICIPANTS_HOST_ONLY`). Deux changements :

- **seuls les `CONFIRMED`** : une ligne `WITHDRAWN` ne sort plus ;
- **blocage** : une personne bloquée avec l'hôte, dans un sens ou dans l'autre, n'apparaît pas.

## 4. « Qui me voit »

Rien côté serveur : l'écran `/settings/who-sees-me` est à vous. Phrase proposée : « Les autres inscrits
de tes créneaux voient ton prénom et ta photo. » **Pas de réglage de retrait** pour l'instant (décision
du propriétaire du dépôt, 13/09) : seul le prénom et l'avatar sont montrés, et seulement à qui est
inscrit au même créneau.
