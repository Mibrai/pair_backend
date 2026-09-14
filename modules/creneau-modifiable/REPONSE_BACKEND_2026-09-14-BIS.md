# Réponse à l'incident du 14/09 — `GET /api/slots/mine` en 404 : un hôte au compte fermé faisait tomber toute la liste

**Date :** 2026-09-14
**Fait suite à :** `PROMPT_BACKEND_2026-09-14-BIS.md`

> **Votre cause probable est la bonne, mot pour mot.** `V116` a désactivé les vingt comptes démo ;
> `getMySlots` compose le profil de chaque hôte par `getPublicProfile`, qui lève
> `UserNotFoundException` pour un compte inactif. Un seul hôte dans ce cas, et toute la liste sortait
> en `404 « Utilisateur introuvable »`. Reproduit en test avant correction, 4 échecs sur 5.
>
> **§3.2 — décision : le créneau sort de « mes créneaux ».** Il est déjà absent du fil et de la carte,
> et sa fiche rend désormais `404 « Créneau introuvable »`.
>
> **§3.3 — la revue a trouvé deux autres listes tombées pour la même cause**, et vous ne les aviez
> pas encore rencontrées : les **inscrits vus par l'hôte** et sa **file d'attente**. Plus deux
> surfaces exposées à la même cause (§3).
>
> **Ce n'est pas propre aux comptes démo.** Fermer son compte (`deactivateAccount`) pose le même
> `is_active = false`. Le défaut aurait donc surgi au premier utilisateur réel qui ferme son compte
> en ayant des inscrits.

---

## 1. La cause, confirmée

| Maillon | Code |
|---|---|
| `GET /slots/mine` → `SlotService.getMySlots` | rassemble les créneaux hébergés et rejoints, **sans regarder l'hôte** |
| → `feedContext(slots, userId)` | appelle `userService.getPublicProfile(hôte, userId)` pour chaque hôte distinct |
| → `findActiveUser` | lève `UserNotFoundException` si `is_active = false` → `404 NOT_FOUND` |
| `V116__fermeture_comptes_demo.sql` | `is_active = false` sur `demo%@pair.app`, **sous railway et prod seulement** : invisible en dev et en test |

**Pourquoi le fil de Cologne répondait `200`** : le fil (`/slots/feed`) et la carte
(`/slots/bounds`) sélectionnent leurs créneaux en SQL, et la clause commune
`OPEN_SLOTS_VISIBLE_BASE` porte `u.is_active = TRUE`. Un hôte fermé n'y arrive jamais jusqu'au
profil. « Mes créneaux » part des inscriptions, pas de cette clause.

## 2. La décision du §3.2 : retiré de la liste

Nous avons écarté l'hôte « compte fermé » sans profil, pour trois raisons :

- **cohérence** : le fil et la carte ne montrent déjà plus ce créneau. Le garder ici ferait exister
  une séance à un seul endroit de l'app ;
- **la fiche** : un créneau listé doit s'ouvrir. `getMySlots` applique déjà cette règle au
  blocage : « la liste annoncerait un créneau dont la fiche rend 404 » ;
- **le cas réel d'aujourd'hui** : ce sont des séances de démonstration, sans organisateur réel.

**Ce que cette décision ne règle pas, et nous le disons** : fermer un compte **n'annule pas** ses
créneaux et **ne prévient pas** ses inscrits. Un inscrit voit la séance disparaître, sans message.
Pour les comptes démo, c'est sans conséquence. Pour un vrai organisateur qui ferme son compte la
veille d'une séance, c'est une question produit : annuler ses créneaux à venir à la fermeture, avec
le `SLOT_CANCELLED` habituel. Elle est hors du périmètre d'un correctif d'incident, et nous ne la
tranchons pas ici.

## 3. La revue du §3.3

Tous les appelants de `getPublicProfile` dans une boucle, et tous les chemins qui composent un
créneau :

| Surface | Avant | Après |
|---|---|---|
| `GET /slots/mine` (± `upcoming`) | **404 pour toute la liste** | 200, créneau de l'hôte fermé omis |
| `GET /slots/{id}` | 404 « **Utilisateur** introuvable » | 404 « **Créneau** introuvable » |
| `POST /slots/{id}/join`, `POST /slots/{id}/waitlist` | 404 « Utilisateur introuvable », **après** les contrôles d'entrée | 404 « Créneau introuvable », **en tête** de `SlotEntryGuard`, donc aussi pour `POST /programs/{id}/join` avec un `scheduleId` |
| `GET /slots/{id}/participants` (l'hôte) | **404 pour toute la liste** si un inscrit a fermé son compte | 200, inscrit fermé omis |
| `GET /slots/{id}/waitlist` (l'hôte) | **404 pour toute la file** | 200, compte fermé omis |
| `GET /attendances/{id}/co-participants` | 404 si un co-présent a fermé son compte | co-présent fermé omis |
| Cartes-souvenirs (`SlotRecapService`) | 404 pour la liste si **l'hôte** est fermé (les présents étaient déjà filtrés) | carte gardée, `host: null` |
| `GET /slots/feed`, `/slots/bounds` | déjà filtrés en SQL | inchangé |
| `GET /slots/{id}/co-participants` | déjà filtré (`isActive`) | inchangé |

**Et un filet** : `feedContext` n'appelle plus `getPublicProfile` pour un hôte inactif. Si une
future liste oublie le filtre, son créneau sort avec un hôte `null` au lieu de faire tomber tout le
lot. L'app sait afficher un créneau sans profil d'hôte, vous nous l'avez dit.

## 4. Vérification

**Test d'intégration** `HoteDesactiveIntegrationTest` (5 tests). Le compte est désactivé en base,
exactement comme `V116` :

- `/slots/mine` et `?upcoming=true` : 200, le créneau de l'hôte fermé absent, celui d'un hôte actif
  présent ;
- `/slots/{id}` par un inscrit : 404 « Créneau introuvable. » ;
- `/slots/feed` : 200, créneau absent ;
- `POST /slots/{id}/join` : 404 « Créneau introuvable. », aucune ligne d'inscription créée ;
- `/slots/{id}/participants` par l'hôte : 200, l'inscrit fermé absent.

**Rejoué sans le correctif**, le test échoue sur 4 de ses 5 cas : `/slots/mine` et la liste des
inscrits en `404`, la fiche et l'inscription en « Utilisateur introuvable. ». Le fil passe dans les
deux cas, comme attendu. Les lignes « Avant » de la file d'attente, des co-présents et des
cartes-souvenirs viennent de la lecture du code, pas d'un test.

**Votre protocole du §4 tient tel quel** après déploiement : `GET /api/slots/mine` et
`?upcoming=true` en `200` avec les deux comptes de test. Les créneaux des hôtes démo n'y figurent
plus.
