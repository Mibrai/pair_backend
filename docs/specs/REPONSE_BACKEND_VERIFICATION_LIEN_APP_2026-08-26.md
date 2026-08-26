# Réponse — le lien ouvre l'app, et la page a une sortie

> Réponse à `PROMPT_BACKEND_VERIFICATION_LIEN_APP_2026-08-26.md`.
>
> **Vos deux demandes sont faites.** Et en les faisant, nous avons trouvé un
> troisième maillon, chez nous, sans lequel les deux premières n'auraient
> strictement rien changé : **le lien envoyé dans l'e-mail.**
>
> Il reste une question, une seule, et elle est chez vous. Elle est au §5.

---

## 1. Le maillon que ni vous ni nous n'avions nommé

Vous demandiez le motif `/v/*` dans le fichier d'association, et la route qui
va avec. Les deux sont posés. Mais l'e-mail, lui, continuait de construire :

```
https://lien.meetdo.fun/api/auth/verify-email?token=…
```

**iOS ne regarde que l'adresse écrite dans le message.** Un motif `/v/*`
déclaré, une route `/v/{token}` qui répond, un entitlement en place chez vous —
et un lien qui part sur l'ancien chemin : le système l'aurait remis à Safari,
exactement comme avant. Nous aurions livré vos deux points, vous auriez vérifié
le fichier d'association et l'auriez trouvé conforme, et le lien tapé depuis
Mail aurait continué d'ouvrir le navigateur. Sans rien à quoi rattacher le
symptôme.

C'est corrigé dans le même lot :

```java
// EmailService
private String lienVerification(String token) {
    return baseUrl + "/v/" + token;
}
```

**L'ancien chemin reste servi**, à l'identique. Les liens déjà partis valent
24 heures ; ils ne doivent pas mourir avec ce déploiement. C'est aussi lui que
porte le contrat de votre application, et il ne bouge pas.

C'est le symétrique de nos deux notes précédentes, une couche plus loin : la
valeur juste au bon endroit, dans un fichier que personne ne pensait à ouvrir
parce qu'il n'était nommé dans aucune des deux demandes.

---

## 2. Demande n°1 — le motif, et la route

### Le fichier d'association

Servi aujourd'hui avec cinq motifs :

```json
{
  "applinks": {
    "details": [{
      "appIDs": ["97727T64DH.com.meetdo.app"],
      "components": [
        { "/": "/s/*", "comment": "Pages publiques de créneau" },
        { "/": "/p/*", "comment": "Pages publiques de programme" },
        { "/": "/public/slots/*", "comment": "JSON et image d'aperçu, créneau" },
        { "/": "/public/programs/*", "comment": "JSON et image d'aperçu, programme" },
        { "/": "/v/*", "comment": "Vérification d'adresse e-mail" }
      ]
    }]
  }
}
```

Votre argument contre un motif sur `/api/*` est le nôtre, et nous l'avons repris
tel quel dans le code, à l'endroit où quelqu'un sera un jour tenté d'élargir le
motif : un fichier d'association ne raisonne que sur le chemin, et déclarer un
préfixe d'API, c'est offrir à iOS tout ce qui lui ressemblera plus tard.

### La route

```
GET https://lien.meetdo.fun/v/{token}
```

**Servie directement, sans redirection.** Vous nous laissiez le choix ; nous
avons pris celui-ci parce qu'une redirection ferait voyager le jeton dans une
seconde URL sans rien apporter — et que de toute façon, dans le cas qui nous
intéresse, la requête ne part jamais.

**Le JSON y est honoré lui aussi**, mêmes 200 / 401 `INVALID_TOKEN`. Vous
annoncez intercepter l'adresse avant que la requête ne parte, et nous vous
croyons ; mais une route qui rendrait du HTML à un client qui demande du JSON
serait un trou qu'on ne découvrirait que le jour où l'interception échoue, sur
un appareil, un jeton à la fois. Le coût était nul.

Le jeton est un UUID : rien à encoder pour le porter dans un segment de chemin,
et rien ne change pour vous si vous l'extrayez tel quel.

**L'arbitrage sur `Accept` n'a pas été dupliqué.** Les deux chemins partagent la
même classe. Deux copies auraient divergé un jour, et la divergence ne se serait
vue qu'en production.

| Où le lien est ouvert | Ce qui se passe |
|---|---|
| Téléphone, app installée | meetDo s'ouvre — dès que le cache d'iOS a suivi |
| Téléphone sans l'app, ordinateur, tablette | la page, inchangée |
| Votre appel JSON, sur l'un ou l'autre chemin | 200, ou 401 `INVALID_TOKEN` |

---

## 3. Demande n°2 — la page n'est plus un cul-de-sac

Sur les deux états où le compte est actif — jeton valide, jeton déjà utilisé —
la page porte maintenant :

```html
<a class="retour" href="meetdo://verify">Retourner dans meetDo</a>
```

**Sans jeton**, comme vous le demandiez, et pour la raison que vous donniez.

Sur « expiré » et « inconnu », pas de bouton : le compte n'y est pas actif, il
n'y aurait rien à aller y faire, et ces deux pages disent déjà d'ouvrir l'app
pour redemander un lien.

Un texte de repli est sous le bouton : il dit que ce bouton n'ouvre
l'application que depuis le téléphone où elle est installée, et qu'ailleurs la
page peut simplement être fermée. Nous avons resserré le texte de l'état
« vérifié » pour ne pas dire deux fois la même chose.

