# Réponse au ticket du 07/09 — le webhook que vous demandez est écrit depuis le 1er septembre, et l'e-mail de vérification est le seul courrier qui ne passe pas par lui

**Date :** 2026-09-07
**Fait suite à :** `PROMPT_BACKEND_2026-09-07.md`

> **Votre §1 est acquis, et nous n'y revenons pas** : il n'y a rien à chercher
> côté app. Nous ajoutons même une correction en votre défaveur — l'envoi n'est
> pas asynchrone chez nous, contrairement à ce que vous supposiez, et cela
> explique une partie de ce que vous observez sans expliquer le symptôme (§7).
>
> **Le livrable (c) est aux trois quarts fait, et vous ne pouviez pas le
> savoir.** Le webhook de rebond Resend est écrit, signé, routé et branché depuis
> le lot du 1er septembre. Ce qui manque tient en une ligne : l'e-mail de
> vérification est le seul de nos courriers qui **jette l'identifiant Resend** au
> lieu de le garder. Un rebond le concernant arrive donc bien chez nous, ne
> trouve rien à recouper, et repart en silence — §1.
>
> **Votre §4 est le seul endroit où nous vous devons une réponse que le code ne
> porte pas**, et nous ne l'avons pas encore : les trois chiffres et la question
> du mode d'essai demandent le tableau de bord, pas le dépôt. Nous ne les
> inventons pas — §2.
>
> **Votre question du (b) a une réponse, et elle est dans le code** : le `From:`
> est sur l'apex. C'est donc l'apex à qui il manque l'autorisation de SES — §3.
>
> **Vos deux fils du §6 ont chacun une réponse, et les deux sont mauvaises pour
> nous** : le second expéditeur n'a pas été traité malgré notre engagement du
> 26/08, et la langue de l'e-mail est pire que votre hypothèse — §6.
>
> **Enfin, une correction sur votre recette** : votre test à
> `@example.invalid` échouerait sur une chaîne pourtant correctement branchée,
> et nous préférons vous le dire avant que vous ne l'écriviez — §8.

---

## 1. Le renversement : (c) est déjà là, sauf pour l'e-mail qui vous intéresse

Vous nous citez notre phrase du 01/09 — *« le vrai délivré demanderait de
brancher les webhooks du fournisseur »* — et vous en tirez, très
raisonnablement, qu'il n'y a rien. Vous concluez : *« c'est vrai de tout votre
courrier sortant, e-mail de vérification compris. »*

**La première moitié de cette phrase n'est plus vraie ; la seconde l'est
entièrement.** C'est une distinction inconfortable pour nous, et il faut
l'écrire précisément.

Depuis le lot du 1er septembre, il existe chez nous :

| Pièce | Où | Ce qu'elle fait |
|---|---|---|
| `POST /public/resend-webhook` | `ResendWebhookController.java:43` | reçoit les accusés Resend |
| Vérification de signature Svix | `ResendWebhookVerifier.java` | 401 sous profil de déploiement si la signature manque ou ne concorde pas |
| Route publique déclarée | `SecurityConfig.java:118` | le fournisseur appelle sans identité meetDo |
| `OutboxService.recordDelivery` | `OutboxService.java:95` | traduit `email.delivered` / `bounced` / `complained` / `delivery_delayed` en état |
| L'axe d'état lui-même | `OutboxDelivery.java` | `UNKNOWN · DELIVERED · DELAYED · BOUNCED · COMPLAINED` |

Le webhook fonctionne. Il rapporte les rebonds. Il ne les rapporte simplement
**que pour les messages passés par l'outbox** — c'est-à-dire les alertes de
veille, pour lesquelles il a été écrit.

### Ce qui manque, précisément

`recordDelivery` recoupe l'accusé par `outbox_messages.provider_message_id`.
L'e-mail de vérification n'emprunte pas l'outbox : son chemin est direct, et
synchrone.

```
AuthService.register:46
  → EmailVerificationService.sendVerificationEmail:41
    → EmailService.sendVerificationEmail:50
      → ResendEmailService.sendHtmlEmail:50
```

Et cette dernière ligne est tout le défaut :

```java
public boolean sendHtmlEmail(String to, String subject, String htmlContent) {
    return sendHtmlEmailReturningId(to, subject, htmlContent) != null;   // :51
}
```

L'identifiant Resend **est obtenu, puis converti en booléen et jeté**. La méthode
qui le conserve existe, juste en dessous, et son commentaire dit exactement
pourquoi elle existe :

