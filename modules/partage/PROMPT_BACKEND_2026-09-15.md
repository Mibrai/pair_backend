# Le fichier agenda (`.ics`) public écrit le lieu et la récurrence ensemble

**Date :** 2026-09-15
**Module :** `partage`
**Audit :** signalement de la vérification d'audit mobile du 14/09/2026 ; doctrine §9 (jamais un lieu
et une récurrence ensemble dans un contenu publié)

> **Ce que fait l'app, depuis le 15/09** : elle retire toute ligne `LOCATION` d'un `.ics` qu'elle passe
> à la feuille de partage (`lib/features/sharing/domain/ics_sans_lieu.dart`).
>
> **Ce qui vous appartient** : les routes publiques, que l'app ouvre dans le navigateur sans pouvoir
> les filtrer.

---

## 1. Relevé du 15/09/2026 (code serveur, production `0c3fb50`)

- `SlotCalendarService.toEvent` écrit `LOCATION` (nom du lieu **et** adresse) dans le même `VEVENT`
  que la `RRULE`.
- Routes publiques concernées : `/public/slots/{token}/calendar.ics` et `/s/{token}/calendar.ics` —
  un contenu diffusé sans demandeur authentifié : un lieu et une régularité ensemble, le schéma de vie
  que le module `safety_watch` existe pour empêcher.

## 2. La demande

1. Sur les routes **publiques** : ne jamais écrire `LOCATION` quand le créneau a une `RRULE` (le lien
   public reste dans `DESCRIPTION`).
2. Dites-nous si vous l'appliquez aussi aux routes **authentifiées** : l'app pourra alors retirer son
   propre filtre, ou le garder pour les créneaux ponctuels selon la décision produit.
3. Un test : un créneau récurrent servi en `.ics` public ne porte pas `LOCATION`.

## 3. Comment nous vérifierons

`GET /s/<jeton-d-un-créneau-récurrent>/calendar.ics` sans session : `RRULE` présente, aucune ligne
`LOCATION`.
