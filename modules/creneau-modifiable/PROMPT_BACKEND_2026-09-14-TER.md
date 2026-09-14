# Le `PUT` qui fusionne ne sait rien retirer : quatre gestes de l'écran de modification annoncent « modifié » sans rien changer

**Date :** 2026-09-14
**Module :** `creneau-modifiable`
**Fait suite à :** `REPONSE_BACKEND_2026-09-02.md`, §2 (« le `PUT` fusionne »)

> **Ce que fait l'app** : son écran de modification envoie un corps construit par
> `buildSlotSchedulePayload` (`lib/features/slots/domain/slot_schedule_payload.dart`), qui **omet
> une clé quand la valeur est vide** — c'est le même constructeur que la création, où l'omission
> est juste.
>
> **Ce qui vous appartient** : dire comment un client retire une valeur. Votre §2.1 le dit pour
> `primaryLanguage`, `level` et `accessibilityTags`, pas pour les quatre champs ci-dessous. Nous
> avions écrit « votre écran, qui renvoie tout, reste correct » en le reprenant de vous : c'est faux
> pour ces quatre gestes, et c'est de notre côté que la phrase a été relue trop vite.

---

## 1. Relevé du 14/09/2026 (code serveur `ef09739`, déployé ; contrat `/v3/api-docs` de 13 h 44)

`ProgramService.updateSchedule` (`ProgramService.java:656-662`) applique `if (request.x() != null)`
à chaque champ. Un champ absent ou `null` garde l'ancienne valeur — c'est la règle écrite au contrat.
Conséquence, pour un organisateur qui modifie une séance déjà publiée :

| Geste dans l'écran | Corps envoyé | Ce que garde le serveur |
|---|---|---|
| « Une seule fois » sur un créneau récurrent | `recurrenceRule` absent | **la récurrence** |
| « Sans limite » de places | `maxParticipants` absent | **l'ancien plafond** |
| Mot d'accueil vidé | `welcomeNote` absent | **l'ancien mot** |
| Durée « non précisée » | `endsAt` absent | **l'ancienne fin** (le contrat dit que `endsAt` ne se retire pas) |

L'écran affiche « modifié », la fiche relue montre l'ancienne valeur. Nous n'avons pas rejoué ces
gestes en production (écriture) : le constat vient du code des deux côtés.

Lecture de code, pas une proposition : `welcomeNote: ""` serait aujourd'hui enregistré comme chaîne
vide après `strip()` ; `recurrenceRule: ""` serait enregistré tel quel, et nous ne savons pas ce qu'en
fait `RecurringSlotRolloverJob`. Nous ne l'enverrons pas sans que ce soit écrit au contrat.

## 2. La demande

1. **Une convention de retrait au contrat**, sur le modèle de votre §2.1, pour :
   - `recurrenceRule` → créneau ponctuel (chaîne vide ?) ; dites ce que devient la série en cours
     et ses séances futures déjà inscrites ;
   - `maxParticipants` → sans limite (une valeur explicite, par exemple `0`, ou une clé dédiée) ;
   - `welcomeNote` → aucun mot (chaîne vide ?).
2. **`endsAt`** : confirmer qu'il ne se retire pas ; l'app retirera alors « durée non précisée » de
   l'écran de **modification** (il reste à la création).
3. Si une notification part pour ces retraits (`SCHEDULE_CHANGED` ne concerne que l'heure et le
   lieu, §1.2) : rien à changer, dites-le seulement pour la récurrence.
4. Un test d'intégration par retrait : la fiche relue porte la valeur retirée.

## 3. Côté app, en attendant

L'app n'invente pas de convention. Elle envisage de masquer ces trois choix dans l'écran de
modification, ou de dire qu'ils ne s'appliquent pas à un créneau publié, jusqu'à votre réponse.

## 4. Comment nous vérifierons

Relevé du contrat, puis sur un créneau de test (écriture soumise à l'accord de l'utilisateur) : les
trois retraits, et `GET /programs/{id}` relu après chacun.
