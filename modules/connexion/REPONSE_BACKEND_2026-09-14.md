# Réponse du 14/09 — le lien « mot de passe oublié » a une page, et c'est `/r/{token}`

**Date :** 2026-09-14
**Fait suite à :** `PROMPT_BACKEND_2026-09-14.md`
**Branche :** `fix/lien-reinitialisation-mot-de-passe` — **pas encore déployée**

> **Le chemin retenu est `/r/{token}`** (§3). Les e-mails partent désormais avec
> lui ; `/reset-password?token=` reste servi pour les e-mails déjà envoyés.
> Vous pouvez ajouter la forme `/r/<jeton>` à `deep_links.dart`.
>
> **Votre relevé du §1 est exact sur tous les points**, avec une correction qui
> joue contre nous : `Referrer-Policy: no-referrer` n'était **pas** posé sur la
> page de vérification, contrairement à ce que dit votre demande. Il ne l'était
> nulle part. C'est corrigé pour les deux pages (§4).
>
> **Deux écarts délibérés par rapport à votre demande**, argumentés plus bas :
> le formulaire web ne poste pas sur `/api/auth/reset-password` (§2.2), et la
> page ne propose pas de bouton « Ouvrir dans meetDo », parce qu'aucun bouton ne
> peut transmettre le jeton sans contredire P-MS-17 (§2.3).
>
> **Une mise en garde sur le calendrier de l'AASA** (§5) : dès qu'Apple l'aura
> relu, le lien ouvrira **toute** version installée de l'app, y compris celles
> qui ne savent pas encore quoi en faire.

---

## 1. Votre relevé, vérifié dans le code

| Votre constat | Ce que dit le code au commit `4c3d38b` |
|---|---|
| `EmailService` compose `/reset-password?token=` | Exact. |
| Aucun contrôleur ne sert `GET /reset-password` | Exact. Le commentaire l'avouait depuis le 25/08. |
| Retombe sur `anyRequest().authenticated()` et sort en 401 JSON | Exact. |
| L'AASA ne liste pas le chemin | Exact : `/s/*`, `/p/*`, `/public/slots/*`, `/public/programs/*`, `/v/*`. |
| Corps de `POST /api/auth/reset-password` : `{token, newPassword}` | Exact. |
| « `Referrer-Policy: no-referrer`, comme pour la vérification » | **Inexact.** Aucune réponse du serveur ne posait cet en-tête. Or le pied de page de toutes les pages publiques renvoie vers `https://meetdo.fun` : un clic dessus depuis `/v/<jeton>` envoyait l'adresse complète, jeton compris, dans le `Referer`. |

## 2. La page

### 2.1 Ce qu'elle rend

`GET /r/{token}` et `GET /reset-password?token=…` rendent la **même page**
(`ReinitialisationLinkController`, gabarit `reset-password.html`), toujours en
HTML, toujours en **200**, sans session :

| État du jeton | La page affiche |
|---|---|
| valide | le formulaire : nouveau mot de passe, confirmation |
| expiré (plus de 30 min) | « Ce lien a expiré », puis le formulaire « Recevoir un nouveau lien » |
| déjà consommé | « Ce lien a déjà servi ». Le texte couvre les deux cas possibles : une réinitialisation réussie, ou un lien plus récent demandé depuis, qui clôt les précédents. Suivi du même formulaire de nouveau lien. |
| inconnu, ou jeton absent | « Ce lien n'est pas reconnu » (lien tronqué ?), puis le formulaire de nouveau lien |

Lire l'état ne consomme rien : un robot d'aperçu qui suit le lien ne l'use pas.

Après envoi du formulaire :

- **réussi** : « Votre mot de passe est changé ». Toutes les sessions sont coupées,
  comme par l'API, puisque c'est le même `AuthService.resetPassword` ;
- **les deux saisies diffèrent**, ou moins de 8 ou plus de 100 caractères : le
  formulaire revient avec le message. Le lien reste valable et **aucune saisie
  n'est réinjectée** dans la page ;
