# Réponse du 15/09 — le `.ics` public d'un créneau récurrent ne porte plus de lieu

**Date :** 2026-09-15
**Module :** `partage`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15.md`

> **Votre relevé est exact, et c'est livré.** Sur les deux routes publiques, un créneau récurrent est
> servi **sans `LOCATION`**. **Les routes authentifiées gardent le lieu** (§2).

---

## 1. La règle

`GET /public/slots/{token}/calendar.ics` et `GET /s/{token}/calendar.ics`, servies sans session :

| Créneau | `RRULE` | `LOCATION` | Lien vers la page publique (`DESCRIPTION`) |
|---|---|---|---|
| **récurrent** | présente | **absente** | présent |
| ponctuel | absente | présente, comme avant : nom du lieu, et l'adresse seulement si elle est diffusable | présent |

Un lieu et une régularité ensemble, dans un fichier qui circule sans demandeur, décrivent où quelqu'un
se trouve chaque semaine. Un créneau ponctuel ne décrit qu'un rendez-vous, et garde son lieu.

## 2. Les routes authentifiées : le lieu reste

`GET /api/slots/{scheduleId}/calendar.ics` et `GET /api/slots/mine/calendar.ics` **gardent `LOCATION`**,
récurrence comprise. Ces fichiers sont servis à une personne identifiée, pour **son propre agenda** :
le lieu y est utile, et l'adresse y suit déjà la règle de visibilité de chacun.

Le risque apparaît quand ce fichier est **transmis à d'autres**. C'est votre feuille de partage, que
vous filtrez déjà (`ics_sans_lieu.dart`). **Gardez ce filtre** : le serveur ne peut pas savoir qu'un
fichier téléchargé pour soi va être partagé. Pour les créneaux ponctuels partagés, la décision
produit vous appartient.

## 3. Vérification

**Tests** (`SlotCalendarIntegrationTest`) :

- créneau récurrent : le `.ics` public, par ses deux adresses, porte `RRULE` et le lien de la page,
  et aucune ligne `LOCATION` ; le `.ics` authentifié de l'organisateur garde `LOCATION` et `RRULE` ;
- créneau ponctuel : le `.ics` public garde `LOCATION`.

**Votre protocole du §3** : `GET /s/<jeton-d-un-créneau-récurrent>/calendar.ics` sans session rend
`RRULE` et aucune ligne `LOCATION`.
