# Réponse backend — le signalement d'une diffusion (août 2026)

> Réponse au §1.2 de `BACKEND_EN_ATTENTE_2026-08-17.md`, qui reprend
> `PROMPT_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md` du 2026-08-15. **Corrigé.**
>
> Vous décriviez **deux promesses non tenues** dont les effets se cumulaient.
> L'audit n'en trouve **qu'une**, mais elle explique à elle seule tout ce que
> vous avez observé.
>
> - **Le compteur de non-lus était bien en défaut**, et pour une raison qui
>   rendait le symptôme exactement aussi précis que votre titre : *la première*
>   annonce. C'est corrigé.
> - **La notification, elle, partait déjà.** Nous l'avons vérifiée avant de
>   toucher à quoi que ce soit : la push est émise, avec son type dédié et son
>   payload complet. Ce qu'elle portait de faux, c'était son **badge** — hérité
>   du même défaut. Le §3 dit ce qu'il reste alors de votre observation, et ce
>   qu'il faut regarder de votre côté.
>
> Deux points de votre liste trouvent par ailleurs leur réponse ici sans qu'il
> ait fallu écrire une ligne : votre §2.4 est **déjà livré** et y figure à tort
> (§5 — vous invitiez à le dire, c'est dit), et la question ouverte de votre
> §3.2 se répond par **oui** (§6).

---

## 1. Le défaut, et pourquoi il visait la première annonce

### Ce qui était promis

`REPONSE_BACKEND_MESSAGERIE_PROGRAMME_2026-08.md` écrivait, au §1 :

> Une diffusion est un message : elle compte dans
> `GET /api/conversations/unread-count` et dans le `unreadCount` du fil, comme
> n'importe quel autre.

C'était faux, et le reste de ce document explique pourquoi.

### Ce qui se passait

Le compte de non-lus se lisait par une jointure **interne** entre les messages
et `conversation_members` : pas de ligne de membre, pas de message compté.

Sur une conversation à deux, la ligne existe dès la création du fil, et la règle
ne se voyait pas. Sur un fil de diffusion, elle n'a pas la même vie. Nous avons
délibérément choisi que **l'appartenance soit dérivée** des inscriptions actives,
et que `conversation_members` ne porte plus que `lastReadAt` — écrit à la
**première lecture**, pas à l'envoi. C'est ce qui fait qu'un nouvel inscrit gagne
le fil et tout son historique sans qu'aucun traitement ne passe derrière lui.

Les deux décisions se contredisaient. Une diffusion arrivait chez des
participants qui n'avaient pas de ligne — et n'était comptée pour personne :

- `GET /api/conversations/unread-count` restait à son total précédent ;
- le `unreadCount` du fil valait `0` dans `GET /api/conversations` ;
- le badge d'icône, qui additionne les deux moitiés, ne bougeait pas.

Le participant devait donc **ouvrir la messagerie et regarder** — précisément ce
que vous décriviez.

### Pourquoi *la première*

Une fois le fil ouvert une fois, la ligne de membre est écrite et tout rentre
dans l'ordre : les diffusions suivantes comptent normalement. Le défaut ne
frappait donc que les annonces reçues **avant la première ouverture du fil** —
c'est-à-dire, en pratique, celle qui aurait dû faire ouvrir le fil.

Votre titre était plus exact que notre code.

### Ce que nos tests ne voyaient pas

`ProgramBroadcastIntegrationTest.leBadgeDUnPartant_neDoitPasResterBloque`
mesurait bien un compte de non-lus sur un fil de diffusion. Mais il appelait
`markAsRead` **avant** de mesurer — pour installer le `lastReadAt` dont le test
avait besoin. Cet appel créait la ligne de membre au passage, et refermait
exactement l'intervalle où le défaut se logeait.

Le test était juste ; il ne posait pas la bonne question. Aucun de nos tests ne
comptait les non-lus d'un participant qui n'avait **jamais** ouvert le fil,
c'est-à-dire le seul état dans lequel se trouve quelqu'un qui reçoit une
première annonce.

---

## 2. Le correctif

La jointure sur `conversation_members` devient **externe**, dans les deux
requêtes de comptage (`countUnreadByUserId` et
`countUnreadByUserIdAndConversationId`). L'appartenance, elle, devient une
condition à part entière, et prend les deux formes qu'elle a réellement :

- **conversation à deux** — la ligne de membre fait foi, comme avant ;
- **fil de diffusion** — inscription `ACTIVE` au programme, ou auteur du
  programme ; la ligne de membre n'entre pas en jeu.

Ligne absente vaut désormais `lastReadAt` nul, c'est-à-dire « fil jamais
ouvert » — la même chose que ce que la ligne aurait dit. Le partage annoncé au
lot précédent est enfin celui que le code applique : `conversation_members` porte
la lecture, le programme porte le droit.

**Aucun changement de contrat.** Pas de route nouvelle, pas de champ nouveau, pas
de migration : les mêmes appels rendent désormais les bons nombres. Rien à
changer chez vous.

**Aucune reprise de données non plus** : le compte se recalcule à chaque lecture.
Les diffusions déjà parties et jamais signalées comptent à partir de maintenant,
sans que rien n'ait à être rejoué.

### Ce qui ne change pas, et qui a été revérifié

- Un participant **parti** ne garde pas au badge les messages d'un fil qu'il ne
  peut plus ouvrir. C'était l'objet de la clause d'origine, et elle est
  préservée : sans elle, il resterait avec un nombre impossible à faire
  retomber.
- L'**auteur** ne compte pas ses propres diffusions : envoyer n'est pas recevoir.
- La somme des `unreadCount` de la liste retombe sur `unread-count`. Les deux
  requêtes appliquent la même règle, à dessein — un badge qui diffère selon la
  façon dont on le calcule est un badge faux deux fois.

---

## 3. La notification : elle partait, et ce qu'il reste de votre observation

Nous ne l'avons pas supposé, nous l'avons mesuré. Un test d'intégration
substitue le service de push et observe ce qui lui est demandé lors d'une
**première** diffusion. Résultat : l'envoi a bien lieu, pour le participant et
pas pour l'auteur, avec

```
type    : PROGRAM_BROADCAST          (et non NEW_MESSAGE)
payload : programId, programTitle, conversationId, messageId,
          senderId, messageAuthorName, messageBody
```

C'est le contrat annoncé. **Cette moitié du §1.2 n'était pas en défaut côté
serveur**, et nous la verrouillons désormais par un test plutôt que par une
intention.

Reste que votre observation était une observation. Trois choses peuvent
l'expliquer, et deux vous appartiennent :

1. **Le badge de cette push valait faux.** Elle porte le total réel du
   destinataire, lu par le même compteur que le §1 — il ne voyait pas la
   diffusion. La bannière arrivait donc avec `aps.badge` **inchangé**, et sur un
   appareil sans autre non-lu, avec `0` : iOS efface l'icône dans le même
   mouvement où il affiche la bannière. Une notification qui n'incrémente rien
   ressemble beaucoup à une notification qui n'arrive pas. **Corrigé** : le test
   vérifie maintenant que le badge vaut `1` sur une première diffusion.
2. **Aucun jeton d'appareil enregistré** pour le compte destinataire : l'envoi
   sort alors sans erreur et sans destinataire. C'est le premier point à
   vérifier si vous reproduisez encore — sur les deux comptes réels de votre
   reproduction.
3. **`pushEnabled` à faux** pour `PROGRAM_BROADCAST` chez ce destinataire. Le
   défaut est `true` quand aucune préférence n'existe, mais une préférence
   écrite explicitement fait foi. Le type est à ajouter à
   `notification_pref_catalog.dart` — c'était déjà signalé au lot précédent, et
   ça le reste.

Si après ce correctif une diffusion ne fait toujours rien apparaître, ce sont les
points 2 et 3 qu'il faut instrumenter, et nous vous aiderons volontiers à lire
les journaux d'envoi côté serveur.

### La notification in-app reste volontairement absente

Une diffusion ne crée pas d'entrée dans le centre de notifications. Le lot
précédent le justifiait ainsi : la doubler ferait compter deux fois la même chose
sur l'icône, le badge additionnant notifications non lues et messages non lus.

Cet argument supposait que la diffusion comptât dans les messages non lus. Elle
ne le faisait pas — d'où le cumul que vous décriviez : ni l'un, ni l'autre.
Maintenant qu'elle y compte, **l'argument redevient valable** et nous maintenons
le choix.

Si vous voulez malgré tout une entrée in-app — pour qu'une diffusion se retrouve
dans l'historique des notifications et pas seulement dans la messagerie —
dites-le : c'est une ligne, et il faudra alors décider laquelle des deux compte
au badge. Nous ne l'avons pas tranché à votre place.

---

## 4. Vérification

Nouvelle classe `ProgramBroadcastSignalementIntegrationTest`, **8 tests**, tous
sur une **première** diffusion jamais précédée d'une lecture — la condition qui
manquait à la couverture existante :

| Test | Ce qu'il verrouille |
|---|---|
| `unePremiereDiffusion_doitCompterAuBadgeDuParticipant` | `unread-count` vaut 1 sans ligne de membre |
| `unePremiereDiffusion_doitCompterDansLeFil` | `unreadCount` du fil vaut 1 — la somme retombe sur le total |
| `uneDiffusionLue_neDoitPlusCompter` | la lecture fait retomber à 0, la suivante compte à nouveau |
| `lAuteur_neDoitPasCompterSesPropresDiffusions` | envoyer n'est pas recevoir |
| `unNouvelInscrit_doitTrouverLHistoriqueNonLu` | l'historique gagné est un historique non lu |
| `unPartant_neDoitPasGarderLeCompteDUnFilQuIlNeVoitPlus` | non-régression de la clause d'origine |
| `unePremiereDiffusion_doitPartirEnPushAvecSonBadge` | type `PROGRAM_BROADCAST`, payload, **badge à 1** |
| `lExpediteur_neDoitPasRecevoirSaPropreDiffusion` | l'auteur n'est pas notifié de sa propre annonce |

Les **quatre tests de comptage** échouaient avant le correctif, tous en rendant
`0` là où `1` était attendu. Les huit passent après.

Voisinage relancé sans régression : `ProgramBroadcastIntegrationTest`,
`ConversationUnreadCountIntegrationTest`, `ChatServiceTest`,
`ChatPushListenerTest`, `ChatControllerTest`.

---

## 5. Un point de votre liste est déjà livré : `mutable-content` (§2.4)

Votre §2.4 dit que la charge APNs ne porte pas `mutable-content: 1`, et que vos
deux extensions iOS restent inertes. Votre §5 classait ce point parmi les quatre
« rapportés sur la foi d'observations antérieures ». Il est **périmé**.

La charge de toute push **visible** porte, depuis le 2026-08-12 :

```
aps : { badge, sound, mutable-content: 1, category: <catégorie du template> }
```

`category` accompagne `mutable-content` parce que l'une sans l'autre ne réveille
rien. Les deux ont été posées **avant** que vos extensions existent, précisément
pour qu'elles ne soient pas du code mort le jour de leur livraison.

À noter, parce que la distinction compte pour vous : la push **silencieuse** de
correction de badge ne les porte ni l'une ni l'autre, et c'est voulu — il n'y a
pas d'`alert` à enrichir, et ces deux clés appartiennent aux pushes visibles.

Si vos extensions ne se déclenchent toujours pas, ce n'est donc pas la charge :
c'est à chercher du côté de leur enregistrement ou de la catégorie qu'elles
déclarent. Dites-nous quelle catégorie elles attendent et nous confirmerons
qu'elle correspond à celle que nous envoyons.

---

## 6. Votre question du §3.2 : oui, et vous pouvez retirer la seconde lecture

> Si les routes par contexte servaient déjà ces deux champs à un appelant
> authentifié, dites-le : nous retirerions cette seconde lecture.

**Elles les servent.** Les trois routes rendent le même `SlotRecapDto` que
`/api/recaps/mine`, par le même rendu, avec le même identifiant d'appelant :

```
GET /api/programs/{programId}/recaps    ⎫
GET /api/activities/{activityId}/recaps ⎬ → toDto(recap, appelant authentifié)
GET /api/users/{userId}/recaps          ⎭
```

`canContribute` est calculé pour l'appelant — fenêtre de contribution encore
ouverte **et** présence confirmée sur cette occurrence — et `myVibes` rend ses
propres ambiances. Ni l'un ni l'autre n'a jamais dépendu de la route empruntée :
ce sont des champs du DTO, remplis depuis le porteur du jeton.

Pour l'usage que vous décrivez — « retrouver ma contribution sur les cartes que
la route décide d'exister » — la seconde lecture est donc **redondante, et peut
partir**.

Une nuance, pour que le retrait ne vous surprenne pas ailleurs : `/recaps/mine`
n'est pas l'équivalent des trois routes. Il peut porter des cartes qu'elles
excluent délibérément — la carte **privée** d'une séance à laquelle vous étiez
n'apparaît pas sur `/users/{userId}/recaps`, qui ne sert que le public, une carte
privée d'un tiers n'ayant pas à s'afficher sur un profil. Si un écran comptait
sur cette lecture pour faire apparaître ces cartes-là, et non pour enrichir les
autres, il perdrait quelque chose. Pour les deux champs en question, non.

---

## Ce qui est livré

| Point | État | Où |
|---|---|---|
| §1.2 — une diffusion compte au badge et dans le fil | **Corrigé** | `MessageRepository`, deux requêtes |
| §1.2 — la push de diffusion | **Vérifié, n'était pas en défaut** | test dédié |
| §1.2 — le badge porté par cette push | **Corrigé** | conséquence du premier point |
| §2.4 — `mutable-content` sur les pushs visibles | **Déjà livré** (2026-08-12) | `PushNotificationService.visibleAps` |
| §3.2 — `canContribute` / `myVibes` sur les routes par contexte | **Déjà servis** | `SlotRecapService.toDto` |

Aucun changement de contrat, aucune migration, aucune reprise de données.
