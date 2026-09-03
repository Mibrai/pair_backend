# Réponse backend — `data.type` sur les messages FCM

**Date :** 2026-09-03
**Répond à :** `modules/notifications/PROMPT_BACKEND_2026-09-03.md`
**Concerne :** `docs/specs/TODO_BACKEND_PUSH_2026-08-15.md` (§T2)

> **Vous avez raison, et la cause est exactement celle que vous avez mesurée :
> `data.type` ne partait pas.**
>
> La réponse à votre §4.3 est la moins flatteuse des deux que vous proposiez :
> **la clé n'a jamais été envoyée.** Ce n'est pas une régression, pas un
> refactor, pas un gabarit cassé. Le §T2 du 15/08 décrivait une intention et
> l'écrivait au présent. Nous en portons la responsabilité : c'est notre
> document qui vous a fait chercher une disparition là où il n'y avait jamais
> eu de présence.
>
> **C'est corrigé, et le garde-fou du §4.4 est en place** — un test qui échoue
> si `data.type` manque, sur les 37 valeurs de l'énumération.
>
> **Vous n'avez rien à changer côté client.** Votre repli sur `SYSTEM` et votre
> refus de naviguer étaient les bons comportements ; ils ont fait exactement ce
> pour quoi ils sont écrits.

---

## 1. Ce que le code disait, avant

Le chemin est court, et il ne laisse aucune place au doute.

`NotificationService.notify(userId, type, payload)` reçoit le type et la charge
métier comme **deux paramètres distincts**. Ils ne sont jamais rapprochés.
`PushNotificationService.sendToTokens` reçoit le type lui aussi — mais ne s'en
sert que pour choisir la configuration APNs et Android (priorité,
`interruption-level`, `collapse-id`). La charge `data`, elle, était construite
ainsi :

```java
static Map<String, String> dataPayload(Map<String, Object> payload, String title, String body) {
    Map<String, String> data = new LinkedHashMap<>();
    payload.forEach((key, value) -> data.put(key, String.valueOf(value)));
    // … puis l'éviction pour tenir sous les 4 Ko
}
```

Recopie fidèle du payload métier, **rien d'ajouté**. Et aucun des treize
producteurs de notifications ne pose de clé `type` : la recherche sur tout le
dépôt ne rend que des paramètres JPA, une claim JWT et un `@RequestParam`.

La corroboration la plus nette est dans notre propre code, et elle est
embarrassante. Le commentaire qui gouverne l'ordre d'éviction dit, depuis le
15/08 :

> Ne doivent jamais y entrer […] `type` et les identifiants, **qui cassent le
> routage du tap**.

Nous avions écrit la règle qui protège la clé. Nous ne l'avions jamais posée.

## 2. Réponse au §4.3 — et deux autres clés à retirer du §T2

**« Servis aujourd'hui, à ne pas changer » était faux sur trois des treize clés
listées.**

| Clé du §T2 | État réel au 03/09 |
|---|---|
| `type` | **jamais envoyée** |
| `fromUserId` | **jamais envoyée** — zéro occurrence dans le dépôt |
| `fromUserName` | **jamais envoyée** — zéro occurrence dans le dépôt |
| les dix autres | servies, comme annoncé |

Sur les messages, ce sont `senderId` et `messageAuthorName` qui portent
l'émetteur, jamais `fromUserId` / `fromUserName`. Si votre client lit encore ces
deux noms quelque part, ils n'ont jamais rien reçu — et c'est notre erreur de
rédaction, pas la vôtre.

Votre question portait sur la nécessité d'un test de non-régression chez nous.
La réponse est oui, et il est écrit (§4 ci-dessous) — mais il ne garde pas
contre une régression : il garde contre une **affirmation jamais vérifiée**,
ce qui est le défaut réel de ce dossier.

## 3. Le correctif

`data.type` est désormais posé **à l'émetteur**, dans `dataPayload`, depuis le
`NotificationType` qui était déjà là :

```java
static Map<String, String> dataPayload(NotificationType type, Map<String, Object> payload,
                                       String title, String body) {
    Map<String, String> data = new LinkedHashMap<>();
    payload.forEach((key, value) -> data.put(key, String.valueOf(value)));

    data.put("type", type.name());
    // … puis l'éviction, inchangée
}
```

Trois décisions, et leurs raisons :

**À l'émetteur, et non chez les producteurs.** Ils sont treize, chacun compose
son payload à la main, et le type est déjà un paramètre de l'envoi. Le leur
faire répéter, c'était attendre que le quatorzième l'oublie — sans que rien ne
le signale, ce qui est précisément l'histoire que vous nous racontez. Un seul
point de pose, sur le chemin obligé de tous les envois.

**Posé après la charge métier.** Le type de l'envoi fait foi sur une clé `type`
qu'un payload porterait par accident — le type d'un signalement, celui d'un
abonnement. Aucun producteur n'en pose une aujourd'hui ; la règle est écrite
maintenant pour que le routage du tap ne puisse pas être détourné plus tard.

