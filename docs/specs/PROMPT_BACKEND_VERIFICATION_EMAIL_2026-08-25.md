# Le lien de vérification d'e-mail pointe sur `localhost:3000`

> **Nos testeurs ne peuvent pas valider leur compte.** L'e-mail envoyé après
> l'inscription contient un lien vers `http://localhost:3000/…` : le navigateur
> affiche « impossible d'accéder à ce site », et le compte reste non vérifié.
>
> Nous avons vérifié ce que nous pouvions vérifier depuis l'extérieur, et le
> défaut n'est pas là où on l'attendrait : **`FRONTEND_URL` est correcte.** Le
> lien est donc construit à partir d'autre chose.
>
> Deuxième point, moins visible et tout aussi bloquant : **même corrigée, la
> cible n'existe pas.** Il n'y a aucune page de vérification à servir.

---

## 1. Ce que nous avons constaté, et comment

Relevé le 25 août 2026, sur l'environnement `production`.

| Maillon | Valeur / réponse | Verdict |
|---|---|---|
| Variable `FRONTEND_URL` | `https://meetdo.fun` | **correcte** — ce n'est pas elle qui produit le `localhost:3000` |
| `GET https://meetdo.fun/verify-email?token=x` | **404** | aucune page de vérification |
| `GET https://meetdo.fun/verify?token=x` | **404** | idem |
| `GET https://meetdo.fun/` | 200 | le site vitrine répond, mais il ne connaît pas ce chemin |
| `GET https://lien.meetdo.fun/api/auth/verify-email?token=test-invalide` | **401** `{"code":"INVALID_TOKEN"}` | **la route d'API fonctionne** |

Deux conclusions que ces mesures imposent :

1. Le lien de l'e-mail **n'est pas bâti sur `FRONTEND_URL`**. Une autre
   propriété porte l'URL de vérification, avec une valeur par défaut de
   développement (`http://localhost:3000`) qui n'a jamais été surchargée en
   production. C'est chez vous, et vous seuls pouvez la nommer.
2. Corriger cette propriété vers `https://meetdo.fun` **ne suffirait pas** :
   le site vitrine rend 404 sur les deux chemins plausibles. Il n'y a pas de
   frontend web qui serve cette page — `localhost:3000` suggère qu'il en a
   existé un en développement, mais rien de tel n'est déployé.

La seule brique de la chaîne qui fonctionne aujourd'hui est votre propre route
d'API.

---

## 2. Ce que nous proposons

**Faire pointer le lien de l'e-mail directement sur l'API**, et lui faire rendre
une page quand c'est un navigateur qui la demande :

```
https://lien.meetdo.fun/api/auth/verify-email?token=…
```

Ce que cela vous coûte : une propriété à corriger, et une réponse `text/html`
sur cette route lorsque l'en-tête `Accept` le demande — deux phrases et un
bouton suffisent. Ce que cela vous évite : déployer et maintenir un frontend web
dont la seule raison d'être serait cette page.

Nous insistons sur le rendu HTML parce que la route rend aujourd'hui du JSON.
Un testeur qui clique dans son e-mail et reçoit
`{"message":"Email vérifié"}` en pleine page conclut que quelque chose a mal
tourné — et nous écrit. Le compte est pourtant vérifié : c'est le pire des cas,
celui où tout fonctionne et où personne ne le croit.

**Trois états à distinguer dans cette page**, parce qu'ils appellent trois
gestes différents :

| Cas | Ce que la page doit dire |
|---|---|
| Jeton valide | Compte vérifié, « vous pouvez retourner dans l'app » |
| Jeton expiré | Comment en redemander un — vous exposez déjà `POST /auth/resend-verification` |
| Jeton inconnu | Le dire sans détour, sans laisser croire à une panne |

---

## 3. Ce que nous faisons de notre côté en attendant

Nous donnons à nos testeurs la reconstruction manuelle du lien : remplacer
`http://localhost:3000/verify-email` par
`https://lien.meetdo.fun/api/auth/verify-email` en gardant le jeton. Cela
fonctionne — nous l'avons vérifié — et les débloque aujourd'hui.

Ce n'est évidemment pas tenable au-delà d'un cercle de testeurs prévenus.

---

## 4. Une question, pour ne pas refaire le tour deux fois

**Le lien doit-il un jour ouvrir l'app plutôt qu'un navigateur ?**

Techniquement, oui : `lien.meetdo.fun` est déjà déclaré en domaine associé côté
iOS, donc un lien de la forme `https://lien.meetdo.fun/verify/{token}` pourrait
ouvrir meetDo directement, l'app appelant ensuite votre route. Nous ne le
demandons pas aujourd'hui — notre routeur de liens ne connaît que
`programs`, `activities` et `slots`, et l'ajout serait de notre ressort.

La question est de savoir si vous préférez que nous allions dans cette
direction, auquel cas la page HTML n'est qu'une étape ; ou si la page web reste
la cible définitive, auquel cas elle mérite d'être soignée. Les deux se
défendent. Ce qui ne se défend pas, c'est l'état actuel : un lien qui ne mène
nulle part, envoyé à chaque inscription.

---

## 5. Pendant que nous vous écrivons — deux autres points

**Une erreur récurrente en production**, sans rapport avec ce ticket, relevée
dans vos journaux le 25 août à 17h25 (trois occurrences en deux secondes) :

```
java.lang.IllegalArgumentException:
  No enum constant org.program.pair.domain.notification.NotificationFrequency.DAILY
```

L'app envoie `DAILY_DIGEST`, conforme à votre contrat
(`IMMEDIATE | DAILY_DIGEST | WEEKLY`) — la valeur `DAILY` ne vient donc pas de
nous. Elle ressemble à des lignes en base héritées d'une version antérieure de
l'énumération. Comme l'envoi est asynchrone, **rien ne remonte à
l'utilisateur** : les comptes concernés cessent simplement de recevoir des
notifications, sans que personne ne puisse le constater autrement qu'en lisant
vos journaux.

**Le stockage des médias est réparé de notre côté du réglage.** Le volume était
monté sur `/app/models` tandis que l'application écrivait dans `/data/uploads` —
un troisième chemin, différent des deux que le diagnostic d'août avait
envisagés. Nous avons posé `STORAGE_PATH=/app/models/uploads` : votre journal
de démarrage affiche désormais `Storage initialized at: /app/models/uploads`.
Le marqueur de persistance confirmera au prochain redéploiement. Merci pour ce
signal de démarrage — c'est lui qui a rendu la vérification immédiate.
