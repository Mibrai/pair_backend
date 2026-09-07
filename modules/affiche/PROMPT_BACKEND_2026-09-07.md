# Module « affiche » — six demandes, dont deux bloquantes

**Écrit le 2026-09-07.**
Plan : [`PLAN_IMPLEMENTATION_2026-09-07.md`](PLAN_IMPLEMENTATION_2026-09-07.md) ·
Maquette : [`template/meetdo-affiche.html`](template/meetdo-affiche.html)

---

## Ce que le client sait déjà faire seul

Une « affiche » est un objet visuel que l'app compose après une participation validée, que la
personne découvre en privé, puis publie — ou non.

**Le module n'ajoute aucun appel réseau pour composer.** Il est une lecture pure de
`GET /recaps/mine`, que l'écran « Mes moments » charge déjà : à partir de mes cartes-souvenirs,
il calcule une transition (« cette séance-ci est la première en escalade »), choisit un motif
parmi treize, et compose un visuel avec les jetons du design system. Aucun média n'est envoyé,
aucun état n'est stocké.

Sortent donc **sans aucune livraison de votre part** : la composition, la découverte privée, la
galerie sur « Mes moments », l'export de l'affiche en image vers la feuille de partage du
téléphone, et l'écran de réglage d'audience.

**Six choses manquent.** Les deux premières bloquent la publication ; les autres l'améliorent.

---

## Demande 1 — Publier une affiche quand je ne suis pas l'hôte · **bloquante**

### Ce qui est demandé

Un objet par personne et par créneau :

```
PUT    /affiches/{scheduleId}     { motif, audience }   → publie (idempotent)
DELETE /affiches/{scheduleId}                           → dépublie
GET    /users/{id}/affiches                             → celles que j'ai le droit de voir
```

L'objet rendu : `{ scheduleId, motif, publishedAt, audience }`. Le **texte et le visuel restent
côté client** — nous n'avons pas besoin que vous stockiez la phrase, seulement le fait qu'une
affiche existe, laquelle, et pour qui.

### Pourquoi le client ne peut pas s'en passer

Vérifié dans le contrat : le seul verbe de publication livré est
`PATCH /slots/{id}/recap/visibility`, et il est **réservé à l'hôte** (403 sinon). Notre écran
« Mes moments » le reflète déjà : `splitMyMoments` restreint la liste « à publier » aux cartes
dont `host.id == myId`.

Or le déclencheur d'une affiche est `Attendance.was_present` — **la présence, pas
l'organisation**. Le simple participant, qui est la majorité des cas, n'a aujourd'hui aucun
endroit où publier quoi que ce soit.

Les réglages privés ne sont pas un repli : `/users/me/preferences/{key}` est lisible **par son
seul propriétaire**, ce qui est précisément la propriété pour laquelle nous l'avions choisi
ailleurs.

### Si la réponse est autre

Les lots 1 à 4 sortent quand même : l'affiche existe pour soi et pour qui on la donne
directement, par image. Le drapeau `affichePublication` reste éteint. Le produit livré est
cohérent, seulement plus petit.

---

## Demande 2 — L'audience appliquée côté serveur · **bloquante**

### Ce qui est demandé

`audience ∈ { NOBODY, SUBSCRIBERS, EVERYONE }`, **défaut `NOBODY`**, et surtout : **appliquée
dans `GET /users/{id}/affiches`**. Un lecteur qui n'a pas le droit reçoit une liste vide, pas une
liste filtrée côté client.

### Pourquoi le client ne peut pas s'en passer

Nous ne pouvons pas filtrer sur « les abonnés de quelqu'un d'autre » : `GET /users/{id}/subscribers`
n'existe pas, et son absence est **délibérée** — savoir qui suit un tiers n'a aucun usage produit
et créerait une exposition non voulue. Un filtre côté client supposerait de vous faire envoyer, à
chaque lecteur, la liste des abonnés de la personne regardée : exactement ce que ce refus
protégeait.

⚠️ **Une précision qui vient d'un défaut déjà corrigé chez nous.** Le 06/09, nous avons rangé
l'audience du statut live dans `/users/me/preferences/{key}` en écrivant noir sur blanc que *« le
serveur range cette valeur, il ne l'interprète pas »*. C'est acceptable pour un réglage qui
n'engage que l'affichage local. Ça ne l'est pas ici : une affiche publiée est vue par d'autres, et
un réglage de confidentialité que rien n'applique est une promesse non tenue. Nous préférons ne
pas livrer la publication plutôt que de l'afficher sans qu'elle soit opposable.

### Si la réponse est autre

Le réglage reste dans `/users/me/preferences/affiche.audience` comme valeur **déclarative sans
application**, et le module refuse de publier autre chose que `NOBODY` — c'est-à-dire que la
publication reste éteinte. Nous n'affichons jamais un réglage de confidentialité que rien
n'applique.

---

## Demande 3 — La fin du créneau, ou la fenêtre de mise en avant

### Ce qui est demandé

Soit `slotEndedAt` sur `SlotRecapDto`, soit un `featuredUntil` explicite sur l'affiche.

### Pourquoi

Une affiche reste « en avant » sept jours sur le profil, puis descend en galerie. Nous voulions
aligner cette fenêtre sur `SlotRecap.canContribute`, déjà porté par vous. Deux obstacles :

