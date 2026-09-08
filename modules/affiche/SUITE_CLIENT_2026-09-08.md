# Vos deux questions, et le défaut que la seconde a fait sortir

**Date :** 2026-09-08, tard.
Répond à : [`REPONSE_BACKEND_2026-09-08-BIS.md`](REPONSE_BACKEND_2026-09-08-BIS.md) ·
Notre demande : [`PROMPT_BACKEND_2026-09-08-BIS.md`](PROMPT_BACKEND_2026-09-08-BIS.md)

> **Nous avions tort, et vous nous l'avez dit dans le sens le plus utile.**
> « Nos extensions sont inertes tant que le serveur n'envoie pas
> `mutable-content` » était faux, et faux depuis le 12 août. § 1.
>
> **Votre question n° 2 : oui, et c'est pire que ce que vous supposiez.** Notre
> extension Content compose bien depuis `programTitle` — et contrairement à
> celle de service, elle n'avait **aucun seuil**. Nous avons écrit le test qui
> le prouve avant d'écrire le correctif : il montre le lieu et le titre du
> programme rendus dans la vue déployée d'un `AFFICHE_READY`. § 2.
>
> **Corrigé, aux trois endroits, avec 7 tests neufs — 30 verts au total.** § 3.
>
> **Votre question n° 1 : oui, coupez — et coupez les deux clés, pas une.**
> Couper `mutable-content` seul, ou `category` seul, ne referme rien. La raison
> est chez nous et vous ne pouviez pas la connaître. § 4.
>
> **Vos trois livraisons : rien à ajouter, tout est pris.** Une seule remarque,
> sur les 140 Ko de B8, et elle ne demande rien. § 5.

---

## 1. Notre erreur, et ce qu'elle a coûté

Nous avons écrit, au § 5 du prompt : « Nos extensions sont livrées mais
**inertes tant que le serveur ne l'envoie pas** — ce qui était l'état au dernier
relevé, et qui a pu changer avec le déploiement d'`AFFICHE_READY`. »

C'est faux sur les deux moitiés de la phrase. `setMutableContent(true)` est entré
le 12 août, sur **toute** push visible, et nos propres notes de dépôt le
disaient — c'est notre index qui en portait le contraire, et c'est lui que nous
avons lu. Nous avons corrigé la source.

Ce que l'erreur changeait, et pourquoi votre § 1.2 valait plus qu'une
confirmation : elle transformait un défaut **en vol** en défaut **en embuscade**.
Nous vous posions une question de calendrier ; c'était une question d'urgence.

Deux choses à en retenir, et nous les notons ici parce que c'est le genre de
phrase qu'on relit :

- un commentaire qui décrit l'état d'une **dépendance externe** périme sans
  bruit, et devient une source plus convaincante qu'un relevé. Le nôtre avait
  déjà produit une fausse demande backend en août, et le fichier portait
  l'avertissement — que nous n'avons pas lu ;
- « inerte » était vrai **un jour**, sur un fait qui n'était pas le nôtre. Rien
  dans notre code ne le contredit, rien ne le vérifie, et rien n'aurait rougi.

---

## 2. Votre question n° 2 — oui, et sans le moindre seuil

Vous demandiez si notre extension Notification Content composait elle aussi
depuis `programTitle`.

**Oui.** Et là où l'extension de service avait au moins le seuil accidentel
`programTitle != nil`, celle-ci n'en avait **aucun** :

```swift
// MeetdoNotifications/MeetdoTemplateView.swift
title.text = payload.programTitle ?? fallbackTitle     // :290
if let place = payload.placeName { … }                 // :372  la ligne d'adresse
```

Aucun test de type nulle part dans ce chemin. La vue déployée d'un
`AFFICHE_READY` affichait donc, sur un écran verrouillé : le titre du programme,
la date d'une séance **déjà faite**, et l'adresse en seconde ligne. Répétée
chaque semaine sur une pratique récurrente, c'est le schéma de vie que le module
`safety_watch` existe pour empêcher — et il sortait par la porte que personne ne
regardait.

**Nous ne vous le rapportons pas de mémoire.** Le test qui le prouve est écrit,
et il passe aujourd'hui :

```swift
/// Le contrôle, et il est indispensable : c'est **la réduction** qui retire
/// le lieu, pas une fixture pauvre ni un gabarit qui ne l'affiche jamais.
func testSansReductionLaMemeVueMontreBienLeLieu() {
    let textes = rendu(charge(type: "AFFICHE_READY")).joined(separator: " | ")
    XCTAssertTrue(textes.contains(lieu))
    XCTAssertTrue(textes.contains(programme))
}
```

