# `/slots/bounds` est câblé, et il attend un déploiement

**Date :** 2026-09-04
**Fait suite à :** `REPONSE_BACKEND_2026-09-04.md`

> **La route n'est pas en ligne.** Écrite et testée chez vous, absente du binaire
> déployé : `/v3/api-docs` liste 206 routes sans elle, et l'appel tombe dans le
> joker `GET /slots/{scheduleId}` — §1. C'est le seul point qui vous concerne.
>
> **Tout le reste est écrit, testé, et derrière un drapeau éteint**
> (`FeatureFlags.slotsByBounds`). Un seul mot à changer le jour du déploiement,
> plus une vérification HTTP — §2.
>
> **Votre choix de la forme (b) nous va, et votre argument (a) tient :** notre
> carte fait bien deux appels séparés, un par onglet. Vous aviez raison de ne
> pas les fondre — §3.
>
> **Votre §4.1 nous a fait ajouter une garantie que nous n'avions pas demandée**,
> et nous nous appuyons dessus : tout élément rendu porte des coordonnées — §3.

---

## 1. La route n'est pas déployée

Relevé le 04/09, compte de test, production :

```
GET  /api/slots/bounds?north=55.1&south=47.2&east=15.1&west=5.8
→ 400 INVALID_PARAMETER
  « Paramètre 'scheduleId' invalide : valeur 'bounds' n'est pas du type attendu »

POST /api/slots/bounds → 405
```

Le `400` est signé : la requête tombe dans le joker `GET /slots/{scheduleId}`,
qui essaie de lire « bounds » comme un UUID. `/slots/in-bounds` rend exactement
la même erreur, ce qui confirme le diagnostic plutôt qu'une faute de frappe. Et
`/v3/api-docs` porte 206 routes, sans `/api/slots/bounds`.

Rien à corriger de votre côté sinon un déploiement — nous le signalons parce que
vos douze tests d'intégration ne peuvent pas le voir, et parce que nous nous
sommes déjà fait prendre : la spécification décrit le binaire en ligne, jamais
votre `master`.

---

## 2. Ce qui est écrit chez nous

Derrière `FeatureFlags.slotsByBounds`, éteint, et documenté pour ce qui le
lève — **une seule chose, et elle se vérifie en une commande** : que la route
réponde.

| Pièce | État |
|---|---|
| `ApiConstants.slotsBounds` | écrit |
| `SlotBoundsFilters` — le rectangle et les filtres du fil | écrit |
| `SlotBoundsPage` — `slots`, `truncated`, `totalInBounds` | écrit |
| `SlotRepository.getInBounds` | écrit, **sans écrêtage de `limit`** |
| `mergeOwnSlotsInBounds` — la fusion de ses propres créneaux, par rectangle | écrit |
| `mapSlotsInBoundsProvider` — deux sources, comme le fil | écrit |
| Le bandeau de troncature en mode Créneaux | écrit |

**Douze tests**, dont quatre montent la carte entière sur le rectangle grâce à
un paramètre de test (`MapPage.slotsByBounds`) : un chemin gardé par un drapeau
est un chemin que personne n'exerce, et qu'on allume un jour sans l'avoir vu
tourner.

**Un défaut trouvé par ces tests, et il valait le détour.** Notre bandeau
« cherchés dans un rayon de 50 km » est conditionné à l'écart entre la zone
demandée et la zone interrogée — vous l'aviez noté, il devait s'éteindre seul.
Il ne s'éteignait pas : le rayon demandé restait mémorisé avant l'aiguillage, et
la phrase s'affichait donc **au-dessus d'une carte qui venait de couvrir un pays
entier**. Corrigé : le rayon n'est retenu que sur le chemin du disque.

---

## 3. Vos trois arbitrages

**a. La forme (b) nous va, et votre argument (a) est juste.** Vous demandiez si
notre carte fait un seul appel pour les deux onglets : **non**, elle en fait
deux, un par onglet, et ils ne partent pas ensemble. Fondre les créneaux dans
`/map/bounds` aurait donc bien fait payer à chaque onglet le calcul de l'autre.
La question est close, et vous avez tranché dans le bon sens.

**b. `truncated` sur `/map/bounds` : vous avez évité un défaut que nous
n'avions pas vu.** Notre bandeau de troncature de l'onglet Activités lit ce
champ aujourd'hui. Une quatrième couche l'aurait fait s'allumer pour des
créneaux qu'il n'affiche pas — un changement de sens invisible à la
compilation, sur un client déployé. Nous n'y avions pas pensé.

**c. Votre §4.1 est une garantie de plus, et nous nous appuyons dessus.** « Tout
élément rendu par cette route porte des `lat`/`lng` non nuls » est écrit dans la
documentation de `SlotBoundsPage.slots`, avec votre raison — sur un rectangle,
répondre « ce créneau est dedans » le situe déjà, même sans coordonnées. Notre
`hasPreciseLocation` reste posé par-dessus, comme ceinture, exactement comme
vous le conseillez.

**Et votre §4.2 nous a coûté une fusion.** Vos créneaux exclus, c'est la règle
du fil — mais appliquée en SQL, elle traverse aussi le rectangle. Sans
`mergeOwnSlotsInBounds`, changer de géométrie aurait fait disparaître de sa
propre carte tout ce qu'on organise : le défaut d'août, réintroduit par la porte
de derrière. Nous ne demandons pas la couche « ce que j'organise » que vous
proposez : notre fusion locale suffit, et elle a l'avantage de ne rien coûter à
votre route.

---

## 4. Une chose à ne pas perdre de vue

`from`/`to` valent « maintenant → +7 jours » par défaut, et nous ne les envoyons
que lorsque l'utilisateur a choisi une fenêtre. Dézoomer sur un pays montrera
donc **la semaine**, pas tout ce qui existe. C'est cohérent avec le fil et nous
ne demandons rien — mais c'est la prochaine question qu'on nous posera, et nous
préférons l'avoir écrite ici avant qu'elle ne se pose.

---

## 5. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | `GET /api/slots/bounds` | **écrit chez vous, pas déployé** — le seul point ouvert |
| 2 | Le câblage client complet | écrit et testé, derrière un drapeau éteint |
| 3 | Votre forme (b) contre notre (a) | **vous aviez raison** — notre carte fait deux appels |
| 4 | `lat`/`lng` garantis non nuls | pris, et documenté comme tel |
| 5 | Nos propres créneaux exclus | fusion locale par rectangle — rien à faire chez vous |
| 6 | `limit` 200, `400` au-delà | respecté, **aucun écrêtage côté client** |
