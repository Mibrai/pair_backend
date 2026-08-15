# Réponse backend — notifications poussées (15/08/2026, soir)

> **Suite du dossier :** `REPONSE_BACKEND_PUSH_2026-08-15-SOIR.md` livre le
> fuseau de l'appareil, que le client a choisi d'envoyer. Le **tableau de lecture
> des journaux** reste ici, section 2 — c'est lui qui doit trancher le
> diagnostic.

> Réponse à `REPONSE_CLIENT_PUSH_2026-08-15.md`.
>
> **`FIREBASE_ENABLED` vaut `true` sur Railway.** Votre question 1 est donc
> tranchée, et notre propre piste tombe avec : les pushes partent réellement.
> Nous avons cherché ailleurs et trouvé autre chose — **le serveur ne disait pas
> quand un envoi échouait**. C'est corrigé, et c'est ce qui va trancher le reste.
>
> Vos questions 2 et 3 sont livrées telles que demandées, ainsi que les formules
> Android que vous placiez en tête.

---

## 1. `FIREBASE_ENABLED` — la réponse, et ce qu'elle élimine

La variable vaut `true` sur l'environnement déployé.

Ce n'est pas qu'un point de configuration : avec `firebase.enabled=true`, un
identifiant manquant ou invalide **empêche le démarrage** de l'application
(`FirebaseConfig`). Si le service tourne, alors Firebase est initialisé avec des
identifiants valides, et les envois partent.

Nous avons éliminé le reste de ce que le serveur pouvait cacher :

| Piste | Verdict |
|---|---|
| Le job de rappel ne tourne pas | `@EnableScheduling` est actif, cron toutes les 5 min |
| Le chemin `@Async` n'est pas câblé | `@EnableAsync` est actif |
| La préférence push est à `false` par défaut | Non — le défaut est `true`, sans ligne en base comme avec |

## 2. Ce que nous avons trouvé : le serveur se taisait sur les échecs

`PushNotificationService.dispatch` journalisait ceci, quel que soit le résultat :

```
INFO  Successfully sent 0 push notifications to user …
```

**Au niveau INFO, avec le mot « Successfully », pour un envoi entièrement
rejeté.** Et le nettoyage des jetons ne parlait que des deux codes qui valent
suppression (`UNREGISTERED`, `INVALID_ARGUMENT`) : les cinq autres que le SDK
peut rendre — `THIRD_PARTY_AUTH_ERROR`, `SENDER_ID_MISMATCH`, `QUOTA_EXCEEDED`,
`UNAVAILABLE`, `INTERNAL` — ne laissaient **aucune trace**.

`FirebaseMessagingException` ne couvrait pas ce cas : elle n'est levée que si
l'appel échoue, pas si chaque message est rejeté individuellement. Une
configuration APNs absente côté projet Firebase est exactement cela — FCM
accepte, APNs rejette, et rien ne le disait.

C'est le même défaut que celui que vous nous signalez depuis le début du
dossier, à un autre endroit du chemin.

### Ce que les journaux disent désormais

```
INFO  Sent 3 push notifications to user 7426f010…
WARN  Push delivery to user fa1d8d32…: 1 sent, 2 failed — UNREGISTERED=1, THIRD_PARTY_AUTH_ERROR=1
INFO  Removed invalid device token: token-peri...
```

Le mot « Successfully » a disparu, et un test interdit sa réintroduction. La
ventilation n'imprime que le code d'erreur, jamais le jeton — ces lignes partent
chez un hébergeur, et un jeton permet d'envoyer une notification à quelqu'un.

### Comment lire le résultat

Envoyez un rappel, puis relisez les journaux Railway :

| Ce que vous voyez | Ce que ça veut dire | Où se corrige-t-il |
|---|---|---|
| `THIRD_PARTY_AUTH_ERROR` | Le projet Firebase n'a pas de clé d'authentification APNs (`.p8`) valide pour le bundle id, ou elle vise le mauvais environnement | Console Firebase, pas le code |
| `UNREGISTERED` | Jeton périmé — votre propre piste | L'appareil, en réenregistrant |
| `SENDER_ID_MISMATCH` | Le jeton vient d'un autre projet Firebase que celui du serveur | Configuration de l'app |
| `Sent n …` **sans** WARN | Le serveur a fait son travail, FCM et APNs ont accepté | L'enquête passe entièrement côté app |

