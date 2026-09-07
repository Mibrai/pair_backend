# Livrables (a) et (b) — les deux enregistrements DNS à poser

**Date :** 2026-09-07
**Pourquoi ce fichier :** ces deux livrables ne sont pas du code. Ils ne peuvent
pas être posés depuis le dépôt, et rien dans la suite de tests ne les couvrira.
Ils sont donc écrits ici, prêts à copier, avec ce qu'il faut pour les vérifier.

---

## Ce que le relevé du client montrait

```
$ dig +short TXT meetdo.fun
"v=spf1 include:_spf.mail.hostinger.com ~all"      ← Hostinger + MailChannels, pas SES

$ dig +short TXT resend._domainkey.meetdo.fun
"p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDNMQ2A70y2p+KaxYJeaZAWp7euYfrurkWQ…"

$ dig +short TXT _dmarc.meetdo.fun
"v=DMARC1; p=none"                                  ← aucun rua=, donc aucun rapport

$ dig +short TXT send.meetdo.fun
"v=spf1 include:amazonses.com ~all"                 ← SPF ici, mais pas de DKIM ici
```

**Le domaine du `From:` est l'apex.** `resend.from-email` vaut `infos@meetdo.fun`
(défaut de `ResendEmailService:25`, repris dans `application-railway.properties`).
C'est donc l'apex qui doit porter les deux moitiés, et c'est l'autorisation
d'expédition qui lui manque.

> **À confirmer avant de poser (b) :** la variable `RESEND_FROM_EMAIL` peut
> surcharger ce défaut en production, et le dépôt ne dit pas sa valeur. Si elle
> avait été portée à `send.meetdo.fun`, c'est l'inverse qu'il faut faire — poser
> la clé DKIM sur le sous-domaine. Le chantier mobile relève les en-têtes
> (`From:`, `Return-Path:`, `Authentication-Results:`) d'un e-mail reçu et nous
> les rend : trente secondes de leur côté, et la question est tranchée sans aller
> chercher la variable.
>
> **Le DNS seul ne tranche pas, et c'est vérifié.** Au 07/09,
> `resend._domainkey.meetdo.fun` porte bien une clé et
> `resend._domainkey.send.meetdo.fun` est vide — donc DKIM n'existe QUE sur
> l'apex. Mais cela ne prouve pas où est le `From:` : si les envois partaient de
> `send.meetdo.fun`, son SPF suffirait à faire passer DMARC par alignement de
> l'enveloppe, DKIM absent ou non. **Les deux montages « marchent » aujourd'hui,
> et c'est précisément ce qui rend le défaut invisible.** Seul l'en-tête d'un
> e-mail reçu dit lequel est en place.

---

## (a) Le `rua=` sur le DMARC

C'est celui à poser en premier, même si rien d'autre n'est fait. Il ne change
rien au courrier qui part, et il rend observable dès le lendemain ce qui est
aujourd'hui invisible : quels fournisseurs acceptent notre courrier, lesquels le
refusent, et depuis quand.

| Champ | Valeur |
|---|---|
| Type | `TXT` |
| Nom | `_dmarc.meetdo.fun` |
| Valeur | `v=DMARC1; p=none; rua=mailto:dmarc@meetdo.fun; fo=1` |

**Ce qui change et ce qui ne change pas.** `p=none` est conservé : la politique
reste « ne rien faire », et aucun message ne sera rejeté à cause de cet
enregistrement. `rua=` ajoute la destination des rapports agrégés, que les grands
fournisseurs émettent gratuitement chaque jour. `fo=1` demande un rapport dès
qu'une des deux authentifications échoue, et pas seulement quand les deux
échouent — c'est ce qui rendra visible le SPF manquant de (b) avant même qu'il ne
coûte un message.

**La boîte `dmarc@meetdo.fun` doit exister et accepter du courrier** avant de
poser l'enregistrement : les rapports sont des pièces jointes XML quotidiennes,
et une adresse qui rebondit ferait taire ce qu'on cherche justement à entendre.

> ✅ **Fait le 07/09.** La boîte est créée, et `meetdo.fun` porte bien ses MX
> Hostinger (`mx1`/`mx2.hostinger.com`) : elle recevra. Il ne reste que
> l'enregistrement TXT ci-dessus, qui n'est pas encore posé — relevé du 07/09 au
> soir : `_dmarc.meetdo.fun` vaut toujours `"v=DMARC1; p=none"`, sans `rua=`.

---

## (b) SES autorisé dans le SPF de l'apex

| Champ | Valeur |
|---|---|
| Type | `TXT` |
| Nom | `meetdo.fun` |
| Valeur actuelle | `v=spf1 include:_spf.mail.hostinger.com ~all` |
| **Valeur à poser** | `v=spf1 include:_spf.mail.hostinger.com include:amazonses.com ~all` |

Un seul `include:` ajouté, à la même place que celui déjà présent sur
`send.meetdo.fun`. Hostinger reste autorisé — c'est lui qui sert le courrier
entrant et les envois hors Resend, et le retirer casserait ce qui marche.

**Ce que cela répare.** Aujourd'hui, l'alignement relâché de DMARC sauve la mise
tant que Resend pose son enveloppe de retour sur `send.meetdo.fun`. Le jour où un
envoi part avec un `Return-Path` en `@meetdo.fun` — un second expéditeur, une
bibliothèque différente, un SMTP direct — il est en softfail. L'enregistrement
ci-dessus supprime cette dépendance à une condition qu'aucun test ne surveille.

**Le nombre de recherches DNS a été compté, et il passe largement.** SPF en
autorise dix ; en dépliant les deux `include:` le 07/09 :

| # | Recherche | Contenu |
|---|---|---|
| 1 | `include:_spf.mail.hostinger.com` | deux `include:` imbriqués |
| 2 | └─ `include:relay.mail.hostinger.com` | `ip4`/`ip6` seulement |
| 3 | └─ `include:relay.mailchannels.net` | `ip4` seulement |
| 4 | `include:amazonses.com` | `ip4`/`ip6` seulement, **aucun `include:` imbriqué** |

**4 sur 10.** Aucune des trois feuilles n'ajoute de recherche, donc le total est
stable : ce n'est pas une marge qui se consommera toute seule.

Relevé du 07/09 au soir : `meetdo.fun` vaut toujours
`"v=spf1 include:_spf.mail.hostinger.com ~all"` — l'enregistrement n'est pas
encore posé.

---

## La vérification, après pose

Dans l'ordre où le chantier mobile l'a annoncée :

```bash
# (a) — le rua doit apparaître
dig +short TXT _dmarc.meetdo.fun
#   attendu : "v=DMARC1; p=none; rua=mailto:dmarc@meetdo.fun; fo=1"

# (b) — SES doit être autorisé, en dépliant les include:
dig +short TXT meetdo.fun
dig +short TXT amazonses.com
```

Puis, sur un e-mail de vérification **reçu après la pose** — c'est ce relevé-là
qui compte, et non le DNS seul :

```
Authentication-Results: spf=pass   (domaine testé = celui du Return-Path)
                        dkim=pass  (d=meetdo.fun)
                        dmarc=pass
```

Les rapports agrégés commencent à arriver le lendemain, une fois par jour et par
fournisseur. Le premier qui compte est celui d'un fournisseur allemand — c'est
l'hypothèse du client au §4 de son ticket, et c'est là que le relevé la
confirmera ou l'écartera.
