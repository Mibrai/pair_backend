# Hygiène du dépôt — journaux, historique SQL et scripts suivis par git

> Fiche **P-BA-22** du plan architecture. Inventaire établi par `git ls-files` le
> **12/09/2026** sur `master` à `57e0a04` (et non d'après la fiche du 11/09, qui
> a une journée de retard). Le dépôt est **public** : tout ce qui est suivi est
> publié.

Ce document ne retire rien par lui-même. Il porte l'inventaire, le classement,
et la **liste ordonnée des commandes** à jouer au moment du commit. Seul
`.gitignore` a déjà été modifié, pour que ce qui part ne revienne pas.

---

## 1. Ce qui a déjà été fait

`.gitignore` reçoit six sections : `*.log`, `/SQLHistory/`,
`frontend-config.local.json` + `/local/`, `.env` (avec `!.env.example`), l'état
local de Claude Code, et — surtout — `/audit/` et `/docs/runbooks/`.

**Ces deux derniers dossiers n'étaient ignorés par rien.** `git check-ignore
audit docs/runbooks` sortait en 1 : un `git add -A` distrait publiait les six
plans d'audit (62 constats exploitables sur une application en production, ce
que leur propre README interdit) et les runbooks qui nomment des comptes de
production au mot de passe publié. C'était le risque le plus concret de la
fiche, et il n'y figurait pas.

Deux limites à connaître :

- une règle d'ignorance ne « désuit » rien. `docs/runbooks/RUNBOOK_MIGRATION_BASE_EUROPE_2026-08-24.md`
  est déjà suivi ; il le reste, et ses modifications restent visibles. Relu à
  cette occasion : il ne contient aucun secret en clair (il demande les mots de
  passe par `read -rs`), donc il peut rester publié ;
- un futur runbook que l'on veut publier demandera un `git add -f`.

---

## 2. Inventaire — trois tas

### Tas 1 — sort du suivi (résidus)

**Journaux, 9 fichiers, 534 Ko** — retirés de l'index, **laissés sur le disque**
(couverts par `*.log`).

| Fichier | Taille | Dernier commit | Pourquoi c'est un résidu |
|---|---|---|---|
| `app-final.log` | 123 Ko | 26/06 Init Commit | trace de démarrage d'un poste de dev, jamais relue depuis |
| `app-debug.log` | 122 Ko | 26/06 Init Commit | idem |
| `app-clean.log` | 103 Ko | 26/06 Init Commit | idem |
| `app-test.log` | 53 Ko | 26/06 Init Commit | sortie d'une exécution de tests de juin ; la suite en compte 1371 aujourd'hui |
| `app-activities.log` | 42 Ko | 26/06 Init Commit | trace d'un chargement d'activités |
| `spring-boot.log` | 31 Ko | 04/07 `b50dd96` | trace d'un correctif `LazyInitializationException` déjà livré |
| `app-flyway.log` | 27 Ko | 26/06 Init Commit | trace de migrations, aujourd'hui 105 versions plus loin |
| `app.log` | 22 Ko | 26/06 Init Commit | journal courant d'alors |
| `test-output.log` | 12 Ko | 30/06 `82d2436` | sortie d'un seed de test |

Relus avant de conclure : **aucune donnée personnelle réelle**. Les seules
adresses qui y figurent sont des adresses de test
(`test@example.com`, `sports-fan@test.com`, `user-1782160257@test.com`…), et on
n'y trouve ni empreinte bcrypt (`$2a$`) ni jeton JWT (`eyJ…`). Leur retrait est
donc de l'hygiène, pas de la sécurité, et ne dépend pas de P-BS-19.

**`SQLHistory/`, 36 fichiers** — retiré de l'index, **laissé sur le disque**
(couvert par `/SQLHistory/`). Son README le décrit lui-même comme « tous les
scripts SQL exécutés manuellement sur `pair_db` » : 24 `.sql` et 12 `.sh`/`.bat`
d'avant Flyway, quand `src/main/resources/db/migration` n'existait pas. Il en
porte aujourd'hui **105 migrations**. Le dossier a un chemin
`C:\Program Files\PostgreSQL\18\bin\psql.exe` dans son mode d'emploi et le mot
de passe local `Pair2026!` — il n'est plus jouable tel quel. Dernier commit :
26/06, Init Commit.

*Variante si l'on veut garder les sondes :* quatre scripts de fumée y vivent
(`test-map.sh`, `test-programs.sh`, `test-search.sh`,
`test-activities-complete.sh`), et `README.md` les cite. Les sauver avant le
retrait : `mkdir -p scripts/smoke && git mv SQLHistory/test-*.sh scripts/smoke/`.

**Racine, 6 fichiers**

| Fichier | Pourquoi c'est un résidu | Sort |
|---|---|---|
| `DOCUMENTATION_INDEX.md` | copie périmée de `docs/DOCUMENTATION_INDEX.md` : 379 lignes contre 383, le `diff` ne montre que 4 lignes **en plus** dans la version `docs/` (Resend, SMTP). Strict sous-ensemble. | supprimé du disque et de l'index |
| `stop-app.sh` | script bash qui appelle `tasklist`, `netstat -ano`, `findstr` et `taskkill` : des commandes Windows. Inexécutable sur le poste (macOS) ; `lsof -ti:8090 \| xargs kill` le remplace. | supprimé |
| `create-test-user.sh` | un seul `curl` d'inscription de `test@example.com`, référencé par aucun document ; `quick-test.sh` fait la même inscription. | supprimé |
| `test-phase3.sh` | sondes d'un jalon clos ; cité seulement par des documents d'archive (`docs/status/SESSION_SUMMARY.md`, `docs/implementation/PHASE3_VALIDATION.md`). | supprimé |
| `test-auth.html` | page de test manuel de l'authentification contre `http://localhost:8090/api`, datée du 26/06, référencée **nulle part**. | supprimé |
| `frontend-config.local.json` | configuration d'un poste : `192.168.2.214`, `environment: local-network`. | retiré de l'index, laissé sur le disque |

