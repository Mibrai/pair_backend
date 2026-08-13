# Pair — Journal de déploiement Railway
## Documentation de référence, basée sur le déploiement réel du backend

> Ce document retrace, étape par étape, le déploiement effectif du backend
> Spring Boot + PostgreSQL (PostGIS + pgvector) de Pair sur Railway. Il inclut
> chaque commande, l'endroit exact où l'exécuter, le rôle de l'étape, et
> **toutes les erreurs rencontrées avec leur correction** — pour éviter de
> les reproduire lors d'un prochain déploiement (nouvel environnement,
> nouvelle machine, ou onboarding d'un collaborateur).

---

## Vue d'ensemble de l'architecture cible

```
Projet Railway "pair_backend"
├── Service "pair_backend"   → backend Spring Boot (Dockerfile Java)
├── Service "postgres_db"    → PostgreSQL + PostGIS + pgvector (Dockerfile custom)
└── Service "postgres"       → résidu d'un essai raté, supprimé
```

**Point clé à retenir avant de commencer** : ces deux services sont **totalement indépendants** dans Railway, chacun avec son propre Dockerfile, ses propres variables, son propre déploiement. Toute la confusion rencontrée pendant ce déploiement est venue du fait qu'ils partagent le même **projet** Railway mais doivent être gérés depuis des **dossiers locaux séparés**.

---

## Organisation des dossiers locaux — la base de tout

```
F:\Projekt\Pair\
├── pair-postgres\     ← UNIQUEMENT le Dockerfile + init SQL de la base
│   ├── Dockerfile
│   └── init-extensions.sql
└── pair_backend\       ← le VRAI code Spring Boot (repo GitHub Mibrai/pair_backend)
    ├── Dockerfile
    ├── mvnw, mvnw.cmd, .mvn/
    ├── pom.xml
    └── src/
```

