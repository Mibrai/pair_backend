# Prompt à coller dans le Claude Code du dépôt backend

> **Relance de B3 du lot 7** (`PROMPT_BACKEND_LOT7_2026-08.md`), que
> `REPONSE_BACKEND_LOT7_2026-08.md` déclare livré : « `aps.badge` (iOS) et
> `notification_count` (Android) portent le nombre de non lues du destinataire
> **après** enregistrement de la notification qui part — *c'était codé en dur à
> `1`* ». Sur l'appareil, le badge affiche toujours `1`.

---

## Le symptôme

iPhone physique, build release, compte réel, **2026-08-12** : *« le badge sur
l'icône affiche toujours 1, peu importe le nombre de messages ou de
notifications que je reçois »*.

Le nombre observé est `1` et ne bouge jamais, quel que soit le volume reçu.

**Ce que le client a corrigé le même jour**, pour que le partage des
responsabilités soit net et que vous ne cherchiez pas ce qui n'est plus là :

- il comptait des **fils** de messagerie au lieu des **messages** — cinq
  messages d'une même personne affichaient `1`. Corrigé : il somme désormais
  `ConversationSummaryDto.unreadCount` ;
- il ne recomptait rien au retour au premier plan, donc une valeur posée pendant
  l'absence survivait jusqu'à l'ouverture de l'écran des notifications. Corrigé :
  les deux compteurs sont redemandés à chaque réveil.

Ces deux corrections rendent le badge juste **dès que l'app tourne**. Elles ne
peuvent rien pour l'app fermée — et c'est là que le badge sert.

**Le raisonnement, app fermée :** aucun code client ne s'exécute. Le nombre
affiché ne peut donc venir que de `aps.badge` (iOS) / `notification_count`
(Android). Deux lectures possibles, toutes deux de votre côté : la valeur
envoyée vaut `1`, ou elle est absente — auquel cas iOS conserve la précédente,
ce qui produit exactement le même symptôme.

Vérification demandée avant tout le reste : recevoir deux notifications app
fermée et relever la charge émise. Si `aps.badge` y vaut `2`, le sujet est
clos côté serveur et je reprends l'enquête côté client.

---

## D1 — vérifier que le correctif B3 est bien en production

**Priorité : haute.**

Trois hypothèses, à départager de votre côté :

1. le correctif n'est pas déployé sur l'environnement de production ;
2. il l'est, mais **un autre producteur de notifications** n'y passe pas et
   garde le `1` codé en dur — c'est l'hypothèse la plus probable étant donné que
   le symptôme est constant, y compris sur les messages ;
3. la valeur est calculée mais pas transmise (perdue par la couche FCM, ou
   posée sur `data` au lieu de `aps`).

Ce qui aiderait, dans l'ordre : la charge exacte d'un envoi de production
(journal du producteur, `aps` compris), et la liste des chemins de code qui
construisent une charge push.

---

## D2 — le badge doit compter les notifications **et** les messages

**Priorité : haute.** C'est la demande de fond, et elle élargit B3.

B3 ne parlait que des notifications. Or iOS n'offre **qu'un** badge par app :
celui de l'icône est le total de ce qui reste à lire, toutes natures confondues.
Un message non lu compte autant qu'une notification non lue — l'utilisateur ne
distingue pas les deux quand il regarde son écran d'accueil.

Demandé, sur **tout** envoi push, y compris ceux de la messagerie :

```json
{
  "aps": {
    "alert": { "title": "…", "body": "…" },
    "badge": 7,
    "sound": "default"
  }
}
```

où `7` = *notifications non lues* + *messages non lus* du destinataire, **après**
enregistrement de ce qui part. Équivalent Android :
`notification.notification_count`.

Trois précisions qui évitent un aller-retour :

- **des messages, pas des conversations.** Cinq messages non lus dans un seul fil
  comptent pour cinq. C'est exactement le défaut que le client vient de corriger
  chez lui ; le serveur doit compter de la même façon, sans quoi les deux
  autorités du badge se contrediront à chaque bascule premier plan / arrière-plan.
- **zéro est une valeur légitime.** C'est ainsi qu'un badge s'efface, et la
  réponse au lot 7 le dit déjà. Un champ absent, lui, laisse la valeur
  précédente en place : ce n'est pas équivalent.
- **la messagerie doit envoyer un badge elle aussi.** Si seuls les envois du
  domaine « notifications » portent `aps.badge`, un message reçu laisse le badge
  sur sa valeur d'avant — ce qui ressemble beaucoup au symptôme observé.

**Sans cette demande** : le badge n'est juste qu'après un lancement de l'app,
c'est-à-dire exactement quand il ne sert plus à rien.

---

## D3 — `GET /conversations/unread-count`

**Priorité : moyenne.** Confort côté client, mais aussi garantie de cohérence.

Il existe `GET /notifications/unread-count`. Il n'existe **rien** d'équivalent
pour la messagerie : le client somme le champ `unreadCount` de
`GET /conversations`, donc il doit charger la liste complète des conversations
pour connaître un seul entier — au démarrage, et à chaque retour au premier
plan.

Demandé, sur le modèle exact de l'existant (objet à une clé entière) :

```http
GET /api/conversations/unread-count
→ 200 { "unreadCount": 4 }
```

où `4` est le nombre de **messages** non lus, tous fils confondus.

Intérêt au-delà du confort : c'est cette valeur que `aps.badge` doit additionner
en D2. Qu'elle soit exposée rend les deux calculs vérifiables l'un par l'autre,
au lieu de deux implémentations qui divergent sans que personne ne s'en aperçoive.

**Sans cette demande** : le client garde sa somme locale. Elle est juste, mais
elle coûte le chargement de la liste des conversations, et elle est plafonnée à
ce que cette liste renvoie si elle est un jour paginée.

---

## D4 — effacer le badge quand la lecture a eu lieu ailleurs

**Priorité : basse.**

Cas : l'utilisateur lit ses notifications sur le web, ou sur un second appareil.
Aucun push ne part, donc le badge du téléphone resté fermé garde sa valeur — il
annonce du non-lu qui n'existe plus.

La correction habituelle est un push silencieux
(`"aps": { "content-available": 1, "badge": 0 }`, sans `alert`) émis sur
`PUT /notifications/read-all` et sur la lecture d'une conversation, vers les
**autres** appareils du compte.

**Sans cette demande** : le badge se corrige à la prochaine ouverture de l'app.
C'est le comportement actuel, et il est acceptable — d'où la priorité basse.

---

## Récapitulatif

| # | Demande | Priorité | Ce qui se dégrade sans elle |
| --- | --- | --- | --- |
| **D1** | **vérifier B3 en production** | **haute** | **le badge affiche `1` quoi qu'il arrive** |
| **D2** | **`aps.badge` = notifications + messages, sur tout envoi** | **haute** | **badge faux app fermée, et muet sur les messages** |
| D3 | `GET /conversations/unread-count` | moyenne | une liste entière chargée pour un entier |
| D4 | push silencieux d'effacement | basse | badge périmé après lecture sur un autre appareil |

## Question ouverte

Le badge doit-il compter **autre chose** que les notifications et les messages —
une demande de partenaire en attente, une confirmation de présence à donner ?
Côté client, tout ce qui réclame une action figure déjà dans l'un des deux flux ;
si ce n'est pas vrai côté serveur, dites-le, c'est le total qui change.
