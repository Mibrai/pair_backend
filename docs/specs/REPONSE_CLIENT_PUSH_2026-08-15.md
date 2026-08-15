# Réponse client — notifications poussées (15/08/2026)

> Réponse à l'« Addendum du 15/08 » de
> `REPONSE_BACKEND_NOTIFICATION_TEMPLATE_2026-08.md`.
>
> **D'abord, une correction de notre côté.** `TODO_BACKEND_PUSH_2026-08-15.md` a
> été écrit sans votre réponse sous les yeux — elle n'existait pas encore dans
> notre dépôt à ce moment — et il classe T1 « bloquante » sur la foi d'une
> observation d'appareil, pas d'une lecture de votre code. Vous avez raison :
> `mutable-content` part depuis le lot précédent, et quatre des six tâches
> étaient déjà livrées. Le TODO est caduc sur ces points ; ce document le
> remplace. Nous en tirons une règle pour la suite : ne plus déduire l'état du
> serveur de ce que montre un téléphone.
>
> **Ce que nous avons livré le 15/08, et qui change le cadre de T5 :** les deux
> extensions iOS. Détail plus bas.

---

## Vos quatre questions, dans l'ordre

### 1. Le rebours : **relatif**, comme aujourd'hui

Gardez `{rebours}` (« dans 45 min »). Deux raisons, et la première est nouvelle
depuis ce matin :

- **sur iOS, il ne vieillit plus.** L'extension de service que nous venons de
  livrer réécrit `title`/`subtitle`/`body` **au moment de l'affichage**, à partir
  de `sessionAt`, sur l'appareil et dans son fuseau. Une push restée deux heures
  dans le centre affiche donc le temps restant réel, pas celui de l'émission ;
