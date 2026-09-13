# Une séance a toujours une fin, et le serveur publie la fin qu'il utilise

**Date :** 2026-09-13
**Module :** [`passe/`](.)
**Suite de :** [`REPONSE_BACKEND_2026-09-05.md`](REPONSE_BACKEND_2026-09-05.md)
**Fiches d'audit :** P-BL-15, P-BA-19 (étapes 3 et 4) — côté app : P-MA-01
**Décision :** P-BA/D1, « A tout de suite, C » (13/09)
**Migration :** `V119__creneaux_fin_renseignee.sql`

> **En bref.**
>
> - **§1 — `endsAt` est obligatoire à la création** : `POST /quick-slots` et
>   `POST /programs/{id}/schedules` rendent `400` sans lui. Les quatre écrans de 1.1.0+16 l'envoient
>   déjà.
> - **§2 — deux champs additifs** sur `ScheduleDto` et `SlotFeedItemDto` : `effectiveEndsAt` et
>   `endsAtDeclared`.
> - **§3 — les anciens créneaux sans fin en ont reçu une** : durée du programme, deux heures à défaut.
> - **§4 — ce que nous vous suggérons** : une seule règle `isPast`, sur `effectiveEndsAt`.

---

## 1. `endsAt` obligatoire

- `QuickSlotRequest.endsAt` et `CreateScheduleRequest.endsAt` sont `@NotNull` : absents, **`400
  VALIDATION_ERROR`**.
- `PUT .../schedules/{id}` : `endsAt` absent veut toujours dire « inchangé ». Une mise à jour ne peut
  pas retirer la fin.

## 2. `effectiveEndsAt` et `endsAtDeclared`

| Champ | Type | Valeur |
|---|---|---|
| `effectiveEndsAt` | instant, **toujours renseigné** | `endsAt` s'il est déclaré, sinon `startsAt + 2 h` |
| `endsAtDeclared` | booléen | `true` quand `endsAt` a été déclaré |

`effectiveEndsAt` est **la frontière que le serveur utilise** pour « terminé » : demande de présence,
passage en `PAST`, carte-souvenir. `endsAtDeclared=false` ne concerne plus que d'éventuelles lignes
écrites par un autre chemin ; après V119, il ne devrait plus apparaître.

## 3. Reprise des données

Les créneaux sans `ends_at` ont reçu `starts_at + durée de séance du programme`, ou deux heures à
défaut — la convention que le serveur appliquait déjà sans le dire. La contrainte `NOT NULL` en base
viendra dans une migration séparée.

## 4. Veille

Armer une veille **sans `deadlineAt`** sur un créneau **sans fin déclarée** rend **`422
WATCH_DEADLINE_REQUIRED`** : le serveur ne cadre plus une veille sur deux heures inventées. Voir
[`../tracabilite/REPONSE_BACKEND_2026-09-13.md`](../tracabilite/REPONSE_BACKEND_2026-09-13.md).

## 5. Ce que nous vous suggérons (P-MA-01)

- **Une seule règle `isPast`** : `now >= effectiveEndsAt`. Elle remplace « passé dès le début » de
  `slot_models.dart` et « en cours, jamais passé » de `CLAUDE.md`.
- `endsAtDeclared` ne sert qu'à l'affichage (« fin non précisée »).
