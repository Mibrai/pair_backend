# Ce que leur réponse change chez nous : une décision à rendre, un écran à réécrire, et une recette qui aurait accusé une chaîne correcte

**Date :** 2026-09-07
**Fait suite à :** `REPONSE_BACKEND_2026-09-07.md`

> **Leur unique question a une réponse, et elle est vérifiée dans le code :**
> non, l'écran d'inscription n'a aucun traitement particulier du `429`. Ils
> peuvent changer le seuil. Une seule condition, et elle n'est pas
> négociable — §1.
>
> **Quatre points de notre ticket étaient faux ou incomplets**, dont un que nous
> avions affirmé sans le vérifier. Nous les reprenons ici plutôt que de les
> laisser dans un document que quelqu'un relira dans six mois — §2.
>
> **Un trou que leur réponse ouvre sans le voir :** `BOUNCED` est un diagnostic
> sans remède. Le contrat ne porte aucun moyen de corriger l'adresse d'un
> compte — nous l'avons cherché. C'est le huitième livrable, et il conditionne
> l'intérêt du quatrième — §3.
>
> **Ce que nous écrirons quand (d) arrivera** : un enum, cinq clés, un drapeau,
> et un écran qui cesse d'affirmer « nous avons envoyé un lien à {email} » à
> quelqu'un dont l'envoi a peut-être été refusé à la soumission — §4.
>
> **La recette, corrigée par leur §8.2**, et un test qui tranche leur propre
> réserve du §3 depuis l'extérieur : lire les en-têtes d'un e-mail reçu — §5.

---

## 1. Leur §10 — l'écran d'inscription et le `429`

**Vérifié, pas supposé.** `tryMapDioException`
(`lib/core/network/exceptions.dart:317-345`) n'a pas de cas `429` : le statut
tombe dans la branche `_`, et cette branche a une particularité qui vous
concerne directement.

```dart
_ => (code == null && serverMessage.isEmpty)
        ? null                                    // ← rien d'exploitable
        : ApiException(message: serverMessage, statusCode: statusCode, code: code),
```

Deux issues, donc, selon ce que porte **votre corps de réponse** :

| Ce que vous envoyez | Ce que l'utilisateur lit |
|---|---|
| un `code` **ou** un `message` | votre phrase, en avertissement |
| ni l'un ni l'autre | « Une erreur est survenue. Réessaie. » |

Le niveau est correct dans les deux cas — `noticeLevelForApiError`
(`lib/shared/utils/api_error_notice.dart`) classe tout 4xx nu en avertissement,
pas en panne, ce qui est le bon registre pour un quota.

**Donc : changez le seuil, nous n'avons rien à contourner.** La seule condition
est que le `429` porte **un `code` et un message**. Ce n'est pas une préférence
d'affichage, c'est une règle de notre couche réseau : `ApiException.message` ne
contient **que** du texte serveur, et vide y signifie « le serveur n'a rien
dit ». Un `429` au corps vide devient donc, chez nous, indiscernable d'une panne
générique — et « réessayez » est exactement le mauvais conseil pour un quota
horaire, puisque réessayer ne peut pas marcher.

**Nommez le `code`, et qu'il soit stable.** C'est lui, et non votre phrase, qui
nous permet de dire « réessayez dans une heure » dans la langue de l'app.
`RATE_LIMITED` nous convient ; le nom est le vôtre, la stabilité est ce qui nous
importe. Sans code, nous dépendons de votre phrase — qui, elle, est déjà servie
selon l'`Accept-Language`, donc lisible : c'est un repli acceptable, pas une
cible.

Une remarque sur votre offre du §6 : **oui, traitez `register` dans le même
geste.** Vous avez raison de dire que cette borne-là nous coûte plus que celle
que nous signalions. Cinq inscriptions par heure et par IP, c'est un groupe de
six qui s'inscrit ensemble et dont la dernière personne se voit refuser la
création de compte — le scénario d'arrivée que nous décrivions, avec le pire
moment possible pour un refus.

---

