# Le lien `/p/` d'un programme, pour qui le voit et pas seulement pour son auteur

**Date :** 2026-09-14
**Module :** `partage`

> **Ce que l'app a déjà fait, sans vous** : la fiche d'activité ne colle plus
> `meetdo://activities/{clé}` quand un lien `https` existe. Elle demande
> `GET /api/programs/{id}/share-link` pour le programme de l'activité qui a la séance la plus
> proche, et colle la page `/p/{jeton}`. Le jour où votre réponse change, l'app en profite
> **sans nouvelle version**.
>
> **Ce qui vous appartient** : aujourd'hui ce lien n'est rendu qu'à l'organisateur. Tous les
> autres reçoivent `404`, et leur message part en `meetdo://` — du texte mort dans WhatsApp,
> iMessage ou un SMS.

---

## 1. Ce qu'on a vu

Signalé le 14/09/2026 : le partage depuis la fiche envoie `meetdo://activities/…`, et le lien
ne se touche pas. Aucune messagerie ne rend cliquable un schéma propriétaire.

Relevé du 14/09/2026 à 18 h 01 :

| Source | Constat |
|---|---|
| `/v3/api-docs` | aucune route publique d'activité ; `/public/slots/{token}` et `/public/programs/{token}` seulement |
| AASA de `lien.meetdo.fun` | **200**, motifs `/s/*`, `/p/*`, `/v/*` |
| `PublicProgramService.shareLink` (clone à `c6568d8`) | `404` si l'appelant n'est pas l'organisateur |
| `PublicSlotService.shareLink` | ouvert à **tous les participants** du créneau |

Le partage d'un **programme** par quelqu'un qui n'en est pas l'auteur tombe dans le même trou :
`program_detail_page.dart` retombe lui aussi sur `meetdo://programs/{id}`.

## 2. Ce qu'on demande

`GET /api/programs/{programId}/share-link` rend **200** à tout utilisateur authentifié, dès que
`publiclyVisible(program)` est vrai.

- **Organisateur** : rien ne change (jeton créé à la première demande, `404` jamais `403`).
- **Autre utilisateur** : `200` si `publiclyVisible(program)`, sinon le même `404` qu'aujourd'hui.
  Le jeton peut être créé à cette demande, comme pour un créneau.

### Pourquoi ça ne retire rien à l'auteur

La Javadoc dit : « c'est son auteur qui décide s'il existe sur le web ouvert ». Il le décide
déjà, par `isPubliclyShareable` (défaut `TRUE`, V78), que `publiclyVisible` lit. Un auteur qui a
fermé le partage garde son `404`. Créer le jeton ne publie rien de plus : la page `/p/{jeton}` ne
s'affiche qu'aux mêmes conditions, et **le jeton n'est jamais régénéré**, donc une fermeture
suivie d'une réouverture ne casse aucun lien.

La dissymétrie avec le créneau (participants) n'a plus de raison d'être : un programme
`isPublic`, `ACTIVE`, sur la carte, est déjà lisible par tout utilisateur de l'app.

### L'autre voie, qu'on ne demande pas

Une page `/a/{jeton}` par activité. Plus juste en théorie, mais c'est une page, un jeton, un motif
AASA et un filtre Android de plus, pour un contenu que la page du programme le plus proche porte
déjà. À rediscuter seulement si la première voie ne suffit pas.

## 3. Ce qui nous dira que c'est livré

1. `/v3/api-docs` : la description de `share-link` ne dit plus « organisateur ».
2. Avec `lena.mueller@web.de`, `GET /api/programs/{id}/share-link` sur un programme public d'un
   **autre** organisateur → `200`, `token` non nul, `publiclyShareable: true`.
3. Le même appel sur un programme dont l'auteur a fermé le partage → `404`.
