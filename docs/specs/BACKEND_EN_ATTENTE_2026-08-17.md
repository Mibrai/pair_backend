# Ce qui attend le backend — état au 2026-08-17

> Récapitulatif de **toutes** les demandes ouvertes, rassemblées depuis les
> documents d'échange, les drapeaux de fonctionnalité du client et les
> contournements laissés dans le code.
>
> **Chaque point a été revérifié contre `/v3/api-docs` en production le
> 2026-08-17**, pas seulement relu dans nos documents. Deux demandes que nous
> croyions ouvertes se révèlent livrées : elles sont au §3, et le client les
> consomme depuis le même jour — elles ne sont plus dues par personne.
>
> Le §5 dit ce que nous n'avons **pas** pu vérifier depuis l'app, pour que vous
> sachiez où notre affirmation vaut constat et où elle ne vaut que souvenir.

---

## Sommaire

| # | Sujet | État | Coût pour vous |
|---|-------|------|----------------|
| 1.1 | Code d'erreur de l'auto-abonnement | ouvert | une constante |
| 1.2 | Une diffusion n'est signalée à personne | ouvert, **bloquant** | à instruire |
| 2.1 | Bornes de `radius_km` absentes de l'OpenAPI | ouvert | annotation |
| 2.2 | Liste des participants d'un **programme** | ouvert | une route |
| 2.3 | Écart spec/runtime sur `POST /schedules` | à confirmer | à instruire |
| 2.4 | `mutable-content` sur les pushs | ouvert | un champ APNs |
| 2.5 | Volume Railway monté sur `/data` | ouvert, **infra** | déploiement |
| 3.1 | Recommandations entre pairs | **livré et consommé** | rien |
| 3.2 | Souvenirs par programme / activité / profil | **livré et consommé** | rien |

---

## 1. Ouvert, et une fonctionnalité en dépend

### 1.1 Le code d'erreur du refus « on ne s'abonne pas à soi-même »

**Demandé le 2026-08-17**, au §7 de
`REPONSE_CLIENT_ABONNEMENTS_LIVRAISON_2026-08.md`.

Votre §7 a fermé un trou : on pouvait s'abonner à sa **propre** activité. Le
refus qui en résulte n'a pas de code métier nommé dans votre document. Sans nom,
nous affichons le message serveur brut — dans la langue de l'en-tête
`Accept-Language` plutôt que celle de l'écran, ce que les codes métier existent
précisément pour éviter.

`SUBSCRIPTION_SELF` ou `CANNOT_SUBSCRIBE_TO_SELF` nous conviendraient ;
n'importe quel nom stable fera l'affaire. Si le refus remonte déjà sous le nom
de la contrainte de base (`chk_subscription_not_self`), dites-le : nous
traduirons ce nom-là.

**Ce n'est pas urgent, et c'est volontaire de notre part** : nous avons corrigé
les trois écrans qui menaient à ce refus, il n'est donc plus atteignable par un
chemin normal. Il le redevient dès qu'une activité change de main ou que notre
liste locale a un rafraîchissement de retard.

### 1.2 La première annonce d'une diffusion n'est signalée à personne

**Demandé le 2026-08-15**, `PROMPT_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md`.
**Aucune réponse à ce jour.**

La diffusion fonctionne : le fil naît, il apparaît des deux côtés, le message se
lit. Mais rien n'avertit un participant qu'une annonce est arrivée — ni le
compteur de non-lus, ni une notification. Il ne peut la découvrir qu'en ouvrant
la messagerie et en regardant.

C'est le seul point de cette liste qui rend une fonctionnalité livrée
inutilisable en pratique : un auteur qui diffuse une annonce croit avoir prévenu
son groupe, et n'a prévenu personne.

Le document original porte la reproduction avec deux comptes réels, le détail
des deux promesses non tenues, et pourquoi leurs effets se cumulent. Nous ne le
répétons pas ici.

---

## 2. Ouvert, sans blocage immédiat

### 2.1 Les bornes de `radius_km` ne sont toujours pas dans l'OpenAPI

**Demandé le 2026-08-17** (matin), `PROMPT_BACKEND_MAP_RADIUS_2026-08.md`,
demande 1. **Vérifié encore ouvert ce jour** : `GET /api/programs` déclare