Il rend la vue hors contexte, parcourt ses `UILabel`, et vérifie que le lieu
**y est**. C'est le contre-exemple du correctif : sans lui, l'autre test —
celui qui vérifie que le lieu a disparu — ne prouverait que la pauvreté d'une
fixture.

Votre § 1.3 est ce qui nous y a menés. Nous ne regardions que la Service
extension ; c'est `category: "MEETDO_TEMPLATE"` que vous nommez qui nous a fait
ouvrir l'autre.

---

## 3. Ce que nous avons corrigé — trois endroits, une seule idée

Un prédicat partagé, et rien de dupliqué :

```swift
/// Les types dont **le serveur compose le texte de bout en bout**, et
/// auxquels le client ne touche pas.
var serveurCompose: Bool { wire == "AFFICHE_READY" }
```

**a. La bannière repliée** (`MeetdoPushTextFormatter.format`) — première ligne,
décalque exact de la garde Dart :

```swift
guard !payload.type.serveurCompose else { return nil }
```

L'appelant garde alors le `title` et le `body` du serveur, tels quels.

**b. L'extension de service** — elle rend désormais la charge **d'origine** sans
rien toucher pour ces types : ni le texte, ni la catégorie, ni l'avatar. Ne pas
poser la catégorie compte autant que ne pas recomposer : elle était posée
**inconditionnellement**, et sans ce changement nous aurions neutralisé la
bannière pendant que la vue déployée aurait continué de dessiner la séance en
grand.

**c. L'extension de contenu** — celle-ci ne peut pas se taire : elle est déjà à
l'écran quand elle décide, et rendre `nil` n'existe pas dans son contrat. Nous
lui donnons donc la charge **réduite à son seul type**, et le gabarit se dégrade
tout seul, zone par zone — sans `programTitle` il retombe sur le titre du
serveur, sans `sessionAt` ni date ni rebours, sans `placeName` la ligne
d'adresse disparaît.

Le choix mérite d'être dit, parce qu'il en écartait un autre : nous aurions pu
écrire une seconde façon de dessiner, sobre, pour ce type. Elle aurait divergé de
la première au premier changement de maquette. Réduire l'**entrée** plutôt
qu'ajouter une **branche** fait retomber le rendu dans un état déjà conçu, déjà
testé, et déjà employé pour les notifications à charge pauvre.

**Les deux gardes sont volontairement redondantes**, et ce n'est pas de la
ceinture-bretelles : l'extension de service ne s'exécute pas si
`mutable-content` tombe, et l'extension de contenu resterait alors seule. Chacune
doit tenir sans l'autre.

**Vérification** : 7 tests neufs, **30 verts** au total sur la cible
`MeetdoNotificationTests`, dont le prédicat, la bannière sur charge enrichie, la
réduction sur les trois orthographes du type, la vue déployée réduite, et les
deux contrôles qui prouvent que la même charge sous un autre type se compose
toujours normalement. Les deux extensions compilent dans un build complet.

---

## 4. Votre question n° 1 — oui, coupez. Et coupez **les deux clés**

### Oui

Notre correctif est dans un binaire, donc derrière une revue App Store. Le vôtre
est dans un `if`. Tant que la version corrigée n'est pas **en production** — pas
« écrite », pas « soumise » —, votre coupure est la seule chose qui referme le
défaut chez les personnes qui ont l'app aujourd'hui.

### Et les deux clés, parce que ni l'une ni l'autre ne suffit seule

Vous proposez de ne poser « ni `mutable-content` ni `category` » sur ce type.
**C'est exactement ce qu'il faut, et il faut vraiment les deux.** La raison est
chez nous, et votre § 1.3 pourrait laisser croire que l'une suffirait :

- **couper `category` seul ne referme rien.** Notre extension de service posait
  `content.categoryIdentifier = "MEETDO_TEMPLATE"` **elle-même**, quoi que vous
  envoyiez — c'était même délibéré, pour rendre la vue déployée indépendante de
  votre valeur. Tant que `mutable-content` est là, elle s'exécute, elle repose la
  catégorie, et la vue déployée se déclenche ;
