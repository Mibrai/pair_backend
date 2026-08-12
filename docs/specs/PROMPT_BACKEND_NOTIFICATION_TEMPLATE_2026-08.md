# Demandes backend — template de notification (août 2026)

Contexte : le client a implémenté un **template unique de notification**, appliqué
à trois surfaces — la carte de la liste in-app, le texte de la bannière poussée,
et (plus tard) les vues natives déployées. Design et contrat complets :
`Template/meetdo-notification-template.html`.

Le template exige que **toute** notification porte : le nom de l'activité, le
titre du programme, le nom de l'auteur, la date, l'heure et l'adresse. Puis,
selon le type, le message reçu et son auteur, ou le temps restant avant le début
du créneau.

Six demandes. **N1 est bloquante** et conditionne toutes les autres : sans elle,
le template ne s'affiche jamais. N2 dit comment la satisfaire sans saisie
manuelle. Les autres suppriment des angles morts.

Chaque demande dit ce que le client fait **aujourd'hui sans elle**, pour que la
priorisation soit une décision et non une devinette.

---

## L'état mesuré, et non supposé

Relevé le 2026-08-12 sur `GET /api/notifications?page=0&size=5`, compte de test
`lena.mueller@web.de`. Une seule notification, dont voici le corps intégral :

```json
{
  "id": "f1000000-0000-0000-0000-000000000002",
  "type": "NEW_PEER_REC",
  "channel": "IN_APP",
  "payload": {
    "fromUserId": "00000000-0000-0000-0000-000000000003",
    "fromUserName": "Max Schmidt"
  },
  "isRead": true,
  "sentAt": "2026-06-20T19:23:20.756221Z",
  "readAt": "2026-06-21T19:23:20.756221Z",
  "scheduledAt": null
}
```

Sur les onze champs du template, **un seul** est servi (`fromUserName`). Le
client le lit déjà — voir N1, « ce qui marche déjà ». Les dix autres sont absents,
donc la liste retombe sur sa ligne compacte d'origine. **Ce n'est pas un défaut
client** : il n'y a rien à mettre dans une carte.

---

## N1 — Enrichir le `payload` de `NotificationDto`

**Priorité : bloquant.** Sans elle, aucune des trois surfaces du template
n'affiche quoi que ce soit de nouveau, sur aucune plateforme.

Demandé — dix clés à ajouter au `payload`, en `camelCase` :

```jsonc
{
  // ── Déjà servi, ne rien changer ─────────────────────────────────────
  "programId":       "prg_8f31",
  "scheduleId":      "sch_5c07",
  "authorId":        "usr_2a19",
  "conversationId":  "cnv_77b4",     // NEW_MESSAGE uniquement
  "fromUserId":      "usr_0003",     // ← la clé de jointure, voir N2
  "fromUserName":    "Max Schmidt",  // déjà lu par le client (zone 4)

  // ── À ajouter — l'identité de la séance ─────────────────────────────
  "activityName":      "Natation",
  "programTitle":      "Longueurs du soir · niveau confirmé",
  "authorName":        "Lena Müller",
  "authorAvatarUrl":   "https://…/avatars/2a19.jpg",  // facultatif
  "categoryIcon":      "pool",
  "categoryColorRamp": "ocean-blue",

  // ── À ajouter — les coordonnées ─────────────────────────────────────
  "endsAt":         "2026-08-17T18:00:00Z",
  "addressPublic":  "Piscine du Rhône, 8 quai Claude Bernard, Lyon",

  // ── À ajouter — le message (NEW_MESSAGE uniquement) ─────────────────
  "messageAuthorName": "Sophie Martin",
  "messageBody":       "On se retrouve devant le court 3 ?"
}
```

Quatre points de contrat :

1. **Des données brutes, jamais des phrases.** `endsAt` est un ISO 8601, pas
   « 20:00 ». Le client met en forme selon la langue *et le fuseau* de
   l'appareil au moment où il affiche, et **recalcule** le compte à rebours à
   chaque rendu — une notification qui a dormi deux heures dans le centre
   afficherait sinon un « dans 45 min » faux.
2. **`categoryColorRamp` n'est pas une couleur.** Le client la résout vers sa
   palette validée en vision déficiente (`CategoryPalette.resolve`) ; il ne
   peint jamais avec le hex du serveur. Les valeurs nommées (`ocean-blue`) sont
   parfaites. C'est ce champ qui accorde la couleur de la notification à celle
   du pin du même programme sur la carte.