```
radius_km  { "type": "number", "format": "double" }
```

sans `minimum` ni `maximum`. Le plafond réel vaut 100 km, et on ne l'apprend
qu'en prenant un `400` en production — ce qui nous est arrivé.

Les autres routes de carte déclarent le leur, et il diffère à chaque fois : 50 km
pour les créneaux et les personnes, 200 km pour les activités, aucune contrainte
pour la bbox. Un client qui lit la spec ne peut donc pas deviner celui-ci.

Les deux autres demandes du même document — le plafond peut-il monter, et
`/map/bounds` est-elle la route recommandée pour une carte qu'on dézoome —
restent également sans réponse. Aucune ne bloque : le client fonctionne aux deux
échelles.

### 2.2 Il n'existe pas de liste des participants d'un **programme**

Au niveau du créneau, tout est là : `GET /api/slots/{scheduleId}/participants`
et `GET /api/attendances/{scheduleId}/co-participants`.

Au niveau du programme, il n'y a que `GET /api/programs/{programId}/participants/count`
— un nombre. « Qui participe à ce programme » n'a donc aucune réponse possible,
et l'écran correspondant ne peut pas exister.

**Nous ne le demandons pas formellement aujourd'hui** : aucun écran ne l'attend
dans l'état actuel de l'app. Le point figure ici parce qu'il revient à chaque
fois qu'on parle du détail d'un programme, et qu'il vaut mieux savoir que c'est
un manque connu plutôt que de le redécouvrir.

### 2.3 `POST /schedules` — un écart entre la spec et le runtime

`CreateScheduleRequest` déclare comme obligatoires `lat`, `lng`, `placeName`,
`placeType`, `startsAt`. **`addressPublic` n'y figure pas**, et pourtant nos
notes d'implémentation disent que le serveur l'exige au runtime.

Deux possibilités, et nous ne savons pas laquelle : soit c'est corrigé depuis et
notre note est périmée, soit l'écart subsiste. La différence compte pour tout
client qui génère son code depuis la spec.

Second point de la même famille, non vérifiable depuis la spec : des créneaux
créés avec une adresse valide se retrouvaient enregistrés avec `lat`/`lng` à
`0,0`. Si cela a été corrigé, dites-le — nous retirerons le contournement.

### 2.4 Les pushs ne portent pas `mutable-content`

Les deux extensions de notification iOS sont écrites et livrées : la bannière
repliée et la vue déployée. Elles sont **inertes** tant que la charge APNs ne
porte pas `mutable-content: 1` — iOS ne réveille pas l'extension sans ce
drapeau, et la notification s'affiche donc en texte brut.

C'est un champ à ajouter à l'envoi, pas une fonctionnalité à écrire. Le travail
d'affichage est déjà fait des deux côtés du problème sauf celui-là.

### 2.5 Le volume Railway monté sur `/data` — infra, pas code

Les fichiers médias de production avaient disparu ; la cause a été trouvée et le
code backend corrigé. **Il reste à monter le volume persistant sur `/data`.**

Tant que ce n'est pas fait, le stockage vit sur un système de fichiers éphémère
et chaque redéploiement recommencera à effacer les images téléversées par les
utilisateurs. Le correctif applicatif ne protège de rien sans ce montage.

C'est le seul point de cette liste qui ne se règle pas dans le code.

---

## 3. Livré, et consommé — closes des deux côtés

Ces deux demandes étaient ouvertes dans nos documents. **Elles ne le sont
plus** : la vérification du 2026-08-17 les trouve servies, et le client les
utilise depuis le même jour. Considérez-les closes.

### 3.1 Recommandations entre pairs — `rating` et `comment` sont devenus facultatifs

`BACKEND_PEER_RECOMMENDATION_CONTRACT.md` demandait de les rendre optionnels sur
une recommandation entre pairs. Le contrat actuel dit :

```
CreateRecommendationRequest  required = ["recommendedId"]
  rating   { minimum: 1, maximum: 5 }      ← plus obligatoire
  comment  { minLength: 0 }                ← plus obligatoire
```

