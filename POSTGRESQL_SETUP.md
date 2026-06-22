# PostgreSQL Setup Guide for Pair

## Option 1: Docker (Recommandé)

### Installation Docker Desktop
1. Télécharger **Docker Desktop** : https://www.docker.com/products/docker-desktop/
2. Installer et redémarrer votre ordinateur
3. Démarrer Docker Desktop

### Lancer PostgreSQL avec PostGIS + pgvector

```bash
# Créer et démarrer le conteneur
docker run -d --name pair-postgres \
  -e POSTGRES_USER=pair_user \
  -e POSTGRES_PASSWORD=changeme \
  -e POSTGRES_DB=pair_db \
  -p 5432:5432 \
  postgis/postgis:16-3.4

# Installer pgvector
docker exec -it pair-postgres bash -c "apt-get update && apt-get install -y postgresql-16-pgvector"

# Redémarrer le conteneur
docker restart pair-postgres

# Vérifier que le conteneur fonctionne
docker ps | grep pair-postgres
```

### Tester la connexion

```bash
docker exec -it pair-postgres psql -U pair_user -d pair_db -c "SELECT version();"
```

### Activer les extensions

```bash
docker exec -it pair-postgres psql -U pair_user -d pair_db -c "
CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
"
```

### Vérifier les extensions

```bash
docker exec -it pair-postgres psql -U pair_user -d pair_db -c "\dx"
```

Vous devriez voir :
```
                          List of installed extensions
    Name     | Version |   Schema   |                      Description
-------------+---------+------------+-------------------------------------------------------
 plpgsql     | 1.0     | pg_catalog | PL/pgSQL procedural language
 postgis     | 3.4.x   | public     | PostGIS geometry and geography spatial types
 uuid-ossp   | 1.1     | public     | generate universally unique identifiers (UUIDs)
 vector      | 0.x.x   | public     | vector data type and ivfflat access method
```

---

## Option 2: Installation locale (Windows)

### 1. Installer PostgreSQL 16

1. Télécharger : https://www.enterprisedb.com/downloads/postgres-postgresql-downloads
2. Choisir **PostgreSQL 16** pour Windows
3. Pendant l'installation :
   - Port : **5432**
   - Mot de passe superuser `postgres` : **choisir un mot de passe**
   - Installer **Stack Builder** (inclut PostGIS)

### 2. Installer PostGIS

1. Lancer **Stack Builder** (depuis le menu Démarrer)
2. Sélectionner votre installation PostgreSQL 16
3. Dans **Spatial Extensions** → cocher **PostGIS**
4. Installer

### 3. Installer pgvector

**Téléchargement** :
- https://github.com/pgvector/pgvector/releases
- Télécharger `pgvector-v0.7.0-pg16-windows-x64.zip`

**Installation** :
1. Extraire le contenu
2. Copier `vector.dll` vers `C:\Program Files\PostgreSQL\16\lib\`
3. Copier les fichiers `.sql` et `.control` vers `C:\Program Files\PostgreSQL\16\share\extension\`

### 4. Créer la base de données

Ouvrir **pgAdmin 4** ou **SQL Shell (psql)** :

```sql
-- Se connecter en tant que postgres
CREATE USER pair_user WITH PASSWORD 'changeme';
CREATE DATABASE pair_db OWNER pair_user;

-- Se connecter à pair_db
\c pair_db

-- Activer les extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

-- Vérifier
\dx
```

---

## Option 3: PostgreSQL Cloud (gratuit)

### Neon (recommandé pour dev)

1. Créer un compte : https://neon.tech/
2. Créer un nouveau projet PostgreSQL 16
3. Copier la **connection string**

**Configuration** :

Modifier `application.properties` :

```properties
spring.datasource.url=jdbc:postgresql://your-project.neon.tech/neondb?sslmode=require
spring.datasource.username=your-username
spring.datasource.password=your-password
```

**Note** : Neon supporte PostGIS mais **pas encore pgvector** nativement.

---

## Lancer les migrations Flyway

Une fois PostgreSQL démarré :

```bash
cd /path/to/Pair
mvn flyway:migrate
```

Vous devriez voir :

```
[INFO] Successfully validated 9 migrations
[INFO] Creating Schema History table "pair_db"."flyway_schema_history"
[INFO] Current version of schema "public": << Empty Schema >>
[INFO] Migrating schema "public" to version "1 - enable extensions"
[INFO] Migrating schema "public" to version "2 - create users table"
[INFO] Migrating schema "public" to version "3 - create categories and activities"
[INFO] Migrating schema "public" to version "4 - create user activities"
[INFO] Migrating schema "public" to version "5 - create programs schedules media"
[INFO] Migrating schema "public" to version "6 - create chat tables"
[INFO] Migrating schema "public" to version "7 - create reviews badges recommendations"
[INFO] Migrating schema "public" to version "8 - create notifications"
[INFO] Migrating schema "public" to version "9 - create reports searchlogs progressions"
[INFO] Successfully applied 9 migrations to schema "public"
```

---

## Démarrer l'application

```bash
mvn spring-boot:run
```

Si tout fonctionne, vous verrez :

```
Started PairApplication in X.XXX seconds (process running for X.XXX)
```

---

## Commandes utiles Docker

```bash
# Démarrer le conteneur
docker start pair-postgres

# Arrêter le conteneur
docker stop pair-postgres

# Voir les logs
docker logs pair-postgres

# Se connecter en psql
docker exec -it pair-postgres psql -U pair_user -d pair_db

# Supprimer le conteneur (attention : perte des données)
docker rm -f pair-postgres
```

---

## Dépannage

### Erreur : "FATAL: Passwort-Authentifizierung fehlgeschlagen"

✅ **Solution** : Vérifier le mot de passe dans `application.properties`

```properties
spring.datasource.username=pair_user
spring.datasource.password=changeme
```

Ou définir les variables d'environnement :

```bash
export DB_USER=pair_user
export DB_PASSWORD=changeme
```

### Erreur : "Connection refused"

✅ **Solution** : PostgreSQL n'est pas démarré

```bash
docker start pair-postgres
# ou sur Windows : Démarrer le service PostgreSQL depuis Services
```

### Erreur : "Extension vector does not exist"

✅ **Solution** : pgvector n'est pas installé

```bash
# Docker
docker exec -it pair-postgres bash -c "apt-get update && apt-get install -y postgresql-16-pgvector"
docker restart pair-postgres

# Locale : télécharger et installer manuellement (voir Option 2)
```

---

## Prochaines étapes

Une fois PostgreSQL configuré :
1. ✅ Lancer `mvn flyway:migrate`
2. ✅ Démarrer l'application : `mvn spring-boot:run`
3. ✅ Passer à la **Phase 1** : Implémentation Auth + API REST

Consultez **NEXT_STEPS.md** pour la suite !
