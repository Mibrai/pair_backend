# Un programme partagé n'est pas cliquable — 20 août 2026

> **Demande.** Le partage public existe pour les **créneaux** et pour eux seuls.
> Un programme partagé arrive chez le destinataire en texte mort. Nous demandons
> la même chose pour les programmes, calquée sur ce qui existe déjà.

---

## Le défaut, vu par celui qui reçoit

Partager un programme depuis l'app produit un message dont le lien est :

```
meetdo://programs/{id}
```

**Aucune messagerie ne rend un schéma propriétaire cliquable.** WhatsApp,
iMessage, Signal, Gmail : tous ne linkifient que `http(s)://`. Le destinataire
reçoit une chaîne à recopier à la main — et s'il n'a pas l'app, la recopier ne
donne rien non plus.

C'est exactement le défaut que `/s/{token}` a réglé pour les créneaux le 20/08.
Les programmes sont restés derrière.

## Ce que nous avons vérifié avant de demander

Sur `/v3/api-docs`, **172 routes**. Le partage public n'y existe que pour les
créneaux :

```
GET   /api/slots/{scheduleId}/share-link      → PublicShareLinkDto
PATCH /api/slots/{scheduleId}/shareable       → PublicShareLinkDto
GET   /public/slots/{token}                   → PublicSlotView
GET   /public/slots/{token}/calendar.ics
GET   /public/slots/{token}/cover.png
GET   /public/slots/{token}/image
GET   /s/{token}/calendar.ics
```

Aucune route de programme ne porte `public`, `share` ni `token`. Et sur le
domaine public, les quatre formes plausibles rendent `401` — le code que rend
une route **absente**, là où un jeton inconnu sur une route existante rend `404`
avec la page « Créneau indisponible » :

```
/p/zzz                  401        /public/programs/zzz    401
/programs/zzz           401        /public/program/zzz     401
```

## Ce que nous demandons — le même contrat, transposé

Rien à inventer : le contrat des créneaux marche, il suffit de le décliner.

| Créneau (existe) | Programme (demandé) |
|---|---|
| `GET /api/slots/{id}/share-link` → `PublicShareLinkDto` | `GET /api/programs/{id}/share-link` → `PublicShareLinkDto` |
| `PATCH /api/slots/{id}/shareable` (`SetShareableRequest`) | `PATCH /api/programs/{id}/shareable` |
| `GET /public/slots/{token}` → `PublicSlotView` | `GET /public/programs/{token}` → `PublicProgramView` |
| `GET /s/{token}` (page HTML + OpenGraph) | `GET /p/{token}` (idem) |
| `GET /public/slots/{token}/cover.png` | `GET /public/programs/{token}/cover.png` |

`PublicShareLinkDto` porte déjà `token`, `shortUrl`, `pageUrl`, `shareable` :
nous lirons `pageUrl` tel quel, sans le composer nous-mêmes.

Pour `PublicProgramView`, ce qu'une page publique de programme doit montrer —
et **rien de plus**, la page étant sans compte : `title`, `description`,
`activityName`, `categoryName`, `categoryColorRamp`, `locationType`, `city`,
`placeName`, `nextSessionAt`, `sessionCount`, `enrolledCount`,
`maxParticipants`, `organizerGivenName`, `organizerVerified`, `hasImage`.

Les mêmes garde-fous que pour les créneaux : **jeton opaque, jamais l'UUID
interne** — une URL bâtie sur l'identifiant se laisse énumérer —, et le partage
révocable par `shareable`.

## ⚠️ Le fichier d'association est à mettre à jour en même temps

`apple-app-site-association` ne déclare aujourd'hui que deux motifs :

```json
"components": [
  { "/": "/s/*" },
  { "/": "/public/slots/*" }
]
```

Un lien `/p/{token}` **n'ouvrirait pas l'app** tant que le motif n'y est pas :
iOS ignore silencieusement ce que l'AASA ne déclare pas. À ajouter en même
temps que la route, sinon la livraison paraîtra faite et ne le sera qu'à
moitié. Idem pour `assetlinks.json` le jour où l'empreinte Android existera.

Rappel du cache : Apple sert l'association depuis son CDN
(`app-site-association.cdn-apple.com`) et les appareils la gardent — une mise à
jour n'est pas instantanée pour les installations existantes.

## Ce que l'app fera dès la livraison

Le code de partage est déjà en deux temps : `shareableSlotLink()` rend l'URL
publique si elle existe, le `meetdo://` sinon. Nous ferons le même pour les
programmes, donc aucun texte de partage ne changera — seul le lien deviendra
cliquable, et la page montrera quelque chose à qui n'a pas encore l'app.

## Un défaut mineur au passage

`HEAD` sur `/.well-known/apple-app-site-association` rend `401` alors que `GET`
rend `200` : le filtre de sécurité ne couvre pas le verbe. Apple fait un `GET`,
donc la vérification passe et rien n'est cassé — mais tout diagnostic mené en
`curl -I` conclura à tort que le fichier est protégé, et fera chercher au mauvais
endroit.