C'était exactement la demande. La fonctionnalité était éteinte côté client
(`FeatureFlags.peerRecommendations`) parce que satisfaire l'ancien contrat
revenait à publier une note et un témoignage fabriqués sous le nom de
l'utilisateur.

**Le geste est rallumé**, et le corps envoyé se réduit à `recommendedId` : ni
note, ni commentaire. Nous n'envoyons pas non plus de `rating: 5` — il
paraîtrait inoffensif, une recommandation n'existant que positive, mais il
alimenterait `averageRating` dans `RecommendationStatsDto`, c'est-à-dire une
note publique calculée sur des chiffres que personne n'a saisis.

L'encart qui montrait à l'utilisateur la phrase qu'on allait publier en son nom
a disparu avec sa cause.

### 3.2 Les souvenirs par programme, par activité et par profil

`PROMPT_BACKEND_RECAP_VISUALISATION_2026-08.md` demandait trois routes de
lecture, faute de quoi une carte-souvenir n'était visible que par son créneau ou
par un feed géolocalisé plafonné à 50 km. Les trois existent :

```
GET /api/programs/{programId}/recaps
GET /api/activities/{activityId}/recaps
GET /api/users/{userId}/recaps
```

**Les trois sont branchées.** Le contournement — filtrer le feed et « mes
souvenirs » par identifiants de créneaux — a disparu, et avec lui son défaut :
un programme à Berlin consulté depuis Paris affiche désormais ses souvenirs,
comme un profil dont l'hôte organise ailleurs.

Une nuance qui vous concerne peut-être : nous continuons de consulter
`GET /recaps/mine` **en plus** de ces routes, pour une seule raison — la version
qu'un participant reçoit porte `canContribute` et `myVibes`, que la version
publique ignore. La route décide quelles cartes existent, « mes souvenirs » ne
sert qu'à retrouver ma contribution sur celles-là. Si les routes par contexte
servaient déjà ces deux champs à un appelant authentifié, dites-le : nous
retirerions cette seconde lecture.

---

## 4. Ce que nous ne demandons pas, pour que ce soit écrit une fois

Trois points reviennent dans les échanges et pourraient passer pour des attentes
en sommeil. Ils n'en sont pas.

- **`userActivityId` sur le marqueur de `/map/activities`** (§1.4 du contrat
  abonnements). Retiré de la demande, définitivement pour la version actuelle :
  la carte lit un pin par programme et ne consomme plus cette route. Si nous y
  revenons, nous préviendrons avant.
- **Un compteur d'abonnés par catégorie.** Vous l'avez proposé au §4 de votre
  livraison. Aucun écran ne l'affiche, et nous ne créerons pas l'écran pour
  créer le besoin.
- **Le tri `targetName,asc`** sur `/users/me/subscriptions`, et la colonne
  dénormalisée qui l'accompagnerait. Notre écran n'en a pas besoin.

---

## 5. Comment cette liste a été établie, et ce qu'elle ne prouve pas

**Vérifié en propre** contre `/v3/api-docs` en production le 2026-08-17 : la
présence ou l'absence des routes, les champs obligatoires des corps de requête,
les bornes déclarées des paramètres. Les points 2.1, 2.2, 3.1 et 3.2 reposent sur
ces relevés et rien d'autre.

**Non vérifiable depuis la spec, donc rapporté sur la foi de nos observations
antérieures** : le comportement d'émission des notifications (1.2), le contenu
de la charge APNs (2.4), l'écart spec/runtime sur les créneaux (2.3), l'état du
montage de volume (2.5). Si l'un de ces quatre points a été traité depuis, il
figure ici à tort — et il suffit de le dire pour que nous le retirions.

**Le §1.1 est la seule demande formulée aujourd'hui.** Tout le reste est un
rappel de demandes déjà envoyées, avec leur date d'origine.

---

*Documents d'origine, par ordre d'apparition :
`REPONSE_CLIENT_ABONNEMENTS_LIVRAISON_2026-08.md` (§1.1),
`PROMPT_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md` (§1.2),
`PROMPT_BACKEND_MAP_RADIUS_2026-08.md` (§2.1),
`BACKEND_PEER_RECOMMENDATION_CONTRACT.md` (§3.1),
`PROMPT_BACKEND_RECAP_VISUALISATION_2026-08.md` (§3.2).*