> *« Cet identifiant est la clé de recoupement des webhooks de remise. Sans lui,
> on saurait qu'un envoi a été accepté, jamais s'il est arrivé. »*

Nous l'avons écrit pour les veilles, et nous ne l'avons pas appliqué à
l'inscription. Un `email.bounced` visant un e-mail de vérification est donc reçu
par notre serveur, authentifié, lu, cherché en base — et abandonné faute de
ligne correspondante. **La panne que vous décrivez traverse un point de notre
code qui sait quoi en faire, et qui ne peut pas le savoir.**

C'est la raison pour laquelle nous prenons (c) sans discuter : ce n'est pas un
chantier, c'est une continuité à rétablir.

---

## 2. Votre §4 — ce que nous vous devons et n'avons pas encore

Vous demandez trois chiffres et une réponse binaire. **Aucun des quatre ne se lit
dans le dépôt**, et nous ne les approcherons pas par déduction : ce sont
précisément les seules mesures qui trancheraient, et une valeur plausible mais
fausse coûterait plus cher que l'absence.

Ce que nous vous devons, en attente :

1. acceptés contre rebonds sur 30 jours, e-mails de vérification seuls ;
2. **la répartition des échecs par domaine destinataire** — votre hypothèse
   `web.de` / `gmx.de` / `t-online.de` est la première que nous irons regarder,
   et votre raisonnement pour la former est le bon ;
3. le message de refus littéral d'un rebond ;
4. **le compte est-il entièrement sorti du mode d'essai ?**

Sur ce dernier point, une remarque qui a son poids : vous écrivez que ce serait
« la seule cause qui produirait exactement ce symptôme sans qu'aucun rebond
n'apparaisse nulle part ». **Vous avez raison, et notre §1 la rend indétectable
deux fois plutôt qu'une** — un compte en mode d'essai fait refuser l'envoi par
l'API elle-même, ce que `sendHtmlEmail` transforme en `false`, que
`EmailService.sendVerificationEmail:67` journalise en `log.error`, et que
personne ne lit. Le refus est écrit dans nos journaux depuis le début, à une
ligne que rien ne surveille.

Nous vous rendons ces quatre réponses dès que quelqu'un a ouvert le tableau de
bord. Elles ne bloquent aucun des livrables ci-dessous.

---

## 3. Livrable (b) — vous demandiez lequel des deux domaines : c'est l'apex

Votre relevé est exact, et il vous manquait une seule information pour conclure
vous-mêmes : **quel domaine figure réellement dans le `From:`**.

C'est `infos@meetdo.fun` — l'apex. La valeur est le défaut de
`ResendEmailService.java:25`, repris tel quel dans
`application-railway.properties:31`.

Donc, en reprenant votre §3.2 : le domaine du `From:` est celui qui **porte la
clé DKIM** et **dont le SPF ignore SES**. La moitié manquante est l'autorisation
d'expédition, sur l'apex.

Nous retenons l'apex — c'est le domaine déjà écrit dans le `From:`, déjà porteur
de DKIM, et le déplacer vers `send.meetdo.fun` demanderait de changer à la fois
le DNS et une variable de production pour arriver au même endroit. Vous
n'attendiez pas d'avis, seulement qu'il n'y en ait qu'un : **`meetdo.fun`, et
son SPF gagne SES.**

**Une réserve que nous devons poser.** La valeur est surchargeable par la
variable `RESEND_FROM_EMAIL`, et le dépôt ne dit pas ce qu'elle vaut en
production. Nous confirmons cette ligne — ou nous la corrigeons — en même temps
que les chiffres du §2. Si elle avait été portée à `send.meetdo.fun` sans que le
dépôt en garde trace, alors c'est la clé DKIM qui manque, et votre §3.1 décrit
non plus un risque futur mais l'état courant.

---

## 4. Livrable (a) — le `rua=`

Pris, et pris en premier comme vous le demandez. Votre argument est le bon : il
ne change rien au courrier qui part, et il rend observable dès le lendemain ce
qui ne l'est pas aujourd'hui. Nous n'avons rien à y ajouter, sinon que c'est le
seul livrable de ce ticket dont la valeur ne dépend d'aucun des autres.

---

## 5. Livrable (d) — l'état de remise, et deux corrections à votre contrat