- **sur Android, le segment périmé est corrigé par le suivant.** La formule pose
  l'heure absolue juste après le rebours — `dans 45 min · Aujourd'hui 19:00 –
  20:00`. Quelqu'un qui lit une bannière vieille de deux heures voit une
  incohérence entre les deux segments, et c'est la valeur absolue qui fait foi.
  Une formulation absolue seule (« à 20:00 ») perdrait l'urgence, qui est
  précisément ce qu'un rappel doit transmettre.

Votre règle « pas de rebours vers une séance annulée ou passée » est celle que
nous appliquons aussi, des deux côtés : `SLOT_CANCELLED`, `PROGRAM_CANCELLED` et
`ATTENDANCE_PROMPT` ne décomptent jamais, même quand `sessionAt` est présent.
C'est vérifié par nos tests, en Dart comme en Swift.

### 2. L'ordre de sacrifice : **ne jamais évincer `placeName`**

L'ordre que vous proposez — `authorAvatarUrl`, `addressPublic`, `welcomeNote`,
`placeName` — nous convient **sauf sur son dernier terme**.

`placeName` est un nom de lieu : une trentaine de caractères (« Piscine du
Rhône »). `addressPublic` est la ligne longue (jusqu'à 300 en base). Sacrifier le
premier ne libère presque rien et coûte la zone 3 de la carte — la ligne « où » —
alors que sacrifier le second libère dix fois plus pour une perte de précision
seulement.

Ordre demandé :

1. `authorAvatarUrl` — repli sur les initiales, invisible pour l'utilisateur ;
2. `welcomeNote` — nous ne l'affichons nulle part ;
3. `addressPublic` — la carte garde le nom du lieu ;
4. **rien d'autre.** `placeName`, `programTitle`, `activityName`, `sessionAt`,
   `type` et les identifiants ne doivent jamais être évincés : les trois premiers
   vident une zone, `sessionAt` supprime date, heure et rebours d'un coup, et les
   deux derniers cassent le routage du tap.

Si le plafond reste dépassé après ces trois évictions, la bonne réponse est votre
`ERROR` : c'est une charge anormale, pas un cas à dégrader silencieusement.

### 3. `NEARBY_PROGRAM` : **non, pas maintenant**

Retirez-le de vos demandes. Nous l'avions cité parce que la maquette le range
avec les deux autres types de la variante A, sans savoir qu'aucun producteur ne
l'émet. Un fan-out de proximité est une fonctionnalité à part entière — règle de
déclenchement, rayon, fréquence, et une question de vie privée que personne n'a
tranchée — et nous n'avons pas de besoin produit qui la justifie aujourd'hui.

Le client sait déjà l'afficher : le jour où ce type existera, il se rendra sans
changement côté app.

### 4. Les en-têtes APNs explicites : **oui si c'est gratuit, non sinon**

Vous avez raison, FCM v1 les renseigne dès qu'un bloc `notification` est présent.
Nous ne demandons pas de travail pour ça. Si les poser explicitement tient en
deux lignes, prenez-les — cela rend le contrat lisible sans avoir à connaître le
comportement implicite de FCM. Ce n'est pas une demande.

---

## Vos trois confirmations attendues

**L'adresse absente quand le lieu n'est pas diffusable — confirmé, notre repli
couvre le cas.** La ligne de lieu disparaît, la carte reste complète, aucune
mention d'une adresse masquée. Nous confirmons aussi la restriction que vous
assumez : un participant `CONFIRMED` ne verra pas l'adresse dans la notification
alors qu'il la voit dans l'app. C'est acceptable — la notification amène à
l'écran qui, lui, sait à qui il parle. Ne composez pas un payload par
destinataire pour ça.

**`NEW_MESSAGE` sans notification in-app — confirmé, et c'est bien ainsi.** Notre
liste in-app n'attend aucune carte « message » ; `messageAuthorName` et
`messageBody` ne nous servent que dans la charge push, où la bannière et la vue
déployée les affichent. L'option B n'est pas un besoin de notre côté.

**T3, un programme sans créneau n'est plus annoncé — confirmé, c'est ce qu'il
faut.** Une annonce sans date ni lieu était exactement la carte creuse dont nous
nous plaignions. L'annonce unique par programme nous va également.

---

## Ce que nous avons livré le 15/08 (et ce que ça implique pour vous)

| Extension iOS | Rôle |
|---|---|
| `MeetdoNotificationService` | Réécrit la bannière **repliée** sur l'appareil, à partir des champs bruts de `data` : titre du programme, sous-titre `{activityName} · par {authorName}`, corps sur deux lignes, rebours recalculé. |
| `MeetdoNotificationContent` | Rend la **vue déployée** — les cinq zones du template, la couleur d'activité, la bulle de message, le rebours recalculé une fois de plus à l'ouverture. |

Trois conséquences de votre côté :

1. **Sur iOS, votre `title`/`body` deviennent un repli.** Ils restent nécessaires
   — ils s'affichent tels quels si la charge ne porte pas `programTitle`, ou si
   l'extension échoue — mais ce n'est plus eux qu'on lit dans le cas nominal.
   Ne les supprimez pas ; ne les optimisez pas non plus.
2. **T5 ne concerne donc plus vraiment iOS.** Le groupement par
   `(langue, plateforme)` et le `subtitle` iOS que vous chiffrez restent utiles
   comme repli, mais l'urgence est **Android**, qui n'a que le texte du serveur.
   Si vous devez couper T5 en deux, faites les formules Android d'abord.
3. **La catégorie est posée par l'extension elle-même.** Votre
   `APNS_TEMPLATE_CATEGORY` reste contractuelle et nous la gardons alignée sur
   `MEETDO_TEMPLATE`, mais la vue déployée fonctionnerait même si elle
   disparaissait de la charge.

---

## Une clé pour deux emplacements — un point à clarifier

Depuis votre lot, une notification de séance peut porter **`placeName` et
`addressPublic`** (l'adresse filtrée par `SlotAddressVisibility`). Notre client
lit aujourd'hui la première des deux et ignore la seconde : la zone 3 affiche
« Piscine du Rhône » là où la maquette montre « Piscine du Rhône, 8 quai Claude
Bernard, Lyon ».

C'est un défaut de lecture chez nous, pas un défaut de contrat chez vous : nous
le corrigeons pour composer les deux quand les deux arrivent. Aucune action de
votre côté — nous le signalons pour que vous sachiez que **servir les deux clés
est utile**, et que l'ordre de sacrifice de la question 2 s'entend bien ainsi :
perdre `addressPublic` dégrade la précision, perdre `placeName` supprime la
ligne.

---

## Ce que nous vérifions de notre côté

1. **`FIREBASE_ENABLED`** en production — merci pour le pointeur, c'est la
   première chose que nous regardons. Pouvez-vous confirmer sa valeur sur
   l'environnement Railway déployé ? Si elle est à `false`, tout le reste de ce
   dossier est sans objet tant qu'elle n'est pas basculée.
2. **Le jeton d'appareil.** Le nôtre datait d'avant la réinstallation de l'app :
   FCM accepte un jeton périmé, APNs le rejette, et rien ne le signale. Nous
   repartons d'un jeton frais avant de conclure quoi que ce soit sur un
   téléphone.

## Une demande, une seule

**Un moyen de déclencher une notification de test sur un compte donné.** Un
endpoint d'administration (`POST /admin/notifications/test`, corps `{userId,
type}`) qui émette une vraie notification par le chemin de production nous
éviterait la manœuvre actuelle — deux comptes, un créneau, une inscription — pour
vérifier une bannière. Ce n'est pas bloquant, mais chaque aller-retour de ce
dossier a coûté une demi-heure de mise en scène.
