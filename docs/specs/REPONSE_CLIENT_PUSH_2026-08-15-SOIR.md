# Réponse client — notifications poussées (15/08/2026, soir)

> Réponse à `REPONSE_BACKEND_PUSH_2026-08-15.md`.
>
> **Le fuseau : nous l'envoyons.** C'est livré côté client, et le champ part
> déjà — vérifié contre la production, il est accepté et ignoré sans erreur.
> Vous pouvez donc ajouter la colonne quand cela vous arrange, sans fenêtre de
> coordination.
>
> Vos deux autres questions : **non** à l'endpoint de test, **non** aux en-têtes
> explicites. Votre argument sur la ventilation des journaux règle le besoin.

---

## 1. Le fuseau horaire — livré, et le contrat exact

`POST /notifications/devices` reçoit désormais un champ de plus, à côté de
`locale` :

```jsonc
{
  "token": "…",
  "platform": "IOS",
  "locale": "fr",
  "timezone": "Europe/Paris"   // ← nouveau, étiquette IANA
}
```

**Étiquette IANA, jamais un décalage.** `+02:00` décrit un instant, pas une
règle : un rappel envoyé fin octobre pour une séance de novembre serait décalé
d'une heure. `Europe/Paris` porte le changement d'heure avec lui. (Nous avions
la même question côté client et c'est la raison pour laquelle nous n'utilisons
pas `DateTime.timeZoneOffset`.)

**Le champ est omis quand la plateforme ne sait pas répondre** — plutôt
qu'envoyé vide. Votre repli sur `pair.push.zone` reste donc le comportement
nominal pour ces appareils, exactement comme aujourd'hui.

**Il est relu à chaque enregistrement, pas résolu une fois au démarrage.** Un
fuseau change en cours de vie de l'app : on voyage. Le garde-fou d'idempotence
compare maintenant le triplet (jeton, langue, fuseau), donc un changement de
fuseau déclenche un ré-enregistrement du même jeton — c'est le moment précis où
l'écart se verrait.

### Ce que nous attendons de vous

1. la colonne `timezone` sur `device_tokens`, alimentée par ce champ ;
2. le formatage des heures dans ce fuseau, avec repli sur `pair.push.zone`
   quand il est absent ;
3. si vous le renvoyez dans `DeviceTokenDto`, nous le lisons déjà et le
   journalisons — un écho différent de ce que nous avons envoyé nous signalerait
   un repli de votre côté, comme pour `locale`.

### Vous pouvez livrer quand vous voulez : le champ ne casse rien aujourd'hui

Vérifié contre la production ce soir, avec un jeton de sonde créé puis
supprimé :

```
POST /api/notifications/devices  {"token":"probe-…","platform":"IOS",
                                  "locale":"fr","timezone":"Europe/London"}
→ HTTP 200, réponse sans champ `timezone`
DELETE /api/notifications/devices/probe-…  → HTTP 200
```

Votre DTO ignore donc les propriétés inconnues. C'était le seul risque de
séquencement — un `FAIL_ON_UNKNOWN_PROPERTIES` aurait fait échouer
**l'enregistrement entier**, donc supprimé toutes les notifications de
l'appareil pour une heure d'affichage. Il n'y a pas lieu de coordonner les deux
livraisons.

---

## 2. L'endpoint de test — non, vous avez raison

Nous retirons la demande. La ventilation des échecs répond au même besoin par le
bon bout : elle dit ce qui se passe pour une **vraie** notification, là où un
endpoint de test aurait dit ce qui se passe pour une notification fabriquée. Et
elle ne demande pas de route d'administration, donc pas de garde d'autorisation à
décider.

Si le diagnostic s'enlise malgré les journaux, nous le redemanderons — avec un
cas d'usage précis cette fois.

## 3. Les en-têtes APNs explicites — non

Sans objet, comme convenu. FCM v1 les renseigne, et nous n'avons rien observé qui
suggère le contraire.

---

## 4. Sur `INVALID_ARGUMENT` — une piste, sans vous demander de trancher à chaud

Vous signalez que `cleanInvalidTokens` supprime le jeton sur `INVALID_ARGUMENT`
autant que sur `UNREGISTERED`, et qu'`INVALID_ARGUMENT` peut aussi vouloir dire
« charge invalide ». Nous partageons l'analyse et l'arbitrage : ne pas y toucher
dans l'urgence.

Une piste pour quand vous y reviendrez à froid : FCM distingue les deux dans le
**message** de l'exception, pas dans le code. Une charge invalide nomme le champ
fautif (`Invalid JSON payload received…`), un jeton invalide dit
`The registration token is not a valid FCM registration token`. Ne supprimer que
sur ce second cas, et journaliser un `WARN` sur le premier, garderait le
nettoyage utile sans qu'une faute de payload coûte un appareil. Ce n'est pas une
demande — c'est ce que nous ferions.

Nous appliquons votre conseil en attendant : notre appareil de test repart d'un
jeton neuf avant toute conclusion.

---

## 5. Ce qui a bougé côté client depuis notre dernier document

| Quoi | État |
|---|---|
| `placeName` **et** `addressPublic` composés en une ligne | livré, huit tests (Dart et Swift) |
| `timezone` envoyé à `POST /notifications/devices` | livré, quatre tests |
| Les deux extensions iOS | livrées, installées sur l'appareil de test |

Le rappel du contexte, pour que la lecture des journaux soit sans ambiguïté :
**l'appareil de test porte maintenant une version qui envoie `timezone`**. Si
vous voyez ce champ arriver avant d'avoir la colonne, c'est normal.

## 6. Ce qui reste chez nous

1. **Réenregistrer le jeton** — la prochaine ouverture de l'app le fait, et elle
   enverra `timezone` avec.
2. **Déclencher un rappel réel et lire les journaux Railway.** Nous suivrons
   votre tableau : `THIRD_PARTY_AUTH_ERROR` → console Firebase ;
   `UNREGISTERED` → notre jeton ; `Sent n …` sans `WARN` → l'enquête revient
   entièrement chez nous, et nous la prendrons.

Merci pour la ventilation des échecs. C'était le point aveugle du dossier, des
deux côtés : nous cherchions dans le rendu ce qu'un journal muet nous cachait
dans le transport.
