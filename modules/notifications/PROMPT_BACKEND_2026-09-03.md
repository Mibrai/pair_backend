# La charge poussée n'apporte pas `type` — et un tap sur une notification ne mène nulle part

**Date :** 2026-09-03
**Fait suite à :** `ios/docs/TODO_BACKEND_PUSH_2026-08-15.md` (§T2)

> **Un tap sur une notification meetDo n'ouvre pas l'élément notifié.** L'app
> reste sur sa page d'atterrissage — la carte —, et cela se produit **sans
> aucune erreur**, ni côté serveur ni côté client.
>
> **Cause mesurée sur l'appareil : la clé `type` n'arrive pas dans la charge
> `data`.** Sans elle le client ne peut pas savoir de quoi parle la
> notification, donc il ne navigue pas — délibérément — et l'utilisateur reste
> où il était.
>
> **Une seule demande, et elle est petite** : remettre `type` (et les
> identifiants qui vont avec) dans le bloc `data` des messages FCM. Le §T2 du
> 15/08 les listait déjà comme « servis aujourd'hui, à ne pas changer » — c'est
> donc soit une régression, soit une affirmation qui n'a jamais été vraie pour
> les types concernés.

---

## 1. Ce qui a été mesuré, et comment

Le client journalise chaque notification reçue. Relevé sur un iPhone, en
production, le 03/09 :

```
14:24:25.175 DEBUG   [push] notification reçue au premier plan type=system
14:21:23.264 SUCCESS [push] appareil enregistré pour le push
                            platform=IOS tokenTail=O8nUaLT0 locale=fr
                            timezone=Europe/Berlin cause=attach
```

La seconde ligne dit que **tout le socle fonctionne** : jeton enregistré, langue
et fuseau transmis, appareil joignable. La bannière s'affiche d'ailleurs
normalement — c'est ce qui rend le défaut si discret.

La première ligne est le défaut. **`type=system`** n'est pas un type que vous
envoyez : c'est le **repli** du client quand il ne trouve pas de type. Il est
délibéré et documenté chez nous — « un type ajouté côté serveur ne doit pas
casser l'app » —, et sa conséquence l'est tout autant.

## 2. Pourquoi ce n'est pas un type inconnu de notre côté

C'était l'autre explication possible, et nous l'avons écartée avant de vous
écrire.

Nous avons comparé l'énumération de votre contrat (`UpdatePreferenceRequest.type`,
**37 valeurs**) à la nôtre (**36**). La seule valeur que nous ne déclarons pas
sous ce nom est `SYSTEM` — parce que c'est justement notre repli, déclaré
autrement. **Aucun de vos types de notification ne nous est inconnu.**

Si `type` était arrivé avec `SLOT_JOINED`, `WATCH_ARRIVAL_PROMPT` ou n'importe
laquelle de vos 37 valeurs, le client l'aurait reconnu. Il a rendu `system` :
la clé n'était pas là.

## 3. Ce qui se passe ensuite, et pourquoi l'app ne « corrige » pas

L'enchaînement complet, pour que la conséquence soit lisible :

1. la bannière s'affiche depuis `aps.alert` — **rien n'a l'air cassé** ;
2. `type` absent → repli sur `SYSTEM` ;
3. notre table de routage ne rend aucune destination pour `SYSTEM` ;
4. **le client ne navigue pas, exprès.** La règle est écrite chez nous :
   « déplacer quelqu'un qui lisait autre chose est plus grave que de ne rien
   faire ». Un badge débloqué ou une action de modération se lisent dans la
   notification elle-même, sans écran à ouvrir ;
5. l'app reste sur sa route d'atterrissage, `/map`.

D'où le symptôme rapporté : « ça ouvre la carte ». Ce n'est pas une mauvaise
destination, c'est **l'absence de destination**.

**Nous ne comptons pas contourner ça côté client**, et nous préférons le dire :
deviner le type à partir des identifiants présents (« il y a un `scheduleId`,
donc c'est un créneau ») remplacerait une table unique — celle qui sert aussi la
liste in-app — par une heuristique parallèle. Deux vérités qui divergent au
premier type ajouté, et une bannière qui n'emmène plus au même endroit que la
ligne de la liste.

## 4. Ce que nous demandons

**4.1 — Remettre `type` dans le bloc `data` de tous les messages FCM.** C'est la
demande, et elle suffit à refermer le défaut.

**4.2 — Vérifier que les identifiants suivent.** Le §T2 du 15/08 les donnait
pour acquis ; nous ne pouvons pas confirmer leur présence puisque nous ne voyons
même pas `type`. Ce dont chaque famille a besoin pour ouvrir le bon écran :

| Ce que la notification annonce | Clé attendue dans `data` |
|---|---|
| un message, une diffusion | `conversationId` (à défaut `programId`) |
| un programme, une séance | `programId`, et `scheduleId` s'il y en a un |
| un créneau (rejoint, annulé, liste d'attente, présence) | `scheduleId` |
| une veille retour | `watchId` |
| un abonné, un pair, un auteur | `authorId` / `subscriberId` |

Rappel des contraintes de la charge, inchangées : toutes les valeurs sont des
**chaînes** (contrainte FCM), une clé absente et une chaîne vide sont traitées
pareil, et le `snake_case` est accepté même si le `camelCase` est la référence.

**4.3 — Une question, pour comprendre plutôt que pour accuser.** Le §T2 du
15/08 affirmait que `type`, `programId` et `scheduleId` étaient « servis
aujourd'hui, à ne pas changer ». Ont-ils disparu depuis — un refactor de
l'émetteur, un gabarit qui ne remplit plus `data` —, ou bien la liste
décrivait-elle l'intention plutôt que l'existant ? La réponse ne change pas la
demande, mais elle dit s'il faut un test de non-régression chez vous.

**4.4 — Un test qui ferme la porte.** C'est ce que nous demandons vraiment, plus
que le correctif : **un test qui échoue si `data.type` est absent** d'un message
sortant. Ce défaut n'a produit aucune erreur pendant des semaines, des deux
côtés — la bannière s'affiche, le tap est reçu, le client se tait par prudence.
Rien ne le signalait. Un correctif sans garde-fou se re-perdra au prochain
refactor de l'émetteur, et personne ne le remarquera avant qu'un utilisateur ne
rapporte, une fois de plus, que « les notifications ne servent à rien ».

---

## 5. Ce que nous faisons de notre côté

Rien qui vous concerne, mais autant que ce soit écrit :

- **un écran de journal de session** a été ajouté aux réglages (À propos →
  Journal de session). Le tampon existait déjà en mémoire, y compris en
  release ; il n'avait simplement aucune porte. C'est lui qui a produit la ligne
  du §1, sans câble ni build de débogage ;
- **une sonde** est prête, qui fera dire au journal **les noms des clés** que la
  charge transporte — jamais leurs valeurs, `data` portant des noms de
  personnes, un extrait de message et parfois un jeton de consentement. Si votre
  réponse au §4.3 est « elles y sont pourtant », c'est elle qui tranchera.

---

## 6. Récapitulatif

| # | Demande | Nature |
|---|---|---|
| 1 | Remettre `data.type` sur tous les messages FCM | **bloquant** |
| 2 | Vérifier les identifiants du §4.2 | demande |
| 3 | Régression ou intention ? (§4.3) | question |
| 4 | Un test qui échoue si `data.type` manque | demande |
