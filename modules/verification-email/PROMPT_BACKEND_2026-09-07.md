# Une partie des inscrits ne reçoit jamais l'e-mail de vérification — et rien, nulle part, ne permet de l'apprendre

**Date :** 2026-09-07

> **Le symptôme, tel qu'il nous remonte :** sur l'écran d'inscription, certains
> comptes reçoivent l'e-mail de vérification et d'autres ne le reçoivent jamais.
> Même version d'app, même écran, même geste.
>
> **Côté app, il n'y a rien à chercher, et nous l'avons vérifié avant de vous
> écrire :** l'inscription est un unique `POST /api/auth/register`. L'app ne
> compose aucun e-mail, n'en demande pas l'envoi, et aucun drapeau ni aucune
> branche ne peut faire qu'un compte soit servi et pas un autre — §1.
>
> **Ce que nous ne pouvons pas trancher d'ici, c'est la suite**, et c'est le
> cœur de ce prompt : **trois silences empilés** font que ni vous ni nous
> n'apprenons qu'un e-mail n'est pas arrivé — §2.
>
> **Un relevé DNS de ce jour**, que nous vous livrons brut, montre deux trous
> réels dans la configuration d'envoi — §3.
>
> **Ce que nous vous demandons** tient en un diagnostic que vous seuls pouvez
> faire (§4) et quatre livrables, dont trois sont des enregistrements DNS ou un
> webhook que vous nous aviez vous-mêmes proposé le 1er septembre (§5).

---

## 1. Ce que fait l'app, pour clore cette piste tout de suite

`RegisterPage._submit()` (`lib/features/auth/presentation/register_page.dart:165`)
appelle `AuthRepository.register()`
(`lib/features/auth/data/auth_repository.dart:44`), qui fait exactement ceci et
rien d'autre :

```
POST /api/auth/register
{ "email": "…", "password": "…", "displayName": "…" }
```

L'adresse est `trim()`ée avant l'envoi, à la saisie comme à la soumission. Il
n'existe **aucun** chemin client conditionnel entre le formulaire et cet appel :
pas de drapeau de fonctionnalité, pas de branche par domaine, pas de retry, pas
d'appel à `resend-verification` derrière. L'envoi de l'e-mail est entièrement
chez vous, et il est asynchrone.

Nous l'écrivons noir sur blanc parce que la première hypothèse d'un ticket comme
celui-ci est toujours « le client n'a pas envoyé la demande ». Ici, il n'y a pas
de demande à envoyer.

---

## 2. Pourquoi ce défaut est invisible des deux côtés

Trois silences, chacun défendable seul, qui empilés rendent la panne
inobservable :

**a. `POST /auth/register` rend 201 avec un `accessToken`.** L'utilisateur est
connecté immédiatement et atterrit sur notre écran de vérification. Que
l'e-mail soit parti, refusé ou jamais composé, la réponse est la même et l'app
ne peut pas faire la différence.

**b. `POST /auth/resend-verification` rend toujours 200**, y compris pour une
adresse inconnue ou déjà vérifiée. C'est votre choix du 25 août et nous ne le
contestons pas — il protège l'énumération des comptes. Mais il a une
conséquence directe : quand l'utilisateur retouche « renvoyer l'e-mail », nous
lui affichons « e-mail renvoyé » sur la foi d'un 200 qui ne dit rien de l'envoi.

**c. Votre propre réponse du 01/09 pose la troisième pierre**
(`modules/tracabilite/REPONSE_BACKEND_2026-09-01-BIS.md`, §4.4) :

> *« Honnêtement : ce n'est pas encore le "délivré" plein. `SENT` veut dire
> "accepté par le fournisseur d'e-mail", pas "arrivé dans la boîte". Le vrai
> délivré — et les rebonds — demanderait de brancher les webhooks du
> fournisseur. »*

