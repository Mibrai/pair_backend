# Un compte non vérifié peut publier un créneau : seule l'app l'en empêche

**Date :** 2026-09-14
**Module :** `verification-email`
**Audit :** P-MU-17 (arrivée plus courte, brouillon avant vérification)
**Décision de l'utilisateur, 14/09/2026 :** d'abord que le serveur refuse la publication par un compte
non vérifié ; ensuite seulement, côté app, un brouillon avant vérification.

> **Ce que fait l'app aujourd'hui** : elle interdit les écrans de création d'un créneau
> (`/slots/new`, `/slots/quick`) à un compte dont l'e-mail n'est pas vérifié
> (`_verifiedOnlyRoutes`, `lib/core/router/app_router.dart`). C'est **la seule barrière**.
>
> **Ce qu'elle voudrait faire** : laisser la personne préparer son créneau pendant qu'elle vérifie
> son adresse, et ne bloquer qu'au moment de **publier**. Elle ne le fera pas tant que le serveur
> accepte la publication d'un compte non vérifié.

---

## 1. Relevé du 14/09/2026 (code serveur `9908a69`)

- `VerificationStatus.UNVERIFIED` n'est lu que pour l'**affichage** : pages publiques
  (`PublicSlotService.java:272-274`, `PublicProgramService.java:210`) et badges
  (`BadgeService.java:155`).
- **Aucun contrôle de vérification** dans les routes d'écriture de créneau : `QuickSlotController`,
  `SlotController`, `ProgramController` (`POST /programs/{id}/schedules`) et leurs services — relevé
  du 13/09/2026 par la passe P-MU-17, reconfirmé par recherche de `verificationStatus` le 14/09.
- Un client autre que l'app — ou une app modifiée — publie donc un créneau sans adresse vérifiée.

## 2. La demande

1. **Refuser la publication** par un compte `UNVERIFIED` : `POST /api/quick-slots`,
   `POST /api/programs/{programId}/schedules`, et toute route qui rend un créneau visible à autrui
   (passage d'un programme en `ACTIVE` s'il publie ses créneaux).
2. **Un code d'erreur stable**, par exemple `403 EMAIL_NOT_VERIFIED`, que l'app traduira en « vérifie
   ton adresse pour publier » avec un lien vers la vérification — jamais un 403 générique.
3. **Dites-nous ce qui reste permis** à un compte non vérifié : créer un programme en brouillon
   (`DRAFT`) ? rejoindre un créneau ? envoyer un message ? L'app alignera son parcours sur votre
   liste plutôt que de la deviner.

## 3. Côté app, ensuite

Quand le refus sera déployé et relevé (contrat **et** appel réel avec un compte non vérifié), l'app
retirera la garde des écrans de création, gardera le créneau en brouillon local (effacé à la
déconnexion, P-MS-05), et affichera le refus au moment de publier.

## 4. Comment nous vérifierons

Relevé `/v3/api-docs` (le `403` documenté), puis avec un compte de test non vérifié : `POST
/api/quick-slots` → `403 EMAIL_NOT_VERIFIED`, rien de créé ; après vérification, la même requête
aboutit.
