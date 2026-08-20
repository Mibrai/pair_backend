# Ce qui reste à vérifier côté client mobile — 20 août 2026

> **Document unique.** Ces points étaient dispersés dans quatre échanges
> (`QUESTIONS_EQUIPE_MOBILE_2026-08-19`, `PROMPT_BACKEND_REPONSES_MOBILE_2026-08-19`,
> `REPONSE_BACKEND_MOBILE_2026-08-19`, et la spécification des liens publics). Les
> conversations closes n'y figurent plus : ce fichier ne contient que ce qui est **encore
> ouvert** au 20 août 2026.
>
> **Mis à jour le 20 août au soir**, après la livraison du partage public de programme : un
> point s'ajoute (n° 5), un autre se referme (le verbe `HEAD`).
>
> **Trois niveaux.** ⛔ bloque une fonctionnalité livrée · ⚠️ décidé par défaut, à confirmer
> ou à renverser · ℹ️ à intégrer, sans décision à prendre.

---

## Récapitulatif

| # | Point | Niveau | De qui |
|---|---|---|---|
| 1 | Empreinte SHA-256 de release Android | ⛔ | qui tient le compte Play |
| 2 | Entitlement iOS + intent-filter Android sur `lien.meetdo.fun` | ⛔ | mobile |
| 3 | Sous-domaine `lien.meetdo.fun` : DNS + Railway | ⛔ | qui tient l'infrastructure |
| 4 | Identifiants de store, pour le lien de téléchargement | ⚠️ | à la publication |
| 5 | `meetdo://programs/{jeton}` : le routage accepte-t-il un jeton ? | ⛔ | mobile |
| 6 | Onboarding : émettre les quatre vrais noms d'étapes | ⚠️ | mobile |
| 7 | « Mes activités » : quelle lecture ? | ⚠️ | mobile |
| 8 | Réglages e-mail : ouvrir le champ, ou l'afficher tel quel ? | ⚠️ | les deux |
| 9 | `first_name` : toujours différé | ⚠️ | les deux |
| 10 | Treize comportements à intégrer sans décision | ℹ️ | mobile |

---

## ⛔ 1. L'empreinte SHA-256 de release

`https://lien.meetdo.fun/.well-known/assetlinks.json` répond **`404`**, et c'est
délibéré : la valeur manque, et publier une association inventée serait pire que de n'en
publier aucune — Apple et Google mettent ces fichiers en cache agressivement, et une
association fausse mémorisée par un appareil est plus longue à corriger qu'une absente.

Elle dépend d'une décision qui n'est pas prise : **signature locale ou Play App Signing**.
Dans le second cas c'est Google qui détient le certificat, et l'empreinte n'est lisible que
dans la console — celle du keystore local ne sert alors qu'à l'upload.

Tant qu'elle manque, **les App Links Android ne se vérifient pas**. Les liens universels iOS,
eux, ne dépendent pas d'elle : `apple-app-site-association` est servi depuis le 19 août avec
`97727T64DH.com.meetdo.app`, au format `appIDs`/`components`, et déclare désormais **quatre**
motifs — `/s/*`, `/p/*`, `/public/slots/*`, `/public/programs/*`.

> Rappel que vous nous aviez fait, et qui vaut ici : Apple sert ce fichier depuis son propre
> CDN et les appareils le gardent. Les motifs de programme ajoutés aujourd'hui ne seront pas
> visibles instantanément des installations existantes.

Le jour où l'empreinte existera, `assetlinks.json` couvrira les mêmes chemins sans autre
changement : il ne déclare pas de motifs, seulement le paquet et l'empreinte.

Une fois connue, elle se pose par la variable d'environnement `ANDROID_SHA256` — aucun
déploiement de code n'est nécessaire.

---

## ⛔ 2. Les deux moitiés de l'association, côté mobile

Servir les fichiers ne suffit pas, et c'est l'équipe mobile qui l'a signalé — notre premier
document présentait les trois valeurs comme le seul obstacle, ce qui était faux.

- **iOS** — `ios/Runner/Runner.entitlements` ne contient que `aps-environment`. Sans
  `com.apple.developer.associated-domains` valant **`applinks:lien.meetdo.fun`**, iOS ne
  télécharge jamais l'AASA, si bien servi soit-il. Cela suppose aussi d'activer la capacité
  *Associated Domains* sur l'identifiant d'app, donc de régénérer les profils.