**`.claude/memories/`, 6 fichiers, ~200 Ko — hors fiche.** Les six fichiers sont
**identiques au bit près** (même MD5) à `docs/specs/pair-data-model-spec.md`,
`pair-phase1..4-spec.md` et `pair-readme-claude-code.md`. C'est de la
duplication pure, dans un dossier d'outillage local. Retiré de l'index, laissé
sur le disque.

### Tas 2 — se déplace (utile, mal rangé)

| Aujourd'hui | Destination | Pourquoi c'est utile |
|---|---|---|
| `start-db.sh` | `scripts/start-db.sh` | le seul script de la racine encore entretenu (commit `b7e7597`, 01/09). Monte le conteneur PostGIS + pgvector et documente, en commentaire, le défaut `$DOCKERps` qui l'a fait échouer. Indispensable pour lancer la suite en local. |
| `quick-test.sh` | `scripts/smoke/quick-test.sh` | sonde manuelle inscription + conversations, citée par `README.md` et `docs/guides/COMMANDES_UTILES.md`. |
| `test-conversations.sh` | `scripts/smoke/test-conversations.sh` | sonde de la messagerie, citée par `README.md`, `docs/guides/AUTHENTICATION_GUIDE.md` et `COMMANDES_UTILES.md`. |
| `run-map-visibility-tests.sh` | `scripts/run-map-visibility-tests.sh` | lance `mvn test -Dtest=MapVisibilityIntegrationTest` — une classe qui existe toujours — et explique quoi vérifier en cas d'échec (vie privée, floutage, comptes désactivés). Cité par `docs/tests/MAP_VISIBILITY_TESTS_README.md` et `QUICKSTART_MAP_TESTS.md`. |
| `run-map-visibility-tests.bat` | `scripts/run-map-visibility-tests.bat` | équivalent Windows du précédent, cité par les mêmes documents. |
| `frontend-config.json` | `docs/api/frontend-config.json` | contrat d'API servi à l'équipe mobile (52 routes, tailles de page, plafonds d'upload). Dix documents le citent. Sa place est à côté de `docs/api/api-endpoints.md`. |
| `RAILWAY_ENV_VARS.md` | `docs/deployment/RAILWAY_ENV_VARS.md` | **à ne pas fondre dans un guide fusionné** : 153 lignes entretenues jusqu'au 12/08 (`3d17392`), qui portent le volume obligatoire `/app/uploads`, l'incident média du 11/08, et tout le protocole `FIREBASE_CREDENTIALS_BASE64`. C'est la référence d'exploitation vivante. |
| `RAILWAY_SEEDING.md` | `docs/deployment/RAILWAY_SEEDING.md` | décrit le chargement de `scripts/seed-railway-data.sql` (fichier suivi, toujours là) et ne publie aucun identifiant. |
| `RESEND_QUICKSTART.md` | `docs/guides/RESEND_QUICKSTART.md` | mise en route email en 3 étapes ; `docs/guides/` porte déjà `EMAIL_CONFIGURATION.md`. |
| `RESEND_SETUP.md` | `docs/guides/RESEND_SETUP.md` | guide détaillé Resend/DNS, cité par `RAILWAY_ENV_VARS.md`. |
| `MAP_ACTIVITIES_IMPLEMENTATION.md` | `docs/implementation/MAP_ACTIVITIES_IMPLEMENTATION.md` | rapport d'implémentation de la carte (03/07) ; `docs/implementation/` est fait pour ça. |

### Tas 3 — reste, et reste où il est

`README.md`, `Dockerfile`, `pom.xml`, `mvnw`, `mvnw.cmd`, `.gitignore`,
`.gitattributes`, `.env.example`, `railway.json` (apparu le 12/09, hors de cette
fiche), `.mvn/wrapper/maven-wrapper.properties`, `.github/`, `pair-postgres/`,
`src/`, `docs/`, `modules/`, `scripts/`.

`src/main/resources/pair-keystore.p12` (2,7 Ko) **reste ici aussi**, bien qu'il
soit un binaire suivi : `application.properties:11` le charge en
`classpath:pair-keystore.p12`, le retirer casse le démarrage HTTPS, et le
constat est déjà consigné au plan sécurité (`PLAN_BACKEND_SECURITE`, étape
« `git rm --cached` + `*.p12` + `local/` + `keytool` documenté »). C'est de la
sécurité, pas de l'hygiène. `.gitignore` prépare le terrain avec `/local/` mais
n'ajoute pas `*.p12`, pour ne pas poser une règle inerte sur un fichier suivi.

### Gelé — suspendu à P-BS-06 (Lot 2)

Sept fichiers de la racine ne bougent pas, parce qu'ils publient ou chargent les
dix comptes de démonstration à UUID fixes :

- `railway_seed_data.sql` (26 Ko, mot de passe et empreinte bcrypt en clair,
  10 occurrences de `@pair.test`) et `find_badge_categories.sql` — exclusion
  explicite de la fiche ;
- `load_railway_data.sh`, `load_railway_data.py`, `load_data_railway_cli.sh` :
  ces trois scripts n'existent que pour jouer `railway_seed_data.sql`, par un
  chemin **relatif à la racine**. Les déplacer les casserait, et l'étape 4
  peut décider de supprimer le SQL : leur sort est le sien. `load_railway_data.sh`
  affiche en plus `Password: Test1234!` en fin d'exécution ;
- **`QUICK_START_RAILWAY.md` et `RAILWAY_SEED_README.md` — trouvaille hors
  fiche.** Ces deux documents publient le tableau des dix comptes
  (`alice@pair.test` … `julien@pair.test`) **avec le mot de passe `Test1234!`**,
  contre le déploiement `pairbackend-production` nommément. C'est exactement ce
  que le commit `c41322f` du lot 0 a cessé de faire pour les comptes de démo.
  Les déplacer ou les fusionner ne ferait que déménager la publication : ils
  relèvent de P-BS-06 avec le SQL qu'ils documentent.

C'est pourquoi la fusion des quatre guides Railway en un `docs/deployment/RAILWAY.md`
(étape 3 de la fiche) **n'est pas faite ici** : deux des quatre sont gelés, et
le troisième est la référence vivante qu'il ne faut pas diluer.

---

## 3. Les commandes, dans l'ordre

À jouer d'un bloc au moment du commit, depuis la racine du dépôt. Rien ici ne
réécrit l'histoire (décision **D6, option C** : rien n'est réécrit, tout secret
publié est tenu pour brûlé et tourné ; un `git filter-repo` casserait les sommes
de contrôle Flyway et tous les clones).

