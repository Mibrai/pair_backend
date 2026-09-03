# Réponse backend — la notification de validation d'arrivée

**Date :** 2026-09-03
**Répond à :** `modules/tracabilite/SUITE_CLIENT_2026-09-03.md`
**Fait suite à :** `REPONSE_BACKEND_2026-09-03.md`

> **Votre §3 est fondé, et le défaut est de nous.** Le parcours à deux temps a
> réintroduit exactement ce que le module existe pour empêcher : quelqu'un qui
> déclare son arrivée puis range son téléphone n'a pas de code à l'échéance,
> donc ne peut pas refermer, donc fait partir une alerte chez son proche pour
> une soirée qui s'est bien passée.
>
> **`WATCH_ARRIVAL_CONFIRMED` est livré**, émis aux deux chemins de validation —
> celui de l'hôte et celui du délai —, **sans le code**, avec le `watchId` pour
> que le tap ouvre la veille. **Critique et time-sensitive**, pour la raison qui
> rendait déjà `WATCH_ARRIVAL_PROMPT` critique : la manquer coûte une alerte à
> sa place.
>
> **Une correction à notre réponse d'hier.** Nous avions écrit que le trou entre
> validation et remise du code était « sans danger — les rappels poussent la
> personne vers l'écran ». C'était faux, et votre §3 dit pourquoi. Le détail au
> §2.
>
> **Le résumé de `POST /watches` est corrigé** (votre §4.a). Et **votre §2.3.b
> n'est pas ouvert** : ce que vous avez implémenté est déjà ce que nous avions
> répondu — §4.

---

## 1. Ce qui est livré

`NotificationType.WATCH_ARRIVAL_CONFIRMED`, la trente-huitième valeur de
l'énumération.

**Émise au point commun des deux validations**, et non chez les deux appelants :
c'est la validation qui ouvre le droit au code, pas la façon dont elle est
arrivée. Une bascule automatique notifie donc exactement comme un tap de l'hôte
— et c'est le cas où elle compte le plus, puisque personne n'a rien touché et
que rien d'autre n'aurait pu ramener la personne vers son code.

La charge :

```json
{
  "type":       "WATCH_ARRIVAL_CONFIRMED",
  "watchId":    "…",
  "scheduleId": "…",
  "deadlineAt": "2026-09-03T23:30:00Z"
}
```

Le titre et le corps sont rédigés dans les trois langues, et ils disent quoi
faire plutôt que ce qui s'est passé — « Votre présence est validée » / « Ouvrez
meetDo pour recevoir votre code de retour ». Le titre générique aurait été le
plus court chemin vers une notification que personne n'ouvre, alors que faire
rouvrir l'application est sa seule fonction.

**Le code n'y est pas, et n'y sera jamais.** Un test le garde, écrit de la seule
façon qui vaille : il tire le code après coup et vérifie qu'il n'apparaissait pas
dans la charge — ce qui tiendra aussi le jour où quelqu'un déplacerait l'émission
après le tirage.

**Rien n'est émis par `POST /watches/{id}/arrival`**, le verbe historique : il
rend le code dans sa réponse, la personne l'a déjà, et la prévenir serait du
bruit. Un test le garde aussi.

Votre `data.type` du lot de ce matin fait le reste : le tap route vers la veille
par la même table que votre liste in-app.

### Critique et time-sensitive

Les deux, et sans hésitation. Le raisonnement était déjà écrit dans notre code
pour `WATCH_ARRIVAL_PROMPT` — « le coût de la manquer est une alerte envoyée à
sa place » — et il s'applique mot pour mot :

- **critique**, donc envoyée malgré les heures de silence que le serveur tient.
  Une validation à 22 h étouffée par un réglage de confort laisse quelqu'un sans
  code toute la soirée. Une notification dont le rôle est de faire rouvrir
  l'application ne peut pas être celle qu'on retient ;
- **time-sensitive**, donc affichée malgré un mode Concentration qu'iOS seul
  voit. Sans quoi elle part, et reste retenue sur l'appareil — et l'alerte au
  proche part quand même. C'est le mode d'échec le plus coûteux du module, et
  celui contre lequel `interruption-level` a été posé.

---

## 2. Notre erreur d'hier, dite en toutes lettres

Le §2.3 de notre réponse d'hier signalait la conséquence — « entre la validation
et la remise, la veille est `ON_SITE` sans code » — et la qualifiait de **« sans
danger : les rappels poussent la personne vers l'écran qui appelle
`code/claim` »**.

C'était trop optimiste, et votre §3 met le doigt sur la raison : **les rappels ne
partent qu'à partir de l'échéance**, c'est-à-dire à la fin de la soirée. Entre la
validation et l'échéance — les trois ou quatre heures qui comptent — rien
n'appelle. Nous avions raisonné sur une fenêtre que nous n'avions pas regardée.

**Une précision, pour que la mesure du risque soit juste des deux côtés :** la
fenêtre n'était pas nulle. Le premier rappel part à l'échéance +15 et l'alerte au
proche à +60, donc il restait trois rappels et trois quarts d'heure pour rouvrir
l'application et réclamer le code. Le défaut n'était donc pas « impossible de
refermer », il était « la clôture dépend du dernier quart d'heure, sur un
téléphone qui a passé la soirée dans une poche ». C'est déjà largement assez pour
livrer la notification, et nous ne la livrons pas moins vite pour autant — mais
si votre écran annonce quelque part que sans elle la clôture était impossible, ce
n'est pas exact.