## 2. Nos quatre erreurs

| # | Ce que nous avons écrit | Ce qui est vrai |
|---|---|---|
| 1 | « L'envoi de l'e-mail est asynchrone » | Il est **synchrone**, dans la transaction d'inscription, avec un aller-retour HTTP Europe ↔ San Francisco |
| 2 | Recette : `@example.invalid` doit passer à `BOUNCED` | Il passera à `FAILED` — TLD réservé, refus à la soumission, aucun message créé, donc aucun rebond |
| 3 | Contrat : `NONE · PENDING · SENT · BOUNCED · FAILED` | Il manquait **`DELIVERED`**, le seul état qui dise « arrivé » plutôt que « accepté » |
| 4 | Limiteur : nous n'avons signalé que `resend-verification` | `register` est borné à **5/h/IP**, et c'est celui qui nous coûte le plus |

La première mérite d'être regardée en face. **Nous l'avons affirmée, pas
vérifiée** — l'app ne peut pas voir la différence entre un envoi synchrone et un
envoi asynchrone derrière un `201`, et nous l'avons écrite comme un fait dans un
document qui reprochait par ailleurs l'absence de mesure. C'est exactement le
défaut contre lequel notre propre règle existe : relever, puis affirmer. Le
paragraphe était en plus inutile à la démonstration.

La troisième est plus instructive qu'elle n'en a l'air. Nous avons transposé
`alertDelivery` en recopiant ses valeurs de mémoire, et nous en avons perdu
**celle qui portait la seule bonne nouvelle** — au moment précis où nous
écrivions un ticket pour distinguer « accepté » de « arrivé ». Un contrat se
relève, lui aussi.

---

## 3. Le trou que leur réponse ouvre : `BOUNCED` n'a pas de suite

Leur livrable (d) nous donne de quoi **dire** à quelqu'un que son adresse a
rejeté le message. Il ne donne rien pour **en sortir**.

Nous avons cherché la route de correction dans le contrat, ce matin, sur
`/v3/api-docs` :

```
PUT /api/users/me → UpdateProfileRequest
   { displayName, bio, locationPublic, onlineStatusVisible,
     receiveMessages, blurRadiusM }
```

Pas d'`email`. Et il n'existe aucune route dédiée : `POST /users/me/change-password`
existe, son équivalent pour l'adresse n'existe pas. **Une adresse mal saisie à
l'inscription est donc définitive**, et le compte qui la porte ne peut plus
jamais être vérifié — ni recevoir quoi que ce soit.

C'est une conséquence directe de ce ticket, et non un sujet voisin : la faute de
frappe à l'inscription est le premier cas de rebond dur, avant même le domaine
qui refuse. Aujourd'hui elle est invisible ; avec (d) elle devient visible et
sans issue, ce qui est un progrès pour nous et une impasse pour la personne.

**Nous demandons donc un huitième livrable** — la forme est la leur :

- soit `POST /api/users/me/change-email { email }`, qui repart sur le cycle de
  vérification et ne bascule l'adresse qu'au clic du lien ;
- soit, si c'est trop pour ce lot, **une réponse à la question « que doit faire
  cette personne ? »** que nous puissions écrire à l'écran. « Recréez un
  compte » est une réponse, si c'en est une.

Sans l'un des deux, nous afficherons un état `BOUNCED` qui nomme un problème et
laisse l'utilisateur devant une porte fermée — soit précisément ce que ce ticket
cherchait à supprimer, déplacé d'un cran.

---

## 4. Ce que nous écrirons quand (d) arrivera

Rien avant : le champ n'existe pas, et un écran écrit contre un contrat annoncé
est un écran qu'on réécrit. Le plan, pour qu'il soit relisible le jour venu :

**a. La lecture.** `AuthRepository.fetchVerificationStatus()`
(`lib/features/auth/data/auth_repository.dart:100`) lit déjà `GET /users/me` et
**ne décode que le champ attendu** — son cartouche explique pourquoi : elle est
relue à chaque retour au premier plan, et un champ sans rapport qui manquerait
ferait échouer une lecture dont tout le reste marchait. Le second champ se lit au
même endroit, avec la même prudence : absent ⇒ `null` ⇒ on garde ce qu'on
croyait savoir.

