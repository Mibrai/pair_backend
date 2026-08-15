# Backend — ce qu'il reste à faire pour les notifications poussées

> Rédigé le 15/08/2026, après la livraison côté iOS des deux surfaces natives du
> template. **Ce document est autoportant** : il ne suppose pas d'avoir lu
> `PROMPT_BACKEND_NOTIFICATION_TEMPLATE_2026-08.md`, qui reste l'historique
> complet du dossier.

## En une phrase

Le client sait rendre le template de notification sur les trois surfaces
(carte in-app, bannière repliée, vue déployée sur l'écran verrouillé). Il manque
**une clé dans la charge APNs** pour que les deux surfaces natives d'iOS
s'exécutent, et **six champs dans `data`** pour qu'elles aient quelque chose à
afficher.

| # | Tâche | Priorité | Sans elle |
|---|---|---|---|
| T1 | `aps.mutable-content: 1` | **bloquante** | Les deux extensions iOS ne s'exécutent jamais. |
| T2 | Six champs à ajouter dans `data` | haute | Chaque champ absent retire sa zone de la carte. |
| T3 | `sessionAt` / `placeName` / `scheduleId` sur les trois types « variante A » | moyenne | Ces notifications s'affichent sans date, sans lieu, sans rebours. |
| T4 | Tronquer `messageBody` à 120 caractères **à l'émission** | haute | Les messages longs ne sont pas notifiés du tout, silencieusement. |
| T5 | Composer `title` / `subtitle` / `body` selon la formule ci-dessous | moyenne | Le texte de repli reste approximatif ; Android n'a que ça. |
| T6 | Confirmer la planification du rappel à T-2 h | à vérifier | Impossible de distinguer « pas envoyé » de « mal rendu ». |

---

## T1 — `aps.mutable-content: 1` — **bloquante**

### Ce qui a été livré côté iOS le 15/08

| Extension | Rôle |
|---|---|
| `MeetdoNotificationService` | Réécrit `title` / `subtitle` / `body` de la bannière **repliée** sur l'appareil, dans sa langue et son fuseau, avec le compte à rebours recalculé au moment de l'affichage. |
| `MeetdoNotificationContent` | Rend la **vue déployée** (celle qu'on obtient en tirant la bannière vers le bas) : activité, titre, auteur, date, heure, lieu, bulle de message, compte à rebours. |

Les deux sont installées et reconnues par iOS — vérifié sur l'appareil :
`PluginBundleIds = ("com.meetdo.app.NotificationContent", "com.meetdo.app.NotificationService")`.

### Le verrou

iOS n'exécute une extension de service **que** si la charge APNs porte
`mutable-content`. Tant que la clé manque, les deux extensions sont compilées,
signées, embarquées… et jamais appelées. Rien ne le signale : la bannière
s'affiche normalement, avec le texte du serveur.

### Ce qu'il faut envoyer

```jsonc
// FCM HTTP v1 — POST /v1/projects/meetdo-76ab7/messages:send
{
  "message": {
    "token": "<jeton de l'appareil>",
    "notification": {                    // inchangé : repli, et seule source sur Android
      "title": "…",
      "body": "…"
    },
    "data": { /* voir T2 */ },
    "apns": {
      "headers": {
        "apns-priority": "10",
        "apns-push-type": "alert"        // exigé par APNs depuis iOS 13
      },
      "payload": {
        "aps": {
          "mutable-content": 1,          // ← LA clé
          "category": "MEETDO_TEMPLATE", // facultatif, voir ci-dessous
          "sound": "default"
        }
      }
    }
  }
}
```

Avec le SDK Admin Java :

```java
ApnsConfig.builder()
    .putHeader("apns-priority", "10")
    .putHeader("apns-push-type", "alert")
    .setAps(Aps.builder()
        .setMutableContent(true)
        .setCategory("MEETDO_TEMPLATE")
        .setSound("default")
        .build())
    .build();
```

**`category` n'est pas obligatoire** : l'extension de service pose elle-même
`MEETDO_TEMPLATE` sur la notification, ce qui suffit à déclencher la vue
déployée. L'envoyer quand même a un avantage — la vue déployée fonctionnerait
alors même si l'extension de service échouait.

**Ne pas toucher aux push de fond** (celles qui ne portent que `badge`, avec
`apns-push-type: background` et `apns-priority: 5`) : `mutable-content` ne les
concerne pas et APNs rejette une push de fond mal étiquetée.

---

## T2 — Les six champs qui manquent dans `data`

Servis aujourd'hui, à ne pas changer : `type`, `programId`, `scheduleId`,
`activityId`, `activityName`, `programTitle`, `categoryId`, `categoryColorRamp`,
`placeName`, `sessionAt`, `fromUserId`, `fromUserName`, `conversationId`.

À ajouter :

| Clé | Exemple | Ce qu'on perd sans elle |
|---|---|---|
| `authorName` | `"Lena Müller"` | La ligne « par Lena Müller » sous le titre. |
| `authorAvatarUrl` | `"https://…/avatars/2a19.jpg"` | La photo de l'auteur — repli sur ses initiales, donc non bloquant. |
| `categoryIcon` | `"pool"` | L'icône de la pastille (clé Material : `pool`, `directions_run`, `sports_tennis`, `fitness_center`…). Repli : une icône générique. |
| `endsAt` | `"2026-08-17T18:00:00Z"` | « 19:00 » au lieu de « 19:00 – 20:00 ». |
| `messageAuthorName` | `"Sophie Martin"` | Le nom au-dessus de la bulle d'une `NEW_MESSAGE`. |
| `messageBody` | `"On se retrouve devant le court 3 ?"` | La bulle de message entière. |

Contraintes de la charge `data` :

- **toutes les valeurs sont des chaînes** (contrainte FCM) — y compris les
  nombres et les booléens ;
- **les dates sont en ISO 8601** (`2026-08-17T17:00:00Z`, avec ou sans fraction
  de seconde, les deux sont lues) ;
- une clé absente et une chaîne vide sont traitées pareil, donc `"authorName": ""`
  ne casse rien ;
- le client accepte aussi le `snake_case` (`author_name`), mais le `camelCase`
  est la forme de référence ;
- APNs plafonne la charge à **4 Ko**, tout compris.

---

## T3 — Les trois types de la « variante A »

`AUTHOR_NEW_PROGRAM`, `NEARBY_PROGRAM`, `ACTIVITY_NEW_PROGRAM` portent sur un
**programme**, mais la maquette leur demande d'annoncer un **créneau** : date,
heure, lieu et compte à rebours vers le premier créneau à venir.

Demandé : y servir `sessionAt`, `placeName` et `scheduleId` du **premier créneau
à venir** du programme. Le client est déjà prêt — ces trois types décomptent dès
que `sessionAt` arrive.

Sans cela, ces notifications rendent un titre, un auteur, et rien d'autre.

---

## T4 — Tronquer `messageBody` à l'émission

À couper à **120 caractères**, sur une frontière de mot, suivi d'une ellipse
(« … »).

Ce n'est pas cosmétique : APNs plafonne la charge à 4 Ko et un message de trois
mille caractères ne produit pas une bannière tronquée — il fait **rejeter la
notification entière**. L'utilisateur ne reçoit rien, et rien ne le signale. Le
client applique la même coupe à l'affichage, mais elle ne protège que le rendu,
pas le transport.

---

## T5 — La formule du texte, pour les deux plateformes

C'est le texte qui s'affiche quand l'app est fermée. Le serveur reste seul à
pouvoir le composer sur Android, et sur iOS il sert de repli quand la charge
n'est pas enrichie.

| Champ | iOS | Android |
|---|---|---|
| `title` | `{programTitle}` | `{activityName} · {programTitle}` |
| `subtitle` | `{activityName} · par {authorName}` | — (Android n'a pas de sous-titre) |
| `body` — rappel | `{rebours} · {date} {heure}` puis, à la ligne, `{placeName}` | idem, avec `· par {authorName}` en fin de première ligne |
| `body` — programme | `{date} {heure} · {rebours}` puis `{placeName}` | idem, avec l'auteur |
| `body` — message | `{messageAuthorName} : {messageBody}` puis `{date} {heure} · {placeName}` | idem |

Trois règles qui vont avec :

1. **le titre ne se tronque pas côté serveur** — iOS et Android coupent à la
   largeur réelle de l'écran, couper à l'avance perd des caractères qui
   tenaient ;
2. **l'adresse passe en dernier** — c'est la ligne la plus longue et la moins
   urgente, celle qui doit sauter quand la bannière se replie sur une ligne ;
3. **le compte à rebours ne s'affiche que si la séance est encore à venir.**
   Décompter vers une séance annulée (`SLOT_CANCELLED`, `PROGRAM_CANCELLED`) ou
   vers une séance passée dont on demande confirmation de présence
   (`ATTENDANCE_PROMPT`) est faux — et ces charges portent `sessionAt` comme les
   autres.

**La langue** est celle de `locale`, propriété de l'appareil, déjà envoyée par le
client à chaque enregistrement de jeton (`POST /notifications/devices`). Les
dates suivent cette même locale — `dim. 17 août`, `Sun 17 Aug`, `So. 17. Aug.` —
jamais un motif littéral.

La référence exécutable de cette formule est `lib/core/push/push_text.dart` et
ses tests (`test/notification_template_test.dart`) : ce que ces tests attendent
est exactement ce que le serveur doit rendre.

---

## T6 — Confirmer la planification du rappel

Règle produit : à moins de deux heures du début d'un créneau où l'utilisateur est
inscrit, un rappel part sur son appareil. Trois questions, auxquelles le client ne
peut pas répondre :

1. le `PROGRAM_REMINDER` est-il planifié pour **tous les inscrits** d'un créneau,
   ou seulement pour l'hôte ?
2. est-il **replanifié** quand un créneau est déplacé, et **annulé** quand il est
   supprimé ?
3. `scheduledAt` porte-t-il bien le **début du créneau** et non l'heure d'envoi ?
   Le client décompte vers `scheduledAt` ; les deux valeurs diffèrent de deux
   heures, et les confondre affiche « maintenant » deux heures trop tôt.

---

## Comment vérifier que T1 est passée, sans outil

Envoyer un rappel sur un iPhone qui porte la version du 15/08 et regarder la
bannière :

- **trois lignes** — le titre du programme, puis « Natation · par Lena Müller »,
  puis le rebours et le lieu → l'extension s'est exécutée, `mutable-content` est
  bien là ;
- **le texte du serveur d'un seul bloc** → la clé manque encore.

Et en tirant la bannière vers le bas : si la vue déployée montre la carte meetDo
(marque, pastille colorée, date, heure, lieu, compte à rebours), tout le chemin
fonctionne.

---

## Ce qui n'est **pas** demandé

- **Composer la vue déployée côté serveur** : elle se compose sur l'appareil, à
  partir des champs bruts. C'est ce qui permet de recalculer le compte à rebours
  à l'ouverture — une notification qui a dormi deux heures dans le centre
  afficherait sinon « dans 45 min » alors que la séance a commencé.
- **Envoyer des textes déjà mis en forme pour les dates** : le client les
  formate dans la langue et le fuseau de l'appareil.
- **Renommer quoi que ce soit.** `placeName` et `sessionAt` sont les bons noms,
  le client s'est aligné dessus.