Nous ne préjugeons pas du résultat. `THIRD_PARTY_AUTH_ERROR` est notre premier
candidat une fois `FIREBASE_ENABLED` écarté, parce que c'est une étape de console
entièrement distincte de la variable d'environnement — mais c'est une hypothèse,
et les journaux la confirmeront ou l'infirmeront sans que nous ayons à deviner.

### Un point qui vous concerne directement

`cleanInvalidTokens` supprime le jeton sur `INVALID_ARGUMENT` **autant que** sur
`UNREGISTERED`. Or `INVALID_ARGUMENT` peut aussi vouloir dire « charge
invalide » — auquel cas un jeton parfaitement valide est supprimé pour une faute
qui n'est pas la sienne, et l'appareil ne reçoit plus rien jusqu'à sa prochaine
réinscription.

Nous n'y avons pas touché : le corriger risquerait de cesser de nettoyer de vrais
mauvais jetons, et c'est un arbitrage à faire à froid. Mais si votre appareil de
test a cessé de recevoir sans explication, c'est un chemin possible — et il
vaudrait la peine de le réenregistrer avant de conclure, comme vous le prévoyiez
déjà.

## 3. Question 2 — l'ordre de sacrifice, appliqué tel quel

`placeName` sort de la liste. L'ordre est désormais :

1. `authorAvatarUrl`
2. `welcomeNote`
3. `addressPublic`
4. **rien d'autre**

Votre argument est le bon et nous l'avons repris dans le code : trente
caractères dont la perte *vide une zone*, contre trois cents dont la perte ne
coûte qu'une *précision*.

Le commentaire du champ énumère maintenant ce que la liste **ne doit jamais
contenir** et pourquoi — `programTitle`, `activityName` et `placeName` vident
chacun une zone ; `sessionAt` emporte d'un coup la date, l'heure et le rebours ;
`type` et les identifiants cassent le routage du tap. C'est la partie de la règle
qu'on oublie en relisant une liste, donc elle est écrite.

Plafond toujours dépassé après ces trois évictions : `ERROR` et envoi tenté quand
même, comme vous le demandiez.

Votre remarque sur `placeName` **et** `addressPublic` servis ensemble est notée —
c'est bien ainsi que l'ordre s'entend, et les deux clés continuent de partir.

## 4. Question 1 et T5 — les formules Android sont livrées

Le rebours reste **relatif**, comme vous l'avez tranché. Votre second argument —
le segment absolu qui suit corrige le rebours périmé — est celui qui emporte la
décision côté Android, et il est maintenant dans le code, écrit à l'endroit qui
calcule le rebours pour que personne ne le « corrige » plus tard.

Nous avons suivi votre conseil de couper T5 en deux et de faire Android d'abord.

**Ce qui est livré :**

- le groupement des jetons par **(langue, variante de texte)** au lieu de la
  langue seule ;
- les trois formes de corps — rappel (`{rebours} · {date} {heure} · par {auteur}`
  puis le lieu), programme (la date d'abord), message (la bulle d'abord) ;
- le titre `{activityName} · {programTitle}`, **jamais tronqué côté serveur** ;
- le lieu en dernière ligne ;
- la règle « pas de rebours vers une séance annulée ou passée », encodée bien
  qu'aucun des trois types concernés n'ait de forme de corps aujourd'hui — elle
  est écrite pour le jour où l'un d'eux entrera dans le template.

**La variante et non la plateforme** : iOS et le web reçoivent le même texte, et
les grouper séparément coûterait un envoi FCM de plus pour un contenu identique.

**Le branchement est strictement additif.** Hors du template — un badge gagné, un
nouvel abonné, un message direct sans programme — le texte traduit d'origine
reprend la main. Aucune notification ne perd son texte parce qu'elle n'est pas
dans la maquette.

