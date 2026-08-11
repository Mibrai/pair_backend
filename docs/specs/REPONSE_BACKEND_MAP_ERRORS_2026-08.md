# Réponse backend — la carte ne ment plus sur ses pannes

> Réponse à `REPONSE_CLIENT_MAP_CLUSTERS_2026-08.md`. Vos deux décisions sont
> suivies : le point 1 est repris et livré, le point 2 n'est pas touché. Nous
> avons remonté l'origine du défaut, et elle mérite d'être dite : ce n'était pas
> un arbitrage, c'était un échafaudage oublié.

---

## Point 1 — livré, et le masque n'était même pas un choix

`GET /api/map/activities` laisse désormais sortir ses erreurs.

Nous avons cherché pourquoi ce `catch (Exception)` existait, parce qu'un repli
délibéré et un oubli ne se traitent pas pareil. Le commit qui l'a posé, le
**2026-07-04**, le dit lui-même :

> *« Add try-catch block to gracefully handle errors and return empty response
> with default center instead of 500 error. **Helps debug issues on Railway
> deployment**. »*

Et le commit **suivant** sur ce même fichier : *« fix: use eager fetch for
schedules to avoid **LazyInitializationException** »*. Autrement dit : le masque
avait été posé pour survivre à un bug précis, ce bug a été corrigé à la source
le lendemain par un `JOIN FETCH`, et le masque est resté cinq semaines à couvrir
des pannes qu'il n'avait jamais été censé couvrir.

Vous aviez donc raison sur toute la ligne, et pour une raison de plus que celles
que vous invoquiez : personne n'avait décidé que la carte devait mentir.

### Vos trois demandes

1. **L'erreur sort avec son vrai statut.** Une défaillance produit un
   `500 INTERNAL_ERROR`. Le repli — liste vide, centre Paris, `200` — est
   supprimé, ainsi que la fabrique `untruncated()` qui n'existait que pour lui.
2. **La journalisation passe par le logger, avec le `rid:`.** Et sans une ligne
   de code ajoutée : `GlobalExceptionHandler` journalisait déjà en `log.error`,
   et le motif `%5p [rid:%X{requestId:-}]` y colle l'identifiant du MDC. Le
   `System.err` + `printStackTrace` disparaît. Un `log` local ici ferait
   doublon, nous n'en avons donc pas mis.

   Conséquence directe pour vous : **un `X-Request-Id` que vous nous donnez mène
   maintenant quelque part** sur cette route. `RequestIdFilter` renvoie
   l'en-tête même en erreur — le test le vérifie.
3. **Le champ « ceci est un repli » : nous ne l'avons pas fait, faute d'objet.**
   Sans repli, il n'y a rien à étiqueter. Nous avons retenu votre principe —
   rien ne doit se faire passer pour un résultat normal — et la façon la plus
   propre de l'honorer était de supprimer le résultat qui n'en était pas un,
   plutôt que de garder le mensonge en le nommant. Si un consommateur public se
   manifestait un jour en réclamant un mode dégradé, il devrait être explicite
   **et** demandé, pas subi.

### La frontière, verrouillée par un test

`MapActivitiesErrorPathIntegrationTest` interroge **la même route avec le même
dépôt simulé**, et c'est ce qui rend la frontière lisible :

| Situation | Réponse |
| --- | --- |
| Aucune donnée | `200`, `activities: []`, `totalInBounds: 0`, `defaultCenter` servi |
| Dépôt défaillant | `500`, `code: INTERNAL_ERROR`, en-tête `X-Request-Id` présent, **pas de champ `activities`** |
| Paramètre invalide | `400`, `code: MAP_ZOOM_OUT_OF_RANGE` (inchangé : la validation était déjà hors du masque) |

Une carte vide reste donc possible et légitime — c'est le chemin normal. Ce qui
disparaît, c'est la carte vide **en cas de panne**.

Le javadoc de la méthode porte désormais cette histoire, pour que l'échafaudage
ne soit pas reposé par quelqu'un qui verrait passer un 500 en production et le
prendrait pour un problème d'affichage.

### Sur votre limite Riverpod

Vous nous dites que le `code` ne vous parviendra pas sur cette surface tant que
le typage n'aura pas été repris. C'est noté, et cela ne change rien à ce que
nous livrons : ce que vous demandiez, c'est **le statut**, et vous l'aurez. Le
gain immédiat n'est d'ailleurs pas côté écran mais côté enquête — la ligne
serveur devient corrélable, ce qui vous avait précisément manqué sur les médias.

Quand vous ferez ce chantier, les codes seront là, à jour et traduits.

## Point 2 — la fenêtre de dix minutes : rien fait, et rien ne sera fait

Décision reçue, et nous la partageons. Votre argument — masquer une activité
vivante est borné et se répare seul, garder une activité terminée n'est borné
par rien — est le bon, et il rejoint le nôtre : dérouler la RRULE à la lecture
ferait payer un calcul de récurrence par entité sérialisée sur trois routes,
exactement ce que nous cherchons à éviter ailleurs.

Nous notons votre levier de repli : resserrer la période du job plutôt que
changer la lecture, si la fenêtre devenait un jour visible à l'usage.

## Ce que nous vous devons encore

Rien sur ce sujet. Restent, de l'incident média et indépendamment de vous :

1. **Le volume Railway monté sur `/data`** — action d'infrastructure, sans quoi
   les téléversements continueront de disparaître à chaque redéploiement. Les
   journaux de démarrage disent maintenant laquelle des deux situations nous
   sommes.
2. Un **N+1** vu au passage sur cette même route : l'utilisateur organisateur
   n'est pas dans le `JOIN FETCH` des créneaux, ce qui coûte une requête par
   activité à la construction des marqueurs. Ce n'est pas un bug, c'est une
   facture ; nous la traiterons séparément.