```bash
# 0. Le .gitignore d'abord : ce qui sort devient ignoré dans la même seconde.
git add .gitignore

# 1. Sortent du suivi, restent sur le disque (couverts par .gitignore).
git rm -r --cached --quiet -- '*.log' SQLHistory frontend-config.local.json .claude/memories

# 2. Résidus, retirés de l'index ET du disque (l'historique git les conserve).
git rm --quiet -- DOCUMENTATION_INDEX.md stop-app.sh create-test-user.sh test-phase3.sh test-auth.html

# 3. Déplacements — scripts.
mkdir -p scripts/smoke
git mv start-db.sh                  scripts/start-db.sh
git mv run-map-visibility-tests.sh  scripts/run-map-visibility-tests.sh
git mv run-map-visibility-tests.bat scripts/run-map-visibility-tests.bat
git mv quick-test.sh                scripts/smoke/quick-test.sh
git mv test-conversations.sh        scripts/smoke/test-conversations.sh

# 4. Déplacements — documentation et contrat d'API.
git mv frontend-config.json              docs/api/frontend-config.json
git mv RAILWAY_ENV_VARS.md               docs/deployment/RAILWAY_ENV_VARS.md
git mv RAILWAY_SEEDING.md                docs/deployment/RAILWAY_SEEDING.md
git mv RESEND_QUICKSTART.md              docs/guides/RESEND_QUICKSTART.md
git mv RESEND_SETUP.md                   docs/guides/RESEND_SETUP.md
git mv MAP_ACTIVITIES_IMPLEMENTATION.md  docs/implementation/MAP_ACTIVITIES_IMPLEMENTATION.md

# 5. La CI devient bloquante : supprimer la ligne 67 de .github/workflows/ci.yml
#    (« continue-on-error: true », sous l'étape « aucun fichier .log suivi par
#    git (P-BA-22) ») et le paragraphe de commentaire lignes 60-65 qui l'explique.

# 6. Vérifications.
test -z "$(git ls-files '*.log')"       && echo "OK : aucun .log suivi"
test -z "$(git ls-files 'SQLHistory/*')" && echo "OK : SQLHistory hors suivi"
git ls-files | grep -v /                 # 15 fichiers attendus (voir plus bas)
git status --porcelain | grep -E '^\?\? (audit|docs/runbooks)/' \
  || echo "OK : audit/ et docs/runbooks/ ne peuvent plus être ajoutés par distraction"
```