**`type` n'est pas évinçable.** Il ne figure pas dans la liste de sacrifice, et
un test le vérifie sur une charge qui déborde. La valeur est le nom de la
constante (`SLOT_JOINED`, `WATCH_ARRIVAL_PROMPT`…), chaîne, comme le veut FCM.

**Hors périmètre, délibérément :** la push silencieuse de correction du badge
(`content-available`, sans `alert`) ne porte que `badge`. Elle n'affiche rien et
ne peut pas être tapée : lui poser un type serait décoratif.

## 4. Le test — votre §4.4, qui comptait plus que le correctif

Quatre tests, dans `PushNotificationServiceTest` :

| Test | Ce qu'il ferme |
|---|---|
| `chaqueType_doitVoyagerDansLaCharge` — paramétré sur **les 37 valeurs** de l'énumération | Un type ajouté demain hérite du test le jour où il est écrit. C'est ce qui manquait. |
| `leType_doitArriverDansLeMessageFCM` | Relit le `MulticastMessage` remis à FCM, pas seulement l'utilitaire : un émetteur qui cesserait de l'appeler tomberait ici. |
| `chargeInderacinable_doitGarderLeType` | Le jour où quelqu'un ajoutera `type` à la liste d'éviction pour gagner quelques octets. |
| `unTypeDansLaChargeMetier_neDoitPasEcraserCeluiDeLEnvoi` | La règle de précédence du §3, avant qu'un producteur ne nomme `type` autre chose. |

**Vérifié que le garde-fou mord :** la ligne du correctif retirée, **41 tests
sur 67 échouent** dans cette classe. Remise, 67 sur 67 passent. Le test tombe
donc pour la bonne raison, et pas par accident de compilation.

## 5. Les identifiants du §4.2 — audités, tous présents

Votre tableau, vérifié producteur par producteur contre le code :

| Ce que la notification annonce | Clé attendue | État | Où c'est posé |
|---|---|---|---|
| un message, une diffusion | `conversationId`, à défaut `programId` | ✅ les deux | `ChatPushListener` |
| un programme, une séance | `programId` + `scheduleId` | ✅ | `NotificationPayload.ofSchedule` / `ofProgram` |
| un créneau (rejoint, annulé, liste d'attente, présence) | `scheduleId` | ✅ | `SlotService`, `SlotCancellationService`, `WaitlistPromoter`, `AttendancePromptJob`, `ProgramService` |
| une veille retour | `watchId` | ✅ sur les 4 envois | `WatchEscalationService` |
| un abonné, un pair, un auteur | `authorId` / `subscriberId` | ✅ | `SubscriptionService`, `ofProgram` |

Les valeurs sont normalisées en chaînes à la construction (`UUID` et `Instant`
deviennent leur représentation textuelle, une énumération son nom), et une
valeur nulle n'est pas écrite du tout plutôt qu'écrite à `null` — conforme à ce
que vous rappelez sur l'équivalence clé absente / chaîne vide.

**Une seule clé manquait, et c'était bien `type`.**

Deux cas hors de votre tableau, pour être complets : `GUARDIAN_CONSENT_REQUEST`
ne porte que `consentToken` et `ownerName` — dites-nous si l'écran de consentement
a besoin d'un identifiant de plus ; et `WATCH_LOST_ORGANIZER` porte `watchId`
comme les autres notifications de veille.

## 6. Votre §2 — le comptage est exact

Notre énumération porte bien **37 constantes**, `WATCH_LOST_ORGANIZER` comprise.
Vos 36 sont les nôtres moins `SYSTEM`. Votre analyse est juste, et nous
ajoutons une précision qui vous rassurera : **`SYSTEM` n'est émis par aucun
producteur**. Il existe dans le contrat de préférences, il n'est jamais envoyé
en push. Votre repli ne peut donc pas entrer en collision avec un type réel du
serveur — ni aujourd'hui, ni si vous le gardez sous ce nom.

## 7. Ce que nous retenons

Vous écrivez que le test comptait plus que le correctif. Nous en tirons la même
conclusion, et une de plus, sur nos documents plutôt que sur notre code : le
§T2 du 15/08 listait treize clés « servies aujourd'hui » sans que personne ne
l'ait vérifié contre le code. Trois ne l'étaient pas. C'est ce qui vous a coûté
la journée du 03/09 à écarter une hypothèse — un type inconnu — que nous
aurions pu écarter pour vous en une commande.

Nous vérifierons désormais chaque affirmation d'existant contre le code avant de
l'écrire au présent.

Votre sonde du §5 n'a plus rien à trancher, mais gardez-la : elle dira `type`
présent, et ce sera la confirmation la plus rapide que le déploiement est passé.

---

## 8. Récapitulatif

| # | Votre demande | État |
|---|---|---|
| 1 | Remettre `data.type` sur tous les messages FCM | **fait** — posé à l'émetteur, sur le chemin obligé de tous les envois |
| 2 | Vérifier les identifiants du §4.2 | **fait** — tous présents, audit au §5 |
| 3 | Régression ou intention ? | **répondu** — jamais servie ; `fromUserId` et `fromUserName` non plus |
| 4 | Un test qui échoue si `data.type` manque | **fait** — 4 tests, dont un paramétré sur les 37 types |
