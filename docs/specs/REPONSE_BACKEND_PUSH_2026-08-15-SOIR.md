# Réponse backend — le fuseau de l'appareil (15/08/2026, soir)

> Réponse à `REPONSE_CLIENT_PUSH_2026-08-15-SOIR.md`.
>
> **Le fuseau est livré.** Le champ que vous envoyez déjà est désormais lu,
> persisté, relu à chaque ré-enregistrement, et les heures des textes Android
> sont composées dedans. Vous n'avez rien à changer.
>
> Vos deux refus sont enregistrés : pas d'endpoint de test, pas d'en-têtes
> explicites. Le dossier n'a donc plus qu'un point ouvert, et il est chez vous —
> lire les journaux Railway après un rappel réel.

---

## 1. Le fuseau — ce qui est livré

| Élément | État |
|---|---|
| Colonne `device_tokens.timezone` (étiquette IANA, 64 caractères) | migration V56 |
| Lecture du champ à `POST /notifications/devices` | livré |
| Mise à jour sur **ré-enregistrement** du même jeton | livré |
| Composition des heures Android dans ce fuseau | livré |
| Repli sur `pair.push.zone` quand il manque | livré |
| Écho dans `DeviceTokenDto` (votre point 3) | livré |

Vos trois attentes sont couvertes, dans l'ordre où vous les avez écrites.

### L'écho, et ce qu'il vous dit

`DeviceTokenDto` renvoie désormais `timezone` à côté de `locale`, avec le même
contrat : **la valeur effectivement retenue, pas celle que vous avez envoyée.**

- vous récupérez `"Europe/London"` après avoir envoyé `"Europe/London"` → nous
  composerons dedans ;
- vous récupérez `null` après avoir envoyé quelque chose → l'étiquette n'a pas
  été reconnue, et nous composons dans le fuseau de référence. C'est exactement
  le signal de repli que vous décrivez pour `locale`.

### Une étiquette non reconnue ne fait pas échouer l'enregistrement

Nous validons l'étiquette à l'enregistrement, mais une valeur illisible est
**laissée tomber avec un `WARN`**, jamais transformée en erreur de requête.

Le raisonnement est le vôtre, appliqué à l'autre bout : vous nous signaliez qu'un
`FAIL_ON_UNKNOWN_PROPERTIES` aurait fait échouer l'enregistrement entier, donc
supprimé toutes les notifications de l'appareil pour une heure d'affichage. Un
`400` sur un fuseau mal orthographié aurait exactement le même coût. Le fuseau
est un agrément ; le jeton est la condition de tout le reste.

Nous validons **à l'enregistrement et non à l'envoi** pour la même raison :
`ZoneId.of` lève sur une étiquette inconnue, et une valeur illisible en base
ferait échouer la composition d'une push des mois plus tard, loin de sa cause.

### Ce qui change pour vos utilisateurs, et quand

**Rien, jusqu'à un ré-enregistrement.** Les jetons existants portent un fuseau
nul, donc le repli sur `pair.push.zone` — le comportement de tout le monde
jusqu'ici. Le premier ré-enregistrement pose la vraie valeur, et c'est à ce
moment-là que l'heure devient juste pour un appareil hors CET.

La migration ne remplit **rien** volontairement. Deviner `Europe/Paris` pour
tous donnerait la même heure qu'aujourd'hui, mais rendrait l'inconnu
indiscernable du déclaré — et nous ne saurions plus lire nos propres données.

### Le ré-enregistrement est le chemin qui compte

Votre remarque sur le garde d'idempotence comparant le triplet (jeton, langue,
fuseau) a orienté l'implémentation : le fuseau est mis à jour **sur un jeton
existant**, pas seulement à la création. N'alimenter qu'à la création aurait figé
le fuseau du premier enregistrement pour toute la vie de l'appareil — un
utilisateur qui voyage n'aurait jamais vu l'heure changer.

## 2. Ce que ça donne à l'écran

Une même séance, à la même seconde, vue de trois appareils :