Racine attendue après coup, **15 fichiers suivis** au lieu de 41 : `.env.example`,
`.gitattributes`, `.gitignore`, `Dockerfile`, `README.md`, `mvnw`, `mvnw.cmd`,
`pom.xml`, `railway.json` — plus les 7 gelés (`railway_seed_data.sql`,
`find_badge_categories.sql`, `load_railway_data.sh`, `load_railway_data.py`,
`load_data_railway_cli.sh`, `QUICK_START_RAILWAY.md`, `RAILWAY_SEED_README.md`),
que P-BS-06 finira de trancher. Au total **57 fichiers quittent l'index** (26 à
la racine, 36 dans `SQLHistory/`, 6 dans `.claude/memories/`, moins les 11
déplacés qui y restent sous un autre nom).

### Références à corriger après les déplacements

Les documents d'archive (`docs/archived/`, `docs/status/`,
`docs/implementation/PHASE*`, `docs/troubleshooting/RESOLUTION_COMPLETE.md`)
sont des comptes rendus datés : **les laisser tels quels**. À corriger, en
revanche :

| Fichier | Ce qui y est cité |
|---|---|
| `README.md` | `bash test-conversations.sh`, `bash quick-test.sh` → `scripts/smoke/…` ; `bash test-activities-complete.sh`, `test-map.sh`, `test-programs.sh`, `test-search.sh` (ils vivaient dans `SQLHistory/`) → à supprimer ou à repointer ; `cp frontend-config.json src/config/` → `docs/api/frontend-config.json` ; les renvois à `DOCUMENTATION_INDEX.md` → `docs/DOCUMENTATION_INDEX.md` ; `tail -f app.log` (le journal n'est plus livré) |
| `docs/DOCUMENTATION_INDEX.md` | `frontend-config.json`, `frontend-config.local.json`, `quick-test.sh`, `stop-app.sh`, `test-conversations.sh` |
| `docs/guides/COMMANDES_UTILES.md` | `stop-app.sh` (supprimé), `quick-test.sh`, `test-conversations.sh` |
| `docs/guides/AUTHENTICATION_GUIDE.md` | `test-conversations.sh` |
| `docs/guides/FRONTEND_SETUP.md`, `FRONTEND_QUICKSTART.md`, `FRONTEND_SETUP_ADDENDUM.md` | `frontend-config.json`, `frontend-config.local.json` |
| `docs/tests/MAP_VISIBILITY_TESTS_README.md`, `docs/tests/QUICKSTART_MAP_TESTS.md` | `run-map-visibility-tests.sh` et `.bat` |
| `docs/deployment/RAILWAY_ENV_VARS.md` (après déplacement) | renvoi `RESEND_SETUP.md` → `../guides/RESEND_SETUP.md` |
| `docs/guides/README.md`, `docs/deployment/README.md`, `docs/api/README.md`, `docs/implementation/README.md`, `docs/INDEX.md` | ajouter les documents arrivants, dont cette fiche |

Un script lancé depuis la racine ne sera plus trouvé : c'est le seul risque
d'usage. `scripts/README.md` doit donc gagner une ligne pour `start-db.sh`, et
`Dockerfile` n'est pas concerné — il ne copie que `pom.xml`, `.mvn` et `src`,
l'image ne change pas.

---

## 4. Doublons repérés en passant (hors périmètre, à trancher par un humain)

- `docs/deployment/pair-deploiement-railway-journal.md` et
  `docs/specs/pair-deploiement-railway-journal.md` sont **identiques au bit
  près** (58 Ko chacun) et aucun autre document ne les cite. Garder la copie
  `deployment/`, retirer l'autre.
- `docs/DOCUMENTATION_INDEX.md` (383 l.), `docs/INDEX.md` (157 l.) et
  `docs/README.md` sont trois index concurrents de la même arborescence, dont
  un daté « dernière mise à jour 2026-07-01 · 88 documents » alors que `docs/`
  en porte 190. Un seul devrait survivre.