- **Android** — l'intent-filter App Links est en commentaire dans le manifeste, avec
  `REMPLACER_PAR_LE_DOMAINE_PUBLIC` à la place du domaine. À poser sur
  **`lien.meetdo.fun`**, chemin `/s/`, avec `android:autoVerify="true"`.

> **Le domaine est `lien.meetdo.fun`, et non `meetdo.fun`.** Nous avions confirmé le second
> le 19 août ; c'était faux, et la correction est arrivée le lendemain. `meetdo.fun` est le
> site vitrine hébergé chez Hostinger ; ce backend vit sur Railway, et le sous-domaine lui
> est dédié. Ni proxy ni redirection ne permettaient de servir les deux depuis le même nom :
> `mod_proxy` est absent de l'offre d'hébergement, et une redirection `302` aurait cassé
> l'aperçu — plusieurs robots de prévisualisation ne la suivent pas, or l'aperçu est toute
> la raison d'être de la page.
>
> **À vérifier avant de régénérer quoi que ce soit**, la correction ayant suivi la première
> réponse d'un jour.

Les deux moitiés échouent **silencieusement** l'une sans l'autre : un lien qui ne rouvre pas
l'application s'ouvre simplement dans le navigateur, sans message d'erreur exploitable.

---

## ⛔ 3. Le sous-domaine n'existe pas encore

Le code y pointe déjà (`pair.public.base-url`), l'infrastructure non. Il reste à créer
l'enregistrement DNS chez Hostinger, à déclarer le domaine personnalisé côté Railway, et à
poser `PUBLIC_BASE_URL=https://lien.meetdo.fun`.

**Rien de ce qui précède n'est testable avant cela** : ni l'AASA, ni l'aperçu dans une
messagerie, ni les liens universels.

Une fois le sous-domaine en place, la redirection `/s/*` du `.htaccess` racine devient
inutile.

---

## ⚠️ 4. Les identifiants de store

L'application n'est publiée nulle part, donc la page publique ne peut pas proposer de
téléchargement. Son bouton vise `meetdo://slot/{jeton}` — le schéma d'URI, qui fonctionne
aujourd'hui —, et une ligne discrète dit « Il faut avoir installé meetDo sur cet appareil ».

Cette ligne devient un lien de téléchargement dès que les identifiants existent. La balise
`apple-itunes-app` attend la même chose.

---

## ⛔ 5. Le bouton de la page de programme vise un jeton, pas un identifiant

Le partage public de programme est livré. Le bouton de sa page vise
**`meetdo://programs/{jeton}`** — le jeton opaque de 22 caractères, jamais l'identifiant
interne, une adresse bâtie sur la clé primaire se laissant énumérer.

Or votre document annonce que `deep_links.dart` traite `meetdo://programs/42`, c'est-à-dire
un **identifiant**. Si cet hôte n'accepte qu'un entier ou un UUID, le bouton ne mènera nulle
part.

**Deux issues, et le choix vous revient** : accepter un jeton sur cet hôte — le backend le
résout par `GET /public/programs/{jeton}` —, ou nous dire quelle forme d'adresse vous
attendez. C'est le seul point de cette livraison qui dépend de vous, et il ne se voit à
l'exécution que sur un appareil où l'application est installée.

Le même doute ne se pose pas pour les créneaux : `meetdo://slot/{jeton}` était déjà le
contrat que vous nous aviez décrit.

---

## ⚠️ 6. Onboarding — émettre les quatre vrais noms

`PATCH /api/users/me/onboarding` accepte désormais les **quatre écrans réels**, dans l'ordre
réel : `ACTIVITIES`, `LEVELS`, `LOCATION`, `PREVIEW`. `WELCOME` et `DONE` ont disparu — le
premier n'a jamais eu d'écran, et la fin se lit sur `onboardingCompletedAt`.

**Rien n'est cassé en attendant.** L'ancien vocabulaire reste accepté et traduit
(`WELCOME → ACTIVITIES`, `DISCOVERY` et `DONE → PREVIEW`). Mieux : la séquence que la
traduction actuelle émet — `ACTIVITIES, ACTIVITIES, LOCATION, DISCOVERY` — devient
**croissante** une fois relue dans le vrai ordre. Le défaut signalé est donc déjà corrigé
pour les utilisateurs de la version publiée, sans qu'ils mettent à jour.