`GET /api/users/me` portera `verificationEmailDelivery`, sur le compte du porteur
et sur lui seul, sans journal ni horodatage. La transposition d'`alertDelivery`
que vous proposez est la bonne : l'agrégation existe déjà
(`WatchService.deliveryOf:324`) et nous reprenons son vocabulaire.

**Deux points où votre contrat, tel qu'écrit, ne conviendrait pas.**

### 5.1 · Il vous manque `DELIVERED`, qui est la seule bonne nouvelle

Vous listez `NONE | PENDING | SENT | BOUNCED | FAILED`. Notre agrégation produit
aussi **`DELIVERED`**, et c'est le seul état qui dise « arrivé dans la boîte »
plutôt que « accepté par le fournisseur » — c'est-à-dire exactement la
distinction pour laquelle vous nous écrivez.

Le contrat sera donc :

```
GET /api/users/me
{ …, "verificationEmailDelivery":
      "NONE" | "PENDING" | "SENT" | "DELIVERED" | "BOUNCED" | "FAILED" }
```

Traitez toute valeur inconnue comme `SENT` plutôt que d'échouer : c'est la règle
que vous appliquez déjà à `onboardingStep`, et elle nous laisse ajouter
`COMPLAINED` un jour sans vous casser.

### 5.2 · L'état vivra sur le compte, pas sur la ligne d'envoi

Notre outbox purge le corps des messages partis depuis sept jours
(`OutboxSweepJob.java:56`) — un message d'alerte porte un nom, un lieu, une
heure, et n'a pas à s'attarder. Un `verificationEmailDelivery` calculé depuis
cette table retomberait donc à `NONE` au huitième jour, ce qui ferait dire à
votre écran « rien n'est parti » d'un compte dont l'e-mail avait rebondi.

L'état sera porté par le compte lui-même. Cela ne change rien à ce que vous
lisez ; nous l'écrivons parce que c'est le genre de détail qui, laissé au
hasard, produit un second ticket dans un mois.

---

## 6. Livrable (e) — accepté, et nous vous en proposons un second

Vous avez raison sur les faits : `checkResendVerification` est bien par IP seule,
trois par heure (`RateLimiter.java:63-64, 135-138`).

Ce que vous ne pouviez pas voir : **le patron que vous demandez existe déjà dans
le même fichier**, à quarante lignes de là. `checkLogin(ip, email)`
(`RateLimiter.java:93-103`) porte un budget serré sur le compte et un plafond
large sur l'adresse, et son commentaire tient votre argument mot pour mot :

> *« Une adresse IP n'identifie pas une personne : derrière un partage de
> connexion, un NAT d'entreprise ou un relais, elle en désigne des dizaines. La
> borner seule fait qu'un compte en bloque un autre sans que ni l'un ni l'autre
> n'ait rien fait d'anormal. »*

Nous l'avions écrit pour la connexion le 1er septembre, après que le même défaut
vous a bloqués. Nous ne l'avons pas propagé aux deux routes voisines. `resend`
passe donc par adresse **et** par IP, l'IP gardant un plafond plus haut, comme
vous le proposez.

**Et nous vous en offrons un second, que vous n'avez pas demandé.**
`checkRegister` est à **cinq inscriptions par heure et par IP**
(`RateLimiter.java:61`). C'est le même défaut, un cran plus haut : il ne punit
pas un renvoi, il empêche la sixième personne d'un groupe de créer son compte.
Vous écrivez que s'inscrire ensemble « est le mode d'arrivée normal sur
meetDo » — si c'est vrai, cette borne-là vous coûte davantage que celle que vous
signalez, et elle rend un 429 là où votre écran attend un 201. Nous la traitons
dans le même geste, sauf avis contraire de votre part.

*Une bonne nouvelle au passage :* `server.forward-headers-strategy=framework` est
posé (`application-railway.properties:18`), donc l'adresse vue par le limiteur
est bien celle du client et non celle du proxy Railway. Votre scénario est réel,
mais il n'est pas aggravé par un défaut de configuration : sans cette ligne,
**tous** vos utilisateurs auraient partagé un seul quota.

---

## 7. Vos deux fils du §6 — les deux réponses sont à notre charge

### 7.1 · Le second expéditeur : non, il n'a pas été traité

Vous demandez : *« A-t-il été traité, et est-il toujours sans appelant ? »*

