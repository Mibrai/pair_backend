# Réponse du backend à l'équipe mobile — 2026-08-19

En réponse à `PROMPT_BACKEND_REPONSES_MOBILE_2026-08-19.md`.

Les cinq points de votre récapitulatif sont traités. Trois étaient des
changements de notre côté, un est une valeur que nous confirmons, le dernier est
une question à laquelle nous répondons. Le seul qui reste ouvert ne dépend
d'aucun des deux : l'empreinte SHA-256.

---

## 1. Les étapes d'onboarding — ✅ nous adoptons vos quatre écrans

Vous aviez raison sur le fond et sur la gravité. L'énumération décrivait la
spécification, pas le produit ; c'est le produit qui fait autorité.

`OnboardingStep` vaut désormais, dans cet ordre :

```
ACTIVITIES → LEVELS → LOCATION → PREVIEW
```

`WELCOME` et `DONE` ont disparu, pour les raisons exactes que vous donnez. La
clôture du parcours n'est plus une valeur mais une position : **franchir le
dernier écran pose `onboardingCompletedAt`**. Nous l'avons écrit comme
`ordinal() == values().length - 1` et non comme `== PREVIEW`, pour que l'ajout
d'un cinquième écran déplace le point de clôture sans qu'on ait à s'en souvenir.

`POST /api/users/me/onboarding/skip` ne change pas : il referme le parcours en
**conservant** l'étape atteinte, qui est la seule information qu'un abandon
apporte.

### Votre traduction peut rester en place, et n'a rien cassé

Nous acceptons encore l'ancien vocabulaire en entrée, traduit vers le vrai
parcours :

| Reçu | Enregistré |
|---|---|
| `WELCOME` | `ACTIVITIES` |
| `ACTIVITIES` | `ACTIVITIES` |
| `LOCATION` | `LOCATION` |
| `DISCOVERY` | `PREVIEW` |
| `DONE` | `PREVIEW` — et referme le parcours |

Refuser ces cinq valeurs aurait cassé votre build publié d'un déploiement à
l'autre. Il y a mieux : la séquence que votre shim émet aujourd'hui —
`ACTIVITIES, ACTIVITIES, LOCATION, DISCOVERY` — devient **croissante** une fois
relue dans ce vocabulaire, ce qu'elle n'était pas avant. Le défaut que vous
décriviez est donc déjà corrigé pour vos utilisateurs actuels, sans qu'ils aient
à mettre à jour.

Ce que la traduction ne rend toujours pas, c'est la distinction entre vos deux
premiers écrans : l'ancien vocabulaire n'avait qu'un mot pour les deux. C'est
la raison pour laquelle ce repli est une transition et non un acquis — envoyez
les quatre vrais noms dès que vous le pouvez, et vous pourrez alors retirer le
shim des deux côtés.

Une valeur hors des neuf reste un `400` : accepter n'importe quoi enregistrerait
un avancement qui ne veut rien dire, sans que vous appreniez jamais l'erreur.

### ⚠️ Un point d'attention en LECTURE, à votre charge

Nous **émettons** désormais `LEVELS` et `PREVIEW`, que votre build publié ne
connaît pas. Deux endroits sont concernés : `GET /api/users/me/onboarding` et le
champ `onboardingStep` de `GET /api/users/me`.

Le garde-fou est `onboardingCompletedAt`, que vous lisez déjà : les comptes
existants l'ont tous, et le routage vers l'accueil ne dépend donc pas de la
valeur d'étape. Le seul cas exposé est un compte **en cours** de parcours au
moment de la bascule, population qui n'existe qu'en développement. Vérifiez tout
de même que votre lecture d'une valeur inconnue dégrade proprement plutôt que de
lever.

La migration `V74` réécrit les valeurs stockées (`WELCOME → ACTIVITIES`,
`DISCOVERY`/`DONE` → `PREVIEW`). Elle n'est pas cosmétique : sans elle, toute
lecture d'un compte migré par `V60` aurait levé à la désérialisation.

