# 🚀 Guide de Déploiement - Application Pair

**Version**: 1.0.0  
**Date**: 2026-06-23  
**Phase**: 1 & 2 Complètes

---

## 📋 Prérequis

### Environnement
- **Java**: 17 ou supérieur (Azul Zulu 17.0.14 recommandé)
- **PostgreSQL**: 18.4 avec extensions PostGIS
- **Maven**: 3.8+ (ou utiliser `./mvnw`)
- **OS**: Windows 10/11, Linux, macOS

### Ports Requis
- **8090**: Application Spring Boot
- **5432**: PostgreSQL

---

## 🗄️ Configuration Base de Données

### 1. Installation PostgreSQL avec PostGIS

```bash
# Windows: Télécharger depuis postgresql.org
# Inclure PostGIS dans l'installation

# Linux (Ubuntu/Debian)
sudo apt-get install postgresql-18 postgresql-18-postgis-3

# macOS
brew install postgresql@18 postgis
```

### 2. Créer la Base de Données

```sql
-- Connexion en tant que postgres
CREATE DATABASE pair_db;
CREATE USER pair_user WITH PASSWORD 'Pair2026!';
GRANT ALL PRIVILEGES ON DATABASE pair_db TO pair_user;

-- Se connecter à pair_db
\c pair_db

-- Créer extensions
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

### 3. Exécuter les Scripts SQL

```bash
cd SQLHistory

# Script principal
psql -h localhost -U pair_user -d pair_db -f SETUP_WITHOUT_EMBEDDING.sql

# Scripts Phase 2
psql -h localhost -U pair_user -d pair_db -f 03_create_programs_tables.sql
psql -h localhost -U pair_user -d pair_db -f 04_seed_map_test_data.sql
psql -h localhost -U pair_user -d pair_db -f 05_create_chat_tables.sql
psql -h localhost -U pair_user -d pair_db -f 06_add_last_message_at.sql
psql -h localhost -U pair_user -d pair_db -f 08_setup_fulltext_search.sql
psql -h localhost -U pair_user -d pair_db -f 09_create_progressions_table.sql
```

---

## ⚙️ Configuration Application

### 1. Variables d'Environnement

Créer un fichier `.env` ou définir les variables:

```bash
# Base de données
DB_USER=pair_user
DB_PASSWORD=Pair2026!

# JWT Secret (générer un nouveau secret en production!)
JWT_SECRET=YXByaWNvZGV2YXBwbGljYXRpb25wYWlyYXV0aGVudGljYXRpb25zZWNyZXRrZXk=

# LLM API (optionnel)
ANTHROPIC_API_KEY=sk-ant-api-key-here

# OpenAI (futur, pour embeddings)
OPENAI_API_KEY=sk-openai-key-here

# Storage
STORAGE_PATH=./uploads
```

### 2. application.properties

Le fichier `src/main/resources/application.properties` est déjà configuré.

Pour override en production:

```properties
# Production database
spring.datasource.url=jdbc:postgresql://prod-db-host:5432/pair_db
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}

# Production JWT (IMPORTANT: Changer le secret!)
jwt.secret=${JWT_SECRET}

# Disable dev tools
spring.devtools.restart.enabled=false

# Logging
logging.level.root=INFO
logging.level.org.program.pair=INFO
```

---

## 🔨 Compilation & Build

### Développement

```bash
# Compiler
./mvnw clean compile

# Lancer l'application
./mvnw spring-boot:run
```

### Production

```bash
# Build JAR
./mvnw clean package -DskipTests

# Le JAR sera dans target/Pair-0.0.1-SNAPSHOT.jar
```

### Lancer le JAR

```bash
java -jar target/Pair-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=production \
  --server.port=8090
```

---

## 🐳 Docker (Optionnel)

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/Pair-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### docker-compose.yml

```yaml
version: '3.8'

services:
  db:
    image: postgis/postgis:18-3.4
    environment:
      POSTGRES_DB: pair_db
      POSTGRES_USER: pair_user
      POSTGRES_PASSWORD: Pair2026!
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  app:
    build: .
    ports:
      - "8090:8090"
    environment:
      DB_USER: pair_user
      DB_PASSWORD: Pair2026!
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/pair_db
    depends_on:
      - db

volumes:
  postgres_data:
```

### Lancer avec Docker

```bash
# Build & Run
docker-compose up -d

# Voir les logs
docker-compose logs -f app

# Arrêter
docker-compose down
```

---

## 🧪 Validation du Déploiement

### 1. Health Check

```bash
curl http://localhost:8090/actuator/health
# Devrait retourner: {"status":"UP"}
```

### 2. Tests Endpoints Publics

```bash
# Categories
curl http://localhost:8090/api/categories

# Activities
curl http://localhost:8090/api/activities
```

### 3. Test Authentification

```bash
# Register
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test1234!",
    "displayName": "Test User"
  }'

# Devrait retourner un accessToken
```

### 4. Scripts de Test

```bash
cd SQLHistory

# Tests Phase 1
bash test-activities-complete.sh
bash test-programs.sh
bash test-map.sh
bash test-chat.sh

# Tests Phase 2
bash test-search.sh
bash test-progressions.sh
```

---

## 📊 Monitoring

### Actuator Endpoints

Spring Boot Actuator expose plusieurs endpoints:

```bash
# Health
curl http://localhost:8090/actuator/health