Vous l'écriviez pour les alertes de veille. **C'est vrai de tout votre courrier
sortant, e-mail de vérification compris.** Un rebond dur, un refus de connexion
ou un classement en indésirable ne remonte donc nulle part : ni dans vos
journaux, ni dans l'API, ni chez nous, ni chez la personne qui attend son
e-mail.

C'est la définition d'une panne qui dure : elle ne peut être signalée que par la
personne qui la subit, et cette personne, précisément, n'a aucun moyen de vous
joindre puisqu'elle n'a pas fini de créer son compte.

---

## 3. Le relevé DNS du 07/09/2026

Interrogé depuis l'extérieur, sans accès à vos serveurs :

```
$ dig +short TXT meetdo.fun
"v=spf1 include:_spf.mail.hostinger.com ~all"

$ dig +short TXT _spf.mail.hostinger.com
"v=spf1 include:relay.mail.hostinger.com include:relay.mailchannels.net ~all"
   → relay.mail.hostinger.com  : ip4:148.222.54.0/24, 148.222.55.0/24,
                                 189.12.192.0/22, + deux blocs IPv6
   → relay.mailchannels.net    : ip4:23.83.208.0/20, 35.85.190.185/32

$ dig +short TXT resend._domainkey.meetdo.fun
"p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDNMQ2A70y2p+KaxYJeaZAWp7euYfrurkWQ…"

$ dig +short TXT _dmarc.meetdo.fun
"v=DMARC1; p=none"

$ dig +short TXT send.meetdo.fun
"v=spf1 include:amazonses.com ~all"

$ dig +short TXT resend._domainkey.send.meetdo.fun
   (rien)

$ dig +short MX send.meetdo.fun
10 feedback-smtp.eu-west-1.amazonses.com.
```

Nous en tirons deux constats, et une question.

### 3.1 · Le SPF de l'apex n'autorise pas Resend

`meetdo.fun` déclare Hostinger et MailChannels, **et rien d'autre**. Amazon SES,
par qui Resend expédie, n'y figure à aucune profondeur : nous avons déplié les
deux `include:`.

Tant que Resend pose son enveloppe de retour sur `send.meetdo.fun`, l'alignement
relâché de DMARC sauve la mise et le courrier passe. Mais **le jour où un envoi
part avec un `Return-Path` en `@meetdo.fun`** — un second expéditeur, une
bibliothèque différente, un SMTP direct — **il est en softfail**, et les
fournisseurs stricts le traitent comme tel. Nous ne savons pas si ce jour est
déjà arrivé pour une partie de votre courrier. Vous, oui.

### 3.2 · La configuration est à cheval sur deux domaines, à moitié faite sur chacun

La clé DKIM `resend._domainkey` est sur **l'apex** ; le SPF de Resend est sur
**le sous-domaine**, où il n'y a pas de clé DKIM. C'est la trace d'une
configuration commencée dans un sens et finie dans l'autre. Elle fonctionne par
la grâce de l'alignement relâché, ce qui est exactement le genre de montage qui
tient jusqu'à ce qu'un fournisseur durcisse ses règles.

**Nous ne demandons pas un chantier : nous demandons que les deux moitiés soient
sur le même domaine**, celui qui apparaît réellement dans le `From:`.

### 3.3 · `p=none` sans `rua=` — le trou qui nous coûte ce ticket

`_dmarc.meetdo.fun` vaut `v=DMARC1; p=none`. Pas de `rua=`, donc **aucun rapport
agrégé n'est envoyé à personne**.

C'est précisément l'information qui manque aujourd'hui : quels fournisseurs
acceptent votre courrier, lesquels le refusent, et depuis quand. Elle existe,
les grands fournisseurs l'émettent gratuitement chaque jour, et il suffit d'une
adresse dans un enregistrement TXT pour la recevoir. Sans elle, la seule source
de vérité sur votre délivrabilité est le témoignage d'utilisateurs qui ne
peuvent pas témoigner.