**Non, et oui.** `EmailTemplateService.sendEmailVerification`
(`domain/email/EmailTemplateService.java:157`) construit toujours son lien sur
`app.frontend-url + "/verify-email?token="`, et `app.frontend-url` vaut encore
`http://localhost:3000` par défaut sous le profil de déploiement
(`application-railway.properties:35`). La classe entière — ses quatre méthodes,
dont un e-mail de bienvenue et une réinitialisation de mot de passe — **n'a
aucun appelant.**

Nous écrivions le 26/08 : *« Nous le traiterons pour lui-même. »* Nous ne l'avons
pas fait. Votre question est donc juste, et la réponse honnête est que
l'engagement n'a pas été tenu.

Il ne produit pas votre symptôme, puisqu'il ne s'exécute jamais. Mais il reste ce
que nous en disions : le défaut du 25 août, endormi. Nous ne le retraiterons pas
une seconde fois par une promesse — **nous le supprimons.** Une classe morte qui
ne demande qu'un appelant pour renvoyer des liens dans le vide n'a aucune raison
d'attendre son réveil dans le dépôt.

### 7.2 · La langue : ni l'un ni l'autre, et pire que votre hypothèse

Vous demandez si la langue vient de l'`Accept-Language` de la requête ou d'une
préférence de compte lue après coup.

**Ni l'une ni l'autre.** Le corps de l'e-mail de vérification est un littéral
français en dur (`EmailService.java:56-63`) : « Vérifiez votre adresse email »,
« Ce lien expire dans 24 heures ». Aucune injection de `Messages`, aucune lecture
de locale. Un utilisateur allemand ne reçoit pas un e-mail en anglais — il en
reçoit un en français.

Votre raisonnement s'applique donc entièrement, et un cran plus fort : quelqu'un
dont l'écran s'appelle *Registrieren* et qui reçoit du français a toutes les
raisons de classer le message en indésirable sans le lire, et aucune de vous
signaler qu'il l'a reçu.

Ce qui rend la chose coûteuse pour rien, c'est que la machinerie existe et
fonctionne ailleurs : `LocaleConfig.AcceptLanguageLocaleResolver` lit déjà
l'en-tête, `messages_de.properties` et `messages_en.properties` sont peuplés, et
`Messages` sert les erreurs traduites que vous recevez sur toutes vos autres
routes. Le corps de l'e-mail est simplement passé à côté.

Nous le traduisons dans ce lot, sur l'`Accept-Language` de la requête
d'inscription — c'est l'appareil qui parle, et c'est celui que vous envoyez sur
toutes vos requêtes. Une nuance dont nous vous devons l'aveu : au renvoi
(`resend-verification`), la langue sera celle de la requête de renvoi, pas celle
de l'inscription. Pour le même appareil c'est la même ; nous ne stockons pas de
préférence de langue au compte, et en créer une pour ce seul usage serait un
second endroit où la même information pourrait diverger.

---

## 8. Ce que vous ne pouviez pas voir, et une correction à votre recette

### 8.1 · L'envoi n'est pas asynchrone

Votre §1 dit : *« L'envoi de l'e-mail est entièrement chez vous, et il est
asynchrone. »* La première moitié est exacte. **La seconde ne l'est pas.**

`AuthService` est transactionnel au niveau de la classe
(`AuthService.java:24`), et l'appel bloquant vers `api.resend.com`
(`ResendEmailService.java:138`) part **à l'intérieur de la transaction
d'inscription** — connexion base tenue pendant tout l'aller-retour HTTP, avec un
service en Europe et une base à San Francisco.

Cela **ne cause pas** votre symptôme : l'échec est avalé, et le 201 part de toute
façon, exactement comme vous le décrivez au §2a. Mais c'est ce qui fait qu'une
inscription est parfois lente sans raison visible, et c'est un défaut qu'on ne
corrige pas seul.

Il se referme avec (c) : faire passer l'e-mail de vérification par l'outbox rend
l'envoi asynchrone par construction — le message est posé en base dans la
transaction, le balayage le remet au fournisseur dix secondes plus tard
(`OutboxSweepJob.java:40`), et l'identifiant Resend est conservé au passage.
**Un seul geste referme le trou d'observabilité, la latence, et l'écart entre ce
que vous supposiez et ce qui se passe.** C'est pour cela que nous ne traitons pas
(c) et (d) séparément.

### 8.2 · Votre point 3 du §8 échouerait sur une chaîne correcte

Vous écrirez : *« sur une adresse volontairement fausse en `@example.invalid`, il
passe à `BOUNCED` dans la minute — c'est le seul test qui prouve que la chaîne
complète est branchée. »*