3. **`addressPublic`, pas `address`.** C'est l'adresse *diffusable*. Une
   notification s'affiche sur un écran verrouillé : l'adresse exacte d'un lieu
   privé non partagé n'a rien à y faire — même filtrage qu'en A1 de la spec
   produit.
4. **`scheduledAt` reste au premier niveau du DTO**, où il est déjà. Ne pas le
   déplacer dans le `payload`.

**Ce qui marche déjà, ne pas le refaire :** `scheduledAt` est servi et lu ;
`fromUserName` est servi et le client s'en sert comme auteur du message. Le
client tolère aussi le `snake_case` (`program_title`, `address_public`…) si vos
gabarits le produisent naturellement.

**Sans cette demande** : la liste in-app affiche la ligne compacte d'origine
(icône, libellé dérivé du type, temps relatif). Les bannières poussées affichent
le `title`/`body` que vous composez aujourd'hui. Rien ne casse — rien
n'apparaît non plus.

---

## N2 — La jointure : d'où tirer ces champs

**Priorité : accompagne N1.** Aucun de ces champs n'est à saisir : la base les
porte déjà.

Pour une `NEW_MESSAGE`, l'expéditeur et le destinataire partagent un programme en
cours, et ce programme porte l'activité, le titre, l'auteur, le créneau et
l'adresse. Une jointure remplit les dix champs.

**La clé est un identifiant, jamais un nom d'affichage.**

| Ordre | Clé | Pourquoi |
|---|---|---|
| 1 | `conversationId` | Le fil désigne **directement** son programme. Sans ambiguïté. |
| 2 | `fromUserId` | UUID déjà présent dans le payload. |
| ✗ | `fromUserName` | À proscrire comme clé. |

Chercher par nom échoue sur trois points : deux personnes peuvent le porter, un
nom se change, et une correspondance ratée produirait une notification attribuée
au **mauvais programme** sans lever la moindre erreur — le type de défaut que
personne ne détecte.

**Un point à trancher côté produit :** si l'expéditeur et le destinataire
partagent **plusieurs** programmes en cours, lequel la notification désigne-t-elle ?
La paire d'utilisateurs ne suffit pas à décider. C'est un argument de plus pour
joindre par `conversationId`.

---

## N3 — Composer le texte des bannières poussées

**Priorité : haute.** Concerne les notifications reçues **app fermée**, où
personne côté client n'est là pour composer.

Le client implémente déjà cette formule (`lib/core/push/push_text.dart`) et s'en
sert pour Android au premier plan. Ses tests
(`test/notification_template_test.dart`) en sont la **spécification exécutable** :
ce que le serveur produit doit leur correspondre.

