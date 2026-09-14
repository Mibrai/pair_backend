# Réponse à votre erratum du 02/09 — `commit` ne vaudra plus `"local"`, et c'était un oubli de notre côté

**Date :** 2026-09-14 — douze jours après votre document, resté sans réponse jusqu'ici.
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-BIS.md`

> **§1 et §3 — pris acte, rien à faire.** Votre erratum sur `/participants` est reçu. Le test que
> nous avions ajouté (`RetourProduction20260902IntegrationTest.laListeDesInscrits_doitPorterLIdentiteDeChacun`)
> reste en place : il fige un contrat juste.
>
> **§2 — vous aviez raison, et la valeur « local » venait bien de nous.** Le `pom.xml` sait lire le
> sha du commit depuis le 02/09, mais le `Dockerfile` ne le lui transmettait pas. **Chaque
> déploiement depuis a gravé `"local"`**, y compris ceux d'aujourd'hui. C'est corrigé, et vérifiable
> dès le prochain déploiement.

---

## 1. `/participants` — pris acte

Rien à instruire. Merci de l'avoir écrit en premier : cela nous a évité de chercher un défaut qui
n'existait pas.

## 2. `"commit":"local"` — la cause, et le correctif

### Ce qui était en place

Le 02/09 (`d687f9c`), nous avions branché l'identité du build :

- `build.commit` vaut `local` par défaut dans le `pom.xml` ;
- un profil Maven, `build-identity-from-env`, s'active quand la variable `RAILWAY_GIT_COMMIT_SHA`
  existe et la grave à la place ;
- `spring-boot:build-info` écrit le résultat dans `build-info.properties`, que `/actuator/info`
  rend.

### Pourquoi cela n'a jamais marché en production

Railway construit l'image à partir de notre `Dockerfile`. **Dans une construction Docker, une
variable fournie par la plateforme n'atteint une commande `RUN` que si le Dockerfile la déclare
par `ARG`.** La nôtre ne la déclarait pas. Maven ne voyait donc jamais le sha, le profil ne
s'activait pas, et la valeur de repli `local` partait en production.

Nous l'avons vérifié des deux côtés avant de corriger :

| Vérification | Résultat |
|---|---|
| Maven avec `RAILWAY_GIT_COMMIT_SHA=e09e9f2…` | `build.commit=e09e9f2…` |
| Maven avec la variable vide, ou absente | `build.commit=local` |
| Docker, variable fournie **sans** `ARG` | invisible dans `RUN` |
| Docker, variable fournie **avec** `ARG` | visible dans `RUN` |

Les déploiements viennent bien de GitHub : Railway enregistre pour chacun le `commitHash`
(`e09e9f23…` pour le dernier), qui est la valeur transmise.

### Le correctif

Une ligne dans l'étape de construction du `Dockerfile`, juste avant le `package` Maven :

```dockerfile
ARG RAILWAY_GIT_COMMIT_SHA
RUN ./mvnw clean package -DskipTests -B
```

Elle est placée **après** le téléchargement des dépendances : sa valeur change à chaque commit, et
plus haut elle invaliderait le cache de cette couche à chaque construction. Une construction
locale, sans la variable, garde `local`.

**Un test déclaratif** (`ObservabiliteConfigurationTest`) exige cette déclaration avant le
`package`, dans l'étape de construction. Il échoue sur le Dockerfile d'avant.

## 3. Ce que vous avez branché — pris acte

La série lue sans la veille, l'interrupteur `notifyGuardian` sur la carte de saisie, `role`,
`RETURN_ANNOUNCED`, la chronologie sur le seul écran de veille, et la suppression des écrans du code
de séance : rien de tout cela ne nous demande quoi que ce soit. Nous gardons votre raison sur le code
de séance comme vous l'avez gardée : un code détenu par l'organisateur ferait de lui un point de
pression.

## 4. Vérification

**Après le prochain déploiement** :

```
curl -s https://lien.meetdo.fun/actuator/info
```

`build.commit` doit valoir le sha complet du commit déployé, celui que montre la page GitHub du
dépôt, et non plus `local`. Vous pouvez donc joindre `/actuator/info` à vos relevés comme vous
l'annonciez, et il désignera désormais un état précis du dépôt.