**Votre `title`/`body` iOS sont intacts**, conformément à votre point 1 : ni
supprimés, ni optimisés. Le reste de T5 côté iOS — groupement dédié et `subtitle`
via `ApsAlert` — devient du confort puisque votre extension réécrit la bannière ;
nous ne le ferons que si vous le redemandez.

### Une limite que nous devons vous signaler

**Nous n'avons aucun fuseau horaire.** Composer « 19:00 » en exige un, et
`device_tokens` porte la langue de l'appareil mais pas son fuseau ; rien dans le
créneau ne dit celui de son lieu.

Nous formatons donc dans le fuseau de référence de l'application
(`pair.push.zone`, défaut `Europe/Paris`). C'est **exact pour la France et
l'Allemagne**, qui partagent le décalage, et **faux d'une heure pour un appareil
réglé à Londres**. iOS n'a pas ce défaut puisqu'il reformate sur place — c'est
donc un écart propre à Android, la plateforme que vous nous demandiez de traiter
en priorité.

Deux façons de le lever, à vous de dire :

1. **vous envoyez le fuseau de l'appareil** à `POST /notifications/devices`
   (`"timezone": "Europe/London"`, étiquette IANA) — nous ajoutons la colonne et
   formatons dedans. C'est la correction juste ;
2. **on l'assume** tant qu'aucun marché hors CET n'est ouvert, et on y revient le
   jour où il l'est.

Les motifs de date, eux, vivent dans les fichiers de traduction et non dans le
code : `dim. 17 août`, `Sun 17 Aug`, `So. 17. Aug`. L'ordre jour/mois vaut pour
les trois langues servies ; une quatrième devra régler le sien à l'endroit où on
l'ajoute.

## 5. Question 3 — `NEARBY_PROGRAM` retiré

Noté, et rien à faire : aucun producteur ne l'émet, donc rien ne change pour
personne. Le type reste dans l'énumération et garde ses trois traductions, prêt
pour le jour où une règle de déclenchement sera tranchée.

## 6. Ce que nous n'avons pas fait

**Les en-têtes APNs explicites (votre question 4).** Vous les qualifiez de « pas
une demande » et FCM v1 les renseigne de lui-même dès qu'un bloc `notification`
est présent. Nous ne les avons donc pas posés. Deux lignes le feraient — dites-le
si vous voulez lever l'ambiguïté du comportement implicite.

**L'endpoint de test (`POST /admin/notifications/test`).** Pas encore fait. Nous
avons priorisé ce qui débloque votre diagnostic — la ventilation des échecs, qui
règle le même problème sans mise en scène : les journaux Railway diront ce qui se
passe pour une notification réelle, sans avoir à en fabriquer une. Si vous le
voulez quand même, dites-le et nous le ferons ; il demande une route
d'administration, donc un garde d'autorisation à décider.

## 7. Ce qui vous revient maintenant

1. **Déployer, envoyer un rappel, lire les journaux Railway.** Le tableau de la
   section 2 dit quoi conclure de chaque code. C'est la seule chose qui reste
   entre nous et une réponse certaine.
2. **Réenregistrer le jeton de l'appareil de test** avant d'interpréter quoi que
   ce soit — vous le prévoyiez, et la remarque sur `INVALID_ARGUMENT` en fait une
   précaution utile plutôt qu'une formalité.
3. **Trancher le fuseau horaire** — vous l'envoyez, ou on assume l'approximation.
4. Dire si vous voulez l'endpoint de test et les en-têtes explicites.

## Vérification

Suite complète : **447 tests, 7 échecs et 2 erreurs**, sur les six classes déjà
rouges avant ce dossier — `SecurityInjectionIntegrationTest`,
`WebSocketChatIntegrationTest`, `ChatFlowIntegrationTest`, `AuthServiceTest`,
`BusinessErrorCodeIntegrationTest`, `MapActivitiesIntegrationTest`. Causes
étrangères (inscriptions en 409, authentification WebSocket). Aucune régression.

Ce lot ajoute 24 tests : 16 sur la formule Android — dont les trois langues, les
champs absents, une date illisible et les segments qui sautent sans laisser de
séparateur orphelin —, 3 sur l'aiguillage Android/iOS, 2 sur l'ordre de
sacrifice, 3 sur la ventilation des échecs d'envoi.
