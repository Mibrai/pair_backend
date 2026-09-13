# Les inscrits d'un créneau voient les prénoms des autres inscrits

**Date :** 2026-09-13
**Module :** `inscription`
**Audit :** P-MU-28 (P2b), P-BL-05

> **Ce que l'app fera, sans vous** : un drapeau `participantNamesForEnrolled`, éteint, par le
> constructeur de la fiche créneau ; une liste en lecture seule pour un inscrit, sans contrôle
> d'arrivée.
>
> **Ce qui vous appartient** : ouvrir la lecture aux inscrits confirmés, avec une réponse réduite.

---

## 1. Relevés du 13/09/2026 (backend `bdc6670`, production)

- `GET /api/slots/{scheduleId}/participants` (`SlotController.java:189-194`,
  `SlotService.java:655-683`) répond `403 SLOT_PARTICIPANTS_HOST_ONLY` à tout appelant qui n'est
  pas l'hôte.
- En HTTP : un compte inscrit (`CONFIRMED`) → **403** `{"code":"SLOT_PARTICIPANTS_HOST_ONLY"}` ;
  le même compte, hôte d'un autre créneau → **200**, 31 lignes.
- Ce que voit l'hôte, par ligne (`SlotParticipantDto`) : `participationId`, `user` — le
  `UserPublicDto` **complet** (`id`, `displayName`, `bio`, `avatarUrl`, `verificationStatus`,
  `badgeCodes`, `activities`, `isOnline`, `subscriberCount`, `subscribed`, `reliabilitySignal`) —,
  `status`, `joinMessage`, `createdAt`, `arrival`.
- Seul `WAITLISTED` est exclu : une ligne `WITHDRAWN` sort aussi. Aucun filtre de blocage.
- Au contrat : aucune description, pas de 403 nommé.
- Voisin existant : `GET /api/attendances/{id}/co-participants` (`AttendanceService.java:139-146`),
  réservé à qui a confirmé sa présence, **après** la séance.

## 2. La demande

1. **Ouvrir la lecture aux inscrits `CONFIRMED`** du créneau, **avant** la séance. Un non-inscrit,
   un `WAITLISTED` ou un `WITHDRAWN` garde le 403.
2. **Pour un non-hôte, une réponse réduite** : `userId`, prénom (ou `displayName`), `avatarUrl`.
   Ni `bio`, ni `activities`, ni `isOnline`, ni `reliabilitySignal`, ni `joinMessage`, ni
   `arrival`, ni `status`. Un DTO distinct plutôt que des champs nuls, pour que la spec le dise.
3. **Seulement les `CONFIRMED`**, pour l'hôte comme pour les inscrits.
4. **Blocage dans les deux sens** (P-BL-05) : une personne bloquée par l'appelant, ou qui l'a
   bloqué, n'apparaît pas, et l'appelant n'apparaît pas chez elle.
5. **« Qui me voit »** : la doctrine exige que tout canal d'observation figure dans
   `/settings/who-sees-me`. Comment l'exposer (« les autres inscrits de tes créneaux voient ton
   prénom » suffit), et un réglage de retrait est-il prévu ?

## 3. Comment nous vérifierons

Relevé de `/v3/api-docs`, puis en HTTP avec deux comptes inscrits au même créneau : 200 réduit pour
l'inscrit, complet pour l'hôte ; 403 pour un non-inscrit ; après un blocage, chacun disparaît chez
l'autre. Bascule du drapeau dans un commit séparé.
