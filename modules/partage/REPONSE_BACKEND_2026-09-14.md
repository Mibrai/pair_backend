# Réponse du 14/09 — le lien `/p/` d'un programme est rendu à tout compte qui peut le voir

**Date :** 2026-09-14
**Module :** `partage`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14.md`

> **Livré dans la forme que vous proposiez**, avec **une condition de plus** : un compte bloqué avec
> l'organisateur, dans un sens ou dans l'autre, reçoit `404`.
>
> Votre relevé est exact : `PublicProgramService.shareLink` rendait `404` à tout autre que
> l'organisateur, là où le lien d'un créneau est ouvert à tous ses participants.

---

## 1. La règle

`GET /api/programs/{programId}/share-link` :

| Appelant | Réponse |
|---|---|
| **L'organisateur** | `200`, dans tous les cas, partage fermé compris (`publiclyShareable: false`) : c'est son écran de réglage qui le lit. Inchangé. |
| **Tout autre compte connecté**, programme publiquement visible | **`200`**, `token` créé à la première demande s'il n'existe pas |
| Autre compte, programme **non** visible | `404`, comme aujourd'hui |
| Autre compte **bloqué avec l'organisateur**, dans un sens ou dans l'autre | `404` |
| Programme inexistant | `404` |

**« Publiquement visible »** est la règle existante de `publiclyVisible`, que la page `/p/{jeton}`
applique déjà :

- partage ouvert par l'auteur (`isPubliclyShareable`) ;
- programme public, `ACTIVE`, non archivé ;
- activité montrée sur la carte ;
- organisateur actif.

Nous n'y avons rien changé.

**Le jeton est le même pour tous** : celui qu'un lecteur fait créer est celui que l'organisateur
reçoit ensuite, et inversement. Il n'est jamais régénéré.

## 2. Pourquoi le blocage en plus

`publiclyVisible` ne regarde pas qui demande : elle a été écrite pour une page lue sans compte.
Ouvrir la route authentifiée à tous sans rien ajouter aurait fabriqué le lien du programme d'un
organisateur pour une personne qu'il a bloquée. C'est la seule lecture de ses contenus qui lui
serait restée, alors que son profil, ses avis et la fiche de ses créneaux lui rendent `404`
(P-BS-14, P-BL-05).

La page `/p/{jeton}` reste publique et sans compte, comme avant : le blocage ne peut rien contre un
lien déjà collé ailleurs, et ce n'est pas l'objet.

## 3. Au contrat

La description de `share-link` ne parle plus d'organisateur : elle énumère les conditions
ci-dessus, le `404` du blocage, et le fait que l'auteur l'obtient toujours.

## 4. Vérification

**Tests** (`PublicProgramPageIntegrationTest`) :

- un autre compte obtient `200`, un `token` non vide et `shareable: true`, et c'est le même jeton
  que celui de l'organisateur ;
- partage fermé par l'auteur : `404` pour un autre compte, `200` avec `shareable: false` pour
  l'auteur ;
- compte bloqué par l'organisateur : `404`.

Le test qui vérifiait « réservé à l'organisateur » est remplacé par le premier.

**Votre protocole du §3 tient** après déploiement, à un nom de champ près : la réponse porte
**`shareable`**, et non `publiclyShareable` (`PublicShareLinkDto`, inchangé). Vérifiez donc la
description sans « organisateur », puis avec `lena.mueller@web.de` : `200`, `token` non nul et
`shareable: true` sur le programme public d'un autre organisateur, et `404` sur un programme au
partage fermé. Ce lot part dans le même déploiement que les réponses `badges`
(BIS) et `inscription` du 14/09.