> ⚠️ **Erreur rencontrée** : la plupart des soucis de ce déploiement viennent du
> fait que les commandes `railway service` / `railway up` étaient exécutées
> depuis le mauvais dossier. `railway up` déploie **toujours le contenu du
> dossier courant**, peu importe le service auquel la CLI est nominalement
> "liée" dans le dashboard. Toujours vérifier son dossier avec `pwd`
> (ou regarder le chemin affiché dans l'invite PowerShell) avant de lancer
> `railway up`.

---

## Partie 1 — Déploiement du service PostgreSQL (`postgres_db`)

### Étape 1.1 — Créer les fichiers de la base

**Où** : nouveau dossier local dédié, séparé du code backend.

```bash
cd F:\Projekt\Pair
mkdir pair-postgres
cd pair-postgres
```

**Rôle** : isoler la configuration Docker de la base dans un dossier propre à elle, pour que `railway up` ne déploie jamais accidentellement le mauvais contenu sur le mauvais service.

### Étape 1.2 — Le Dockerfile de la base

**Fichier** : `pair-postgres/Dockerfile` (⚠️ nommé exactement `Dockerfile`, sans suffixe — voir erreur ci-dessous)

```dockerfile
FROM pgvector/pgvector:pg16

RUN apt-get update && apt-get install -y \
    postgresql-16-postgis-3 \
    postgresql-16-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*

COPY init-extensions.sql /docker-entrypoint-initdb.d/
```

**Fichier** : `pair-postgres/init-extensions.sql`

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

**Rôle** : l'image `pgvector/pgvector:pg16` fournit déjà pgvector ; on ajoute PostGIS par-dessus, puis le script `init-extensions.sql` s'exécute automatiquement au premier démarrage du conteneur (comportement standard de l'image officielle `postgres`, qui exécute tout `.sql` déposé dans `/docker-entrypoint-initdb.d/`).

> ⚠️ **Erreur rencontrée — nom du fichier**
> La commande `railway up --dockerfile Dockerfile.postgres` a échoué avec
> `error: unexpected argument '--dockerfile' found`. La CLI Railway actuelle
> n'a **pas** de flag `--dockerfile` : elle détecte automatiquement un fichier
> nommé exactement `Dockerfile` dans le dossier courant.
> **Correction** : renommer `Dockerfile.postgres` en `Dockerfile` (sans
> extension), ou sinon utiliser un `railway.json` avec
> `"dockerfilePath": "Dockerfile.postgres"` — mais la solution la plus fiable
> reste de nommer directement le fichier `Dockerfile`.

### Étape 1.3 — Créer le service Railway dédié

**Où** : dashboard web Railway (pas la CLI, pour éviter toute ambiguïté de service).

```bash
railway open
```

Dans le dashboard :
1. **"+ New"** → **"Empty Service"**
2. Renommer ce service en **`postgres_db`**

> ⚠️ **Erreur rencontrée — service créé par erreur sur le mauvais service**
> Un premier essai de `railway init` + `railway up` depuis le dossier
> `pair-postgres`, en répondant "ajouter au projet existant" au prompt, a eu
> pour effet de **déployer le Dockerfile Postgres sur le service `pair_backend`
> existant**, l'écrasant temporairement. Résultat observé : le service
> `pair_backend` faisait tourner PostgreSQL au lieu de Spring Boot.
> **Correction** : toujours créer un nouveau service **vide** explicitement
> depuis le dashboard web avant de le cibler en CLI, plutôt que de laisser
> `railway init`/`railway up` deviner ou réutiliser un service existant.

### Étape 1.4 — Lier la CLI à ce service précisément

**Où** : terminal, dans le dossier `pair-postgres`.

```bash
cd F:\Projekt\Pair\pair-postgres
railway service
# Sélectionner "postgres_db" dans la liste proposée
```

**Vérification systématique avant toute action** :

```bash
railway status
```

Doit afficher `Linked service: postgres_db`.

### Étape 1.5 — Ajouter les variables d'environnement de la base

**Où** : terminal (lié à `postgres_db`) ou dashboard → service `postgres_db` → onglet Variables.

```bash
railway variables --set "POSTGRES_PASSWORD=<mot-de-passe-fort>" --set "POSTGRES_USER=pair_user" --set "POSTGRES_DB=pair_db"
```

**Rôle** : l'image officielle `postgres` (base de `pgvector/pgvector`) **refuse de démarrer** sans `POSTGRES_PASSWORD` défini — c'est une protection intégrée à l'image, pas une exigence Railway.

> ⚠️ **Erreur rencontrée — variable manquante**
> Premier déploiement en boucle avec le message :
> ```
> Error: Database is uninitialized and superuser password is not specified.
> You must specify POSTGRES_PASSWORD to a non-empty value for the superuser.
> ```
> **Correction** : ajouter explicitement `POSTGRES_PASSWORD` (et par cohérence
> `POSTGRES_USER`, `POSTGRES_DB`) dans les variables du service, **avant**
> tout déploiement. Si l'erreur persiste après ajout, vérifier que les
> variables sont bien sur le **bon service** (`railway variables` sans
> argument affiche celles du service actuellement lié) et forcer un nouveau
> déploiement avec `railway up`.

> ⚠️ **Point de sécurité** : si un mot de passe se retrouve exposé (capture
> d'écran partagée, log affiché publiquement...), le régénérer sans tarder :
> ```bash
> railway variables --set "POSTGRES_PASSWORD=<nouveau-mot-de-passe>"
> ```

### Étape 1.6 — Déployer

**Où** : terminal, dans `pair-postgres`, CLI liée à `postgres_db`.

```bash
railway up
```

**Résultat attendu dans les logs** (`railway logs`) :

```
database system is ready to accept connections
CREATE EXTENSION
CREATE EXTENSION
CREATE EXTENSION
```

Les trois `CREATE EXTENSION` confirment que `postgis`, `vector` et `uuid-ossp` sont bien actifs.

### Étape 1.7 — Nettoyage des services résiduels

> ⚠️ **Erreur rencontrée** : un troisième service nommé `postgres` (tiret
> simple, distinct de `postgres_db`) est apparu suite aux tout premiers
> essais ratés — un service vide jamais réellement utilisé.
> **Correction** : dashboard → service `postgres` → Settings → Delete Service,
> pour éviter toute confusion future entre les deux noms proches.

### Étape 1.8 — Récupérer les informations de connexion réseau

**Où** : dashboard → service `postgres_db` → Settings → **Networking**.

Deux types d'accès à distinguer :

| Type | Usage | Exemple observé |
|---|---|---|
| **Public Networking — Domaine HTTP** | Ne sert **pas** pour psql (c'est un domaine web, pas TCP) | `postgresdb-production.up.railway.app` |
| **Public Networking — TCP Proxy** | Connexion `psql` depuis l'extérieur (machine locale) | port `5556` (hostname associé visible en cliquant/copiant) |
| **Private Networking** | Connexion interne, utilisée par `pair_backend` | `postgresdb.railway.internal` (raccourci : `postgresdb`) |

> ⚠️ **Erreur rencontrée** : tentative de connexion via `railway connect
> postgres` → `Service "postgres" not found` (mauvais nom), puis
> `railway connect` sur le bon service → `No supported database found in
> service`. **Explication** : `railway connect` ne fonctionne qu'avec l'addon
> PostgreSQL **natif** de Railway, pas avec un service Docker custom comme
> celui-ci. **Solution retenue** : abandonner la vérification manuelle via
> `psql` local (non indispensable) et faire confiance aux logs Flyway du
> backend pour confirmer que les extensions sont bien actives au moment des
> migrations.

**Pour retrouver toutes les infos de connexion sans ambiguïté**, la commande
la plus fiable reste :

```bash
railway service
# Sélectionner postgres_db
railway variables
```

Ça affiche notamment `RAILWAY_PRIVATE_DOMAIN` (à utiliser pour la connexion interne depuis le backend).

---

## Partie 2 — Déploiement du service Backend (`pair_backend`)

### Étape 2.1 — Vérifier/reconnecter la source GitHub

**Où** : dashboard → service `pair_backend` → Settings → Source.

> ⚠️ **Erreur rencontrée** : après les mésaventures de la Partie 1, le service
> `pair_backend` s'est retrouvé **sans aucune source connectée**
> (`Connect Repo` / `Connect Image` tous deux vides) — conséquence du
> déploiement Postgres accidentel qui avait tourné dessus.
> **Correction** : dashboard → `pair_backend` → Settings → Source →
> **Connect Repo** → sélectionner `Mibrai/pair_backend` → branche `master`.

> ℹ️ **Point observé, non bloquant** : après reconnexion, le dashboard a
> affiché "Auto deploy unavailable" sous le nom de la branche. Ça signifie
> que Railway ne redéploie pas automatiquement à chaque `git push` — il faut
> déclencher manuellement (`railway up` ou bouton Deploy du dashboard) tant
> que ce point n'est pas résolu (généralement lié aux permissions du
> GitHub App Railway à revalider).

### Étape 2.2 — Ajouter les variables d'environnement du backend

**Où** : terminal, CLI liée à `pair_backend` (voir Étape 2.4 pour bien se lier), ou dashboard → Variables.

```bash
railway variables --set "PGHOST=postgresdb.railway.internal" \
  --set "PGPORT=5432" \
  --set "PGUSER=pair_user" \
  --set "PGPASSWORD=<mot-de-passe-de-postgres_db>" \
  --set "PGDATABASE=pair_db" \
  --set "SPRING_PROFILES_ACTIVE=railway" \
  --set "JWT_SECRET=<généré via: openssl rand -base64 32>" \
  --set "JWT_ACCESS_TOKEN_EXPIRY_MS=900000" \
  --set "JWT_REFRESH_TOKEN_EXPIRY_MS=2592000000"
```

**Rôle** : `PGHOST` pointe vers le nom réseau **interne** de `postgres_db`
(`postgresdb.railway.internal`) — pas besoin d'exposition publique puisque
les deux services sont dans le même projet Railway et communiquent en privé.

> ⚠️ **Erreur rencontrée — variable SPRING_PROFILES_ACTIVE oubliée**
> Un premier passage a omis cette variable. Sans elle, Spring Boot démarre
> sans profil actif et retombe sur la config par défaut de
> `application.properties`, qui pointait vers `localhost:5432` (pensée pour
> le développement local) :
> ```
> Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432
> refused.
> ```
> **Correction** : toujours vérifier `railway variables` avant de déployer,
> et confirmer que `SPRING_PROFILES_ACTIVE` correspond exactement au nom du
> fichier `application-<profil>.properties` attendu par l'application.

### Étape 2.3 — Créer le fichier de configuration du profil Railway

**Où** : dans le repo backend, sur ta machine locale — `pair_backend\src\main\resources\application-railway.properties`.

**Rôle** : ce fichier **surcharge** uniquement ce qui doit changer par rapport
à `application.properties` (le fichier de base, pensé pour le développement
local). Le reste (JWT, clés API, Flyway, JPA...) continue de venir
d'`application.properties`.

```properties
# Connexion base de donnees - variables Railway (postgres_db)
spring.datasource.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
spring.datasource.username=${PGUSER}
spring.datasource.password=${PGPASSWORD}

# Railway gere le HTTPS en peripherie - desactiver le SSL interne
server.ssl.enabled=false

# Ecouter sur le port injecte par Railway
server.port=${PORT:8080}

redis.enabled=${REDIS_ENABLED:false}
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}

pair.seed.reference-data.enabled=true
pair.seed.demo-data.enabled=true
```

> ⚠️ **Erreur rencontrée n°1 — fichier de profil manquant**
> Il n'existait initialement qu'un `application-prod.properties` (contenant
> uniquement la config des seeds, aucune info de datasource). Le profil actif
> était `railway`, donc aucun fichier ne correspondait, et Spring retombait
> sur les valeurs par défaut d'`application.properties` (`localhost:5432`).
> **Correction** : créer le fichier `application-railway.properties`
> manquant avec le contenu ci-dessus.

> ⚠️ **Erreur rencontrée n°2 — SSL interne actif**
> `application.properties` activait `server.ssl.enabled=true` avec un
> keystore local, sur le port `8090`, en plus d'un port HTTP secondaire
> `8091`. Cette configuration, pensée pour du HTTPS local, entre en conflit
> avec le modèle Railway : Railway gère lui-même le HTTPS en périphérie
> (edge) et route ensuite en HTTP simple vers le conteneur sur le port
> `$PORT` qu'il injecte. Le SSL interne était donc inutile et risquait de
> bloquer le healthcheck/routage.
> **Correction** : `server.ssl.enabled=false` dans le profil `railway`, et
> `server.port=${PORT:8080}` pour écouter dynamiquement sur le port fourni
> par Railway plutôt qu'un port fixe en dur.

> ⚠️ **Erreur rencontrée n°3 — encodage du fichier corrompu**
> Le fichier ayant été créé avec le Bloc-notes Windows classique (Notepad),
> le build Maven a échoué :
> ```
> [ERROR] filtering .../application-railway.properties ... failed with
> MalformedInputException: Input length = 1
> ```
> **Cause** : Notepad avait sauvegardé le fichier dans un encodage différent
> d'UTF-8 (ou avec un BOM mal interprété), ce que Maven ne digère pas lors du
> filtrage des ressources.
> **Correction** — recréer le fichier en forçant l'UTF-8 sans BOM, via
> PowerShell :
> ```powershell
> $content = @"
> ... (contenu du fichier) ...
> "@
> [System.IO.File]::WriteAllText(
>     "src\main\resources\application-railway.properties",
>     $content,
>     [System.Text.UTF8Encoding]::new($false)
> )
> ```
> Ou plus simple avec un éditeur comme VS Code : ouvrir le fichier, regarder
> l'encodage affiché en bas à droite, et choisir explicitement
> **"Save with Encoding" → "UTF-8"** (pas "UTF-8 with BOM").
> **Leçon générale** : éviter Notepad pour créer des fichiers de config sur
> ce projet ; préférer un éditeur de code qui garantit l'UTF-8 par défaut.

### Étape 2.4 — Créer le Dockerfile du backend

**Où** : dans le repo backend local — `pair_backend\Dockerfile`.

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**Rôle** : build multi-stage — la première étape compile le jar avec Maven
dans une image JDK complète, la seconde ne garde que le strict nécessaire
pour l'exécution (JRE léger + le jar), ce qui réduit fortement la taille de
l'image finale déployée.

> ⚠️ **Erreur rencontrée — permissions du Maven Wrapper**
> Sans la ligne `RUN chmod +x mvnw`, le build échouait :
> ```
> /bin/sh: ./mvnw: Permission denied
> Build Failed: ... exit code: 126
> ```
> **Cause** : Git ne préserve pas toujours le bit d'exécution Unix (`+x`) sur
> les scripts wrapper (`mvnw`) quand le repo a été committé depuis un
> environnement Windows.
> **Correction** : ajouter `RUN chmod +x mvnw` juste après avoir copié le
> fichier dans l'image, avant de l'exécuter.

> ℹ️ **Variante si le Maven Wrapper n'est pas présent** dans le projet
> (pas de `mvnw`/`mvnw.cmd`/`.mvn/`) :
> ```dockerfile
> FROM maven:3.9-eclipse-temurin-21-alpine AS build
> WORKDIR /app
> COPY pom.xml ./
> RUN mvn dependency:go-offline -B
> COPY src ./src
> RUN mvn clean package -DskipTests -B
> ```
> (le reste du Dockerfile — stage d'exécution — reste identique)

### Étape 2.5 — Se positionner dans le BON dossier avant toute commande CLI

**Où** : terminal.

```bash
cd F:\Projekt\Pair\pair_backend
```

> ⚠️ **Erreur rencontrée — la plus coûteuse en temps de tout ce déploiement**
> Plusieurs commandes `railway service`, `railway up`, `railway logs` ont été
> exécutées **depuis le dossier `pair-postgres`** alors qu'on croyait agir
> sur `pair_backend`. Résultat : confusion prolongée où les logs de
> PostgreSQL s'affichaient en boucle malgré un `Linked service: pair_backend`
> correct dans `railway status` — parce que `railway up` déploie le
> **contenu du dossier courant**, indépendamment du service listé comme
> "lié". Le prompt `F:\Projekt\Pair\pair-postgres>` était le signal manqué à
> chaque fois.
> **Correction / règle à appliquer systématiquement** :
> 1. Toujours vérifier son dossier courant (`pwd` ou lire l'invite) avant
>    `railway up`.
> 2. Chaque service a son propre dossier dédié — ne jamais lancer `railway up`
>    pour `pair_backend` depuis `pair-postgres`, et inversement.
> 3. `railway link` doit être relancé dans **chaque nouveau dossier local**
>    pour connecter ce dossier au bon projet/service.

**Lier ce dossier au projet existant (si pas encore fait)** :

```bash
railway link
# Choisir : workspace "mibrai's Projects" → projet "pair_backend"
#           → environnement "production" → service "pair_backend"
```

**Vérification systématique** :

```bash
railway status
```

Doit confirmer `Linked service: pair_backend` **et** que le dossier courant
est bien `pair_backend`.

> ⚠️ **Erreur rencontrée — lien absent lors du changement de dossier**
> ```
> No linked project found. Run railway link to connect to a project
> ```
> **Correction** : normal et attendu à chaque nouveau dossier local — refaire
> `railway link` pour ce dossier précisément.

### Étape 2.6 — Pousser le code et déployer

**Où** : terminal, dans `pair_backend`.

```bash
git add Dockerfile src/main/resources/application-railway.properties
git commit -m "Ajout Dockerfile + config profil railway"
git push origin master

railway up
```

### Étape 2.7 — Générer le domaine public

**Où** : dashboard → service `pair_backend` → Settings → Networking → **Public Networking**.

> ⚠️ **Erreur rencontrée — 502 "Application failed to respond"**
> Après un déploiement apparemment réussi (`status: Online`), l'appel à
> `/actuator/health` retournait :
> ```json
> {"status":"error","code":502,"message":"Application failed to respond"}
> ```
> **Cause observée** : au moment du test, "Public Networking" n'affichait
> que le bouton **"Generate Domain"**, sans domaine actif correctement
> rattaché à un port.
> **Correction** : cliquer sur **"Generate Domain"**, choisir explicitement
> le port **8080** (celui défini dans `application-railway.properties` via
> `server.port=${PORT:8080}`). Une fois généré, Railway assigne
> automatiquement une variable `PORT=8080` visible dans
> `railway variables`.

**Vérification** :

```bash
railway variables
# Confirmer la présence de PORT=8080 et RAILWAY_PUBLIC_DOMAIN=...
```

```bash
curl https://<domaine-généré>.up.railway.app/actuator/health
```

### Étape 2.8 — Suivre les logs de déploiement de manière fiable

**Où** : dashboard (méthode la plus fiable) ou terminal.

> ⚠️ **Erreur rencontrée — logs CLI incohérents**
> À plusieurs reprises, `railway logs` a affiché les logs de `postgres_db`
> alors que `railway status` confirmait pourtant `Linked service:
> pair_backend`. Cause probable : dossier courant incorrect (voir Étape 2.5)
> combiné à un possible comportement de cache de la CLI.
> **Méthode de secours fiable, à privilégier en cas de doute** :
> ```bash
> railway open
> ```
> Puis dans le dashboard : service `pair_backend` → onglet **Deployments** →
> cliquer sur le déploiement le plus récent → consulter les **Deploy Logs**
> (distincts des Build Logs). Cette vue ne souffre d'aucune ambiguïté de
> service ou de dossier.

---

## Partie 3 — Erreurs applicatives (post-infrastructure)

Une fois l'infrastructure Railway correctement configurée, les erreurs
suivantes concernent le code de l'application elle-même, pas Railway :

### Erreur — migration Flyway avec référence de colonne ambiguë

```
ERROR: column reference "uid" is ambiguous
Where: PL/pgSQL function inline_code_block line 269 at FOR over SELECT rows
Location: db/migration/V13__bulk_test_data.sql
```

**Cause** : dans un bloc PL/pgSQL (`DO $$ ... $$`), une variable déclarée
(ex. `uid`) porte le même nom qu'une colonne interrogée dans un `SELECT`
au sein d'une boucle `FOR`, rendant la référence ambiguë pour PostgreSQL.

**Statut** : en cours de résolution au moment de la rédaction de ce
document — nécessite de renommer soit la variable PL/pgSQL, soit de
qualifier explicitement la colonne (ex. `users.uid` au lieu de `uid`) dans
le fichier `V13__bulk_test_data.sql`.

> ✅ **Point positif à noter** : cette erreur survient **après** que Flyway
> se soit connecté avec succès et ait exécuté les migrations `V1` à `V12`
> sans problème — la chaîne complète (build → démarrage → connexion DB →
> migrations) fonctionne bien jusqu'à ce point précis.

> ✅ **Résolu** : la migration `V13__bulk_test_data.sql` a été corrigée
> (renommage de la variable PL/pgSQL en conflit avec la colonne `uid`).
> Toutes les migrations passent désormais sans erreur.

### Erreur — healthcheck Actuator en DOWN à cause du SMTP

Une fois toutes les migrations passées, `/actuator/health` retournait :

```json
{"status":"DOWN"}
```

**Diagnostic** : le détail (`management.endpoint.health.show-details`) montrait
que seul le composant `mail` était en cause :

```json
"mail":{"details":{"location":"smtp.hostinger.com:587",
  "error":"MailConnectException: Couldn't connect to host, port:
  smtp.hostinger.com, 587; timeout 5000"},"status":"DOWN"}
```

Tous les autres composants (`db`, `diskSpace`, `livenessState`, `ping`,
`readinessState`, `ssl`) étaient déjà `UP` — la base de données et
l'application elle-même fonctionnaient parfaitement, seul le serveur SMTP
Hostinger n'était pas joignable depuis Railway (port bloqué ou credentials
à vérifier).

**Correction retenue en phase de test** : puisque l'envoi d'email n'est pas
encore la priorité et qu'un simple souci SMTP ne doit pas faire échouer le
healthcheck global (utilisé par Railway pour décider de redémarrer ou non
le conteneur), le composant `mail` a été retiré de l'agrégation :

```properties
management.health.mail.enabled=false
```

Ajouté dans `application-railway.properties`, puis `git push` + `railway up`.

**Résultat final** :

```json
{"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},
"livenessState":{"status":"UP"},"ping":{"status":"UP"},
"readinessState":{"status":"UP"},"ssl":{"status":"UP"}}}
```

> ℹ️ **À reprendre plus tard** : pour activer l'envoi d'email réel (vérification
> de compte, mot de passe oublié), remplacer la config SMTP Hostinger par un
> service pensé pour l'envoi transactionnel programmatique, comme Postmark
> (gratuit jusqu'à 100 emails/mois) :
> ```bash
> railway variables --set "SMTP_HOST=smtp.postmarkapp.com" \
>   --set "SMTP_PORT=587" \
>   --set "SMTP_USER=<token-postmark>" \
>   --set "SMTP_PASSWORD=<token-postmark>"
> ```
> Puis remettre `management.health.mail.enabled=true` une fois la config
> validée, pour que le healthcheck redevienne pleinement représentatif.

---

## Checklist condensée pour un futur déploiement

```markdown
### Service base de données (dossier pair-postgres)
- [ ] Dockerfile nommé exactement "Dockerfile" (pas de suffixe)
- [ ] init-extensions.sql présent (postgis, vector, uuid-ossp)
- [ ] Service Railway créé en "Empty Service" DÉDIÉ, jamais réutilisé
- [ ] `railway link` puis `railway service` → confirmer le bon service
- [ ] Variables POSTGRES_PASSWORD / POSTGRES_USER / POSTGRES_DB définies
      AVANT le premier `railway up`
- [ ] `railway logs` confirme "database system is ready to accept
      connections" + 3x "CREATE EXTENSION"

### Service backend (dossier pair_backend, séparé)
- [ ] Toujours vérifier son dossier courant avant toute commande railway
- [ ] `railway link` refait spécifiquement pour ce dossier
- [ ] Settings → Source → repo GitHub bien connecté (pas vide)
- [ ] Dockerfile présent avec RUN chmod +x mvnw (si wrapper Maven utilisé)
- [ ] Fichier application-<profil>.properties créé en UTF-8 SANS BOM
      (éviter Notepad — utiliser VS Code ou PowerShell WriteAllText)
- [ ] server.ssl.enabled=false dans le profil Railway
- [ ] server.port=${PORT:8080} (pas de port fixe en dur)
- [ ] Variables PGHOST (nom interne .railway.internal), PGPORT, PGUSER,
      PGPASSWORD, PGDATABASE, SPRING_PROFILES_ACTIVE toutes définies
- [ ] Settings → Networking → Generate Domain avec le bon port (8080)
- [ ] curl https://<domaine>/actuator/health retourne 200
- [ ] Logs consultés via dashboard → Deployments → Deploy Logs en cas de
      doute sur la fiabilité de la CLI
```

---

## Glossaire rapide des commandes utilisées

| Commande | Rôle | Où l'exécuter |
|---|---|---|
| `railway link` | Lier le dossier courant à un projet/service Railway | Dans chaque dossier de service, une fois |
| `railway service` | Choisir/confirmer le service ciblé par la CLI | Avant toute action sur un service précis |
| `railway status` | Vérifier le service actuellement lié | À chaque doute, avant toute commande |
| `railway variables` | Lister les variables du service lié | Pour vérifier une config |
| `railway variables --set "K=V"` | Ajouter/modifier une variable | Après `railway service` |
| `railway up` | Déployer le contenu du dossier courant | Dans le dossier exact du service concerné |
| `railway logs` | Consulter les logs (peut être peu fiable — préférer le dashboard en cas de doute) | Service lié + dossier correct |
| `railway open` | Ouvrir le dashboard web | N'importe où |
| `railway domain` | Afficher/générer le domaine public | Service lié |
| `optimum-cli export onnx` | Exporter un modèle Hugging Face au format ONNX | Dossier de travail Python local |
| `python3 -m onnxruntime.quantization.preprocess` | Pré-traiter un modèle avant quantisation (peut échouer sur certains modèles) | Dossier de travail Python local |
| `gh release create` | Publier des fichiers comme assets d'une release GitHub | Racine d'un vrai repo Git |
| `gh release view` | Vérifier le contenu réel d'une release publiée | N'importe où (authentifié) |
| `gh repo edit --visibility public` | Rendre un repo public (débloque l'accès anonyme aux assets) | N'importe où (authentifié) |
| `base64 -i <fichier> \| tr -d '\n' \| pbcopy` | Encoder un secret JSON pour injection en variable d'env | Mac, dossier du secret |
| `railway logs --tail 50 \| grep -i firebase` | Vérifier l'initialisation des push après déploiement | Service backend lié |

---

## Partie 4 — Se connecter à la base de données avec un client graphique (DBeaver)

Une fois le backend déployé, il est très utile de pouvoir explorer directement
les tables et données en base — vérifier que le seed a bien tourné, inspecter
une ligne, corriger une donnée de test — sans passer par l'API ni par `psql`
en ligne de commande.

### Étape 4.1 — Pourquoi `railway connect` ne fonctionne pas ici

**Où** : terminal, n'importe quel dossier lié au projet.

```bash
railway connect postgres_db
```

> ⚠️ **Erreur rencontrée** : `No supported database found in service`.
> **Explication** : `railway connect` ne fonctionne qu'avec l'addon
> PostgreSQL **natif** de Railway (celui qu'on ajoute via "+ New → Database
> → PostgreSQL" dans le dashboard). Notre service `postgres_db` est un
> service Docker **custom** (basé sur `pgvector/pgvector:pg16` + PostGIS) —
> Railway ne le reconnaît pas comme une "base de données gérée" au sens de
> cette commande, même s'il s'agit bien de PostgreSQL en pratique.
> **Conclusion** : pour ce type de service, il faut passer par un client
> externe (DBeaver, TablePlus, pgAdmin, psql local) connecté via le réseau
> **public** (TCP Proxy), pas via la CLI Railway.

### Étape 4.2 — Le premier essai a échoué : mauvais hostname

**Où** : DBeaver, nouvelle connexion PostgreSQL.

Premier réflexe naturel : utiliser le domaine public déjà visible dans
Networking, `postgresdb-production.up.railway.app`, avec le port `5556`
qu'on pensait être celui du TCP Proxy (aperçu dans une capture d'écran
lors d'une étape précédente du déploiement).

```
Host: postgresdb-production.up.railway.app
Port: 5556
```

> ⚠️ **Erreur rencontrée** : `Der Verbindungsversuch schlug fehl` (la
> tentative de connexion a échoué), sans détail exploitable.
> **Cause réelle** : `postgresdb-production.up.railway.app` est le domaine
> **HTTP** (Public Networking → section "Access your application over
> HTTP"), généré pour des requêtes web classiques sur un port applicatif
> (`Port 7070` affiché juste en dessous du domaine dans le dashboard). Ce
> domaine ne route **pas** le protocole PostgreSQL brut, même en pointant
> vers le port 5556. De plus, en y regardant de plus près, le TCP Proxy
> initialement vu (`:5556`) n'existait en réalité **plus** au moment de ce
> test — il avait disparu de la section Networking, sans qu'on sache
> précisément pourquoi (probablement supprimé lors d'une manipulation
> antérieure, ou jamais confirmé correctement à l'origine).

### Étape 4.3 — Chercher le hostname du proxy dans les variables (sans succès)

**Où** : terminal, CLI liée à `postgres_db`.

```bash
railway service
# Sélectionner postgres_db
railway variables
```

> ⚠️ **Erreur rencontrée** : la liste complète des variables (`POSTGRES_DB`,
> `POSTGRES_PASSWORD`, `POSTGRES_USER`, `RAILWAY_PRIVATE_DOMAIN`,
> `RAILWAY_PUBLIC_DOMAIN`, `RAILWAY_SERVICE_POSTGRES_DB_URL`,
> `RAILWAY_STATIC_URL`...) ne contenait **aucune** variable liée au TCP
> Proxy (pas de `RAILWAY_TCP_PROXY_DOMAIN` ni équivalent).
> **Conclusion** : contrairement au domaine HTTP public, Railway
> n'expose pas automatiquement l'hostname du TCP Proxy comme variable
> d'environnement pour ce type de service Docker custom. Il faut aller le
> chercher visuellement dans le dashboard.

### Étape 4.4 — Recréer le TCP Proxy depuis le dashboard

**Où** : dashboard web → service `postgres_db` → Settings → **Networking**.

Constat : la section "Public Networking" ne montrait plus qu'un bouton
**"+ TCP Proxy"**, confirmant qu'aucun proxy TCP actif n'existait à ce
moment-là.

1. Clique sur **"+ TCP Proxy"**
2. Un champ **"Enter your application port"** apparaît, pré-rempli avec
   `5432`

> ℹ️ **Point de compréhension important** : ce champ demande le **port
> interne** de l'application à l'intérieur du conteneur (celui sur lequel
> PostgreSQL écoute réellement, `5432` par défaut) — **pas** le port
> externe/public qui sera généré. Laisser `5432` tel quel est donc correct.

3. Clique sur **"Add Proxy"**

Railway génère alors une nouvelle ligne, affichée sous la forme :

```
hayabusa.proxy.rlwy.net:22326  →  :5432
```

Où :
- `hayabusa.proxy.rlwy.net:22326` est l'**hostname et le port publics**,
  à utiliser dans un client externe
- `:5432` (après la flèche) est le port **interne** vers lequel Railway
  redirige, à titre indicatif seulement

> ⚠️ **Erreur rencontrée — texte tronqué à l'affichage**
> Lors des toutes premières tentatives de lecture de ce type de ligne
> (`:5556` à l'époque), seul le port était visible à l'écran, sans
> l'hostname. **Correction** : le champ contenant l'hostname + port est
> plus large que la zone visible par défaut dans l'interface — cliquer
> directement dans le champ, ou agrandir la fenêtre du navigateur/dashboard,
> révèle le texte complet. Une capture d'écran à taille normale de la
> section entière (pas un zoom sur la ligne) permet généralement de lire
> l'ensemble sans coupure.

### Étape 4.5 — Configuration finale dans DBeaver

**Où** : DBeaver (ou tout autre client SQL graphique), édition de la
connexion PostgreSQL.

```
Host:     hayabusa.proxy.rlwy.net
Port:     22326
User:     pair_user
Password: <valeur de POSTGRES_PASSWORD>
Database: pair_db
```

> ✅ **Résultat** : avec l'hostname et le port du TCP Proxy fraîchement
> recréé (au lieu du domaine HTTP), la connexion aboutit. Les tables
> (`users`, `categories`, `activities`, `programs`...) sont visibles et
> interrogeables directement depuis l'interface graphique.

### Leçon générale à retenir pour la suite

Sur Railway, un service Docker custom expose potentiellement **trois
adresses réseau différentes**, à ne jamais confondre :

| Adresse | Usage | Où la trouver |
|---|---|---|
| **Domaine HTTP public** (`*.up.railway.app`) | Requêtes web/API sur le port applicatif (ex: `7070`) | Networking → "Public Networking" → domaine du haut |
| **TCP Proxy public** (`*.proxy.rlwy.net:PORT`) | Connexion directe à un protocole non-HTTP (PostgreSQL, Redis...) depuis l'extérieur | Networking → doit être créé explicitement via "+ TCP Proxy" |
| **Domaine privé interne** (`*.railway.internal`) | Communication entre services du même projet Railway (ex: backend → base) | Networking → "Private Networking" |

Le TCP Proxy n'est **pas créé par défaut** sur un service Docker custom —
contrairement à l'addon PostgreSQL natif de Railway qui l'active
automatiquement. Il faut penser à le créer soi-même dès qu'un accès
externe direct à la base (client SQL, script local, migration manuelle)
est nécessaire.

---

## Partie 5 — Recherche sémantique gratuite et trilingue (ONNX embarqué)

Cette partie documente le déploiement d'un pipeline de recherche sémantique
**100% gratuit et local** (modèle ONNX embarqué dans le JAR, remplaçant les
appels payants Anthropic/OpenAI), couvrant français, anglais et allemand.
C'est la partie la plus longue de ce journal — plusieurs couches d'erreurs
système se sont révélées successivement, chacune masquant la suivante.

### Étape 5.1 — Export et quantisation du modèle (machine locale)

**Où** : terminal Mac, dans un dossier de travail dédié, séparé de tout
repo Git.

```bash
mkdir -p ~/Documents/pair_semantic/meetdo-embedding-model
cd ~/Documents/pair_semantic/meetdo-embedding-model
pip3 install optimum onnx onnxruntime "optimum-onnx[onnx]" --quiet
optimum-cli export onnx \
  --model sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2 \
  --task feature-extraction --optimize O2 .
```

> ⚠️ **Erreur rencontrée — nom d'extra pip obsolète**
> `pip3 install "optimum[exporters]"` échoue avec
> `WARNING: optimum 2.2.0 does not provide the extra 'exporters'`.
> **Cause** : Hugging Face a restructuré `optimum`, l'export ONNX vit
> maintenant dans un paquet séparé `optimum-onnx`, avec l'extra renommé
> `[onnx]` au lieu de `[exporters]`.
> **Correction** : `pip3 install optimum onnx onnxruntime "optimum-onnx[onnx]"`.

> ⚠️ **Erreur rencontrée — zsh globbing sur les crochets**
> `pip3 install "optimum[exporters]"` sans guillemets échoue avec
> `zsh: no matches found: optimum[exporters]` — zsh interprète `[...]`
> comme un motif de correspondance de fichiers.
> **Correction** : toujours entourer ce type de dépendance de guillemets
> doubles sous zsh (défaut sur macOS).

Quantisation ensuite :

```bash
python3 -c "
from onnxruntime.quantization import quantize_dynamic, QuantType
from onnx import TensorProto
quantize_dynamic(
    'model.onnx', 'model_quantized.onnx',
    weight_type=QuantType.QInt8,
    extra_options={'DefaultTensorType': TensorProto.FLOAT}
)
"
```

> ⚠️ **Erreur rencontrée — inférence de type de tenseur échouée**
> `quantize_dynamic()` sur `model.onnx` brut échoue avec
> `RuntimeError: Unable to find data type for weight_name=...`.
> **Tentative 1 (échouée)** : lancer le pré-traitement recommandé
> `python3 -m onnxruntime.quantization.preprocess --input model.onnx
> --output model_preprocessed.onnx` → échoue à son tour avec
> `Exception: Incomplete symbolic shape inference` (bug connu sur certains
> modèles Transformer exportés).
> **Correction retenue** : contourner complètement le pré-processing en
> forçant le type par défaut directement dans `quantize_dynamic()` via
> `extra_options={'DefaultTensorType': TensorProto.FLOAT}`, appliqué sur
> `model.onnx` (l'original), pas sur une version pré-traitée.

**Résultat** : `model_quantized.onnx` (117,8 Mo, réduit depuis 470 Mo),
`tokenizer.json`, `tokenizer_config.json`, `special_tokens_map.json`.

### Étape 5.2 — Pourquoi Git LFS a été abandonné

**Où** : recherche web + décision d'architecture.

> ⚠️ **Confirmé par le support Railway** : Railway ne résout **jamais**
> les pointeurs Git LFS au clone — seul le pointeur texte de quelques
> octets est récupéré, jamais le contenu binaire réel. Committer le modèle
> via LFS ferait planter le chargement au runtime (fichier "modèle" vide
> de sens).
> **Solution retenue** : publier les 4 fichiers comme assets d'une
> **GitHub Release**, et les télécharger au premier démarrage de
> l'application vers un volume Railway persistant — pas de Git LFS,
> pas de stockage tiers (S3/R2) nécessaire.

### Étape 5.3 — Publier les fichiers en GitHub Release

**Où** : terminal, depuis un vrai repo Git (le dossier de travail
`meetdo-embedding-model` n'en est pas un).

```bash
cd ~/IdeaProjects/pair_backend
gh release create models-v1 \
  ~/Documents/pair_semantic/meetdo-embedding-model/model_quantized.onnx \
  ~/Documents/pair_semantic/meetdo-embedding-model/tokenizer.json \
  ~/Documents/pair_semantic/meetdo-embedding-model/tokenizer_config.json \
  ~/Documents/pair_semantic/meetdo-embedding-model/special_tokens_map.json \
  --title "Modèle embeddings v1" --notes "Modèle multilingue FR/EN/DE, INT8"
```

> ⚠️ **Erreur rencontrée — `gh release create` hors d'un repo Git**
> Lancée depuis le dossier de travail `meetdo-embedding-model` (pas un
> repo Git) : `failed to run git: fatal: not a git repository`.
> **Correction** : se placer dans le vrai repo (`pair_backend`) et
> référencer les fichiers par leur **chemin absolu complet**.

> ⚠️ **Erreur rencontrée — 404 sur les liens de téléchargement**
> `curl -I https://github.com/.../releases/download/models-v1/model_quantized.onnx`
> retourne `404`, alors que `gh release view` confirme les 4 assets bien
> présents et publiés (pas de statut "Draft").
> **Cause** : le repo `pair_backend` était **privé**. GitHub renvoie
> délibérément 404 (plutôt que 401) sur les assets d'un repo privé pour
> une requête non authentifiée — masquant même l'existence de la ressource.
> **Correction** : rendre le repo public (après vérification qu'aucun
> secret n'était en dur dans le code) :
> ```bash
> gh repo edit Mibrai/pair_backend --visibility public
> ```
> Une fois public, `curl -I .../model_quantized.onnx` retourne bien un
> `302` (redirection signée vers `release-assets.githubusercontent.com`) —
> l'URL stable à utiliser en configuration reste toujours l'URL `.../releases/download/...`,
> jamais l'URL de redirection signée (qui expire après ~50 minutes).

### Étape 5.4 — Volume Railway pour la persistance du modèle

**Où** : dashboard Railway, vue canvas du projet (pas dans les Settings
d'un service).

L'option d'ajout de volume ne se trouve **pas** dans l'onglet Settings
classique d'un service — il faut cliquer sur le bloc du service dans la
vue canvas principale, puis clic droit → **"Attach volume"**.

Configuration retenue :
```
Mount Path : /app/models
Volume Size : 500 MB
```

### Étape 5.5 — Permissions du volume vs utilisateur non-root

> ⚠️ **Erreur rencontrée — AccessDeniedException au premier démarrage**
> ```
> java.nio.file.AccessDeniedException: /app/models/embedding
> ```
> **Cause** : le Dockerfile backend fait tourner le conteneur sous un
> utilisateur non-root (`USER spring:spring`, bonne pratique définie dès
> la Partie 2 de ce journal). Railway monte les volumes avec des
> permissions par défaut n'appartenant pas à cet utilisateur, et un
> `chown` fait **au build** dans le Dockerfile ne survit pas au montage du
> volume au runtime (le point de montage remplace le contenu de l'image à
> cet endroit).
> **Tentative 1 (insuffisante)** : `RUN mkdir -p /app/models/embedding &&
> chown -R spring:spring /app/models` avant `USER spring:spring` dans le
> Dockerfile — ne résout rien, car le `chown` est écrasé par le montage.
> **Correction retenue** : retirer l'instruction `USER spring:spring` du
> Dockerfile (stage final), faire tourner le conteneur en **root**. Compromis
> de sécurité jugé raisonnable ici : l'isolation de conteneur Railway reste
> le rempart principal, l'utilisateur non-root n'ajoutait qu'une couche
> marginale, très inférieure au bénéfice de débloquer le volume simplement.
> *(Alternative plus rigoureuse mais plus complexe pour un futur
> durcissement : script d'entrée `docker-entrypoint.sh` exécuté en root qui
> corrige les permissions puis bascule vers l'utilisateur applicatif via
> `gosu` avant de lancer le jar.)*

### Étape 5.6 — Incompatibilité Alpine musl vs bibliothèques natives ONNX

Une fois les permissions résolues, le téléchargement du modèle a
fonctionné (`Modèle téléchargé et persisté sur /app/models/embedding`),
mais le chargement du moteur ONNX a échoué en **deux vagues successives** :

> ⚠️ **Erreur rencontrée n°1 — libstdc++ manquant**
> ```
> UnsatisfiedLinkError: .../libonnxruntime.so: Error loading shared
> library libstdc++.so.6: No such file or directory
> ```
> **Cause** : le Dockerfile backend utilise `eclipse-temurin:21-jre-alpine`
> comme image finale. Alpine Linux est volontairement minimaliste et
> n'inclut pas `libstdc++` par défaut.
> **Correction tentée** : `RUN apk add --no-cache libstdc++ libgcc` dans
> le stage final. Ça a bien résolu **cette** erreur précise, mais en a
> révélé une plus profonde juste derrière.

> ⚠️ **Erreur rencontrée n°2 — incompatibilité fondamentale musl/glibc**
> ```
> UnsatisfiedLinkError: .../libonnxruntime.so: Error loading shared
> library ld-linux-x86-64.so.2: No such file or directory
> ```
> **Cause de fond** : `ld-linux-x86-64.so.2` est le dynamic linker de la
> **glibc**. Alpine Linux n'utilise pas la glibc du tout — il repose sur
> **musl libc**, une implémentation différente et incompatible au niveau
> binaire. Le binaire natif d'ONNX Runtime est compilé pour glibc ; aucun
> ajout de paquet Alpine (`apk add`) ne peut combler cet écart
> fondamental, contrairement à l'erreur n°1 qui n'était qu'une bibliothèque
> manquante isolée.
> **Correction définitive** : remplacer l'image de base du stage final :
> ```dockerfile
> # AVANT
> FROM eclipse-temurin:21-jre-alpine
> # APRÈS
> FROM eclipse-temurin:21-jre-jammy
> ```
> (`jammy` = Ubuntu 22.04, glibc native, compatible nativement avec les
> bibliothèques natives ONNX Runtime). Les lignes `apk add` de la
> tentative précédente ont été retirées (spécifiques à Alpine, sans effet
> sur une image Debian/Ubuntu, et de toute façon inutiles puisque
> glibc/libstdc++ y sont déjà présentes nativement).
> **Compromis accepté** : la taille de l'image finale augmente
> (généralement +100-150 Mo par rapport à Alpine) — non bloquant pour
> l'usage Railway en phase de test/bêta.

### Étape 5.7 — Validation finale en production

**Où** : terminal, tests `curl` contre l'API déployée.

Une fois les 3 corrections cumulées (volume attaché, conteneur en root,
image Debian/Ubuntu), les logs ont enfin montré :
```
Modèle d'embeddings chargé en 7590 ms
```

**Tests de validation trilingue réalisés** :
```bash
# Français — trouve "Séance yoga matinale"
curl -X POST .../api/search -d '{"query":"je cherche quelqu'\''un pour faire du yoga",...}'

# Anglais sans "partner" — retrouve le même programme (format GROUP)
curl -X POST .../api/search -d '{"query":"looking for yoga this morning",...}'

# Allemand — canonicalActivitySlug correctement résolu à "yoga"
curl -X POST .../api/search -d '{"query":"ich suche einen yoga partner heute morgen",...}'
```

> ✅ **Point positif à noter** : une requête anglaise contenant le mot
> "partner" a d'abord semblé produire un résultat vide (`type: "empty"`)
> — ce n'était **pas** un bug, mais le filtre `format: DUO` correctement
> déduit du mot "partner", qui excluait légitimement le seul programme
> existant en base (format `GROUP`). Reformuler sans "partner" a confirmé
> le bon fonctionnement du matching sémantique cross-lingue.

### Leçon générale de la Partie 5

Le débogage d'un problème de bibliothèque native suit souvent un schéma
**en couches** : corriger une erreur en révèle une autre, plus profonde,
masquée derrière. Ne pas s'arrêter à la première correction qui fait
disparaître un message d'erreur — vérifier que le composant fonctionne
réellement de bout en bout (ici : jusqu'au message `Modèle d'embeddings
chargé`, puis jusqu'à un vrai test `curl` de l'endpoint) avant de
considérer le problème résolu.

---

## Partie 6 — Activation des notifications push (Firebase / APNs)

Cette partie documente l'activation de bout en bout des notifications push,
réalisée le 12 août 2026. Le code applicatif — côté backend Spring Boot
(`FirebaseConfig`, `PushNotificationService`) comme côté Flutter
(`core/push/`) — **existait déjà et fonctionnait** ; il ne manquait que la
configuration. Le blocage était signalé dans l'état des lieux du produit
comme l'écart n°1 : *« sans le drapeau `FIREBASE_ENABLED`, aucune push ne
part et rien ne le signale »*.

### Vue d'ensemble : trois fichiers à ne jamais confondre

C'est la source de confusion principale sur ce sujet. Trois fichiers
Firebase/Apple portent des noms proches, ont des rôles opposés, et des
niveaux de sensibilité très différents.

| Fichier | Pour qui | Rôle | Destination | Sensibilité |
|---|---|---|---|---|
| `GoogleService-Info.plist` | App Flutter (iOS) | **Recevoir** les push | `ios/Runner/` | Publique par nature (embarquée dans l'app) |
| `google-services.json` | App Flutter (Android) | **Recevoir** les push | `android/app/` | Publique par nature |
| `firebase-adminsdk-*.json` | Backend Spring Boot | **Envoyer** les push | Variable Railway (jamais un fichier) | 🔴 **Secret** — clé privée d'admin |
| Clé APNs `.p8` | Firebase (téléversement manuel) | Autoriser Firebase à parler à APNs | Console Firebase uniquement | 🔴 **Secret** |

> ⚠️ **Erreur rencontrée — mauvais fichier identifié**
> Le premier réflexe a été de fournir `GoogleService-Info.plist` pour
> résoudre le message backend `Firebase is disabled`. Ce fichier est un
> fichier **client iOS** : il permet à l'iPhone de *recevoir*, pas au
> serveur d'*envoyer*. Le backend a besoin d'un tout autre fichier, le
> **JSON de compte de service Admin SDK**, dont la structure est
> reconnaissable à ses champs `"type": "service_account"`,
> `"private_key"` et `"client_email"`.

### Étape 6.1 — Créer la clé APNs (côté Apple)

**Où** : developer.apple.com → *Certificates, Identifiers & Profiles*.

> ⚠️ **Erreur rencontrée — on demande un CSR**
> Une demande de *Certificate Signing Request* signale qu'on se trouve
> dans la section **Certificates** (ancienne méthode, `.p12`, expire tous
> les ans), et non dans **Keys**.
> **Correction** : passer par le menu **Keys** → bouton **+** → cocher
> **Apple Push Notifications service (APNs)** → Register → télécharger le
> `.p8`. Aucun CSR n'est demandé par cette voie.

| Méthode | CSR requis | Expiration | Recommandé |
|---|---|---|---|
| **APNs Auth Key** (`.p8`) | Non | Jamais | ✅ Oui |
| APNs Certificate (`.p12`) | Oui | 1 an | ❌ Ancienne méthode |

Une seule clé `.p8` couvre développement **et** production.

> ⚠️ **Le téléchargement du `.p8` n'est possible qu'une seule fois.**
> Fichier perdu = clé à révoquer et à recréer. Le conserver dans un
> gestionnaire de mots de passe, jamais dans le dépôt.

Noter au passage deux identifiants demandés ensuite par Firebase :
**Key ID** (10 caractères, affiché après création) et **Team ID**
(en haut à droite du portail développeur, ou dans *Membership*).

**Téléversement** : console Firebase → ⚙️ *Paramètres du projet* → onglet
**Cloud Messaging** → section **Apple app configuration** → Upload du
`.p8` avec Key ID et Team ID.

> ⚠️ **Point de vigilance — l'échec APNs est silencieux.**
> Sans clé APNs correctement téléversée, Firebase accepte les envois
> **sans erreur côté backend** et échoue en aval : les push partent vers
> Android, rien n'arrive sur iOS, et les logs serveur restent
> parfaitement verts. C'est un symptôme trompeur : vérifier explicitement
> la présence de la clé dans la console Firebase, ne jamais le déduire de
> l'absence d'erreur.

### Étape 6.2 — Le problème du secret backend sur un PaaS

`FirebaseConfig` ne savait lire les identifiants que depuis un **chemin de
fichier** (`FIREBASE_CREDENTIALS_PATH`) ou le classpath. Aucune de ces
deux voies n'est utilisable sur Railway :

- le conteneur est reconstruit à chaque déploiement — impossible d'y
  déposer un fichier durablement ;
- le dépôt `Mibrai/pair_backend` est **public** (rendu tel lors de la
  Partie 5, pour que la Release GitHub du modèle ONNX soit téléchargeable
  anonymement) — committer une clé privée y serait une fuite immédiate ;
- le volume Railway existant est déjà monté sur `/app/models` et dédié au
  modèle ML — y loger un secret d'authentification mélangerait deux
  natures de données et se ferait oublier à la prochaine maintenance.

**Solution retenue** : ajouter une troisième source d'identifiants au
code — une variable d'environnement contenant le JSON **encodé en
base64**. C'est la pratique standard pour injecter un secret multiligne
dans un PaaS.

Modification de `FirebaseConfig` (ordre de priorité, sans casser
l'existant) :

```
1. firebase.credentials-base64  → production / Railway
2. firebase.credentials-path    → développement local (comportement d'origine)
3. les deux vides               → IllegalStateException nommant les DEUX variables
```

```properties
# application.properties — ligne ajoutée à côté des deux existantes
firebase.credentials-base64=${FIREBASE_CREDENTIALS_BASE64:}
```

Points de conception retenus lors de cette modification :
- `.trim()` sur la chaîne base64 avant décodage — une variable copiée
  depuis un terminal traîne fréquemment un retour à la ligne ;
- message d'erreur **dédié** si le base64 est présent mais invalide,
  distinct de l'erreur générique d'initialisation Firebase — c'est
  l'erreur la plus probable en pratique, elle doit être nommée ;
- ne jamais faire figurer le contenu de `credentialsBase64` dans un
  message d'erreur (c'est un secret), contrairement à `credentialsPath`
  qui n'est qu'un chemin ;
- log de succès mentionnant la **source utilisée** (`base64` ou chemin)
  sans divulguer le secret.

### Étape 6.3 — Injection dans Railway

**Où** : terminal Mac, dans le repo backend, CLI liée à
`pair_backend_service`.

```bash
# Encoder le JSON, SANS retour à la ligne, directement dans le presse-papier
base64 -i ~/Downloads/meetdo-76ab7-firebase-adminsdk-*.json | tr -d '\n' | pbcopy
```

Le `tr -d '\n'` est indispensable : `base64` insère par défaut un saut de
ligne tous les 76 caractères sur certains systèmes. Le `.trim()` côté Java
ne retire que les espaces en début et fin de chaîne, pas ceux au milieu —
une chaîne non nettoyée échouerait au décodage.

```bash
cd ~/IdeaProjects/pair_backend
railway service                       # sélectionner pair_backend_service
railway variables --set "FIREBASE_CREDENTIALS_BASE64=<coller>"
railway variables --set "FIREBASE_ENABLED=true"
railway up
```

> ⚠️ **Erreur rencontrée — l'ordre des variables fait tomber la production**
> `FIREBASE_ENABLED=true` a été posé **avant** que le code sache lire le
> base64 et avant que le secret ne soit renseigné. Conséquence directe du
> comportement « échec bruyant » voulu par `FirebaseConfig` : le contexte
> Spring n'a pas pu démarrer du tout, et **toute l'application est tombée**
> (`Application run failed`), pas seulement les push.
> ```
> firebase.enabled=true mais firebase.credentials-path est vide.
> ```
> **Correction immédiate** — remettre le service en ligne avant tout :
> ```bash
> railway variables --set "FIREBASE_ENABLED=false"
> ```
> **Ordre correct à respecter** :
> 1. modifier le code (support base64) et le déployer, `FIREBASE_ENABLED`
>    restant à `false` — le service reste en ligne ;
> 2. générer la clé de compte de service et poser
>    `FIREBASE_CREDENTIALS_BASE64` ;
> 3. **seulement alors**, poser `FIREBASE_ENABLED=true`.
>
> *À noter : ce n'est pas un défaut de `FirebaseConfig` — le choix de
> faire échouer le démarrage plutôt que de démarrer silencieusement sans
> push est délibéré et documenté dans la javadoc de la classe. Une
> configuration push cassée se voit au déploiement plutôt que des semaines
> plus tard sur un téléphone. C'est la séquence d'activation qui doit
> s'adapter, pas le code.*

### Étape 6.4 — Vérification

```bash
railway logs --tail 50 | grep -i firebase
```

Résultat attendu :
```
Firebase initialized successfully (push notifications enabled, source: base64)
```

Messages d'échec possibles et leur signification :

| Message | Cause |
|---|---|
| `Firebase is disabled. Push notifications will not work.` | `FIREBASE_ENABLED` n'est pas à `true` sur le bon service |
| `FIREBASE_CREDENTIALS_BASE64 est renseignée mais n'est pas du base64 valide` | Chaîne tronquée ou altérée au collage — refaire l'encodage avec `tr -d '\n'` |
| `firebase.enabled=true mais ... est vide` | Le secret n'a pas été posé, ou posé sur un autre service |

**Validation fonctionnelle réalisée** : notifications reçues avec succès
sur iPhone physique **et** sur simulateur.

### Règle de sécurité à retenir

Ne jamais faire transiter par un canal non sécurisé (conversation,
messagerie, ticket, capture d'écran) : la clé APNs `.p8`, le JSON de
compte de service Firebase, ni aucun `.p12`. Une clé exposée doit être
**révoquée et régénérée**, pas simplement « oubliée » — l'opération prend
deux minutes et n'a aucun impact sur l'app tant que la nouvelle clé est
téléversée dans Firebase.

Les fichiers `GoogleService-Info.plist` et `google-services.json` sont en
revanche sans risque : ils sont conçus pour être embarqués dans l'app
distribuée. Par précaution générale, restreindre tout de même la clé API
qu'ils contiennent dans Google Cloud Console (APIs & Services →
Credentials) au Bundle ID / nom de package de l'app et aux seules API
Firebase nécessaires.

---

## Checklist — activation des notifications push

À suivre dans cet ordre exact lors d'un futur déploiement ou d'un nouvel
environnement. L'ordre n'est pas indicatif : l'inverser fait tomber la
production (voir Étape 6.3).

```markdown
### Côté Apple (une fois par compte développeur)
- [ ] developer.apple.com → **Keys** (PAS Certificates) → + → cocher APNs
- [ ] Télécharger le .p8 — UNE SEULE FOIS possible, le sauvegarder
- [ ] Noter le Key ID (10 caractères) et le Team ID

### Côté Firebase (une fois par projet)
- [ ] Console Firebase → Paramètres du projet → Cloud Messaging →
      Apple app configuration → téléverser le .p8 + Key ID + Team ID
- [ ] Vérifier VISUELLEMENT que la clé est listée — son absence ne produit
      aucune erreur côté backend, seulement des push iOS qui n'arrivent pas
- [ ] Paramètres du projet → Comptes de service → Générer une clé privée
      (JSON avec "type": "service_account")

### Côté app Flutter (une fois par projet)
- [ ] GoogleService-Info.plist dans ios/Runner/
- [ ] google-services.json dans android/app/
- [ ] Les deux fichiers ignorés par git

### Côté backend — ORDRE IMPÉRATIF
- [ ] 1. Code : FirebaseConfig sait lire FIREBASE_CREDENTIALS_BASE64
- [ ] 2. Déployer ce code avec FIREBASE_ENABLED encore à false
- [ ] 3. base64 -i <json> | tr -d '\n' | pbcopy
- [ ] 4. railway variables --set "FIREBASE_CREDENTIALS_BASE64=<coller>"
- [ ] 5. railway variables --set "FIREBASE_ENABLED=true"
- [ ] 6. railway up

### Vérification
- [ ] Logs : "Firebase initialized successfully ... source: base64"
- [ ] GET /api/notifications/devices retourne l'appareil enregistré
- [ ] Notification reçue app FERMÉE (le cas révélateur — app ouverte peut
      passer par un autre chemin)
- [ ] Badge d'icône affiche le bon compte
- [ ] Le tap sur la notification ouvre le bon écran

### Sécurité
- [ ] Aucun .p8, .p12 ni JSON de compte de service dans le dépôt
- [ ] Toute clé ayant transité par un canal non sécurisé est RÉVOQUÉE
      et régénérée, pas simplement oubliée
- [ ] Clé API des fichiers clients restreinte au Bundle ID / package
      dans Google Cloud Console
```

---
