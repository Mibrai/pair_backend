# Ce qu'il manque au serveur pour que « déjà passé » se voie partout

*Écrit le 05/09/2026, après mesure sur la production avec le compte de test.*

L'application vient de livrer une règle uniforme : **une activité, un programme,
une séance ou un créneau déjà passés s'affichent grisés, portent un tampon
« Terminé », et ne se rejoignent plus** — le serveur refusait déjà, l'app cesse
enfin de le proposer. Un interrupteur « Afficher ce qui est terminé », éteint par
défaut, les fait apparaître là où ils étaient simplement absents.

Cette règle tient **entièrement côté client sur trois écrans sur quatre**. Deux
routes l'empêchent d'aller au bout, et ce document ne demande que ça.

---

## Demande 1 — `POST /api/search` : de quoi reconnaître un programme terminé

### Ce qui se passe aujourd'hui

`POST /search` **rend déjà** les programmes dont toutes les séances sont passées.
Vérifié autour de Paris, où les 25 programmes du jeu de démonstration sont tous
expirés :

```bash
curl -s -X POST "$API/search" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' \
  -d '{"query":"photo","lat":48.8566,"lng":2.3522,"radiusMeters":50000}'
# → 1 résultat : « Balade photo urbaine », ACTIVE, startsAt: null, endsAt: null
# Le même programme, par GET /programs : nextSessionAt: null, 1 schedule
#                                        → expiré au sens du lot 5.
```

**Il n'y a donc rien à débloquer côté visibilité** : ils sont là, et c'est très
bien — on veut pouvoir retrouver un programme terminé. Ce qui manque, c'est de
quoi le **distinguer** d'un programme vivant.

`SearchResultDto` porte `startsAt` et `endsAt`, mais ils valent `null` sur un
résultat de type `program` : ils ne parlent que des créneaux. Aucun autre champ
ne dit le temps.

### Ce qui est demandé

Deux champs sur `SearchResultDto`, pour les résultats de type `program` :

| Champ | Type | Sens |
|---|---|---|
| `nextSessionAt` | `string?` (ISO-8601 UTC) | Le même que `ProgramDto.nextSessionAt`, récurrences développées. |
| `isExpired` | `boolean` | Le même verdict que `BrowsedActivityDto.isExpired`, transposé sur la maille programme : **daté** (au moins un créneau) **et** aucune occurrence future. |

La doctrine du lot 5 s'applique mot pour mot, et il est important qu'elle ne
change pas d'une route à l'autre :

- *« expiré uniquement si l'entrée est datée — au moins un créneau — et qu'aucune
  occurrence future n'existe »* ;
- *« une entrée sans aucun créneau n'est jamais expirée »* (un programme qu'on
  vient de créer et dont l'horaire n'est pas encore posé reste **vivant** : le
  griser punirait son auteur pour une étape qu'il n'a pas faite) ;
- *« expiré ⇒ `nextSessionAt: null`, sans exception »*.

### Pourquoi le client ne peut pas s'en passer

Trois contournements ont été essayés et mesurés, aucun ne tient :

1. **Lire `startsAt`/`endsAt`** — `null` sur les résultats `program`.
2. **Croiser avec `GET /programs?lat&lng&radius_km`** — cette route ancre les
   programmes près d'un créneau localisé (ou de l'organisateur) ; la recherche,
   elle, va beaucoup plus large. Mesuré autour de Berlin : **5 programmes rendus
   par `/programs` contre 8 trouvés par `/search`** sur les mêmes requêtes. Le
   croisement laisserait la majorité des résultats sans verdict.
3. **Un `GET /programs/{id}` par résultat** — 20 requêtes par page, sur une route
   authentifiée dont le plancher est d'environ 750 ms.

### Ce que le client fait en attendant

Il lit déjà les deux champs, avec tolérance, et distingue **trois** états :
`unknown` / `upcoming` / `past` (`lib/features/discovery/domain/result_timeliness.dart`).
`unknown` — le cas de toutes les réponses actuelles — ne déclenche **rien** :
aucun tampon, aucun grisage, et surtout aucun retrait de la liste. Masquer sur une
supposition ferait disparaître des programmes vivants d'une recherche.

