# Demande backend — les cartes-souvenirs sont écrites mais presque jamais lisibles

> Les huit routes `/slots/{id}/recap/**` et `/recaps/**` livrées le 2026-08-14
> fonctionnent. L'app écrit dedans depuis, et les mots d'ambiance comme les
> photos arrivent bien chez vous.
>
> Le problème est en **lecture**. Il n'existe aujourd'hui que deux façons de
> retrouver une carte : par son créneau (`GET /slots/{id}/recap`) ou par un feed
> géolocalisé plafonné à 50 km (`GET /recaps/feed`). Or les trois endroits où la
> carte a de la valeur — la page d'un programme, la page d'une activité, le
> profil d'un organisateur — n'ont ni l'un ni l'autre à leur disposition.
>
> Résultat concret : un utilisateur qui refuse la géolocalisation, ou qui
> consulte un programme à plus de 50 km, ne voit **jamais** aucune carte, où
> qu'il aille dans l'app. Ce n'est pas un défaut d'affichage — il n'y a rien à
> afficher.
>
> Relevé sur `/v3/api-docs` et contre la production le 2026-08-15.

---

## 1. Ce qui existe, et pourquoi ça ne suffit pas

| Route livrée | Ce qu'elle sert | Ce qu'elle ne peut pas servir |
|---|---|---|
| `GET /slots/{scheduleId}/recap` | le détail d'un créneau | rien d'agrégé |
| `GET /recaps/feed?lat&lng&radiusMeters` | « autour de moi » | tout ce qui est hors rayon, et **tout** sans position |
| `GET /recaps/mine` | mes propres moments | les cartes des autres |

Les trois emplacements de lecture de l'app sont aujourd'hui obligés de filtrer
`/recaps/feed` côté client. C'est ce filtrage qui les vide :

- **page programme** — on filtre le feed sur les `schedules[].id` du programme.
  Programme à 60 km ⇒ section vide. Géolocalisation refusée ⇒ section vide.
- **profil d'un organisateur** — on filtre le feed sur `host.id`. Même effet.
- **page activité** — impossible : voir §3.

## 2. `GET /programs/{programId}/recaps` — la demande principale

### Pourquoi le client ne peut pas s'en passer

`SlotRecapDto` ne porte **pas** de `programId`. C'était demandé dans
`PROMPT_BACKEND_MEETDO_RECAP_2026-08.md` §4 ; le DTO livré s'en tient à
`programTitle`, qui est un texte d'affichage et n'identifie rien — deux
« Yoga du soir » de deux auteurs différents se mélangeraient sans qu'aucune
erreur ne se voie.

Le repli actuel (filtrer le feed sur les identifiants de créneaux que la page a
déjà) est exact mais reste prisonnier du rayon et de la position.

### Contrat proposé

```
GET /api/programs/{programId}/recaps
```

Réponse : `SlotRecapDto[]` — exactement le DTO existant, aucun champ nouveau,
trié par `slotStartedAt` décroissant.

Règles de visibilité, identiques à celles déjà en vigueur :

- un **visiteur** reçoit les cartes `PUBLIC` du programme ;
- un **participant** reçoit en plus les cartes des créneaux auxquels il a
  assisté, quelle que soit leur visibilité (c'est ce que `/recaps/mine` lui
  rend déjà) ;
- l'**auteur du programme** reçoit toutes les cartes de son programme.

Aucune géolocalisation, aucun rayon.

### Question fermée, à trancher avant toute chose

**`GET /programs/{id}` renvoie-t-il les créneaux déjà passés dans
`schedules[]`, ou seulement ceux à venir ?**

Sur les quatre programmes du compte de test, tous les créneaux rendus sont
futurs — nous ne pouvons pas conclure. La réponse décide de tout :

- **oui** ⇒ nous livrons la page programme dès maintenant, avec un repli qui
  interroge `GET /slots/{id}/recap` séance passée par séance passée. La route
  ci-dessus reste souhaitable (elle remplace N requêtes par une), mais elle
  n'est plus bloquante.
- **non** ⇒ la page programme est **entièrement bloquée** sur cette route.

## 3. `GET /activities/{activityId}/recaps` — aucun repli possible

C'est la seule des quatre demandes pour laquelle nous n'avons **rien** à
proposer en attendant.

