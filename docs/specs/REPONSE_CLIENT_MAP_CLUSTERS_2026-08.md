# Réponse client — clusters de la carte : les deux points ouverts

> Réponse à `REPONSE_BACKEND_MAP_CLUSTERS_2026-08.md`. Le filtre est reçu et
> conforme : rien à envoyer, rien à adapter. Ce document ne traite que les deux
> questions que vous nous posez. La première, **oui, reprenez-la** — avec un
> constat qui la rend plus urgente que vous ne le pensiez, parce que le silence
> est des deux côtés. La seconde, **non, ne faites rien** — et nous expliquons
> pourquoi le mieux serait ici l'ennemi du bien.

---

## Le filtre : reçu, conforme, rien à faire

Le défaut est celui que nous voulions, `includeExpired` n'est posé nulle part, et
les deux gardes-fous clients restent en place comme convenu — ils ne coûtent
qu'un parcours de liste et nous évitent de dépendre d'une invariance serveur.

Nous ne consommons **pas** `GET /map/bounds` : votre second avertissement ne nous
concerne pas aujourd'hui. Il est noté pour le jour où un écran l'appellerait.

Votre correction sur `programCount` est intégrée au code, à l'endroit exact où le
filtre s'applique. Nous le gardons, mais son commentaire dit désormais qu'il
n'écarte rien sur cette route et pourquoi — un filtre dont on croit qu'il trie
est pire qu'un filtre absent.

## Point 1 — `getAllActivitiesForMap` : oui, reprenez-le

Et il y a plus grave que ce que vous décrivez : **le silence est des deux
côtés.**

Nos deux chargements de carte finissent par un `.catchError((_) {})` nu
(`map_page.dart`, `_fetchUsers` et `_fetchActivities`). Nous n'affichons donc
rien sur un échec — pas de bandeau, pas de journal, pas de reprise. Nous
comptions sur le statut HTTP pour distinguer les deux cas ; votre `catch
(Exception)` nous rend même ce statut inutile, puisqu'il rend **200**.

Le résultat combiné : une panne de cette route produit une carte vide,
silencieuse, indiscernable d'une zone sans activité — **des deux côtés à la
fois**. Aucun de nous deux ne peut la voir sans que l'autre change.

C'est exactement la forme qu'avait l'incident média : un défaut d'infrastructure
qui s'est présenté trois semaines durant comme un contenu absent, faute d'un
signal quelque part. Nous ne voulons pas d'un second de ces silences sur la
surface la plus visible de l'app.

**Ce que nous avons fait de notre côté, sans attendre :** les deux `catchError`
muets sont remplacés par une journalisation et un retour à l'écran, avec un test
de péremption qui écarte les requêtes abandonnées (un changement de rayon ou de
caméra en produit à chaque geste — les annoncer apprendrait à ne plus lire les
bandeaux). Cinq tests verrouillent le tout.

**Et une limite que ce chantier a révélée, qui vous concerne.** Nous pouvons
désormais dire *qu'un* chargement a échoué, pas *pourquoi*. Ces deux appels
lisent une famille de `FutureProvider` sans abonné — c'est ce qui rend gratuit
l'aller-retour entre deux paliers de zoom — et Riverpod 3 détruit l'élément
avant qu'il n'émette son erreur : ce qui nous parvient est un `StateError` de
destruction, jamais le statut ni le `code` que vous avez renvoyés. Mesuré, pas
supposé.

Autrement dit : **même un `MEDIA_FILE_NOT_FOUND` ou un 403 bien nommé
n'atteindrait pas l'utilisateur sur cette surface.** Le typage exact demande de
notre côté un autre chantier (appeler le dépôt en direct, ou observer le
provider). Nous le signalons parce que cela borne ce que vaut, pour la carte, un
code d'erreur soigné — sur les autres écrans, il arrive intact.

**Ce que nous vous demandons :**

1. **Laisser l'erreur sortir** avec son vrai statut, plutôt qu'une carte vide en
   200. Un 5xx est une information ; un 200 vide est un mensonge que personne ne
   peut réfuter.
2. **Journaliser par le logger**, pas sur `System.err`, pour que le `rid:` du
   MDC accompagne la ligne (B8, lot 7). Sans lui, un `X-Request-Id` que nous vous
   donnons ne mène nulle part — nous en avons fait l'expérience sur les médias.
3. Si un repli reste souhaitable pour un consommateur public, qu'il soit
   **explicite dans la réponse** (un champ qui dit « ceci est un repli ») plutôt
   que déguisé en résultat normal. Nous saurons alors l'afficher comme tel.

Le `defaultCenter` que ce repli renvoie n'est, lui, **pas consommé** par l'app :
nous le désérialisons sans jamais nous en servir. Personne n'est donc téléporté à
Paris — c'est le seul point de ce mécanisme qui ne nous nuit pas.

## Point 2 — la fenêtre de dix minutes : non, ne la corrigez pas

Elle ne nous gêne pas, et nous étions déjà arrivés à votre conclusion par nous
mêmes. Notre `domain/activity_expiry.dart` documente ce même arbitrage depuis le
lot 5, dans les mêmes termes : c'est **l'erreur la moins chère des deux**.

- **Masquer une activité vivante** est borné (dix minutes), se répare tout seul
  sans action de l'utilisateur, et la fenêtre s'ouvre exactement à l'instant où
  l'occurrence vient de commencer — le moment où la pin a le moins de valeur pour
  quelqu'un qui cherche une séance à venir. L'activité reste par ailleurs
  atteignable par la recherche et par l'Explorer.
- **Garder une activité terminée** n'est borné par rien.

Vous avez donc déplacé le masquage de notre client vers votre réponse **sans
changer ce que l'utilisateur voit** : ces marqueurs disparaissaient déjà. C'est
une simplification, pas une régression.

**Nous vous déconseillons de dérouler la RRULE à la lecture pour cela.** Le
bénéfice se compte en dix minutes de visibilité au pire, sur la fenêtre la moins
utile ; le coût est un calcul de récurrence sur chaque entrée sérialisée, de
`/map/activities` à `/slots/feed` et `/activities/browse` — soit exactement les
routes où nous vous demandons par ailleurs de ne pas payer un accès par entité
(cf. les vignettes de recherche, réponse média point 3). Le job qui maintient
`starts_at` est la bonne architecture ; sa période est le seul réglage, et dix
minutes nous conviennent.

Si un jour cette fenêtre devenait visible à l'usage, le levier le moins cher
serait de resserrer la période du job, pas de changer la lecture.

## Ce qui reste ouvert de notre côté

1. **Faire remonter l'erreur *typée*** jusqu'à la carte, et pas seulement le fait
   qu'il y en ait une (voir la limite Riverpod ci-dessus). C'est ce qui rendra
   vos `code` exploitables sur cette surface.
2. Rien d'autre : le filtre est conforme, les compteurs tiennent, et nos tests
   carte passent sans modification.

## Récapitulatif des décisions

| Point | Décision |
| --- | --- |
| Filtre avant agrégation | Reçu, conforme, en défaut — rien à envoyer |
| `includeExpired` | Jamais posé de notre côté |
| `getAllActivitiesForMap` | **Reprenez-le** : vrai statut + logger + `rid:` |
| Fenêtre de dix minutes | **Ne faites rien** — l'erreur la moins chère des deux |
| `GET /map/bounds` | Non consommé aujourd'hui ; noté |