Ce que la traduction ne rendra jamais, c'est la distinction entre les **deux premiers
écrans** : l'ancien vocabulaire n'avait qu'un mot pour les deux. Envoyer les vrais noms
permet de retirer le shim des deux côtés.

### Un point de vigilance en LECTURE

Le serveur **émet** désormais `LEVELS` et `PREVIEW`, que la version publiée ne connaît pas.
Deux endroits : `GET /api/users/me/onboarding` et le champ `onboardingStep` de
`GET /api/users/me`.

Le garde-fou est `onboardingCompletedAt`, déjà lu : les comptes existants l'ont tous, donc le
routage vers l'accueil ne dépend pas de la valeur d'étape. Le seul cas exposé est un compte
**en cours** de parcours au moment de la bascule.

**À vérifier tout de même** : qu'une valeur d'étape inconnue dégrade proprement plutôt que de
lever.

---

## ⚠️ 7. « Mes activités » — quelle lecture ?

`GET /api/activities/browse?myActivitiesOnly=true` retient aujourd'hui **les entrées portant
une activité que l'appelant a déclarée** — ce qui se pratique autour de lui dans ses sports.

La lecture inverse est défendable : chaque ligne de cette route est une entrée appartenant à
quelqu'un, et « Mes activités » pourrait désigner *mes propres entrées*. Nous avons tranché
pour la première parce que l'Explorer est une surface de découverte, et qu'un filtre ne
rendant que ses trois entrées n'y découvre rien.

**Si le libellé désigne autre chose chez vous, dites-le** : c'est une ligne de `WHERE` à
changer, pas une refonte.

---

## ⚠️ 8. Réglages e-mail — une promesse que le serveur ne tient pas

La préférence par défaut est `emailEnabled = true` pour les **trente et un** types de
notification. Mais `EmailService` ne poste que les types qui **méritent un e-mail** :
aujourd'hui l'annulation d'un créneau, celle d'un programme, et le changement d'horaire.

Cocher « recevoir les e-mails » sur une suggestion d'activité ne produit donc **aucun**
e-mail. Si l'interface laisse croire à un choix par type, c'est une promesse que le serveur
ne tient pas.

Deux issues, et le choix vous revient : ouvrir le champ côté serveur, ou présenter le réglage
tel qu'il est. Notre avis : le présenter tel qu'il est. Envoyer les trente et un types
remplirait les boîtes et ferait couper le canal entier — y compris pour les annulations, qui
en sont la raison d'être.

> À noter, la distinction est **volontairement double** : « traverser les heures de silence »
> et « mériter un e-mail » sont deux listes distinctes. Le rappel de séance traverse le
> silence sans produire d'e-mail — sinon il en partirait un par séance rejointe par chacun.

---

## ⚠️ 9. `first_name` — toujours différé

La page publique réduit le nom d'affichage à son **premier segment** (`GivenName`), faute
d'un champ dédié. Vous aviez confirmé cette réduction avec la même réserve que nous : elle se
trompe sur un nom composé, un prénom en second, ou une graphie qui ne sépare pas par un
espace.

Rien n'a changé depuis, et l'ajout d'un vrai `first_name` reste à décider **ensemble** :
c'est une colonne, un écran de saisie et une reprise des comptes existants.

---

## ℹ️ 10. Treize comportements à intégrer, sans décision à prendre

Aucun de ces points n'attend une réponse ; chacun change ce que le client doit afficher ou
cesser de faire.

1. **Les réglages de confidentialité sont appliqués.** Un profil `PRIVATE` ou `FRIENDS` sans
   abonnement rend `bio`, `badgeCodes`, `subscriberCount`, `reliabilitySignal` et `isOnline`
   nuls. Un client qui filtrait lui-même doit cesser. `GET /api/users/me/preview` rend ce
   qu'un inconnu voit — le même code, avec la relation d'un inconnu.

2. **`GET /api/map/activities` exige un jeton.** Elle était ouverte ; un `401` signifie
   désormais que le jeton manque, pas que la carte est vide.
   `GET /api/users/{id}/programs` rend `404` entre deux comptes bloqués.

