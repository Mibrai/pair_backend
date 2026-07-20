# Prompt backend — corriger le profil public utilisateur

> À coller à Claude dans le dépôt **backend** (Spring Boot, déployé sur Railway,
> base URL `.../api`). Deux endpoints de profil utilisateur sont cassés et
> bloquent l'affichage du profil public d'un auteur depuis l'app mobile Flutter.

---

Contexte : backend Spring Boot de l'app « Pair ». L'app mobile consomme cette
API. Deux endpoints de profil utilisateur sont cassés et empêchent d'afficher
le profil public d'un auteur (nom, activités, programmes, note). Corrige-les.

## BUG 1 — `GET /api/users/{id}` renvoie 500 (INTERNAL_ERROR)

Requête réelle observée :

```
GET /api/users/00000000-0000-0000-0000-000000000002
Authorization: Bearer <token valide>
```

Réponse :

```json
HTTP 500
{ "code": "INTERNAL_ERROR", "message": "Une erreur est survenue.",
  "timestamp": "2026-07-20T20:08:55Z" }
```

Ce qui **fonctionne** (à comparer pour isoler la cause) :

- `GET /api/users/me` → 200 (même entité, projection **privée**)
- `GET /api/users/{id}/programs` → 200
- `GET /api/users/me/activities` → 200

Seule la projection **publique** d'un utilisateur par id échoue. L'utilisateur
`00000000-0000-0000-0000-000000000002` (seed « Lena Müller ») existe bien — il
est organisateur de nombreux programmes renvoyés par ailleurs — ce n'est donc
pas un 404 déguisé.

**Attendu :** obtenir la vraie stack trace de `GET /users/{id}` (contrôleur →
service → mapping vers `UserPublicDto`) au lieu du message masqué « Une erreur
est survenue. ». Hypothèses probables : NPE lors du calcul des agrégats
(`totalActivities` / `totalDistanceKm` / `totalDurationMinutes` / `badges` /
`activities`), ou une relation lazy chargée hors session Hibernate pendant la
sérialisation de la projection publique. Reproduire avec cet id précis,
corriger la cause racine, et garantir un 200 avec un `UserPublicDto` valide
même quand les agrégats sont nuls/absents.

## BUG 2 — `GET /api/users/{id}/activities` renvoie 404 (route inexistante)

Requête réelle observée :

```
GET /api/users/00000000-0000-0000-0000-000000000002/activities
```

Réponse :

```json
HTTP 404
{ "code": "NOT_FOUND",
  "message": "Resource not found: /api/users/00000000-.../activities" }
```

Le endpoint n'existe pas : seul `/api/users/me/activities` est exposé. Le
frontend a besoin de lister les activités **publiques** d'un autre utilisateur
(page profil d'un auteur).

**Attendu :** exposer `GET /api/users/{id}/activities` (auth requise), renvoyant
les `UserActivity` publiques de cet utilisateur, au **même format** que
`GET /api/users/me/activities` (`List<UserActivityDto>`), en filtrant sur la
visibilité publique le cas échéant. Aligner le contrat sur l'existant
`/users/{id}/programs`, qui fonctionne déjà.

## Critères d'acceptation

1. `GET /api/users/{id}` → 200 + `UserPublicDto` pour un id existant (tester
   explicitement `00000000-0000-0000-0000-000000000002`).
2. `GET /api/users/{id}` → 404 **propre** pour un id inexistant (jamais 500).
3. `GET /api/users/{id}/activities` → 200 + `List<UserActivityDto>`.
4. Ajouter / mettre à jour les tests d'intégration couvrant ces deux routes.
5. Ne jamais renvoyer 500 pour la projection publique d'un utilisateur
   existant : les agrégats manquants doivent valoir `0`/`null`, pas lever une
   exception.

---

### Notes de portée (côté investigation frontend)

- Le **500 n'est confirmé que pour l'utilisateur …002** (seul profil public que
  l'app a tenté de charger). Non vérifié sur d'autres id : peut être universel
  (tous les `GET /users/{id}`) ou spécifique aux données de ce seed. La vraie
  exception (critère 1) tranchera.
- Le bug 2 a un contournement frontend possible : la réponse de
  `GET /users/{id}` contient déjà un champ `activities` embarqué dans
  `UserPublic`. Mais tant que le **BUG 1 (500)** n'est pas corrigé, la page ne
  charge pas du tout — le backend reste donc bloquant.