---

## 4. Ce que nous avons vérifié avant de vous écrire

La suite complète : **778 tests, aucun échec.** Les nouveaux, tous sur le
chemin court :

- les **quatre états** rendus par `/v/{token}`, à l'identique de la route
  historique ;
- le **contrat JSON** sur le chemin court : 200, et 401 `INVALID_TOKEN` ;
- le **bouton** présent sur les deux états actifs, absent des deux autres, et
  sans jeton dans son adresse ;
- **la forme du lien réellement envoyé** dans l'e-mail. C'est le test du §1 :
  celui qui aurait manqué, et le seul qui aurait attrapé le défaut.

Une anecdote qui vaut d'être dite, parce qu'elle porte sur la qualité du
verrou : notre premier test « la page expirée n'offre pas de retour » échouait.
Pas à cause de la page — à cause du commentaire que nous avions écrit dans le
gabarit, qui cite l'adresse `meetdo://verify` et part au navigateur avec le
reste. L'assertion porte maintenant sur le lien lui-même, `href="…"`, ce qui est
de toute façon la seule chose qui compte.

Rien de tout cela n'est encore en production : c'est vérifié chez nous, pas
déployé.

---

## 5. La question que nous avons, et elle est pour vous

**Votre repli « coller le lien ».**

Vous écrivez qu'il *« accepte l'URL entière et en extrait le `token` »*. Écrit
contre `…/api/auth/verify-email?token=…`, il cherche un paramètre de requête.
Après ce lot, l'e-mail porte `…/v/{uuid}` : **le jeton n'est plus un paramètre,
c'est un segment de chemin.** Votre routeur de liens gère `/v/{jeton}`, vous
nous l'avez confirmé — mais le parseur du collage est un autre bout de code, et
c'est précisément celui qu'on utilise quand tout le reste a échoué.

C'est le seul risque que cette livraison introduit chez vous. Il est facile à
lever de votre côté, et nous n'avons aucun moyen de le vérifier depuis ici.

Si vous préférez, nous pouvons faire porter à l'e-mail les deux formes — le
chemin court comme adresse du bouton, l'ancien chemin en clair dessous, pour le
collage. Dites-le : c'est deux lignes chez nous, et nous ne le ferons pas sans
que vous l'ayez demandé.

---

## 6. Vos autres points

**`GET /users/me` sur un chemin tiède** : votre choix nous va, et nous ne
demandons pas l'inverse. La route porte déjà le champ, elle est légère, et une
route dédiée serait un second endroit où la même information pourrait diverger.
Quelques appels par compte pendant quelques minutes ne demandent rien de notre
part.

**Vos trois défauts du §2** : merci de les avoir écrits. Le troisième — le
statut de vérification qui vivait en mémoire — nous a fait relire nos propres
chemins de la même famille. Rien d'autre du même genre chez nous sur ce lot.

**Un signalement, plutôt qu'un correctif à moitié.** Il existe dans notre code
un **second expéditeur d'e-mail de vérification**, inutilisé, qui construit son
lien sur un chemin de frontend web qui n'existe sur aucun de nos serveurs. Il
n'a aucun appelant aujourd'hui, donc aucun effet ; nous ne l'avons pas touché
dans ce lot, qui n'en avait pas besoin. Mais c'est exactement la forme du défaut
du 25 août, endormie : le jour où quelqu'un l'appellera, les liens repartiront
dans le vide. Nous le traiterons pour lui-même.

---

## 7. Ce que vous pourrez vérifier après notre déploiement

Dans votre ordre, qui est le bon :

1. `GET /.well-known/apple-app-site-association` contient `/v/*` ;
2. sur un iPhone où meetDo est installée, un lien `https://lien.meetdo.fun/v/…`
   **tapé depuis Mail** ouvre l'app. Et pour que le test soit le vrai test :
   **prenez un lien reçu après le déploiement**, pas un ancien — un lien émis
   avant porte l'ancien chemin et ouvrira Safari, ce qui est le comportement
   correct et non une panne ;
3. le même lien, sur un appareil sans l'app, ouvre la page — inchangée, bouton
   en plus ;
4. `GET /api/auth/verify-email?token=…` en `Accept: application/json` rend
   toujours 200 / 401 `INVALID_TOKEN`. **C'est le point qui nous importe le plus
   à nous aussi**, et il est verrouillé par test sur les deux chemins.

Sur le délai de propagation que vous annoncez : il joue, et le point 2 est le
seul dont l'échec ne prouve rien tant que le cache d'iOS n'a pas tourné. Le
point 1 est immédiat, lui, et c'est celui qui dit si nous avons livré.

---

*Une note pour finir, dans la suite de la vôtre. Vous écriviez que nos deux
défauts étaient symétriques et invisibles l'un à l'autre. Celui de ce lot ne
l'était même pas : le lien de l'e-mail ne se cachait nulle part, il n'était
simplement demandé par personne — vous décriviez le fichier d'association et la
route, nous aussi, et l'adresse réellement envoyée n'apparaissait dans aucune
des deux listes. Ce n'est pas un défaut que l'un ou l'autre pouvait voir de son
côté ; c'est un défaut que le partage des tâches lui-même avait rendu
inadressable. D'où le test qui, désormais, ouvre l'e-mail et lit le lien.*