Conséquence directe : **l'interrupteur « Afficher ce qui est terminé » n'apparaît
pas sur l'écran de recherche** tant qu'aucun résultat ne porte le champ — un
réglage sans effet est pire qu'un réglage absent. Il se lèvera de lui-même le jour
du déploiement, sans qu'une ligne d'app change et sans nouvelle version à publier.

### Bonus, si c'est peu coûteux

Un paramètre `includeExpired` (booléen, défaut `false`) sur `SearchRequest`, aux
mêmes sémantiques que celui de `/activities/browse`. Ce n'est **pas** bloquant :
le filtrage client suffit une fois le verdict connu, et il porte sur une page de
20 résultats. Cela deviendrait utile le jour où l'on veut que `totalCount` compte
juste.

---

## Demande 2 — `GET /api/slots/bounds` : une fenêtre qui accepte le passé

### Ce qui se passe aujourd'hui

`from` est **ramené à maintenant**, quelle que soit la valeur envoyée. Mesuré
sur toute l'Europe (`north=71&south=35&east=40&west=-25`) :

```bash
# sans from                              → 33 créneaux, 0 passé
# from=2026-05-08T19:10:30Z (4 mois)     → 33 créneaux, 0 passé
# from=2020-01-01T00:00:00Z&to=2027-...  → 33 créneaux, 0 passé
```

(À noter au passage : `from` exige le suffixe `Z`. Sans lui, `400 VALIDATION_ERROR
« Failed to convert value of type 'java.lang.String' to required type
'java.time.Instant' »`. Ce n'est pas un bug, juste une chose que la spec ne dit
pas.)

### La conséquence à l'écran

Sur l'onglet **Créneaux** de la carte, l'interrupteur « Afficher ce qui est
terminé » ne peut rien **découvrir** : il ne fait que garder à l'écran ce que le
serveur a déjà envoyé. En pratique, il n'agit que sur :

- mes propres créneaux, qui viennent de `GET /slots/mine?upcoming=false` ;
- les créneaux qui ont commencé pendant que la carte était ouverte.

Un utilisateur qui cherche « où avait lieu ce cours auquel je suis allé le mois
dernier » ne le retrouvera donc pas sur cette carte, alors qu'il retrouve très
bien le **programme** qui le portait.

### Ce qui est demandé

Que `from` soit honoré quand il est dans le passé — ou, si une borne est
préférable côté serveur, un `includePast` explicite avec une fenêtre plafonnée
(trois mois suffiraient largement à l'usage visé).

**Priorité basse.** L'onglet Activités répond déjà à la question « où était-ce »
sur la maille programme, qui est la plus utile. Cette demande ne fait que rendre
l'interrupteur cohérent d'un onglet à l'autre.

---

## Ce qui n'est **pas** demandé, et qui marche déjà

À noter pour éviter un travail inutile — ces trois points ont été vérifiés le
même jour et sont bons :

- **`includeExpired` sur `GET /activities/browse`** fonctionne parfaitement.
  46 entrées sans lui autour de Paris, 71 avec, dont 25 portant `isExpired: true`.
  L'app le branche enfin sur l'interrupteur ; c'est le seul écran des quatre où le
  réglage voyage jusqu'à la base.
- **`GET /programs?lat&lng&radius_km` rend les programmes expirés** (25 sur 25
  autour de Paris, `nextSessionAt: null`). C'est ce qui permet de poser leurs pins
  sur la carte en vue rapprochée sans un appel de plus. Un commentaire du dépôt
  affirmait le contraire ; il a été corrigé.
- **`POST /slots/{id}/join` refuse dès le début de la séance**
  (`400 SLOT_ALREADY_STARTED`, « Ce créneau est déjà passé. ») — et c'est la bonne
  frontière. L'app la respecte enfin : elle proposait « Rejoindre » jusqu'à la
  **fin** de la séance, un bouton dont la seule issue possible était ce refus.
