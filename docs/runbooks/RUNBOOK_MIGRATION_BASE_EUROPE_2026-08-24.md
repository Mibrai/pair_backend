# Runbook — déplacer la base de `sfo` vers l'Europe

> Contexte : `docs/specs/REPONSE_BACKEND_PLANCHER_MESURES_2026-08-24.md`.
> Le service applicatif est en `europe-west4-drams3a`, la base en `sfo`.
> Objectif : les mettre dans la même région, et mesurer le gain.
>
> **Cette procédure s'exécute à la main.** `railway ssh` en production est
> refusé à l'assistant par le classifieur de sécurité ; les étapes marquées
> **[MAIN]** doivent être lancées par vous. Les autres peuvent l'être par
> l'assistant si vous le demandez.

---

## 0. Ce que la migration doit préserver

Établi par audit du dépôt et de la configuration Railway, le 2026-08-24 :

| élément | valeur | conséquence |
|---|---|---|
| Postgres | **16**, image `pgvector/pgvector:pg16` + PostGIS 3 | la base cible doit être **la même image**, pas un Postgres Railway standard |
| extensions requises | `postgis`, `vector`, `uuid-ossp`, `pg_trgm` | `V1__enable_extensions.sql`, `V77__trigram_search.sql` |
| volume source | `postgres_db-volume-vcIU`, **130 Mo** utilisés sur 500 | dump/restore trivial |
| variables lues par l'app | `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | **seules ces cinq** basculent (`application-railway.properties:2-4`) |
| variables *non* lues | `POSTGRES_*`, `RAILWAY_SERVICE_POSTGRES_DB_URL` | ne pas perdre de temps dessus |
| Flyway | `baseline-on-migrate=true`, 77 migrations (jusqu'à V78) | la table `flyway_schema_history` **doit** être dans le dump |
| seeders au démarrage | `ReferenceDataSeeder` + `DemoDataSeeder` actifs en profil `railway` | idempotents (`existsByEmail`, `existsBySlug`, `existsByCode`) — sans danger |

### Pré-requis locaux

- `railway` CLI connecté (`railway whoami`) — présent, v5.29.0.
- `docker` — présent. **`psql` / `pg_dump` sont absents de la machine** : tout
  passe par un conteneur `postgres:16`, ce qui garantit en prime la bonne
  version du client.

```sh
export RW_PROJECT=8a802f7f-7e58-427e-99cb-e12492142fe6
export RW_ENV=production
export APP=pair_backend_service
export OLD_DB=postgres_db
export NEW_DB=postgres_db_eu
export WORKDIR="$(mktemp -d)/migration" && mkdir -p "$WORKDIR" && echo "$WORKDIR"
```

---

## 1. Pré-vol — **avant toute coupure**

### 1.1 [MAIN] Le `SELECT 1` d'avant — irrattrapable

Le client (`REPONSE_APP_FENETRE_MIGRATION_2026-08-24.md` §4) n'a qu'une
insistance : cette mesure disparaît dès que la base bouge. **Ne pas sauter.**

Le conteneur applicatif est un `eclipse-temurin:21-jre-jammy` : ni `psql`, ni
garantie d'avoir `curl`. On mesure donc l'aller-retour au niveau TCP, où un
`connect()` vaut exactement un aller-retour — c'est la même grandeur qu'un
`SELECT 1` sur connexion ouverte, sans dépendre d'un client Postgres.

Le script `scripts/mesure-rtt-db.sh` fait le relevé. Il s'exécute **dans** le
conteneur, alimenté depuis le poste par l'entrée standard — ce qui évite d'avoir
à coller une boucle et ses quotes dans une session interactive :

```sh
railway ssh -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" \
  bash -s -- avant < scripts/mesure-rtt-db.sh | tee "$WORKDIR/rtt-avant.txt"
```

Il mesure vingt `connect()` TCP vers `$PGHOST:$PGPORT` — un établissement de
connexion vaut exactement un aller-retour — après avoir résolu le nom une fois
pour toutes, pour ne pas mesurer le DNS avec le réseau. Trois connexions de
chauffe sont jetées. Si `curl` est présent dans le conteneur, il double la
mesure par un vrai `SELECT 1` : l'indicateur de santé DataSource exécute une
requête de validation sur une connexion déjà ouverte du pool Hikari.

Sortie attendue :

```
  n         : 20 réussis, 0 échoués
  MEDIANE   : 1XX.X ms   <-- la valeur à retenir
RTT_TCP	avant	1XX.X
```

> Si la commande `bash -s` ne passe pas la main au conteneur, ouvrir une session
> interactive (`railway ssh -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP"`) et y coller
> le contenu du script.

**Attendu avant migration : médiane entre 150 et 200 ms.** En dessous de 20 ms,
**arrêter la procédure** — le diagnostic serait faux et le déplacement inutile.

> Noter la valeur. Elle part au client avec sa jumelle d'après.

### 1.2 Empreinte de la base, pour vérifier la restauration

```sh
railway connect -p "$RW_PROJECT" -e "$RW_ENV" "$OLD_DB"
```

puis dans `psql` :

```sql
SELECT version();
SELECT extname, extversion FROM pg_extension ORDER BY 1;
SELECT count(*) AS migrations FROM flyway_schema_history;
SELECT max(installed_rank) AS dernier_rang FROM flyway_schema_history;
SELECT relname, n_live_tup FROM pg_stat_user_tables
 WHERE n_live_tup > 0 ORDER BY n_live_tup DESC LIMIT 25;
SELECT count(*) FROM users WHERE email = 'demo1@pair.app';  -- doit valoir 1
```

Copier la sortie dans `$WORKDIR/empreinte-avant.txt`. C'est le seul juge de la
restauration.

### 1.3 ⚠️ Les médias — vérifier **avant** de redéployer l'application

Le service applicatif n'a **qu'un** volume, monté sur `/app/models` (cache des
modèles d'embedding). Or `LocalStorageService` écrit dans `storage.location`,
soit `STORAGE_PATH=/app/uploads` (`Dockerfile`), et **aucun volume n'est monté
là**. Aucun stockage S3 n'existe dans le code.

Si c'est exact, tout média téléversé depuis le repointage du volume est perdu à
chaque redéploiement — et cette migration en impose un.

**[MAIN]** Dans la session `railway ssh` de l'étape 1.1 :

```sh
df -h /app/uploads /app/models
ls -l /app/uploads | head
find /app/uploads -type f | wc -l
```

- `/app/uploads` sur le même système de fichiers que `/` → **non persistant**.
- S'il contient des fichiers, les rapatrier avant d'aller plus loin :

```sh
railway ssh -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" \
  'tar -cz -C /app uploads' > "$WORKDIR/uploads-$(date +%Y%m%d-%H%M).tgz"
```

> Ce point est **hors périmètre de la migration** et ne doit pas la retarder,
> mais il ne doit pas être découvert après le redéploiement. Il fait l'objet
> d'un sujet à part.

### 1.4 Filet de repli : noter la configuration actuelle

```sh
railway variables list -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" --json \
  > "$WORKDIR/app-variables-avant.json"
railway variables list -p "$RW_PROJECT" -e "$RW_ENV" -s "$OLD_DB" --json \
  > "$WORKDIR/olddb-variables-avant.json"
chmod 600 "$WORKDIR"/*.json
```

> Ces fichiers contiennent les mots de passe. Ils vivent dans un répertoire
> temporaire et sont détruits à l'étape 9.

Relever en particulier `PGDATA` de l'ancienne base : la nouvelle devra avoir
**exactement la même valeur**, sinon le conteneur initialisera un cluster vide
à côté du volume.

---

## 2. Créer la base cible en Europe — **hors coupure**

Rien de ce qui suit n'interrompt le service. À faire tranquillement, et à
vérifier, avant d'ouvrir la fenêtre.

### 2.1 Le service

L'image doit être celle du dépôt (`pair-postgres/`), pas un Postgres Railway
standard : celui-ci n'a **ni PostGIS ni pgvector**, et la restauration
échouerait à la première colonne `geography` ou `vector`.

La CLI ne détecte qu'un fichier nommé exactement `Dockerfile` :

```sh
cp -r pair-postgres "$WORKDIR/pg-eu"
mv "$WORKDIR/pg-eu/Dockerfile.postgres" "$WORKDIR/pg-eu/Dockerfile"
railway add -p "$RW_PROJECT" -e "$RW_ENV" -s "$NEW_DB"
```

### 2.2 La région — **le geste qui est l'objet de toute la manœuvre**

Dans le tableau de bord Railway : service `postgres_db_eu` → **Settings →
Deploy → Region** → choisir **`europe-west4-drams3a`**, identique au service
applicatif.

> Si seule la région métal `ams` est proposée pour un service neuf, la prendre :
> Amsterdam–Eemshaven, c'est ~200 km, soit 2 à 3 ms. Ne **jamais** laisser la
> région par défaut sans la lire.

Régler la région **avant** le premier déploiement, tant que le volume est vide :
un volume appartient à sa région et ne se déplace pas.

### 2.3 Variables, volume, déploiement

Reprendre `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` et `PGDATA` à
l'identique de l'ancienne base (relevés en 1.4) — garder le même utilisateur
évite toute question de propriétaire à la restauration.

Puis, dans le tableau de bord : ajouter un volume monté sur
`/var/lib/postgresql/data`, et déployer :

```sh
(cd "$WORKDIR/pg-eu" && railway up -p "$RW_PROJECT" -e "$RW_ENV" -s "$NEW_DB")
```

### 2.4 ✅ Point de contrôle — ne pas continuer sans ces trois lignes

```sh
railway status -p "$RW_PROJECT" -e "$RW_ENV" --json \
| python3 -c "
import json,sys
d=json.load(sys.stdin)
for e in d['environments']['edges'][0]['node']['serviceInstances']['edges']:
    s=e['node']
    dep=(s.get('latestDeployment') or {}).get('meta',{}).get('serviceManifest',{}).get('deploy',{})
    print('%-24s %s' % (s['serviceName'], dep.get('multiRegionConfig')))
"
```

Attendu :

```
postgres_db              {'sfo': {'numReplicas': 1}}
pair_backend_service     {'europe-west4-drams3a': {'numReplicas': 1}}
postgres_db_eu           {'europe-west4-drams3a': {'numReplicas': 1}}      <-- 
```

Et les extensions, via `railway connect "$NEW_DB"` :

```sql
SELECT extname FROM pg_extension ORDER BY 1;   -- postgis, uuid-ossp, vector attendus
```

**Si la région de `postgres_db_eu` n'est pas celle de l'application, tout le
reste est inutile.** Corriger ici, pas plus loin.

---

## 3. Ouvrir la fenêtre — geler les écritures

> **Noter l'heure de début.** Le client la demande explicitement pour écarter
> ses mesures prises à cheval sur la bascule.

```sh
date -u +"DEBUT %Y-%m-%dT%H:%M:%SZ" | tee -a "$WORKDIR/fenetre.txt"
railway down -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" -y
```

Le service applicatif s'arrête ; plus personne n'écrit. L'ancienne base reste
allumée — c'est elle qu'on lit.

> Le nettoyage programmé du client (douze programmes en brouillon) peut échouer
> pendant cette fenêtre. C'est prévu de leur côté, et rejouable. Ne pas s'en
> émouvoir.

---

## 4. Dump

```sh
railway connect -p "$RW_PROJECT" -e "$RW_ENV" --tunnel-only "$OLD_DB"
```

Laisser ce terminal ouvert ; il affiche l'hôte et le port locaux du tunnel. Dans
un second terminal, avec ces valeurs :

```sh
export SRC_HOST=127.0.0.1 SRC_PORT=<port affiché>
export SRC_USER=<POSTGRES_USER> SRC_DB=<POSTGRES_DB>
read -rs -p "mot de passe source: " PGPASSWORD_SRC; echo

docker run --rm --network host \
  -e PGPASSWORD="$PGPASSWORD_SRC" \
  -v "$WORKDIR:/dump" postgres:16 \
  pg_dump -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" \
          -Fc --no-owner --no-privileges \
          --exclude-table='public.spatial_ref_sys' \
          -f /dump/pair-$(date +%Y%m%d-%H%M).dump
ls -lh "$WORKDIR"/*.dump
```

Deux options méritent leur justification :

- **`--exclude-table=public.spatial_ref_sys`** : PostGIS installe cette table de
  ~8 500 lignes et `init-extensions.sql` la remplit déjà sur la base cible. La
  réimporter provoque un conflit de clef primaire au milieu de la restauration.
  Elle est identique partout ; on la laisse là où elle est.
- **`--no-owner --no-privileges`** : rend la restauration indifférente aux rôles.

`flyway_schema_history` est une table ordinaire : elle est dans le dump, et elle
**doit** y être — sans elle, Flyway prendrait la base restaurée pour une base
neuve à baseliner et tenterait de rejouer les 77 migrations sur des tables
existantes.

---

## 5. Restauration

Ouvrir un tunnel vers la **nouvelle** base (`railway connect --tunnel-only
"$NEW_DB"`), puis :

```sh
export DST_PORT=<port affiché>
read -rs -p "mot de passe cible: " PGPASSWORD_DST; echo

docker run --rm --network host \
  -e PGPASSWORD="$PGPASSWORD_DST" \
  -v "$WORKDIR:/dump" postgres:16 \
  pg_restore -h 127.0.0.1 -p "$DST_PORT" -U "$SRC_USER" -d "$SRC_DB" \
             --no-owner --no-privileges -j 4 --exit-on-error \
             /dump/pair-*.dump
```

`--exit-on-error` est délibéré : mieux vaut une restauration qui s'arrête net
qu'une base à moitié peuplée qu'on croirait bonne.

### ✅ Point de contrôle — rejouer l'empreinte de 1.2

Sur la nouvelle base, relancer **les mêmes requêtes** qu'en 1.2 et comparer à
`empreinte-avant.txt` :

- `count(*) FROM flyway_schema_history` — **identique** ;
- `max(installed_rank)` — **identique** ;
- les 25 tables et leurs volumétries — identiques (`pg_stat_user_tables` peut
  demander un `ANALYZE;` pour être à jour) ;
- `demo1@pair.app` présent — sinon `DemoDataSeeder` réinjecterait le jeu de
  démonstration au premier démarrage ;
- `postgis`, `vector`, `uuid-ossp`, `pg_trgm` dans `pg_extension`.

**Divergence ⇒ ne pas basculer.** L'ancienne base est intacte : reprendre le
dump, ou renoncer et redéployer l'application inchangée (§8).

---

## 6. Bascule

Récupérer `RAILWAY_PRIVATE_DOMAIN` de la nouvelle base, puis :

```sh
railway variables set -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" --skip-deploys \
  "PGHOST=<RAILWAY_PRIVATE_DOMAIN de postgres_db_eu>"
# PGPORT, PGDATABASE, PGUSER, PGPASSWORD : à réaligner de même si elles diffèrent
```

`--skip-deploys` permet de poser les cinq variables **puis** de déployer une
seule fois, plutôt que de redémarrer l'application entre deux valeurs
incohérentes.

```sh
railway redeploy -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" -y
railway logs -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP"
```

Dans les journaux, trois choses et pas une de plus :

- Flyway : `Successfully validated 77 migrations` puis **`No migration
  necessary`**. Toute ligne `Migrating schema` signifie que
  `flyway_schema_history` n'a pas suivi — **revenir en arrière (§8)**.
- `ReferenceDataSeeder terminé` / `DemoDataSeeder terminé` sans création — les
  gardes `existsBy*` doivent court-circuiter.
- Aucun `Storage contains no persistence marker` inattendu (cf. 1.3).

---

## 7. Fermer la fenêtre, et mesurer

```sh
date -u +"FIN %Y-%m-%dT%H:%M:%SZ" | tee -a "$WORKDIR/fenetre.txt"
```

**[MAIN] Le `SELECT 1` d'après** — exactement le même script qu'en 1.1, seule
l'étiquette change :

```sh
railway ssh -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" \
  bash -s -- apres < scripts/mesure-rtt-db.sh | tee "$WORKDIR/rtt-apres.txt"

grep RTT_TCP "$WORKDIR"/rtt-avant.txt "$WORKDIR"/rtt-apres.txt
```

**Attendu : médiane entre 1 et 5 ms.** C'est la moitié du couple que le client
attend ; l'autre moitié dort dans vos notes depuis l'étape 1.1.

Vérification fonctionnelle rapide, depuis n'importe où :

```sh
curl -s -o /dev/null -w 'health %{http_code} en %{time_total}s\n' \
  https://pairbackend-production-35fe.up.railway.app/actuator/health
```

Puis prévenir le client : heure de début, heure de fin, couple `SELECT 1`. Il
relance son `smoke` immédiatement et renvoie le tableau route par route.

**Prédictions publiées le 24 août, à confronter sans les arrondir :**
`/slots/feed` ~175 ms, `/programs` ~160 ms, `unread-count` ~240 ms — cette
dernière étant annoncée comme *ne devant pas* s'améliorer.

---

## 8. Repli

Tant que `postgres_db` (`sfo`) existe et n'a pas été réécrite, le retour arrière
coûte deux commandes :

```sh
railway variables set -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" --skip-deploys \
  "PGHOST=<RAILWAY_PRIVATE_DOMAIN de postgres_db>"
railway redeploy -p "$RW_PROJECT" -e "$RW_ENV" -s "$APP" -y
```

L'ancienne base est restée gelée depuis l'étape 3 : aucune écriture n'a pu la
diverger. Le repli ne perd que le temps passé.

Points de non-retour, dans l'ordre :

1. §3 — le service s'arrête. Réversible : `railway redeploy`.
2. §6 — l'application écrit dans la nouvelle base. À partir d'ici, un repli
   perd les écritures faites depuis la bascule.
3. §9 — l'ancienne base est supprimée. **Irréversible.**

---

## 9. Nettoyage — après validation, pas le jour même

Laisser `postgres_db` (`sfo`) en place **au moins 7 jours**, arrêtée mais non
supprimée. C'est la seule copie hors-ligne de l'état d'avant migration, et elle
coûte le prix d'un volume de 500 Mo.

Quand la campagne du client a confirmé les chiffres :

```sh
# 1. Conserver le dump ailleurs que dans un répertoire temporaire.
cp "$WORKDIR"/*.dump ~/sauvegardes/

# 2. Détruire les secrets extraits en 1.4.
shred -u "$WORKDIR"/*.json 2>/dev/null || rm -P "$WORKDIR"/*.json

# 3. Supprimer l'ancien service depuis le tableau de bord (pas de commande CLI
#    de suppression de service : c'est volontaire, et tant mieux).
```

Renommer alors `postgres_db_eu` en `postgres_db` pour que la configuration
redevienne lisible par le prochain qui la regardera.

---

## 10. Ce qui reste ouvert après cette migration

- **Les médias (§1.3)** — si `/app/uploads` n'est sur aucun volume, le problème
  survit à la migration et se rejouera au prochain déploiement.
- **`unread-count` à ~240 ms** — le terme fixe d'environ 130 ms, jusqu'ici dilué
  par les routes à sept et neuf requêtes, devient le sujet dominant. C'est là
  que `hibernate.generate_statistics` retrouve son intérêt.
- **Le limiteur** — `RateLimiter.checkLogin` bloque 15 minutes après 10
  tentatives par IP, et `checkRegister` n'a **aucune** fenêtre glissante malgré
  son message. À traiter avant de monter l'environnement de recette.
- **L'environnement de recette** — service et base tous deux en Europe dès le
  départ, sur le second projet Railway resté vide.