**b. L'enum**, dans `lib/models/auth_models.dart`, à côté de
`VerificationStatus` et sur son patron — un `switch` avec `default`, **et le
défaut est `SENT`**, comme ils le demandent. C'est le seul endroit de l'app où
un `default` est la bonne réponse : ici, la valeur inconnue vient d'un serveur
qui a le droit d'en ajouter (`COMPLAINED` est annoncé), et échouer sur elle
casserait une lecture qui n'en avait pas besoin.

**c. L'écran** (`verify_email_page.dart`), cinq phrases pour six états :

| État | Ce que l'écran dit |
|---|---|
| `NONE` · `PENDING` | l'e-mail part — rien à faire, l'écran surveille déjà |
| `SENT` | il est parti — s'il n'arrive pas, regardez vos indésirables |
| `DELIVERED` | **il est arrivé** — donc regardez vos indésirables : cette fois c'est une certitude, pas une politesse |
| `BOUNCED` | cette adresse a refusé le message — et la suite, qui dépend du §3 |
| `FAILED` | nous n'avons pas pu l'envoyer ; ce n'est pas votre adresse, réessayez plus tard |

**Ce que dit l'écran aujourd'hui est le vrai motif de ce lot**, et il est pire
que ce que nous avions en tête en écrivant le ticket :

> `authVerifyIntroLink` — *« Nous avons envoyé un lien à {email}. Ouvrez-le pour
> activer votre compte. »*

C'est une **affirmation**, faite à tout le monde, que rien ne soutient : ni le
`201` de l'inscription, ni le `200` du renvoi ne disent qu'un e-mail est parti.
Nous l'écrivons depuis le début à des gens dont l'envoi a peut-être été refusé à
la soumission. Les six états ne sont donc pas un raffinement d'affichage — ils
sont ce qui nous permet enfin de ne dire que ce que nous savons.

**d. Le drapeau.** `FeatureFlags.verificationDelivery`, **écrit éteint**, tenant
le point d'insertion et non l'intérieur du widget, entrant par le constructeur
de `VerifyEmailPage`, et injectable dans le dépôt. Il s'allume dans un commit
séparé, le jour où le champ est **relevé sur `/v3/api-docs` et rejoué en HTTP**
sur le compte de test — pas le jour où leur réponse l'annonce.

**e. Les traductions.** Cinq clés, trois fragments dans `lib/l10n/pending/`,
préfixe `verifdelivery` réservé dans son `README.md`. Le dossier est vide à la
livraison.

**f. Les tests.** Le dépôt avec un `Dio` réel et adaptateur capturant — verbe,
chemin, et surtout **la valeur inconnue qui doit rendre `SENT`** ; l'écran en
`ProviderContainer(overrides:)`, un cas par état ; et
`responsive_verify_email_test.dart`, parce que « cette adresse a refusé le
message » en allemand sur une borne basse est exactement le texte qui déborde.

---

## 5. La recette, corrigée

Leur §8.2 est accepté sans réserve : notre test aurait accusé une chaîne
correcte, et ils nous l'ont dit avant que nous ne l'écrivions.

**Les deux envois, dans cet ordre :**

1. **une boîte inexistante sur un domaine réel qui accepte le courrier** — le
   serveur distant accepte, puis refuse en `550`. Attendu : `BOUNCED`, par le
   chemin complet — envoi, identifiant conservé, webhook, signature, recoupement,
   compte. C'est le seul test qui prouve la chaîne ;
2. **`@example.invalid`** — attendu : `FAILED`. Il prouve l'autre branche, celle
   du refus à la soumission, celle-là même qui se produirait si le compte était
   resté en mode d'essai.

Sur le délai : nous ne ferons pas échouer la recette sur la marge du serveur
distant. Une minute est notre repère, pas notre critère.

