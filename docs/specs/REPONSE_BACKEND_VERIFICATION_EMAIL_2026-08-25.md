# Réponse — le lien de vérification

> Réponse à `PROMPT_BACKEND_VERIFICATION_EMAIL_2026-08-25.md`.
>
> Votre déduction était juste sur les deux points, et vous avez trouvé depuis
> l'extérieur ce que nous n'avions pas vu de l'intérieur. **C'est corrigé, et
> pas seulement là où vous l'aviez localisé** : en suivant la piste, nous avons
> trouvé un troisième défaut, invisible à vos mesures, qui aurait rendu le
> correctif inopérant une fois sur deux.

---

## 1. La propriété que vous nous demandiez de nommer

`email.base-url`, dans `application.properties` :

```properties
email.base-url=${APP_BASE_URL:http://localhost:3000}
```

`APP_BASE_URL` n'a **jamais** été posée en production. `FRONTEND_URL`, que vous
aviez vérifiée, alimente `app.frontend-url` — une autre propriété, servant aux
gabarits d'e-mail de notification. Vous cherchiez au bon endroit ; il y en avait
deux.

Chaque e-mail de vérification est donc parti avec un lien vers la machine du
destinataire, depuis la mise en production.

**Ce que nous avons changé** : le profil `railway` porte désormais son propre
défaut, qui est la vraie valeur de production et non plus une valeur de
développement à surcharger.

```properties
email.base-url=${APP_BASE_URL:https://lien.meetdo.fun}
```

La nuance compte plus que la valeur : l'oubli d'une variable d'environnement
redonne maintenant le bon comportement au lieu du pire. C'est exactement l'oubli
qui a produit ce ticket.

---

## 2. Le lien pointe sur l'API, et l'API rend une page

Nous suivons votre proposition, telle quelle. Le lien vaut désormais :

```
https://lien.meetdo.fun/api/auth/verify-email?token=…
```

Et la route distingue qui l'appelle :

| Appelant | En-tête `Accept` | Réponse |
|---|---|---|
| l'application mobile | `application/json` | **contrat inchangé** — 200, ou 401 `INVALID_TOKEN` |
| un navigateur | `text/html` | une page |

Nous arbitrons explicitement sur l'en-tête plutôt que par la négociation de
contenu du framework : avec deux gestionnaires sur le même chemin, un
`Accept: */*` — ce qu'envoient beaucoup de clients — devient ambigu, et
l'arbitrage se ferait sans nous.

**Les quatre états**, et non trois. Vous en demandiez trois ; il en manquait un,
et c'est celui qui produit le plus de courrier au support :

| Cas | Ce que la page dit |
|---|---|
| Jeton valide | Compte vérifié, retournez dans l'application |
| **Jeton déjà utilisé** | **Le lien a fonctionné, votre compte est actif, il n'y a rien à faire** |
| Jeton expiré | Les liens durent 24 h, en redemander un depuis l'app |
| Jeton inconnu | Le lien est peut-être tronqué par la messagerie ; sinon, en redemander un |

Le second cas est le vôtre, transposé : quelqu'un qui reclique sur son lien — par
réflexe, ou parce qu'il n'a pas vu la première page — recevait « jeton
invalide ». Son compte fonctionnait. C'est le même piège que le JSON en pleine
page, une couche plus loin.

La page rend **200 dans les quatre cas**. Un code d'erreur exposerait le message
à être remplacé par la page d'erreur d'un intermédiaire, c'est-à-dire à ne
jamais atteindre la personne à qui il est destiné.

---

## 3. Le troisième défaut — celui que vos mesures ne pouvaient pas voir

**Les jetons vivaient dans la mémoire du conteneur**, quatre
`ConcurrentHashMap` d'instance. Aucun n'atteignait la base.

Conséquence : **tout redéploiement invalidait d'un coup tous les liens en
circulation.** Le testeur qui s'inscrivait le matin et cliquait l'après-midi
recevait « jeton invalide », sans que rien ne distingue ce cas d'un vrai faux
jeton.

Ce défaut ne se voit pas en développement, où l'on ne redéploie pas entre
l'inscription et le clic. Il ne se voyait pas non plus depuis l'extérieur : vous
testiez avec un jeton volontairement invalide, et la réponse était correcte.

Il était sur le point de nous coûter cher pour une autre raison : **nous
déplaçons la base de données cette semaine**, ce qui redémarre le service. Nous
aurions livré la correction du lien, redémarré, et invalidé tous les liens
émis entre les deux — en concluant, à tort, que le correctif ne marchait pas.

Les jetons sont désormais en base (`auth_tokens`, migration V79), avec :

- une **échéance** explicite, au lieu d'une carte parallèle qui pouvait en
  diverger ;
- un **`consumed_at`** plutôt qu'une suppression — c'est ce qui permet de
  distinguer « déjà vérifié » de « inconnu », les deux messages que vous nous
  demandiez de ne pas confondre ;
- la **fermeture des jetons précédents** à chaque nouvel envoi : deux liens
  actifs pour une même adresse laisseraient l'utilisateur choisir au hasard.

Les jetons de réinitialisation de mot de passe avaient le même défaut, dans la
même classe. Ils sont traités avec, dans la même table : deux tables jumelles
auraient fini par diverger.

---

## 4. `POST /auth/resend-verification` — il n'existait pas

Votre tableau le donne comme déjà exposé. Ce n'était pas le cas : la route
n'existait nulle part dans le code.

Comme la page « lien expiré » a besoin qu'on puisse effectivement en redemander
un, nous l'avons créée :

```
POST /api/auth/resend-verification    { "email": "…" }
```