# Info
curl http://localhost:8090/actuator/info

# Metrics
curl http://localhost:8090/actuator/metrics
```

### Logs

```bash
# Logs en temps réel
tail -f logs/spring-boot-application.log

# Logs Docker
docker-compose logs -f app
```

---

## 🔒 Sécurité Production

### 1. JWT Secret

**CRITIQUE**: Changer le JWT secret en production!

```bash
# Générer un nouveau secret
openssl rand -base64 64

# Définir dans les variables d'environnement
export JWT_SECRET="votre-nouveau-secret-ici"
```

### 2. Base de Données

- ✅ Utiliser des mots de passe forts
- ✅ Limiter l'accès réseau (firewall)
- ✅ Activer SSL/TLS
- ✅ Sauvegardes régulières

### 3. Application

- ✅ HTTPS obligatoire (reverse proxy Nginx/Apache)
- ✅ Rate limiting sur endpoints sensibles
- ✅ CORS configuré correctement
- ✅ Validation stricte des inputs

### 4. Storage

```bash
# Permissions restreintes
chmod 700 uploads/
chown app-user:app-user uploads/
```

---

## 🔄 Mises à Jour

### Déploiement Zero-Downtime

```bash
# 1. Build nouvelle version
./mvnw clean package -DskipTests

# 2. Health check
curl http://localhost:8090/actuator/health

# 3. Graceful shutdown (wait for current requests)
kill -SIGTERM <PID>

# 4. Backup database
pg_dump pair_db > backup_$(date +%Y%m%d).sql

# 5. Run migrations (if any)
psql -h localhost -U pair_user -d pair_db -f migration.sql

# 6. Start new version
java -jar target/Pair-0.0.1-SNAPSHOT.jar
```

---

## 🐛 Troubleshooting

### Port déjà utilisé

```bash
# Trouver le processus
netstat -ano | grep :8090

# Windows
taskkill //PID <PID> //F

# Linux/Mac
kill -9 <PID>
```

### Erreur connexion PostgreSQL

```bash
# Vérifier que PostgreSQL tourne
# Windows
sc query postgresql-x64-18

# Linux
systemctl status postgresql

# Tester connexion
psql -h localhost -U pair_user -d pair_db
```

### Erreur PostGIS

```sql
-- Se connecter à la base
\c pair_db

-- Vérifier extension
\dx

-- Réinstaller si nécessaire
DROP EXTENSION IF EXISTS postgis CASCADE;
CREATE EXTENSION postgis;
```

### Logs d'Erreur

```bash
# Augmenter verbosité
export LOGGING_LEVEL_ROOT=DEBUG

# Logs Spring
tail -100 logs/spring.log | grep ERROR
```

---

## 📈 Performance Tuning

### JVM Options

```bash
java -Xms512m -Xmx2048m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar target/Pair-0.0.1-SNAPSHOT.jar
```

### PostgreSQL

```sql
-- Analyser les indexes
ANALYZE programs;
ANALYZE activities;

-- Vacuum
VACUUM ANALYZE;

-- Stats
SELECT * FROM pg_stat_user_tables 
WHERE schemaname = 'public';
```

### Connection Pool

```properties
# application.properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

---

## 🌐 Reverse Proxy (Nginx)

### Configuration Nginx

```nginx
upstream pair_backend {
    server localhost:8090;
}

server {
    listen 80;
    server_name pair.example.com;

    # Redirect to HTTPS
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name pair.example.com;

    ssl_certificate /etc/ssl/certs/pair.crt;
    ssl_certificate_key /etc/ssl/private/pair.key;

    # WebSocket support
    location /ws {
        proxy_pass http://pair_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }

    # API
    location /api {
        proxy_pass http://pair_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Media files
    location /api/media/files {
        proxy_pass http://pair_backend;
        proxy_cache media_cache;
        proxy_cache_valid 200 1h;
    }
}
```

---

## ✅ Checklist Déploiement

### Avant Déploiement
- [ ] PostgreSQL 18 installé avec PostGIS
- [ ] Base de données créée et configurée
- [ ] Scripts SQL exécutés
- [ ] Variables d'environnement définies
- [ ] JWT secret changé
- [ ] Application compilée (JAR créé)

### Déploiement
- [ ] JAR copié sur serveur
- [ ] Permissions fichiers correctes
- [ ] Application démarrée
- [ ] Health check OK
- [ ] Tests endpoints publics OK

### Après Déploiement
- [ ] Monitoring configuré
- [ ] Logs accessibles
- [ ] Sauvegardes automatiques configurées
- [ ] SSL/TLS activé
- [ ] Reverse proxy configuré

---

## 📞 Support

### Documentation
- `README.md` - Vue d'ensemble
- `PHASE1_COMPLETE.md` - Phase 1
- `PHASE2_COMPLETE.md` - Phase 2
- `TESTING_GUIDE.md` - Guide tests

### Logs
- Application: `logs/spring-boot-application.log`
- PostgreSQL: Voir configuration PostgreSQL
- Nginx: `/var/log/nginx/`

---

## 🎉 Déploiement Réussi!

Application Pair déployée et opérationnelle! ✅

**URL**: http://localhost:8090  
**Health**: http://localhost:8090/actuator/health  
**API Docs**: À venir (Swagger)