- **lien devenu invalide entre-temps** (double envoi, par exemple) : l'état réel
  est affiché, jamais une erreur générique ;
- **plafond atteint** : **429** avec `Retry-After` et une page « Trop de
  tentatives ». Le compteur est **le même** que celui de l'API (20 par heure et
  par connexion) : on ne peut pas contourner le plafond de l'un en passant par
  l'autre.

**Le « mot de passe oublié » vers lequel renvoient les pages d'échec est sur la
page elle-même** : `POST /r` avec une adresse e-mail. Il répond la **même
phrase**, que le compte existe ou non, et applique le même plafond que
`/api/auth/forgot-password`. Nous l'avons préféré à un texte « retournez dans
l'app » : sur un poste de bureau, ce texte laissait la personne sans issue.

### 2.2 Écart : le formulaire poste sur `/r/{token}`, pas sur `/api/auth/reset-password`

Vous demandiez un formulaire qui envoie `POST /api/auth/reset-password`. Cette
route attend du **JSON**, qu'un formulaire HTML ne sait pas produire sans
script. Et un script qui l'appellerait dépendrait de CORS, fermé depuis le
13/09.

Le formulaire classique (`application/x-www-form-urlencoded`, vers
`POST /r/{token}`) fonctionne **sans JavaScript**, y compris dans le navigateur
intégré d'une messagerie. Il aboutit au **même service**, avec les mêmes règles,
le même plafond et la même révocation des sessions. **Le contrat JSON de l'app
ne change pas.**

### 2.3 Écart : pas de bouton « Ouvrir dans meetDo »

Nous avons cherché un bouton qui fonctionne, et il n'y en a pas deux :

- **`meetdo://…?token=`** : votre app ignore, à juste titre, un jeton reçu par
  le schéma personnalisé (P-MS-17). N'importe quelle application peut déclarer
  ce schéma. Un bouton qui le proposerait inviterait à défaire cette décision ;
- **`https://lien.meetdo.fun/r/…`** : iOS **n'ouvre pas** l'app pour un lien
  universel touché depuis une page **du même domaine**. Il reste dans Safari. Le
  bouton rechargerait la page, ce qui est pire que pas de bouton.

La page dit donc simplement : « Sur le téléphone où meetDo est installé, touchez
directement le lien de l'e-mail : il ouvre l'application. » C'est vrai dès que
l'AASA est relu (§5).

**Si vous voulez tout de même ce bouton**, la voie propre est la *Smart App
Banner* de Safari :
`<meta name="apple-itunes-app" content="app-id=…, app-argument=https://lien.meetdo.fun/r/…">`.
Safari affiche alors sa propre bannière, qui ouvre l'app avec l'URL en
argument. Deux choses nous manquent pour la poser : **l'identifiant App Store**
de meetDo, et **votre confirmation** que l'app traite cet argument comme un lien
universel entrant. Envoyez-nous les deux et c'est une ligne dans le gabarit.

## 3. Le chemin retenu : `/r/{token}`

- `EmailService.sendPasswordResetEmail` compose `https://lien.meetdo.fun/r/<jeton>` ;
- le jeton est un UUID : rien à encoder ;
- `/reset-password?token=<jeton>` reste servi indéfiniment. Les liens déjà partis
  sont morts au bout de 30 minutes, mais ils peuvent être ouverts plus tard, et
  ils doivent alors afficher « expiré » plutôt que 401.

**Côté app** : ajoutez `/r/<jeton>` à côté de `/reset-password?token=` dans
`deep_links.dart`. Le jeton est le **dernier segment** du chemin.

## 4. Le jeton ne sort pas

- **`Referrer-Policy: no-referrer`** et **`Cache-Control: no-store`** sur
  **toutes** les réponses de `/r/**` et `/reset-password`, pages d'échec, erreurs
  de saisie et 429 compris. Le gabarit porte en plus
  `<meta name="referrer" content="no-referrer">`, qui tient encore si la page est
  enregistrée ;
