# ⚠️ INCIDENT — `GET /api/slots/mine` rend 404 « Utilisateur introuvable » pour tout le monde

**Date :** 2026-09-14
**Module :** `creneau-modifiable`
**Priorité : immédiate.** « Mes créneaux » et l'accueil de l'app sont vides en production.

---

## 1. Constat, rejoué en production le 14/09/2026 à 11 h 22 (lecture seule)

Avec les deux comptes de test, connexion en `200` :

| Route | Réponse |
|---|---|
| `GET /api/slots/mine` | **`404 {"code":"NOT_FOUND","message":"Utilisateur introuvable."}`** |
| `GET /api/slots/mine?upcoming=true` | **`404`, même corps** |
| `GET /api/users/me` | `200` |
| `GET /api/attendances/mine` | `200` |
| `GET /api/users/me/programs` | `200` |

Le jeton est valide : ce n'est pas la session.

## 2. Cause probable — à confirmer par vous

Lecture du code `origin/master` du 14/09 (hypothèse, pas une preuve) :

- `SlotService.getMySlots` construit `feedContext(slots, userId)`, qui appelle
  `userService.getPublicProfile(id, requesterId)` **pour l'hôte de chaque créneau**
  (`SlotService.java:764-768`).
- `getPublicProfile` passe par `findActiveUser`, qui **lève `UserNotFoundException` si le compte
  n'est pas actif** (`UserService.java:421-425`) — traduite en `404 NOT_FOUND`.
- **`V116__fermeture_comptes_demo.sql`** a passé les vingt comptes `demo%@pair.app` à
  `is_active = false` en production. Les deux comptes de test sont inscrits à des créneaux dont
  l'hôte est un compte démo : **un seul hôte désactivé fait tomber toute la liste**.

Si c'est bien cela, toute route qui compose un `SlotFeedItemDto` par `feedContext` est exposée :
`/slots/feed`, `/slots/{id}`, `/quick-slots`, la recherche — à vérifier. Le fil de Cologne a
pourtant répondu `200` à 11 h 15 : il filtre peut-être déjà les hôtes inactifs en amont.

## 3. La demande

1. **Rétablir `/slots/mine` tout de suite** : un hôte désactivé ne doit jamais faire échouer la
   liste d'un autre utilisateur.
2. **Décider ce que devient un créneau dont l'hôte est désactivé** dans « mes créneaux » d'un
   inscrit : retiré de la liste, ou gardé avec un hôte « compte fermé » (sans profil). L'app sait
   afficher un créneau sans profil d'hôte ; dites-nous lequel.
3. **Passer en revue les autres appelants** de `getPublicProfile` dans une boucle (fil, recherche,
   inscrits, conversations) pour la même cause.
4. Un test d'intégration : un créneau dont l'hôte est désactivé ne fait tomber ni `/slots/mine`,
   ni `/slots/feed`, ni `/slots/{id}` d'un inscrit.

## 4. Comment nous vérifierons

`GET /api/slots/mine` et `?upcoming=true` en `200` avec les deux comptes de test, et la liste
conforme à la décision du §3.2.