3. **Un refus de blocage est un `404`, jamais un `403`.** Ne cherchez pas à le rendre plus
   explicite : un code nommé apprendrait le blocage à celui qui l'a subi.

4. **Le total de `unread-count` exclut les fils en sourdine et archivés**, alors que leur
   `unreadCount` individuel reste exact. Un client qui vérifiait « la somme des fils = le
   badge » doit sommer les fils **ni `muted` ni `archived`** — les deux drapeaux sont sur
   `ConversationSummaryDto` pour cela.

5. **L'indicateur de saisie n'a pas d'échéance côté serveur.** À effacer après quelques
   secondes sans nouvelle : un émetteur qui perd sa connexion ne pourra jamais annoncer qu'il
   s'est arrêté.

6. **Un partage de position échu rend ses trois champs nuls**, y compris sur un message qui
   en portait un. Pas besoin de comparer une échéance à l'heure courante pour décider
   d'afficher ; il reste à faire disparaître le point à l'échéance sur un fil resté ouvert.
   Une durée supérieure à 30 minutes est **refusée** en `400`, jamais rabotée.

7. **Un message reçu ne désarchive pas un fil.** C'est délibéré, à rebours de plusieurs
   messageries : ranger le fil dont on veut se débarrasser n'aurait sinon aucun effet.

8. **Le silence coupe la push, pas la notification**, qui est écrite et attend au réveil.
   À formuler « ne pas être dérangé », pas « ne pas recevoir ». La fenêtre **traverse
   minuit** : « 22 → 7 » est le réglage courant. Une seule borne est refusée, deux bornes
   égales aussi.

9. **`reliabilitySignal` vaut `"USUALLY_SHOWS_UP"` ou `null`**, jamais un libellé négatif ni
   un pourcentage. Un signal absent n'est pas un mauvais signal : il signifie « pas assez de
   données », et il vaut mieux ne rien afficher qu'afficher une réserve.

10. **Le filtre d'accessibilité est restrictif** : un créneau qui ne déclare rien est écarté
    dès qu'on filtre, parce que rien ne permet d'affirmer son accueil. C'est l'inverse du
    filtre de langue, où une absence de déclaration n'exclut pas. À libeller « seulement les
    créneaux qui l'annoncent », pas « accessibles ».

11. **Les compteurs de l'Explorer annoncent ce qu'une case rendrait si on la cochait** : ils
    ignorent les filtres de même nature. La clé `UNSPECIFIED` regroupe les entrées sans
    niveau déclaré — elles comptent dans le total, et les ranger sous « ANY » inventerait une
    déclaration que personne n'a faite.

12. **Un programme se partage désormais**, sur le même contrat qu'un créneau. Le lien est
    réservé à l'**organisateur** — `404` pour quiconque d'autre — et la page **ne se périme
    pas** : un programme sans séance à venir la garde, et elle dit « aucune séance annoncée »
    plutôt que de disparaître. Ce qui l'éteint, c'est l'archivage, la dépublication, ou
    `shareable = false`.

13. **`HEAD` répond comme `GET` sur toutes les pages publiques** — point refermé. Il rendait
    `401` là où `GET` rend `200`, les règles de sécurité ne nommant que `GET`, et cela valait
    pour l'ensemble des routes ouvertes, pas seulement l'AASA. Rien n'était cassé, mais tout
    diagnostic mené en `curl -I` concluait à tort que la page était protégée — votre propre
    document en portait la conséquence, en déduisant d'un `401` qu'une route était absente
    alors qu'elle aurait rendu `401` même en existant.

Deux ajouts mineurs au passage : la recherche tolère désormais les **fautes de frappe**, en
repli seulement — il n'y a pas de « vouliez-vous dire… ? » à afficher, le serveur rend
directement ce qui ressemble. Et `createdVia: "QUICK"` distingue un programme volontairement
nu d'un programme mal rempli.

---

## Ce que le backend doit encore, et à qui

| Attendu | Bloqué par |
|---|---|
| `assetlinks.json` en `200` | l'empreinte SHA-256 |
| Lien de téléchargement sur la page publique | les identifiants de store |
| `first_name` réel | une décision commune |
| Réglages e-mail par type | la réponse au point 8 |
| Bouton de la page de programme opérant | la réponse au point 5 |

Le reste est livré : **758 tests, zéro échec**.
