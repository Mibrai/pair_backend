# Réponse à `REPONSE_BACKEND_2026-08-31.md`

> **Vous avez raison sur `/reports/me`, et l'erreur est de notre côté.** Nous
> l'avons annoncée cassée alors que vous nous aviez écrit sa réparation le 27/08.
> Nous avons corrigé nos notes pour que ça ne se reproduise pas.
>
> **Vous avez tort sur le préfixe `/api`, et c'est notre demande qui prêtait à
> confusion.** Aucune des dix-huit routes n'est à réécrire ; l'explication tient
> en une ligne de code que nous citons.
>
> **Vos quatre points de conception (§3) sont acceptés tels quels.** Les 3.1 et
> 3.2 attrapent deux défauts que nous n'avions pas vus, et le 3.1 aurait produit
> des refus définitifs déclenchés par des robots.
>
> Les quatre réponses que vous attendez sont en §4.

---

## 1. `/reports/me` — vous avez raison, et voici pourquoi nous nous sommes trompés

Nous avons relu `ios/docs/REPONSE_BACKEND_APP_STORE_2026-08-27.md` : le `500`
y est décrit comme corrigé, avec la cause (deux vocabulaires pour la colonne
`status`), le commit et la migration. Vous nous l'aviez bien dit.

La phrase « n'est toujours pas servi » du 31/08 vient d'une note interne qui
datait du 27/08 **au matin**, écrite avant votre réponse et jamais mise à jour.
Nous l'avons corrigée à la source, avec la consigne de relire votre fichier avant
de redire cette route cassée.

Il n'y a donc **rien à vérifier de votre côté** : pas de mauvais déploiement, pas
d'URL fautive. C'était une note périmée, et votre question §5.1 n'a plus d'objet.

Nous vous devons cette précision-là en retour : ce n'était pas non plus le
préfixe.

## 2. Le préfixe `/api` — les routes sont correctes, notre présentation ne l'était pas

`lib/core/config/app_config.dart`, ligne 15 :

```dart
static String get apiBaseUrl {
  …
  return 'https://pairbackend-production-35fe.up.railway.app/api';
}
```

C'est la `baseUrl` de l'`ApiClient`. Les chemins de `ApiConstants` sont donc
**relatifs à une base qui porte déjà `/api`** : écrire `'/api/watches'` dans la
constante produirait `…/api/api/watches`.

Le fichier le documente déjà, sur la route publique voisine :

> ⚠️ Hors `/api`, comme toutes les routes `/public/**` de la v2 : à composer avec
> `AppConfig.publicBaseUrl`, **jamais** à passer telle quelle à l'`ApiClient`,
> dont la base se termine par `/api` — le chemin deviendrait `…/api/public/…` et
> rendrait un 404.

Notre demande listait les chemins tels qu'ils apparaissent dans le code, sans
dire à quoi ils étaient relatifs. C'est ce qui a rendu votre hypothèse
raisonnable, et nous l'écrirons désormais en toutes lettres.

**Rien à changer : votre tableau du §1.2 décrit exactement ce que nous appelons.**
Les cinq routes publiques restent hors préfixe, comme vous l'indiquez.

## 3. Vos quatre points de conception — acceptés

**3.1 · Les liens de consentement en `GET`.** Accepté, et merci. Nous n'avions pas
vu que les scanners de messagerie fabriqueraient des consentements — et surtout
des **refus définitifs et globaux** que plus personne ne peut défaire, sans que le
propriétaire du téléphone ait rien fait ni rien su. C'est un défaut que nous
aurions découvert par un utilisateur furieux, pas par un test.

Nous prenons `GET` (page avec les deux boutons) + `POST` (application). Nos
constantes sont mises à jour, et la page doit dire que le refus est définitif
**avant** d'être cliqué.

**3.2 · L'occurrence figée à l'armement.** Accepté. Nous ne connaissions pas
`RecurringSlotRolloverJob`, et une échéance qui fuit devant elle toutes les dix
minutes est exactement le genre de défaut qu'on met un mois à reproduire.

Le point sur `ends_at` nullable est celui qui nous concerne le plus : notre écran
d'armement affichera **`SlotTiming.endOf(slot) + 1 h`**, donc `starts_at + 3 h`
sur un créneau sans fin déclarée. Merci de l'avoir signalé — nous aurions affiché
une heure et vous en auriez retenu une autre, sur un écran qui promet précisément
une heure.

**3.3 · Un incident n'écrit jamais de ligne `Attendance`.** Accepté, et le piège
que vous décrivez est réel : `Attendance(was_present = false)` nous aurait semblé
la façon naturelle de journaliser. Mettre la séance au dénominateur sans la mettre
au numérateur, c'est exactement la punition que le garde-fou existe pour empêcher.
Nous portons la règle dans notre plan client aussi.

**3.4 · Les relances traversent les heures de silence.** Accepté, c'est
indispensable. Nous posons `apns-collapse-id` et le retrait des notifications
déjà délivrées côté client dès que la charge le porte.

## 4. Les quatre réponses que vous attendez

### 4.1 · `/reports/me` — sans objet

Voir §1. Note périmée de notre côté, pas de problème d'URL ni de déploiement.

### 4.2 · `IN_REVIEW` — **trois états, pas quatre**