1. `canContribute` est documenté « la fenêtre est-elle encore ouverte **pour moi** » — il vaut
   `false` pour quiconque n'était pas présent. Une mise en avant pilotée par lui **disparaîtrait
   du profil vu par les autres**, c'est-à-dire précisément là où elle sert.
2. `SlotRecap` ne porte que `slotStartedAt`. Vous comptez depuis la **fin** du créneau ; le client
   ne connaît pas la fin.

### Si la réponse est autre

Le client calcule `slotStartedAt + 7 j` et l'écrit en commentaire. L'écart vaut la durée d'une
séance, et il joue dans le sens qui **raccourcit** la fenêtre : la direction sûre.

---

## Demande 4 — Une question, pas une demande : que rend `/recaps/mine` ?

### La question

`GET /recaps/mine` rend-il **une carte par présence confirmée**, ou **seulement les cartes portant
au moins une contribution** (ambiance, photo ou mot de l'hôte) ?

### Pourquoi ça décide de notre architecture

Tous nos motifs se déclenchent sur une transition calculée à partir de mon histoire. Si la route
ne rend que les cartes ayant du contenu, alors « première fois en padel » se **redéclenchera** le
jour où quelqu'un ajoutera enfin une ambiance à une séance plus ancienne : l'histoire aura changé
**dans le passé**, et une affiche déjà vue reviendrait.

### Si la réponse est « seulement celles qui ont du contenu »

Le client ajoute une monotonie : un motif ne se déclenche jamais deux fois pour la même clé,
mémorisée dans `/users/me/preferences/affiche.motifs`. Coût : une lecture de plus au démarrage, et
le module perd sa propriété « zéro état » — d'où l'intérêt de poser la question **avant** d'écrire
le premier lot.

---

## Demande 5 — Un type de notification `AFFICHE_READY`

### Ce qui est demandé

Un type de notification poussée, **au plus un par créneau présent**, avec
`data.type = "AFFICHE_READY"` et `data.scheduleId`. Émis quand la carte-souvenir de la séance
devient contributive, c'est-à-dire au moment où l'affiche devient calculable.

### Pourquoi ce n'est pas bloquant, et pourquoi on le demande quand même

Rien ne casse sans lui : l'affiche se découvre dans le fil d'accueil et dans la bannière de
présence. Mais la **découverte est le produit** — c'est le ressort entier de la fonctionnalité —
et le fil ne touche que les gens qui rouvrent l'app d'eux-mêmes.

⚠️ Rappel du 03/09 : `data.type` doit être **réellement envoyé** dans le payload. Son absence
avait rendu tous les taps de notification inertes.

### Si la réponse est autre

Le type est **quand même déclaré** côté client et routé, selon la doctrine du fichier illustrée
par `waitlistPromoted` : un client publié aujourd'hui doit comprendre ce que le serveur enverra
demain.

---

## Demande 6 — `GET /affiches/updates` pour l'anneau sur l'avatar

### Ce qui est demandé

```
GET /affiches/updates?since={iso8601}
→ [ { userId, latestPublishedAt } ]
```

La liste, **déjà filtrée par l'audience de chacun**, des personnes ayant publié une affiche que
*j'ai le droit de voir*. Un identifiant et une date, rien d'autre — ni motif, ni texte, ni image.

### Pourquoi

Quand quelqu'un publie, sa photo porte un anneau dans mes listes jusqu'à ce que j'ouvre l'affiche
— le mécanisme du statut WhatsApp.

Deux raisons qui rendent le calcul client impossible :

1. **Confidentialité.** Savoir qu'une personne a publié dépend de **son** réglage d'audience, que
   seul vous connaissez. Un anneau posé sans ce filtre révélerait l'existence d'une affiche à
   quelqu'un qui n'a pas le droit de la voir : le signal fuiterait ce que l'affiche protège.
2. **Coût.** Baguer les avatars d'une liste de cinquante abonnements ne peut pas coûter cinquante
   requêtes.

Un appel au démarrage et au retour d'arrière-plan suffit. L'état « vue » reste chez nous, sur
l'appareil — nous ne vous demandons **pas** de tenir un accusé de lecture par affiche et par
lecteur.

### Si la réponse est autre

Pas d'anneau. Le module reste entier : la découverte, la galerie, le partage d'image et la
publication n'en dépendent pas.

---

## Récapitulatif

| # | Demande | Bloque quoi | Sans elle |
|---|---|---|---|
| 1 | `PUT/DELETE /affiches/{scheduleId}`, `GET /users/{id}/affiches` | la publication entière | l'affiche existe pour soi et par image |
| 2 | `audience` appliquée côté serveur | la publication entière | on ne publie que `NOBODY` |
| 3 | `slotEndedAt` ou `featuredUntil` | rien | repli sur `slotStartedAt + 7 j` |
| 4 | *(question)* ce que rend `/recaps/mine` | rien | une monotonie côté client, et un état à tenir |
| 5 | type `AFFICHE_READY` | rien | découverte au fil et à la bannière seulement |
| 6 | `GET /affiches/updates?since=` | l'anneau sur l'avatar | pas d'anneau |

**Priorité : 4 d'abord** — c'est une question, elle ne coûte rien à répondre et elle décide de
notre premier lot. Puis 1 et 2 ensemble, qui n'ont aucun sens l'une sans l'autre. Puis 6, 5, 3.