**Et un troisième relevé, qui tranche leur propre réserve du §3.** Ils écrivent
ne pas savoir ce que vaut `RESEND_FROM_EMAIL` en production, et que si elle avait
été portée à `send.meetdo.fun` sans trace au dépôt, alors c'est la clé DKIM qui
manque et notre §3.1 décrit l'état courant. **Cette incertitude se lève depuis
l'extérieur, sans accès à leurs serveurs :** il suffit d'ouvrir un e-mail de
vérification reçu et d'en lire trois en-têtes.

```
From:                     ← infos@meetdo.fun, ou autre chose
Return-Path:              ← le domaine de l'enveloppe, celui que SPF vérifie
Authentication-Results:   ← spf=… dkim=… dmarc=… , avec les domaines testés
```

Nous le ferons, et nous leur rendrons le relevé. C'est trente secondes, et cela
leur épargne une variable d'environnement à aller chercher.

**Le DNS, après leur déploiement :** un `rua=` sur `_dmarc.meetdo.fun`, et SES
autorisé dans le SPF de `meetdo.fun` — vérifié en dépliant les `include:`, comme
la première fois, parce que c'est le dépliage qui avait montré le trou.

**Et le point 4 de notre §8, qu'ils confirment :** `POST /auth/register` rend
toujours `201` avec `accessToken`, `refreshToken`, `userId`, `displayName` et
`verificationStatus`. C'est le seul point de cette recette dont l'échec nous
coûterait plus que le défaut d'origine.

---

## 6. Ce que nous leur devons en retour

**Les trois phrases de l'e-mail, dans les trois langues.** Leur (f) traduit le
corps sur l'`Accept-Language` ; la machinerie est chez eux, mais le ton est chez
nous — nos catalogues portent déjà la voix de meetDo, et un e-mail de
vérification rédigé à part parlerait comme un autre produit. Nous leur envoyons
`fr`, `en`, `de` plutôt que de les laisser traduire un littéral français.

Leur aveu sur le renvoi — la langue sera celle de la requête de renvoi, pas celle
de l'inscription — ne nous pose aucun problème : nous envoyons
l'`Accept-Language` sur toutes nos requêtes, et pour un même appareil c'est la
même. Créer une préférence de langue au compte pour ce seul usage serait,
comme ils l'écrivent, un second endroit où la même information peut diverger.

---

## 7. Récapitulatif

| | Leur livrable | Ce que ça nous coûte |
|---|---|---|
| **a** | `rua=` sur le DMARC | rien |
| **b** | SES au SPF de l'apex | rien — un relevé après déploiement |
| **c** | L'e-mail par l'outbox, identifiant conservé | rien, et l'inscription cesse d'attendre un aller-retour HTTP |
| **d** | `verificationEmailDelivery`, six valeurs | un enum, cinq clés, un drapeau, trois tests — **§4** |
| **e** | Limiteurs par adresse **et** IP, `register` compris | rien, **à condition que le `429` porte un `code` et un message** — §1 |
| **f** | L'e-mail traduit | rien, et nous fournissons les trois phrases — §6 |
| **g** | Suppression du second expéditeur | rien |
| **h** | **Corriger l'adresse d'un compte** — à demander | sans lui, `BOUNCED` est une impasse — **§3** |

Et les quatre réponses de leur §2 — acceptés contre rebonds, répartition par
domaine, message de refus littéral, mode d'essai — restent **la seule chose qui
peut rendre les sept autres sans objet**. Nous n'écrirons pas une ligne de (d)
avant de les avoir.

---

*Une note pour finir. Leur réponse s'achève sur le constat qu'ils avaient écrit
l'instrumentation six jours avant notre ticket, pour un autre canal, sans se
demander où elle servait ailleurs. Notre §2 est de la même famille : nous avons
transposé un contrat qu'ils avaient livré, de mémoire, et nous en avons perdu la
valeur qui portait exactement la distinction que nous réclamions. Des deux
côtés, ce n'est pas l'outil qui manquait — c'est de l'avoir relu.*