- **ajoutés aussi à la page de vérification** `/v/{token}` et
  `/api/auth/verify-email` (§1) ;
- **aucune journalisation** dans le nouveau contrôleur. Le seul endroit où le
  lien est écrit reste le repli de développement d'`EmailService`, qui ne s'allume
  qu'avec `pair.email.journaliser-liens` (P-BS-09) ;
- **ce que nous ne couvrons pas** : une adresse **mal formée** sous `/r/`
  (`/r/<jeton>/autre-chose`, ou une méthode non prévue) passe par les
  gestionnaires génériques 404 et 405, qui journalisent l'URI. Ce n'est jamais
  l'adresse d'un vrai lien, et `/v/` a la même limite. Nous le signalons sans le
  traiter ici.

## 5. L'AASA — et le moment où il prend effet

Ajouté à `/.well-known/apple-app-site-association` :

```json
{ "/": "/r/*", "comment": "Réinitialisation du mot de passe" },
{ "/": "/reset-password", "?": { "token": "*" }, "comment": "Réinitialisation, e-mails envoyés avant le 14/09/2026" }
```

**La mise en garde.** iOS ne regarde pas quelle version de l'app est installée.
Dès qu'un appareil aura relu ce fichier, qu'Apple sert depuis son CDN avec
plusieurs heures de cache, le lien de l'e-mail ouvrira **l'app installée**,
**même une version sans `fix/lien-reinitialisation`**. Cette version s'ouvrira
sans rien faire du lien, et la personne ne verra jamais le formulaire web.

Ce n'est pas pire qu'aujourd'hui, où le lien donne un 401. Mais pendant cette
fenêtre, c'est moins bien que ce que la page web permettrait.

**Dites-nous si la version de l'app qui gère ce lien sort dans les prochains
jours.** Si elle doit attendre, nous pouvons retirer ces deux lignes de l'AASA
et les remettre le jour de la sortie. Le reste du lot (page, formulaire, e-mail)
n'en dépend pas.

Côté Android, rien ne change tant que P-MS-08 n'a pas produit les empreintes :
`assetlinks.json` reste en 404.

## 6. Vérification

**Tests** : `ReinitialisationLienIntegrationTest`, nouvelle classe de 13 tests.
Page sans session sur les deux chemins, en-têtes sur chaque réponse, jeton
bidon, expiré, déjà servi, `HEAD`, réinitialisation réussie suivie d'une
connexion avec le nouveau mot de passe et d'un refus de l'ancien, saisies
différentes et trop courtes sans effet sur le lien, 429 au 21ᵉ envoi, demande
de nouveau lien identique que le compte existe ou non, et e-mail portant
`/r/<jeton>`.

Tests existants mis à jour : AASA (`PublicSlotPageIntegrationTest`), marque des
pages publiques (`MarquePagesPubliquesIntegrationTest`, qui balaie désormais
`/r/`), repli de développement (`EmailServiceDemarrageTest`) et en-tête de la
page de vérification (`EmailVerificationIntegrationTest`).

**Après déploiement, votre protocole du §3 tient tel quel.** Nous y ajoutons un
point : l'envoi du formulaire **depuis un vrai navigateur** sur
`lien.meetdo.fun`. Le navigateur y joint un en-tête `Origin`, et le filtre CORS
ne le reconnaît comme même-origine que grâce aux en-têtes `X-Forwarded-*` de
Railway. C'est déjà le cas des formulaires du contact de confiance, mais ce
point ne se voit pas en test.

```
curl -s https://lien.meetdo.fun/.well-known/apple-app-site-association | grep -E '/r/\*|reset-password'
curl -si https://lien.meetdo.fun/r/essai | grep -iE '^HTTP|referrer-policy|n.est pas reconnu'
curl -si 'https://lien.meetdo.fun/reset-password?token=essai' | head -1   # 200, plus 401
```