```
Paris     dans 2 h · Aujourd'hui 19:00 – 20:00 · par Lena Müller
          Piscine du Rhône
Londres   in 2 h · Today 18:00 – 19:00 · by Lena Müller
          Piscine du Rhône
New York  in 2 h · Today 13:00 – 14:00 · by Lena Müller
          Piscine du Rhône
```

**Le rebours ne bouge pas** — c'est une durée, elle ne dépend d'aucun fuseau.
Seule l'heure absolue se déplace. C'est ce qui rend vos deux segments
complémentaires plutôt que redondants, et c'était l'argument sur lequel vous avez
tranché le rebours relatif.

**Le jour se juge aussi dans le fuseau de l'appareil.** Un cas que nous avons
mis sous test parce qu'il n'est pas intuitif : à 17 h à Paris, il est déjà le
lendemain à Tokyo. La même séance s'annonce alors « Demain 12:00 » à Paris et
« Today 19:00 » à Tokyo. Juger le jour côté serveur aurait écrit « demain » sur
un téléphone pour une séance qui, chez lui, a lieu le jour même.

## 3. Un détail d'implémentation qui vous concerne

Les jetons sont groupés par **(langue, variante de texte, fuseau)** pour l'envoi.
Deux conséquences visibles de votre côté :

- deux de vos appareils en français mais dans deux fuseaux reçoivent **deux
  envois FCM** au lieu d'un — c'est nécessaire, les textes diffèrent ;
- un appareil déclarant `Europe/Paris` et un appareil muet restent dans le
  **même** envoi quand la référence est Paris, puisqu'ils composent la même
  heure. La clé porte le fuseau résolu, pas l'étiquette brute.

## 4. Sur `INVALID_ARGUMENT` — votre piste est retenue, pas encore appliquée

Merci pour la distinction : le message de l'exception nomme le champ fautif pour
une charge invalide (`Invalid JSON payload received…`) là où un jeton invalide
dit `The registration token is not a valid FCM registration token`. C'est
l'information qui manquait pour trancher, et nous ne l'avions pas.

Nous ne l'appliquons pas maintenant, pour la raison sur laquelle nous étions
d'accord : c'est un arbitrage à faire à froid, et la ventilation des journaux va
d'abord nous dire si `INVALID_ARGUMENT` apparaît seulement. Si votre appareil de
test remonte ce code, nous le ferons dans la foulée — ce sera alors documenté par
un cas réel plutôt que par une hypothèse.

## 5. Points fermés

- **Endpoint de test** : retiré à votre demande. Nous le ferons si vous le
  redemandez avec un cas d'usage.
- **En-têtes APNs explicites** : sans objet, comme convenu.
- **`NEARBY_PROGRAM`** : retiré, rien à faire.

## 6. Ce qui reste — et c'est tout ce qui reste

Le dossier n'a plus qu'un point ouvert, et il est chez vous :

1. **Réenregistrer le jeton** — la prochaine ouverture de l'app le fait, et
   posera le fuseau du même coup.
2. **Déclencher un rappel réel et lire les journaux Railway.** Le tableau de
   lecture est dans `REPONSE_BACKEND_PUSH_2026-08-15.md`, section 2 :
   `THIRD_PARTY_AUTH_ERROR` → console Firebase ; `UNREGISTERED` → le jeton ;
   `Sent n …` sans `WARN` → le serveur a fait son travail.

Nous n'avons plus rien à livrer tant que ce résultat n'est pas connu. Dites-nous
ce que vous lisez.

## Vérification

Suite complète : **454 tests, 7 échecs et 2 erreurs**, sur les six classes déjà
rouges avant ce dossier — causes étrangères (inscriptions en 409,
authentification WebSocket). Aucune régression.

Sept tests ajoutés pour le fuseau : l'heure qui suit l'appareil sur trois
fuseaux, le rebours qui n'en dépend pas, le jour qui bascule entre Paris et
Tokyo, la résolution d'une étiquette valide, le repli sur nul / vide / illisible,
et les deux règles de groupement — deux fuseaux donnent deux envois, un fuseau
déclaré égal à la référence n'en donne qu'un.
