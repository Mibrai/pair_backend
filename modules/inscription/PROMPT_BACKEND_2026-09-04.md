# Quitter un créneau est définitif : on ne peut plus jamais le rejoindre

**Date :** 2026-09-04

> **Un défaut signalé par l'utilisateur, reproduit trois fois sur trois contre la
> production.** Il quitte un créneau, change d'avis, retouche « Rejoindre » — et
> l'app affiche « Vous avez déjà rejoint ce créneau ». C'est votre message, et
> il est faux : la personne a bien quitté, vous l'avez enregistré.
>
> **La cause est chez vous, et elle tient en une ligne :** `DELETE` pose
> `WITHDRAWN`, et le contrôle d'unicité de `POST` compte `WITHDRAWN` comme une
> inscription. Se désinscrire est donc **irréversible** — §1.
>
> **Ce n'est pas un cas rare.** Changer d'avis deux fois est le comportement
> ordinaire de quelqu'un qui hésite entre deux séances du même soir. Aujourd'hui
> la première hésitation lui ferme la porte définitivement — §2.
>
> **Une observation que nous ne savons pas expliquer**, et que nous vous
> signalons sans l'affirmer : `participantCount` ne revient pas à sa valeur
> initiale après un aller-retour — §3.

---

## 1. La reproduction

Compte de test, production, `GET`/`POST`/`DELETE` bruts. **Trois créneaux, trois
fois le même résultat** — Basketball, Kickboxen, Salsa :

```
POST   /api/slots/{id}/join    → 201
GET    /api/slots/{id}         → myParticipationStatus: "CONFIRMED"

DELETE /api/slots/{id}/join    → 204
GET    /api/slots/{id}         → myParticipationStatus: "WITHDRAWN"

POST   /api/slots/{id}/join    → 422
                                 { "code": "SLOT_ALREADY_JOINED",
                                   "message": "Vous avez déjà rejoint ce créneau." }
GET    /api/slots/{id}         → myParticipationStatus: "WITHDRAWN"
```

Le `DELETE` fait donc bien son travail — l'état passe à `WITHDRAWN` — et c'est
le `POST` qui refuse ensuite. Son contrôle d'unicité porte sur **l'existence
d'une ligne de participation**, pas sur son état.

**Côté app, rien à corriger, et c'est ce qui rend le défaut visible.** Nous
lisons `WITHDRAWN` correctement : il n'est pas `CONFIRMED`, donc la personne
n'est pas inscrite, donc le bouton « Rejoindre » s'affiche — ce qui est juste.
C'est le serveur qui, seul, considère encore qu'elle l'est.

---

## 2. Ce que nous demandons

**Qu'une participation `WITHDRAWN` ne compte plus comme une inscription.** La
forme est la vôtre — réactiver la ligne existante, ou en créer une nouvelle —
mais le résultat doit être : `POST` après un `DELETE` rend `201` et la personne
est de nouveau inscrite.

Trois précisions, pour que la demande soit complète :

**a. Le contrôle de capacité doit voir la même chose.** Si `WITHDRAWN` ne compte
plus pour l'unicité, il ne doit pas compter pour les places non plus — sans quoi
un créneau se remplirait de gens qui l'ont quitté. C'est la même question posée
deux fois, et nous préférons l'écrire.

**b. Les règles de refus restent entières.** Créneau passé, annulé, complet,
chevauchement (`409 SCHEDULE_CONFLICT`) : rien de tout cela ne doit s'assouplir.
Nous ne demandons pas un droit d'entrée, seulement que le fait d'être parti
cesse d'être un motif de refus.

**c. La liste d'attente, si elle est concernée.** Nous n'avons pas pu la tester
— `POST /slots/{id}/waitlist` n'est pas servi sur nos créneaux de test. Si le
même contrôle d'unicité la gouverne, le même défaut y vit probablement, et nous
préférons vous le signaler que le découvrir plus tard.

---

## 3. Une observation, pas une affirmation

Sur les trois aller-retours, `participantCount` lu sur `GET /slots/{id}` n'est
pas revenu à sa valeur de départ :

```
avant le join : 0
après le join : 3
après le leave : 2
```

Nous ne savons pas l'expliquer, et nous **ne l'affirmons donc pas comme un
défaut**. Deux raisons de prudence : un seul `join` ne peut pas faire `+3`, et
les lectures étaient espacées de quatre dixièmes de seconde, ce qui est peut-être
trop court si le compte est calculé en différé. Sur un créneau auquel nous
n'avons pas touché, trois lectures successives donnent la même valeur, et elle
s'accorde avec celle de `/slots/bounds` — donc rien d'instable au repos.

Ce que nous vous demandons ici n'est pas un correctif mais un regard : si le
compte inclut les participations `WITHDRAWN`, il rejoint le §2.a ; s'il est
calculé en différé, il n'y a rien à faire et nous l'oublions.

---

## 4. Récapitulatif

| # | Point | Nature |
|---|---|---|
| 1 | `POST /slots/{id}/join` après un `DELETE` doit aboutir | **demande** — reproduit 3 fois sur 3 |
| 2 | `WITHDRAWN` ne compte pas non plus pour la capacité | demande |
| 3 | Les autres refus (passé, complet, chevauchement) restent inchangés | confirmation |
| 4 | La liste d'attente porte-t-elle le même contrôle d'unicité ? | question |
| 5 | `participantCount` après un aller-retour | observation, à vérifier de votre côté |

---

## 5. Ce que nous avons laissé dans les données de test

Trois créneaux du compte de test portent une participation `WITHDRAWN` que nous
ne pouvons pas défaire, puisque c'est précisément le défaut : **Basketball**,
**Kickboxen** et **Salsa**. Elles disparaîtront d'elles-mêmes le jour du
correctif — et si vous nettoyez la base avant, il n'y a rien à conserver.