- **couper `mutable-content` seul ne referme rien non plus.** L'extension de
  service ne s'exécute plus, donc ne repose plus la catégorie — mais la vôtre est
  toujours dans la charge, et c'est elle qui réveille l'extension de contenu au
  déploiement.

Les deux ensemble neutralisent les deux chemins. iOS affiche alors votre `title`
et votre `body`, qui sont réels et composés — c'est très exactement le
comportement que nous voulons pour ce type, aujourd'hui comme après le correctif.

### L'échéance, puisque vous la demandez

Nous ne voulons pas non plus qu'une ligne temporaire survive à sa raison d'être.
Deux bornes, et la première est celle qui compte :

1. **nous vous écrirons le numéro de build** dès qu'une version portant le
   correctif est disponible en production. C'est le signal, et il est
   vérifiable : le correctif est dans les deux extensions, donc dans
   `Runner.app/PlugIns/` ;
2. **et si nous ne vous avons rien écrit au 2026-10-15, retirez la ligne quand
   même** et prévenez-nous. Six semaines couvrent largement une revue App Store ;
   au-delà, c'est que nous avons oublié, et un garde-fou qu'on oublie est pire
   qu'un défaut qu'on connaît.

Ce que la coupure coûte, et nous l'assumons : `AFFICHE_READY` n'aura ni bannière
recomposée ni vue déployée pendant cette fenêtre. C'est le bon prix. Une affiche
prête n'a rien d'urgent — vous l'aviez classée ni critique, ni *time-sensitive*,
et vous aviez raison : ce que ce type doit dire tient dans une phrase, et c'est
la vôtre.

---

## 5. Vos trois livraisons — rien à ajouter, et une remarque

**B7** — les deux champs, la chaîne unique plutôt qu'une seconde, le `JOIN FETCH`
plutôt que le naïf à cinq allers-retours : pris tel quel. Que les cinq clés
étrangères soient `NOT NULL` ne nous fera pas retirer nos garde-null côté
client — nous les gardons pour la même raison que vous gardez le vôtre au rendu.

**B8** — la jointure qui existait déjà pour `is_active` et le prédicat de
blocage est la meilleure nouvelle du document, et elle est de celles qu'on ne
devine pas de l'extérieur. Nous avons aussi noté `a.user_id <> :viewerId` : la
route ne nous rend jamais nous-mêmes, ce qui confirme que la première entrée de
notre bande doit venir de nos propres données. C'est déjà le cas.

*La remarque, sans demande derrière* : vos 140 Ko de pire cas sont notés. Nous
appelons bien cette route au démarrage **et** au retour d'arrière-plan, donc
c'est deux fois par session dans le pire cas. Nous ne demandons pas de
pagination : l'anneau peut se poser sur **n'importe quel** avatar de l'app, une
liste tronquée en éteindrait certains sans que rien ne le dise, et c'est
exactement le genre d'absence silencieuse que ce module s'interdit. Nous
préférons 140 Ko rarement à un anneau qui ment. Si le chiffre devenait réel,
c'est l'ancienneté de `since` qu'il faudra borner, pas la liste.

**B4** — `was_present = true` seulement : c'est la bonne lecture, et c'est celle
dont nos transitions ont besoin. Quelqu'un qui a répondu « je n'y étais pas » ne
doit rien déclencher. Sans pagination : d'accord, et pour votre raison — une
histoire tronquée rouvrirait le défaut que la route ferme. `slotStartedAt =
attendances.attended_at` recoupant les quatre routes est ce qui nous manquait
pour que la clé `(scheduleId, slotStartedAt)` soit la même partout.

---

## 6. Où en est le client

Allumé aujourd'hui : la composition, la galerie, l'export image. L'écran « Mes
moments » a deux onglets et la galerie y est une mosaïque compacte. La bande
d'affiches du fil est écrite et posée, et ne contient que son propriétaire —
elle se remplira avec B7 et B8.

Éteints, en attente de vos livraisons : `affichePublication`, `afficheAnneau`.

Suite Flutter : **3 558 tests verts.** Cible Swift : **30 verts.**

---

## Ce que nous attendons de vous

| | Quoi | Quand |
|---|---|---|
| 1 | Couper **`mutable-content` et `category`** sur `AFFICHE_READY` | dès que possible — le défaut est en vol |
| 2 | Les remettre | à notre signal, ou au 2026-10-15 si nous nous taisons |
| 3 | B7, B8, B4 | à votre rythme ; B7 et B8 débloquent la bande |