---

## 4. Ce que vous seuls pouvez regarder — et qui tranchera en dix minutes

Le motif « certains oui, d'autres non », à geste identique, ne vient presque
jamais de l'expéditeur : **il vient du fournisseur du destinataire.** Le
tableau de bord Resend porte, par envoi, le sort réel du message — `delivered`,
`bounced`, `complained` — et le message de refus du serveur distant.

Nous vous demandons **trois chiffres**, sur les trente derniers jours, pour les
seuls e-mails de vérification :

1. **combien ont été acceptés, et combien ont rebondi ou été refusés** ;
2. **la répartition des échecs par domaine de destination.** C'est le nerf : si
   la coupure suit `web.de`, `gmx.de`, `t-online.de` — trois marques du même
   groupe, et le public exact de l'app, dont l'écran s'appelle *Registrieren* —
   le diagnostic est clos sans rien chercher d'autre. Ces fournisseurs refusent
   ou jettent silencieusement le courrier d'un domaine jeune là où Gmail se
   contente de le classer en indésirable : même envoi, deux issues, et
   l'utilisateur Gmail « a reçu » quand l'utilisateur web.de « n'a rien reçu » ;
3. **le message de refus littéral** d'un rebond, s'il y en a. Un `550` porte
   presque toujours la raison en clair, et souvent une URL de politique.

Et **une question de configuration**, dont la réponse est binaire :

> **Votre compte Resend est-il entièrement sorti du mode d'essai ?** Un compte
> dont le domaine n'est pas complètement vérifié ne délivre qu'à l'adresse de
> son propriétaire. Ce serait la seule cause qui produirait exactement ce
> symptôme sans qu'aucun rebond n'apparaisse nulle part.

---

## 5. Ce que nous vous demandons de livrer

Par ordre de coût croissant. Les trois premiers ne touchent pas votre code.

**a. Un `rua=` sur le DMARC de `meetdo.fun`.** Un enregistrement TXT, une
adresse de destination. Il ne change rien au courrier qui part et rend
observable, dès le lendemain, ce qui est aujourd'hui invisible. C'est celui que
nous vous demandons en premier même si vous ne faites rien d'autre.

**b. Un seul domaine d'envoi, complet.** SPF **et** DKIM sur le domaine qui
figure dans le `From:` des e-mails de vérification. Si c'est l'apex, il lui
manque l'autorisation de SES ; si c'est `send.meetdo.fun`, il lui manque la clé
DKIM. Dites-nous lequel vous retenez — nous n'avons pas d'avis, nous avons
besoin qu'il n'y en ait qu'un.

**c. Le webhook de rebond de Resend, que vous nous aviez proposé.** Vous
écriviez le 01/09 : *« Si le point de défaillance unique de l'e-mail vous
inquiète au point de vouloir les rebonds, dites-le et nous priorisons le webhook
Resend. »* **Nous le disons.** Et nous ajoutons un argument que nous n'avions
pas alors : ce n'est plus seulement l'alerte d'un proche qui en dépend, c'est la
création de compte, c'est-à-dire la première minute de chaque utilisateur.

**d. Un état de remise lisible sur le compte.** Une fois (c) branché,
l'information existe chez vous ; il nous faut un moyen de la lire pour cesser de
mentir à l'écran. La forme la plus économique est celle que vous avez déjà
livrée pour les veilles — le champ `alertDelivery` — transposée :

```
GET /api/users/me
{ …, "verificationEmailDelivery": "NONE" | "PENDING" | "SENT" | "BOUNCED" | "FAILED" }
```

Sur le compte du porteur, et sur lui seul. Nous ne demandons ni journal, ni
historique, ni horodatage : un état, celui du dernier envoi.