N'ajoutez pas un état que rien n'écrit. Votre formulation est la bonne : un écran
qui n'affiche jamais « en cours » ment autant qu'un écran qui l'affiche toujours,
et nous refusons de faire écrire du code de modération pour meubler un écran.

Nous afficherons donc **`RECEIVED` · `RESOLVED` · `DISMISSED`**, avec la
projection que vous proposez (`PENDING` → `RECEIVED`, `REVIEWED` + `ACTIONED` →
`RESOLVED`).

Notre lecture est déjà tolérante : `IncidentState.parse` retombe sur `RECEIVED`
pour toute valeur inconnue. Si la modération gagne un jour le geste « pris en
charge », vous pourrez servir `IN_REVIEW` **sans nous prévenir** — nous
l'afficherons dès que nous ajouterons le libellé, et les versions déjà installées
le liront comme `RECEIVED` au lieu de casser.

### 4.3 · Les « 30 jours » — vous avez raison de tiquer, et nous raccourcissons

Notre phrase ne visait pas le partage de position en conversation, que nous ne
touchons pas : elle visait les **trois points de passage du module** (armement,
arrivée, retour), affichés dans la chronologie du journal de l'utilisateur.

Mais votre objection tient quand même, et nous la reprenons à notre compte : que
vaut une coordonnée vieille de trente jours ? Rien, pour un journal personnel. La
chronologie garde son sens avec le **nom du lieu**, qui est déjà la seule chose
que nous ayons le droit de montrer à un contact.

**Retenu : les coordonnées sont effacées 24 h après la clôture de la veille**,
comme le lien public. Seuls survivent dans l'archive l'horodatage et le nom du
lieu. La rétention de trente jours ne concerne plus que la **chronologie des
états** — armée, arrivée, rappels, clôture — qui ne porte aucune coordonnée.

Nous corrigeons le §6.3 de la demande et l'écran du journal en conséquence.

### 4.4 · Les fichiers manquants — c'est notre dépôt, pas notre demande

`modules/tracabilite/` n'est pas encore poussé : le dossier existe chez nous mais
n'a jamais été commité, et vous ne voyez que ce que nous vous avons transmis à la
main. La maquette (`template/meetdo-tracabilite.html`, 26 écrans) et le plan
client partent au prochain envoi.

Ils vous serviront surtout pour deux choses : les **six états de la page publique**
sont dessinés un par un avec leur texte exact, et l'écran « qui me voit » montre
la frontière que le filtre de `safety_share_message.dart` doit tenir.

## 5. Sur vos deux objections d'infrastructure (§2.2)

**L'outbox : oui, et c'est mieux que ce que nous demandions.** Notre exigence
« file dédiée » décrivait une propriété — les alertes ne font pas la queue
derrière les rappels de séance — pas une implémentation. Un pool en mémoire qui
perd ses envois à chaque redéploiement réintroduirait effectivement, par la porte
de derrière, exactement le mode d'échec que « le serveur tient les minuteurs »
existe pour fermer. Une ligne en base écrite dans la transaction de décision est
la bonne réponse, et elle rend l'annulation transactionnelle du §3 triviale.

**Le webhook DLR : accepté, avec la remarque que vous faites vous-même.** Oui,
« instrumenter le SLO » ouvre une surface d'entrée non authentifiée de plus, et
c'est le genre de coût qu'il vaut mieux voir avant que pendant. Si la vérification
de signature `X-Twilio-Signature` vous paraît un chantier disproportionné pour la
priorité 4, nous préférons **différer la mesure du SLO** plutôt que d'ouvrir un
`POST` public mal gardé : l'engagement des 30 s reste, seule sa mesure attend.

**Le temps constant sur le code de contrainte.** Votre analyse est plus fine que
notre exigence : nous avions écrit « même temps de réponse » sans voir que bcrypt
la rendait pratiquement intenable. Évaluer les deux empreintes systématiquement,
fusionner en temps constant, et sortir l'escalade de la transaction de réponse —
c'est exactement ce qu'il faut. Le plancher de 200 ms lié à la distance base ↔
service est un bonus, pas une excuse, et vous le dites ainsi.

## 6. Votre §7.4 — l'amendement est accepté

Vous avez raison, et l'argument est le nôtre retourné d'une section : un hachage
nu d'un numéro de mobile français n'est pas un secret, l'espace se parcourt par
énumération, et la précaution serait décorative — donc pire que rien, puisqu'elle
donnerait le sentiment que la question est réglée.

`HMAC-SHA256(E.164, poivre)` sous la même clé hors base et le même `key_version`,
avec normalisation avant hachage. Rien à ajouter.

---

## 7. Ce que nous changeons de notre côté, aujourd'hui

| Point | Fichier client |
|---|---|
| Consentement en `GET` + `POST` | `lib/core/config/api_constants.dart` |
| `/reports/me` servi, forme à venir | `api_constants.dart` + `feature_flags.dart` |
| Trois états d'incident, lecture tolérante au quatrième | `lib/features/safety_watch/domain/incident_target.dart` |
| Échéance = `SlotTiming.endOf(slot) + 1 h`, `ends_at` nullable | écran d'armement |
| Coordonnées effacées à 24 h, pas 30 jours | `PROMPT_BACKEND` §6.3 + écran du journal |
| Un incident n'écrit jamais d'`Attendance` | plan client |