**Le test est le bon ; l'adresse ne l'est pas.** `.invalid` est un TLD réservé
qui n'a pas de MX et n'en aura jamais. Selon toute vraisemblance, l'API Resend
refusera l'envoi à la soumission, sans jamais créer de message — donc sans
identifiant, donc sans rebond, et **le champ passera à `FAILED`, pas à
`BOUNCED`**. Vous concluriez que la chaîne n'est pas branchée alors qu'elle le
serait ; nous chercherions ensemble un défaut qui n'existe pas.

Prenez plutôt **une boîte inexistante sur un domaine réel qui accepte le
courrier** — le serveur distant accepte, puis refuse en `550`, ce qui produit un
vrai rebond dur et fait passer le champ à `BOUNCED` par le chemin complet :
envoi, identifiant conservé, webhook, signature, recoupement, compte.

Et gardez `@example.invalid` comme second test, avec l'attendu `FAILED` : il
prouve l'autre branche, celle du refus à la soumission — celle-là même qui se
produirait si le compte était resté en mode d'essai.

Sur « dans la minute » : le dépôt à l'outbox est immédiat, le balayage tourne
toutes les dix secondes. Le délai restant est celui du serveur distant et de
Resend, que nous ne maîtrisons pas. Une minute est raisonnable pour un rebond
dur ; ne faites pas échouer la recette sur une marge que personne ne contrôle.

### 8.3 · Votre point 4 : `register` ne bouge pas

Confirmé, et nous partageons votre appréciation du risque. `POST
/api/auth/register` continue de rendre 201 avec `accessToken`, `refreshToken`,
`userId`, `displayName` et `verificationStatus`. Ce qui change est en amont du
`return` et n'en modifie ni la forme ni le code : l'envoi passe de « appel HTTP
bloquant » à « ligne posée en base ». C'est un chemin plus court, pas plus long.

---

## 9. Ce que nous livrons, et dans quel ordre

Dans l'ordre où cela devient utile pour vous, et non dans celui de notre confort :

| | Livrable | Nature |
|---|---|---|
| **a** | `rua=` sur le DMARC de `meetdo.fun` | DNS |
| **b** | SES ajouté au SPF de `meetdo.fun` — l'apex, qui porte déjà DKIM | DNS |
| **c** | L'e-mail de vérification passe par l'outbox, identifiant conservé, webhook existant qui le recoupe | code |
| **d** | `verificationEmailDelivery` sur `GET /api/users/me`, six valeurs | code |
| **e** | `resend-verification` borné par adresse **et** par IP — et `register` avec lui | code |
| **f** | L'e-mail de vérification traduit sur l'`Accept-Language` | code |
| **g** | Suppression du second expéditeur | code |

Les quatre réponses de votre §4 — les trois chiffres et le mode d'essai —
viennent séparément, dès que le tableau de bord a été ouvert. Elles ne
conditionnent aucun de ces sept points, mais elles peuvent en rendre un
inutile : si le compte est en mode d'essai, tout ce qui précède est juste et ne
change rien tant que ce point-là n'est pas réglé.

---

## 10. Ce que nous vous demandons en retour — rien, sauf une chose

Vous n'avez rien à modifier pour recevoir ces livraisons. Un seul point mérite
votre décision :

**La borne d'inscription (§6, second paragraphe).** Nous proposons de passer
`register` de « cinq par heure et par IP » à un couple adresse + IP. Si votre
écran d'inscription a déjà un traitement particulier du 429, dites-nous lequel
avant que nous ne changions le seuil sous vos pieds — c'est le genre de
correction qui améliore la situation générale en déplaçant un cas limite que vous
aviez peut-être appris à contourner.

---

*Une remarque en retour de la vôtre. Vous écrivez que ce ticket n'a pas
d'utilisateur qui se plaint, mais des utilisateurs qui ne reviennent pas, et que
la différence est une affaire d'instrumentation. Nous ajoutons ceci, qui nous
concerne seuls : nous avions écrit l'instrumentation. Nous l'avions écrite six
jours avant votre ticket, pour un autre canal, avec un commentaire expliquant
qu'un envoi accepté n'est pas un envoi arrivé — et nous ne l'avons pas
appliquée à la première minute de chaque utilisateur. Le trou n'était pas qu'il
manquait un outil ; il était que nous ne nous sommes pas demandé où d'autre il
servait.*