**e. Un point mineur, mais qui coûte peu :** le limiteur de
`resend-verification` est à **3 appels par heure et par IP**. Derrière un NAT
d'entreprise, un réseau associatif ou un partage de connexion, trois personnes
qui s'inscrivent le même soir consomment le quota les unes des autres. Un
limiteur **par adresse** — ou par adresse *et* par IP, avec un plafond IP plus
haut — protégerait autant sans punir un groupe qui s'inscrit ensemble, ce qui
est le mode d'arrivée normal sur meetDo.

---

## 6. Deux fils ouverts que nous rattachons ici

**Le second expéditeur de vérification.** Vous nous le signaliez le 26/08
(`ios/docs/REPONSE_BACKEND_VERIFICATION_LIEN_APP_2026-08-26.md`, §6) : un
expéditeur inutilisé, construisant son lien sur un chemin de frontend web
inexistant, *« exactement la forme du défaut du 25 août, endormie »*. Vous
comptiez le traiter pour lui-même. **A-t-il été traité, et est-il toujours sans
appelant ?** S'il s'est réveillé, il produirait des e-mails reçus mais dont le
lien ne mène nulle part — un symptôme voisin, que nos utilisateurs nous
décriraient probablement avec les mêmes mots.

**La langue de l'e-mail.** Est-elle choisie sur l'`Accept-Language` de la requête
d'inscription, ou sur une préférence de compte lue après coup ? Un utilisateur
allemand qui reçoit un e-mail en anglais le prend pour un envoi indésirable et
ne le signale jamais comme reçu. Nous envoyons l'`Accept-Language` sur toutes
nos requêtes ; nous ne savons pas si vous vous en servez ici.

---

## 7. Ce que votre réponse changera chez nous

Concrètement, et c'est petit — ce qui est le signe que la demande est au bon
endroit :

- avec (d), notre écran de vérification cesse d'afficher « vérifiez vos
  indésirables » à quelqu'un dont l'adresse a rebondi, et lui propose de la
  corriger ;
- avec (c), le bouton « renvoyer » cesse d'annoncer un envoi sur la foi d'un 200
  qui ne prouve rien ;
- (a) et (b) ne changent rien chez nous : ils changent le nombre de personnes
  qui arrivent jusqu'à l'écran suivant.

Nous n'attendons pas ces livraisons pour continuer : notre écran de vérification
n'est plus un passage obligé, il porte une sortie explicite. **Le compte
fonctionne sans l'e-mail.** Ce qui ne fonctionne pas, c'est notre capacité à
dire à quelqu'un pourquoi il n'a rien reçu — et la vôtre à le savoir.

---

## 8. Ce que nous vérifierons après votre déploiement

1. `dig +short TXT _dmarc.meetdo.fun` porte un `rua=` ;
2. le domaine du `From:` d'un e-mail de vérification porte **à la fois** un SPF
   couvrant l'expédition réelle et une clé DKIM — nous relèverons les deux, et
   nous lirons les en-têtes d'authentification d'un e-mail reçu, pas seulement
   le DNS ;
3. `GET /api/users/me` porte `verificationEmailDelivery` et, sur une adresse
   volontairement fausse en `@example.invalid`, il passe à `BOUNCED` dans la
   minute — c'est le seul test qui prouve que la chaîne complète est branchée,
   et non seulement le champ ;
4. `POST /api/auth/register` continue de rendre **201 avec `accessToken` et
   `verificationStatus`**. C'est le point où une régression nous coûterait le
   plus cher : cette route porte toutes les entrées dans l'app, et rien de ce
   que nous demandons ici ne justifie d'y toucher.

---

*Une remarque pour finir. Ce ticket n'a pas d'utilisateur qui se plaint : il a
des utilisateurs qui ne reviennent pas. La différence entre les deux est
uniquement une affaire d'instrumentation — et sur ce sujet précis,
l'instrumentation est un enregistrement TXT et un webhook. C'est ce rapport-là
qui nous fait vous l'écrire aujourd'hui plutôt que d'attendre d'en savoir plus.*