`SlotRecapDto` ne porte aucune clé d'activité — seulement `activityName`, un
libellé. Filtrer dessus mélangerait les homonymes, et resterait de toute façon
borné au feed géolocalisé. Nous ne livrerons pas un écran qui se trompe une
fois sur deux : la page activité attend cette route.

```
GET /api/activities/{activityId}/recaps
```

`activityId` est la clé du catalogue, celle que l'app utilise déjà dans sa route
`/activities/{key}`. Réponse : `SlotRecapDto[]` **publiques uniquement**, triées
par `slotStartedAt` décroissant.

L'écran additionne ensuite les `topVibes` côté client pour en tirer les mots
dominants de l'activité — rien à agréger chez vous.

## 4. `GET /users/{userId}/recaps` — la même cécité, sur les profils

Le profil d'un organisateur affiche ses moments récents en filtrant le feed sur
`host.id`. Un organisateur qui exerce à Munich est donc invisible pour qui
consulte son profil depuis Lyon — alors que c'est exactement à ce moment-là
qu'on cherche à savoir ce que ses créneaux donnent.

```
GET /api/users/{userId}/recaps
```

Réponse : `SlotRecapDto[]` **publiques uniquement** (une carte privée d'un tiers
ne doit pas apparaître sur un profil, même pour un participant), triées par
`slotStartedAt` décroissant.

Priorité plus basse que §2 et §3 : le repli actuel est simplement incomplet, il
n'est pas faux.

## 5. `recapWindowClosesAt` sur `SlotRecapDto` — un champ, pas une route

`canContribute` est un booléen : il dit *si* la fenêtre de sept jours est
ouverte, jamais *jusqu'à quand*. L'écran « Mes moments » (livré aujourd'hui)
voudrait dire à un hôte « il te reste deux jours pour publier », et ne le peut
pas :

- `slotStartedAt + 7 jours` serait faux pour tout créneau long — c'est la **fin**
  du créneau qui fait courir la fenêtre, et le DTO ne porte pas `endsAt` ;
- afficher un compte à rebours approximatif sur une décision irréversible est
  pire que ne rien afficher. Nous n'affichons donc rien.

```
"recapWindowClosesAt": "2026-08-19T20:00:00Z"   // nullable
```

`null` quand la fenêtre est déjà close ou ne s'applique pas. Aucun autre
changement de contrat.

## 6. Ce que nous ne demandons pas, et ne demanderons pas

Pour éviter tout malentendu sur la direction du produit : nous ne voulons **ni
note, ni moyenne, ni classement, ni compteur de likes** sur ces routes. La carte
porte sur le moment collectif, jamais sur les individus qui y étaient, et un
champ de ce type ajouté au contrat ne serait pas parsé côté client.

Les seuls agrégats que nous affichons sont des sommes de faits déjà présents
carte par carte : des séances, des présences confirmées, et des mots d'ambiance
choisis par les participants eux-mêmes.

## 7. Ordre de livraison souhaité

| # | Demande | Effet si absent |
|---|---|---|
| 0 | **Répondre à la question du §2** (créneaux passés dans `GET /programs/{id}`) | on ne sait pas si la page programme est livrable |
| 1 | `GET /activities/{activityId}/recaps` | page activité impossible, aucun repli |
| 2 | `GET /programs/{programId}/recaps` | page programme livrable mais coûteuse (N requêtes) |
| 3 | `recapWindowClosesAt` | l'hôte ne sait pas combien de temps il lui reste |
| 4 | `GET /users/{userId}/recaps` | profils incomplets hors rayon |

## 8. Ce qui est déjà livré côté app

Pour que vous sachiez sur quoi ces routes viendront se brancher :

- contribution d'ambiance et photo depuis la confirmation de présence — **en
  production**, elles écrivent chez vous depuis le 14 août ;
- carte-souvenir complète sur le détail d'un créneau — **en production** ;
- écran « Mes moments » (`/recaps/mine`), avec la publication par l'hôte —
  **livré aujourd'hui, 15 août** ;
- page programme, page activité, profils — **en attente des routes ci-dessus**.

---

*Contrat initial : `ios/docs/PROMPT_BACKEND_MEETDO_RECAP_2026-08.md`.
Écarts déjà absorbés côté client : `VisibilityRequest` est un booléen et non
l'enum, et le DTO ne porte pas de `programId`.*