Un cas est imparfait et nous l'assumons par écrit : `LOCATION` était la deuxième
étape sur cinq, elle est la troisième sur quatre. Quelqu'un arrêté là reprendra
*après* le choix du niveau au lieu d'avant. Le seul autre choix — le renvoyer à
`ACTIVITIES` — lui aurait fait refaire un écran qu'il avait fait.

---

## 2. Les deux routes — ✅ fermées

### `/api/map/activities`

Elle a quitté la liste `permitAll` de `SecurityConfig`. Elle exige un jeton, et
**elle masque désormais les organisateurs bloqués**, dans les deux sens.

Le filtrage est fait en mémoire, à contre-courant du reste du lot A3 qui pousse
le prédicat dans le SQL. Ce n'est pas une facilité : un marqueur agrège ici
plusieurs organisateurs sur une même activité, et tous les compteurs de la
réponse — `totalInBounds`, les `count` de clusters, `truncated` — dérivent de la
liste après agrégation. Écarter les créneaux **avant** de construire les
marqueurs les laisse exacts ; un post-filtrage des marqueurs les aurait tous
faussés.

Votre réserve est notée telle quelle : le jour où une page web publique devra
afficher une carte, ce sera une route dédiée à cette page, pas celle-ci.

### `/api/users/{id}/programs`

Elle était déjà authentifiée — c'est notre document qui la classait mal — mais
elle ne consultait pas le blocage, alors que la fiche de profil servie au même
écran refusait déjà. Elle rend maintenant **404** entre deux personnes bloquées,
dans les deux sens, comme la fiche : un code nommé apprendrait le blocage à
celui qui l'a subi.

Un test de non-régression fixe les deux surfaces dans `UserBlockIntegrationTest`.

---

## 3. L'association d'applications — partiellement débloquée

`pair.mobile.apple-app-id = 97727T64DH.com.meetdo.app` et
`pair.mobile.android-package = com.meetdo.app` sont posés en configuration,
surchargeables par variable d'environnement.

**Conséquence immédiate : `/.well-known/apple-app-site-association` répond
maintenant `200`**, en `application/json` et sans redirection, avec les chemins
`/s/*` et `/public/slots/*`.

`/.well-known/assetlinks.json` **répond toujours `404`**, et c'est délibéré :
l'empreinte manque. Nous ne la remplirons pas avec une valeur plausible — une
association fausse mise en cache par un appareil est plus longue à corriger
qu'une association absente. Elle attend la décision sur Play App Signing.

### Le domaine public : ⚠️ **`lien.meetdo.fun`**, et non `meetdo.fun`

**Correction d'une réponse antérieure de ce même document.** Nous avions
confirmé `meetdo.fun` ; c'est faux, et mieux vaut le lire ici qu'après avoir
régénéré vos profils de provisioning.

Les deux noms désignent deux serveurs. `meetdo.fun` est le site vitrine hébergé
chez Hostinger ; ce backend vit sur Railway, et c'est le sous-domaine
`lien.meetdo.fun` qui lui est dédié. Ni proxy ni redirection ne permettaient de
servir les deux depuis le même nom : `mod_proxy` n'est pas disponible sur l'offre
d'hébergement, et une redirection `302` aurait cassé l'aperçu — plusieurs robots
de prévisualisation ne la suivent pas, or l'aperçu est toute la raison d'être de
la page.

Posez donc vos deux moitiés sur **`applinks:lien.meetdo.fun`** dans
`Runner.entitlements`, et le même hôte dans l'intent-filter Android à la place de
`REMPLACER_PAR_LE_DOMAINE_PUBLIC`, avec `android:autoVerify="true"` et le chemin
`/s/`.

`https://lien.meetdo.fun/.well-known/apple-app-site-association` est servi dès à
présent, au format `appIDs`/`components` d'iOS 13 et au-delà.

Merci pour l'angle mort — notre document présentait les trois valeurs comme le
seul obstacle, ce qui était faux, et rien n'aurait fonctionné sans que la cause
soit visible nulle part.

---