Elle répond **toujours 200**, y compris pour une adresse inconnue ou déjà
vérifiée — comme `/forgot-password`, et pour la même raison : un code distinct
dirait à qui essaie des adresses lesquelles sont inscrites. Elle est bornée à
3 appels par heure et par IP.

---

## 5. Votre question du §4 — nous ne la tranchons pas seuls

Vous demandez si le lien doit un jour ouvrir l'app plutôt qu'un navigateur.

**Ce que nous avons livré ne ferme aucune des deux portes.** La page web existe
et fonctionne aujourd'hui ; si vous ajoutez `verify` à votre routeur de liens,
`lien.meetdo.fun` étant déjà déclaré en domaine associé, l'app interceptera le
lien avant le navigateur et appellera la même route en JSON — dont le contrat
n'a pas bougé. La page devient alors le repli pour qui n'a pas l'app installée,
ce qui est exactement son rôle dans ce cas.

Autrement dit, nous n'avons pas besoin de la réponse pour avancer, et vous
pouvez décider plus tard sans que rien ne soit à défaire. Notre préférence,
sans insistance : garder la page comme repli permanent, parce qu'un lien de
vérification arrive souvent sur un appareil qui n'est pas celui où l'app est
installée.

---

## 6. Vos deux autres points

### `NotificationFrequency.DAILY` — corrigé, et ce n'était pas un héritage

Vous supposiez « des lignes en base héritées d'une version antérieure de
l'énumération ». C'est plus embarrassant que cela : **la valeur est écrite par
nos propres migrations de semis**, `V12`, `V13` et `V27`, qui insèrent
`'DAILY'` dans `notification_prefs.frequency`. L'énumération n'a jamais eu cette
valeur ; les migrations n'ont jamais été alignées sur elle.

Comme ces migrations s'appliquent à toute base neuve, le défaut se reproduit à
chaque déploiement sur une base vierge — il n'a rien d'un résidu, et il serait
revenu de lui-même après le déplacement de base de cette semaine.

**C'est corrigé dans ce lot** (migration `V80`), en deux gestes :

1. les lignes existantes passent à `DAILY_DIGEST` — la valeur que votre
   application envoie déjà, et celle que ces préférences ont toujours voulu
   dire ;
2. une contrainte `CHECK` empêche désormais qu'une valeur inconnue de
   l'énumération entre dans la colonne.

Deux choix que nous préférons expliquer plutôt que vous laisser les découvrir :

**Nous n'avons pas réécrit `V12`, `V13` et `V27`.** Elles sont appliquées
partout, et modifier une migration déjà jouée est une habitude qui finit par
coûter cher. Ce n'est pas nécessaire : Flyway applique dans l'ordre, donc sur
une base neuve la correction passe après les trois semis et les rattrape aussi.

**La contrainte est posée `NOT VALID`**, c'est-à-dire qu'elle contrôle les
écritures futures sans vérifier les lignes existantes. Une contrainte validée
aurait fait échouer la migration s'il subsistait une autre valeur inconnue
quelque part — et un échec de migration empêche le service de démarrer. Bloquer
la production pour se protéger d'une donnée hypothétique nous a paru un mauvais
échange. Elle pourra être validée après inspection de la table.

Le défaut n'est jamais venu de l'application : `@Enumerated(STRING)` ne peut
écrire qu'un nom de constante. Il est venu du SQL, le seul chemin où rien ne
vérifiait rien. C'est ce chemin que la contrainte ferme.

Votre lecture du symptôme mérite d'être répétée : l'envoi étant asynchrone,
**rien ne remonte à l'utilisateur**. Les comptes touchés cessent de recevoir
leurs notifications, et personne ne peut le constater autrement qu'en lisant nos
journaux. Nous vous devons ce signalement.

### Le stockage des médias

Bien reçu, et merci de l'avoir instrumenté aussi vite. `/data/uploads` était en
effet un troisième chemin, que notre diagnostic n'avait pas envisagé — nous
avions comparé le point de montage du volume à ce que le `Dockerfile` déclarait,
et pas à ce que l'application faisait réellement. Le signal de démarrage vous a
donné la réponse en une ligne ; nous en ajouterons d'autres du même genre.

---

## 7. Ce que nous vous demandons de vérifier

Après déploiement, un aller-retour complet :

1. inscrire une adresse de test, cliquer le lien reçu — vous devez voir une page,
   pas du JSON ;
2. recliquer le même lien — « votre adresse était déjà vérifiée », et surtout
   pas « lien inconnu » ;
3. l'app, elle, doit continuer à recevoir 200 et 401 `INVALID_TOKEN` comme
   avant. C'est le point où une régression nous échapperait le plus facilement,
   et c'est vous qui la verriez en premier ;
4. et, sur les notifications : plus aucune occurrence de
   `No enum constant NotificationFrequency.DAILY` dans nos journaux. Si vous en
   voyez une après déploiement, elle vient d'un chemin que nous n'avons pas
   trouvé.

Le point 3 nous importe plus que les deux autres : la page est nouvelle et ne
peut rien casser, le contrat JSON est ancien et porte votre application.

---

*Une remarque pour finir, parce qu'elle a changé l'issue de ce ticket. Vous
auriez pu nous écrire « le lien ne marche pas ». Vous avez écrit quelles URL
vous aviez essayées, avec quels codes de retour, et laquelle fonctionnait. C'est
la ligne `401 INVALID_TOKEN` sur la route d'API qui nous a fait chercher la
propriété plutôt que la route — et c'est en la cherchant que nous avons trouvé
les jetons en mémoire, qui n'avaient rien à voir avec votre ticket et vous
auraient bloqués la semaine prochaine.*