| Champ | iOS | Android |
|---|---|---|
| `title` | `{programTitle}` | `{activityName} · {programTitle}` |
| `subtitle` | `{activityName} · par {authorName}` | — *(Android n'en a pas)* |
| `body` — rappel | `{rebours} · {date} {heure}`<br>`{addressPublic}` | idem + ` · par {authorName}` en fin de 1<sup>re</sup> ligne |
| `body` — programme | `{date} {heure} · {rebours}`<br>`{addressPublic}` | idem + ` · par {authorName}` |
| `body` — message | `{messageAuthorName} : {messageBody}`<br>`{date} {heure} · {addressPublic}` | identique |

Quatre règles :

- **L'ordre des segments encode l'urgence.** Un rappel ouvre sur le compte à
  rebours, une annonce de programme sur la date. Sur une bannière repliée à une
  ligne, seul le début survit.
- **L'adresse est toujours la seconde ligne** — la plus longue, la moins urgente,
  donc celle qui doit sauter en premier.
- **Ne pas répéter le logo ni « meetDo »** dans ces chaînes : iOS et Android
  posent déjà l'icône et le nom de l'app en en-tête. Les redoubler consomme une
  des trois lignes disponibles.
- **La langue vient de `locale`**, propriété de l'appareil, déjà envoyée à chaque
  `POST /notifications/devices`. Les dates suivent cette même locale
  (`dim. 17 août` / `Sun 17 Aug` / `So. 17. Aug.`), jamais un motif littéral.

**Sans cette demande** : les bannières gardent le texte actuel. Le client ne peut
rien y faire — app fermée, il ne s'exécute pas.

---

## N4 — Tronquer `messageBody` à l'émission

**Priorité : haute — risque de perte silencieuse.**

APNs plafonne la charge utile à **4 Ko**. Un message de trois mille caractères ne
produit pas une bannière tronquée : il fait **rejeter la notification entière**.
L'utilisateur ne reçoit rien, et rien ne le signale.

Demandé : couper `messageBody` à **120 caractères**, sur une frontière de mot,
suivi d'une ellipse. Le client applique la même coupe à l'affichage, mais elle ne
protège que le rendu — pas le transport.

**Sans cette demande** : les messages longs ne sont pas notifiés du tout, de
façon intermittente et sans trace.

---

## N5 — `mutable-content` et `category` (préparation iOS)

**Priorité : basse — à faire avant, pas pendant.**

La vue déployée riche sur iOS passe par une extension *Notification Content*.
Elle ne se déclenche **que** si la charge APNs porte :

```jsonc
{
  "aps": {
    "mutable-content": 1,
    "category": "MEETDO_TEMPLATE"
  }
}
```

Sans ces deux clés, l'extension est du code mort le jour de sa livraison. Autant
les poser maintenant : elles sont inertes tant que l'extension n'existe pas.

**Sans cette demande** : la vue déployée iOS reste impossible. La bannière
repliée (N3), elle, fonctionne.

---

## N6 — Confirmer la planification du rappel à T-2h

**Priorité : à vérifier, pas forcément à développer.**

Règle produit : *à moins de deux heures du début d'un créneau d'un programme où
l'utilisateur est inscrit, une notification de rappel est déclenchée sur son
appareil.*

Le client est aligné au seuil près : sa fenêtre d'imminence
(`AppNotification.isImminent`) vaut **exactement deux heures**. Un
`PROGRAM_REMINDER` déclenché à T-2h arrive donc pile à la frontière et bascule
dans le bon rendu — coral qui écrase l'accent du type, pastille qui pulse,
remontée en tête de liste. Trois tests verrouillent ce seuil.

Ce que le client **ne peut pas vérifier** : que le serveur planifie effectivement
cet envoi pour chaque inscrit. Merci de confirmer :

1. le `PROGRAM_REMINDER` est-il planifié à T-2h pour **tous** les inscrits d'un
   créneau, ou seulement pour l'hôte ?
2. est-il replanifié quand un créneau est **déplacé**, et annulé quand il est
   supprimé ?
3. `scheduledAt` porte-t-il bien le **début du créneau**, et non l'heure d'envoi
   du rappel ? Le client décompte vers `scheduledAt` ; les deux valeurs
   diffèrent de deux heures, et les confondre afficherait « maintenant » deux
   heures trop tôt.

**Sans cette confirmation** : impossible de distinguer « le rappel n'est pas
envoyé » de « le rappel est envoyé mais mal rendu ». Les deux se présentent de la
même façon sur le téléphone.

---

## Dégradation, si une demande n'est servie qu'en partie

Aucun champ manquant ne vide la carte. Le client a un repli pour chacun, ce qui
permet de livrer N1 par morceaux :

| Champ absent | Conséquence |
|---|---|
| `programTitle` | **Pas de carte du tout** — retour à la ligne compacte. C'est le seuil. |
| `activityName` | Le surtitre disparaît, le titre remonte. |
| `authorName` | La ligne d'auteur disparaît. |
| `authorAvatarUrl` | Initiales sur pastille dégradée. |
| `categoryIcon` | Icône dérivée du type de notification. |
| `categoryColorRamp` | Liseré et pastille passent à l'accent Aurora du type. La carte reste cohérente, elle perd son accord avec le pin de la carte géographique. |
| `endsAt` | Heure de début seule, au lieu d'une plage. |
| `addressPublic` | La ligne d'adresse disparaît. |
| `scheduledAt` | Pas de compte à rebours, pas de remontée en tête. |
| `messageBody` | Pas de bulle de message. |

**Le seul champ qui décide de tout, c'est `programTitle`.** S'il faut livrer une
seule clé en premier, c'est celle-là — accompagnée d'`activityName` et de
`scheduledAt`, qui portent l'essentiel de la valeur.

---

## Références

| Quoi | Où |
|---|---|
| Design complet du template, les trois surfaces | `Template/meetdo-notification-template.html` |
| Lecture du payload côté client | `lib/models/notification_models.dart` |
| Carte in-app | `lib/features/notifications/presentation/widgets/notification_card.dart` |
| Formule du texte de push | `lib/core/push/push_text.dart` |
| Spécification exécutable (27 tests) | `test/notification_template_test.dart` |