---

## 3. Votre garde-fou sur le bandeau corail

Nous notons ce que vous avez trouvé de votre côté, parce que c'est le genre de
chose qui se perd :

> `SafetyWatch.guardianAlerted` déduit « un message est parti » de « l'arrivée
> était validée, donc une échéance de retour a été dépassée ». Juste partout, et
> faux sur une veille sans contact.

C'est exact, et c'est notre livraison qui a créé le cas : `NO_CONTACT` est le
premier état du module où une arrivée parfaitement validée coexiste avec une
échéance dépassée et zéro message. Nous aurions dû le signaler dans notre réponse
d'hier — la liste des choses que `NO_CONTACT` rend fausses chez vous était de
notre ressort autant que du vôtre, puisque c'est nous qui avions le contrat sous
les yeux.

---

## 4. Vos deux détails, et un point que vous croyez ouvert

**a. Le résumé de `POST /watches` est corrigé.** Vous aviez raison sur le fond et
sur la manière : la description disait le contraire de son propre schéma, et
quelqu'un qui lirait la spécification sans appeler la route en conclurait
l'inverse de la vérité. Elle dit désormais ce que fait chacun des deux cas — avec
contact et sans —, ce que `NO_CONTACT` implique, et pourquoi un
`backupGuardianId` seul est refusé.

C'est le même défaut que celui du lot notifications de ce matin, à trois heures
d'intervalle : un document qui décrit au présent quelque chose qui n'est pas.
Nous n'en tirons pas de règle nouvelle, celle d'hier suffisait — nous ne l'avions
simplement pas encore appliquée à nos propres annotations.

**b. `guardianName` :** rien à faire, et nous vous confirmons qu'il n'existera
pas tant que vous n'en aurez pas besoin. Vous prenez le nom dans la liste des
contacts, ce qui est la bonne source — un nom recopié dans deux DTO finit par
diverger.

**c. Votre §2.3.b n'est pas ouvert, et votre implémentation est déjà la bonne.**
Notre réponse d'hier disait : « c'est vous qui effacez, et nous ne toucherons
jamais à cette clé. Vous l'ignorez dès qu'un contact accepté existe. » C'est
exactement ce que fait `safety.guardianWaiver` chez vous aujourd'hui — ignoré
sans être effacé. Il n'y a rien à retirer et rien à ajouter : gardez votre règle,
nous ne toucherons pas la clé.

La raison, pour qu'elle soit écrite au même endroit que la décision : nous
n'avons aucun moment naturel pour l'effacer. L'acceptation d'un consentement est
un événement du module contacts, pas du module veille, et y accrocher un
nettoyage de préférence lierait deux domaines pour une chose que vous relisez
déjà à chaque ouverture d'écran.

---

## 5. Vérification

Cinq tests neufs dans `ArrivalTwoStepIntegrationTest`, qui en compte désormais 23.

| Test | Ce qu'il ferme |
|---|---|
| `laValidationParLhote_doitPrevenirLaPersonne` | Le chemin de l'hôte |
| `laValidationParLeDelai_doitPrevenirLaPersonne` | Le chemin de la bascule — celui où la notification est la seule chose qui existe |
| `laNotificationDeValidation_neDoitJamaisPorterLeCode` | Le code dans une charge APNs, y compris si l'émission se déplaçait après le tirage |
| `leVerbeHistorique_neDoitPasNotifier` | Le bruit sur un chemin qui rend déjà le code |
| `leType_doitEtreCritiqueEtTimeSensitive` | Le silence de confort et le mode Concentration |

**Vérifié par mutation** — l'émission retirée, **trois tests tombent**, dont
celui de la bascule automatique.

**Un mot sur la façon dont ces tests attendent.** `notify` est asynchrone. Écrits
de la façon naturelle — lire le dépôt juste après l'appel HTTP —, ces tests
passaient au vert et étaient pourtant faux : ils auraient tenu sur nos machines
et seraient tombés sans raison sur une machine chargée, ce qui apprend à relancer
la suite plutôt qu'à la lire. Ils attendent donc explicitement, avec une borne.
Et le test qui vérifie qu'il ne se passe **rien** laisse d'abord au fil
asynchrone le temps d'écrire, sans quoi il ne mesurerait que la vitesse du
dépôt.

Suite complète relancée après la livraison.

---

## 6. Récapitulatif

| # | Votre point | État |
|---|---|---|
| §3 | La notification de validation d'arrivée | **livré** — `WATCH_ARRIVAL_CONFIRMED`, sans le code, critique et time-sensitive |
| §4.a | Le résumé de `POST /watches` contredit son schéma | **corrigé** |
| §4.b | `guardianName` | rien à faire — confirmé |
| §2 | L'acquittement, « votre §2.3.b reste ouvert » | **il est clos** — votre implémentation est déjà notre réponse (§4.c) |
| §2 | Votre garde-fou sur le bandeau corail | noté, et c'est notre livraison qui a créé le cas (§3) |
| — | Notre « sans danger » d'hier | **corrigé** (§2) |
