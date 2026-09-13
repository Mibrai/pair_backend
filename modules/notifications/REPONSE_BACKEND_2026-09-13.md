# Les réglages ne font plus taire ce qui ne doit pas se taire, et l'e-mail parle votre langue

**Date :** 2026-09-13
**Module :** [`notifications/`](.)
**Suite de :** [`REPONSE_BACKEND_2026-09-03.md`](REPONSE_BACKEND_2026-09-03.md)
**Fiches d'audit :** P-BL-20, P-BA-18 (option B)
**Décisions :** produit, 13/09
**Migration :** `V117__frequence_notifications_immediate.sql`

> **En bref.**
>
> - **§1 — les sept types verrouillés de l'app sont verrouillés au serveur** : un réglage est accepté
>   sans effet, et la réponse porte la valeur effective.
> - **§2 — `DAILY_DIGEST` et `WEEKLY` sont dépréciées** : acceptées, enregistrées et appliquées comme
>   `IMMEDIATE`. Aucun résumé n'existe.
> - **§3 — l'e-mail d'annulation et de modification part dans la langue de la personne.**
> - **§4 — ce que nous vous suggérons** : masquer le sélecteur de fréquence.

---

## 1. Types verrouillés

Exactement `kNotificationTypesNotSettable` : `ACCOUNT_VERIFICATION`, `PASSWORD_RESET`,
`WATCH_RETURN_REMINDER`, `WATCH_ARRIVAL_PROMPT`, `WATCH_ARRIVAL_CONFIRMED`,
`GUARDIAN_CONSENT_REQUEST`, `WATCH_GUARDIAN_ALERT`.

- `PUT /api/notifications/preferences` sur l'un d'eux : **`200`**, rien n'est écrit, et le corps rend
  `emailEnabled: true`, `pushEnabled: true`, `frequency: IMMEDIATE`.
- `GET /api/notifications/preferences` rend aussi la valeur effective pour ces types.
- L'envoi ignore tout réglage stocké pour eux.

## 2. Fréquences

- `DAILY_DIGEST` et `WEEKLY` restent **acceptées** (jamais de `400`), mais sont **enregistrées comme
  `IMMEDIATE`** : le `PUT` rend `frequency: IMMEDIATE`.
- La fréquence n'entre plus dans l'envoi de l'e-mail : seul `emailEnabled` compte.
- V117 remet en `IMMEDIATE` les préférences existantes.

## 3. Langue de l'e-mail

La langue est celle de **l'appareil le plus récemment utilisé** (`locale` envoyée à
l'enregistrement du jeton, comme pour les push), et le français à défaut. Objet, corps, motif
d'annulation et alternatives sont traduits (fr, en, de).

**Pour que cela marche**, l'app doit continuer d'envoyer `locale` à l'enregistrement de l'appareil —
ce qu'elle fait déjà.

## 4. Ce que nous vous suggérons

- **Masquer le sélecteur de fréquence** (`frequencyMatters: false` partout) : il n'a plus d'effet.
- Rien n'est requis pour l'app publiée.
