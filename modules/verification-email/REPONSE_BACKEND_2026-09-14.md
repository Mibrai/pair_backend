# Publier un créneau demande une adresse vérifiée : `403 EMAIL_NOT_VERIFIED`

**Date :** 2026-09-14
**Module :** [`verification-email/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md) — P-MU-17

> **En bref.**
>
> - **§1 — votre relevé est exact** : aucune route d'écriture ne regardait `verificationStatus`.
> - **§2.1 — livré.** Trois gestes publient, et les trois refusent un compte `UNVERIFIED`.
> - **§2.2 — `403 EMAIL_NOT_VERIFIED`**, un code dédié, traduit en français, anglais et allemand, et
>   documenté au contrat sur les quatre opérations.
> - **§2.3 — la liste de ce qui reste permis est en §3.** En résumé : tout, sauf rendre un créneau
>   visible à autrui. Un programme en brouillon **et ses créneaux** restent permis. Vous pouvez donc
>   garder le brouillon sur le serveur plutôt qu'en local, si vous le préférez.

---

## 1. Le relevé, vérifié

Exact. `verificationStatus` n'était lu que pour l'affichage (pages publiques, badges, résultats de
recherche) et pour le renvoi de l'e-mail de vérification. Rien dans `QuickSlotService`,
`ProgramService` ni `SlotController`.

## 2. Ce qui refuse

La règle vit dans `PublicationVerifiee.exiger`. **Tout statut autre que `UNVERIFIED` publie** :
`EMAIL_VERIFIED`, `PHONE_VERIFIED`, `ID_VERIFIED`.

| Route | Refusée quand | Ce qui est écrit |
|---|---|---|
| `POST /api/quick-slots` | toujours, pour un compte non vérifié : le créneau rapide naît publié | **rien.** Le contrôle passe avant la déclaration d'activité et le programme |
| `POST /api/programs/{programId}/schedules` | le programme **n'est pas** `DRAFT` (`ACTIVE`, `PAUSED`, `DORMANT`…) | rien |
| `PUT` et `PATCH /api/programs/{programId}` | `status: "ACTIVE"` demandé sur un programme qui ne l'est pas encore | rien : le programme reste dans son état, **les autres champs de la requête compris** |

**Le corps du refus** a la forme habituelle :

```json
{"code": "EMAIL_NOT_VERIFIED", "message": "Vérifiez votre adresse e-mail pour publier un créneau.", "timestamp": "…"}
```

Le message suit `Accept-Language`. Vous traduisez le code, comme vous l'avez prévu.

**L'ordre des contrôles.** Sur les routes de programme, la propriété est vérifiée d'abord : un
non-propriétaire reçoit son refus habituel, jamais `EMAIL_NOT_VERIFIED`, qui dirait quelque chose de
l'état de son compte.

**Pourquoi un créneau dans un brouillon passe.** Un programme `DRAFT` est invisible : ni fil, ni carte,
ni recherche, ni page publique (toutes bornent sur `status = 'ACTIVE'`). Un créneau posé dedans n'est
vu de personne. Le refus tombe au moment où il deviendrait visible, c'est-à-dire au passage à
`ACTIVE`. C'est exactement le « bloquer au moment de publier » que vous décrivez.

**`DORMANT`.** Poser un créneau réveille un programme endormi (`ProgramService.wakeIfDormant`). Ce
réveil publie, donc il est refusé à un compte non vérifié.

**Les programmes publiés avant ce contrôle** par des comptes non vérifiés restent en ligne. Nous ne les
retirons pas : la règle vaut pour les publications à venir. Un nouveau créneau sur l'un d'eux est
refusé.

## 3. Ce qui reste permis à un compte non vérifié

Rien d'autre ne change. Liste complète des gestes qui touchent aux créneaux :

| Geste | Permis |
|---|---|
| Créer un programme (`POST /api/programs`, qui naît `DRAFT`) | oui |
| Le modifier tant qu'il est `DRAFT`, **y poser, modifier et supprimer des créneaux** | oui |
| Le dupliquer (la copie naît `DRAFT`) | oui |
| Le passer à `ACTIVE`, publier un créneau rapide | **non** |
| Rejoindre un créneau, se mettre en liste d'attente, se désinscrire | oui |
| Envoyer un message, partager une position | oui |
| Armer une veille, s'abonner, publier une affiche | oui |

Pour rejoindre ou écrire, ce n'est pas un oubli. Votre demande porte sur la publication, et la
décision du 14/09 aussi. Fermer davantage serait une décision produit que personne n'a prise. Si vous
voulez l'étendre, demandez-le, et nous le ferons avec le même code.

## 4. Ce que nous vous suggérons

- **Deux façons de garder le brouillon.** En local, comme vous l'avez prévu (effacé à la déconnexion,
  P-MS-05). Ou sur le serveur, en `DRAFT` : il suit alors la personne d'un appareil à l'autre, et vous
  n'avez rien à effacer. Le serveur accepte les deux.
- **Le passage à `ACTIVE` est le point où afficher le refus.** Une personne vérifiée entre-temps n'a
  rien à refaire : la même requête aboutit dès que `verificationStatus` a changé. Le statut est relu en
  base à chaque appel, pas dans le jeton.

## 5. Vérification

- `PublicationAdresseVerifieeIntegrationTest` (nouveau), avec des comptes réellement non vérifiés
  (`verificationStatus` lu dans la réponse d'inscription) :
  - `POST /api/quick-slots` rend `403 EMAIL_NOT_VERIFIED`, aucune ligne `user_activities` n'est
    écrite, et la même requête rend `201` après vérification ;
  - un brouillon accepte un créneau. `PATCH` puis `PUT` `status: ACTIVE` rendent chacun `403`, le
    programme reste `DRAFT`, et le `PATCH` aboutit après vérification ;
  - un créneau posé sur un programme `ACTIVE` d'un compte non vérifié rend `403`, et aucun créneau
    n'est écrit ;
  - `/v3/api-docs` documente le `403 EMAIL_NOT_VERIFIED` sur les trois opérations.
- **Les 41 classes de test qui publient** avec un compte fraîchement inscrit vérifient désormais son
  adresse juste après l'inscription (`AbstractIntegrationTest.adresseVerifiee`). La suite entière les
  a trouvées. Quatre activaient un programme par `PUT`/`PATCH` ou réveillaient un programme endormi,
  sans passer par les routes de créneau.
- **Suite entière : 1758 tests, 244 classes, verts** après cette correction.
- `ErrorCodeTraductionTest` : le code a ses trois clés.
- **Votre relevé :** `/v3/api-docs`, puis avec un compte de test non vérifié, `POST /api/quick-slots`
  doit rendre `403 EMAIL_NOT_VERIFIED`, et `GET /api/slots/mine` ne rien montrer de neuf.
