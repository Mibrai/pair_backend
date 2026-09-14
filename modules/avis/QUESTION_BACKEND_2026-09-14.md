# Avis de programme : la note de 1 à 5 doit partir — dites-nous comment l'app la lit avant qu'on y touche

**Date :** 2026-09-14
**Module :** `avis` — nouveau module, ouvert par ce document
**Audit :** P-BL-10 (décision D4), lien P-MU-02
**Ce que nous attendons de vous :** une réponse aux trois questions du §2, avant tout changement serveur.

> **Pourquoi c'est nous qui écrivons.** La décision D4, tranchée le 13/09, dit « plus aucune note de 1 à
> 5 ni moyenne publique, ni sur les personnes ni sur les programmes ». La moyenne est partie le 13/09 :
> `averageScore` et `reviewCount` sont servis à `null`. **La note de chaque avis, elle, est restée** :
> `POST /api/reviews` l'exige encore, et chaque avis la rend publiquement. Nous ne pouvons pas la
> retirer sans savoir comment votre app 1.1.0+16 l'envoie et la lit, et nous n'avons pas vos sources.

---

## 1. Ce que le serveur fait aujourd'hui

| Route | Ce qui concerne la note |
|---|---|
| `POST /api/reviews` | `score` **obligatoire**, entre `1.0` et `5.0` ; `comment` obligatoire, 30 à 1000 caractères |
| `GET /api/reviews/programs/{programId}` | chaque `ReviewDto` porte `score` (nombre) et `comment`, lisibles par tout compte connecté |
| `GET /api/reviews/programs/{programId}/summary` | `averageScore` toujours `null` (déprécié) ; `totalReviews` ; `recentReviews[]` avec leur `score` |
| `GET /api/reviews/me` | mes avis, avec leur `score` |
| `GET /api/reviews/can-review/{programId}` | booléen, sans rapport avec la note |

## 2. Nos trois questions

1. **Écriture.** Votre app appelle-t-elle `POST /api/reviews` ? Si oui, envoie-t-elle toujours un `score`,
   et depuis quel écran ?
2. **Lecture.** Votre app lit-elle `score` dans `ReviewDto` (liste, résumé, « mes avis ») ? Si oui, le
   modèle le déclare-t-il **non nul** ? Un avis reçu avec `score: null` s'afficherait-il, ou ferait-il
   échouer toute la liste ?
3. **Le mot.** D4 fait du commentaire un **retour privé**, lu par l'organisateur seul. Votre app
   affiche-t-elle aujourd'hui les commentaires des autres sur la page d'un programme ?

## 3. L'ordre que nous proposons, à caler sur vos réponses

| Étape | Serveur | Condition |
|---|---|---|
| **A** | `score` devient **facultatif** à l'écriture. S'il est envoyé, il est encore accepté et rendu comme aujourd'hui | aucune : sans risque pour 1.1.0+16 |
| **B** | votre version d'app n'envoie plus de note et n'en affiche plus (P-MU-02) | publiée et adoptée |
| **C** | `score` n'est plus enregistré et plus rendu (servi à `null`, puis retiré du contrat) ; le commentaire n'est plus rendu qu'à l'organisateur du programme et à son auteur | après B |
| **D** | la colonne `reviews.score` est supprimée, avec les notes existantes | deux semaines après C |

Si votre réponse à la question 2 est « le modèle tolère `null` », l'étape C peut suivre A sans attendre
B pour la partie « plus rendu ». Si elle est « non nul », **C attend B**, comme pour `averageScore`.

## 4. Ce qui ne change pas d'ici votre réponse

- **Rien côté serveur.** Aucune route, aucun champ, aucun code d'erreur ne bouge avant votre réponse.
- Les recommandations entre personnes, sans note depuis le 13/09, ne sont pas concernées.
- Le booléen public « Recommandé par des participants » que prévoit D4 **n'existe pas encore** côté
  serveur : il fera l'objet d'un document à part, s'il vous est utile.