## 4. Le schéma d'URI — ✅ utilisé, et il corrige un bouton mort

Le bouton de la page publique visait `https://meetdo.fun/s/{jeton}` — l'adresse
de la page elle-même. Il ne menait nulle part dans **les deux** cas de figure :
sans application installée il rechargeait la page, et avec — une fois les liens
universels actifs — iOS n'ouvre justement pas l'application depuis un lien vers
le domaine où le navigateur se trouve déjà.

Il vise maintenant `meetdo://slot/{jeton}`, que `deep_links.dart` route déjà.
Cela fonctionne **aujourd'hui**, sans rien attendre des fichiers d'association ni
des entitlements qui leur manquent encore.

Le schéma échoue en silence quand l'application n'est pas installée — le
navigateur ne fait rien. La page porte donc une ligne discrète sous le bouton
(« Il faut avoir installé meetDo sur cet appareil »), à remplacer par un lien de
téléchargement dès que l'application sera publiée quelque part. Nous ne pouvons
pas le faire avant : il n'y a pas d'identifiant de store.

---

## 5. L'e-mail d'annulation : **en plus**, jamais en remplacement

Réponse nette, et le détail compte pour vos réglages.

Les trois canaux sont **indépendants** et n'ont aucune connaissance les uns des
autres (`NotificationService.notify`) :

1. la notification in-app est écrite **toujours**, sans condition ;
2. l'e-mail part si `emailEnabled` **et** `frequency == IMMEDIATE` ;
3. le push part si `pushEnabled`.

Chacun est enveloppé dans son propre `try/catch` : l'échec de l'un ne déclenche
ni n'empêche l'autre. **Il n'y a aucun repli**, et il ne pourrait pas y en avoir
— le serveur ne sait pas si un push est arrivé. Qu'FCM accepte un message ne dit
rien de sa remise, et l'envoi est `@Async`, donc même l'appelant ne l'apprend
pas.

Ce que vous pouvez donc dire dans les réglages : **les deux cases sont deux
canaux, pas un canal et sa doublure.** Décocher « e-mail » supprime l'e-mail,
même si le push échoue ensuite.

Une précision qui change ce que vous affichez : la préférence par défaut est
`emailEnabled = true` pour tous les types, mais **`EmailService` ne poste que les
notifications critiques** — aujourd'hui `SLOT_CANCELLED` et `PROGRAM_CANCELLED`.
Cocher « recevoir les e-mails » sur une suggestion d'activité ne produira donc
aucun e-mail. Envoyer les trente et un types ferait fuir les gens plus sûrement
qu'aucune fonctionnalité. Si vos réglages laissent croire à un choix par type,
c'est aujourd'hui une promesse que le serveur ne tient pas ; dites-nous si vous
préférez que nous ouvrions le champ ou que vous le présentiez comme il est.

---

## Ce qui reste ouvert

| # | Attendu | De qui |
|---|---|---|
| 3 | Empreinte SHA-256 de release (ou de Play App Signing) | qui tient le compte Play |
| 4 | Identifiants de store, pour le lien de téléchargement | quand l'app sera publiée |
| 5 | Réglages e-mail : ouvrir le champ, ou l'afficher tel quel ? | vous |
| — | `first_name` : toujours différé, ensemble | les deux |

Et de votre côté : entitlement `associated-domains` (iOS) et intent-filter App
Links (Android) sur **`lien.meetdo.fun`** — lisez la correction du §3 avant de
toucher aux profils de provisioning.

Deux ajouts qui vous concernent, issus de la spécification des liens publics :
`GET /s/{jeton}/calendar.ics` sert désormais l'agenda depuis l'adresse courte, et
`PATCH /api/slots/{id}/shareable` permet à l'organisateur de refermer un partage
— le jeton n'est alors ni effacé ni régénéré, si bien que rouvrir rend valides
les liens déjà partagés.

Sur la publication des règles de communauté, votre mise en garde est retenue :
le passage de `pair.guidelines.current-version` suit la publication au store,
il ne la précède jamais. C'est une variable d'environnement, pas un redéploiement.
