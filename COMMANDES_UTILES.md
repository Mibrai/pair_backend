# 🛠️ Commandes Utiles - Application Pair

## 🚀 Démarrage / Arrêt

### Démarrer l'Application

```bash
# Méthode 1: Arrière-plan avec logs dans un fichier
mvn spring-boot:run > app.log 2>&1 &

# Méthode 2: Au premier plan (voir les logs en direct)
mvn spring-boot:run

# Méthode 3: Sans tests
mvn spring-boot:run -DskipTests
```

---

### Arrêter l'Application

#### Option 1: Ctrl+C (si au premier plan)
```bash
Ctrl + C
```

#### Option 2: Trouver et tuer le processus
```bash
# Trouver le PID sur le port 8090
netstat -ano | findstr ":8090"

# Tuer le processus (remplacer 12345 par le PID trouvé)
taskkill //F //PID 12345
```

#### Option 3: Script automatique (recommandé)
```bash
bash stop-app.sh
```

#### Option 4: Tuer tous les processus Java
```bash
# ⚠️ ATTENTION: Tue TOUS les processus Java
tasklist | findstr "java.exe"
taskkill //F //IM java.exe
```

---

## 📊 Vérification Status

### Vérifier si l'application tourne

```bash
# Méthode 1: Vérifier le port 8090
netstat -ano | findstr ":8090"

# Méthode 2: Tester l'API
curl http://localhost:8090/api/categories

# Méthode 3: Vérifier les processus Java
tasklist | findstr "java.exe"
```

### Vérifier les logs

```bash
# Si démarré en arrière-plan avec > app.log
tail -f app.log

# Voir les dernières lignes
tail -50 app.log

# Rechercher des erreurs
grep -i error app.log
grep -i exception app.log

# Vérifier le démarrage
grep "Started PairApplication" app.log
```

---

## 🗄️ Base de Données

### PostgreSQL avec Docker

```bash
# Démarrer
docker start pair-postgres

# Arrêter
docker stop pair-postgres

# Voir les logs
docker logs pair-postgres

# Se connecter
docker exec -it pair-postgres psql -U pair_user -d pair_db
```

### PostgreSQL Local

```bash
# Windows
net start postgresql-x64-16

net stop postgresql-x64-16

# Se connecter
psql -h localhost -U pair_user -d pair_db

# Mot de passe
Pair2026!
```

---

## 🔨 Build & Compilation

### Compilation

```bash
# Compilation simple
mvn clean compile

# Sans tests
mvn clean compile -DskipTests

# Avec tests
mvn clean test

# Package (créer le JAR)
mvn clean package
```

### Nettoyage

```bash
# Nettoyer les fichiers compilés
mvn clean

# Nettoyer complètement (+ dépendances)
mvn clean -U
```

---

## 🧪 Tests

### Lancer les Tests

```bash
# Tous les tests
mvn test

# Tests d'une classe spécifique
mvn test -Dtest=UserServiceTest

# Tests d'un package
mvn test -Dtest=org.program.pair.domain.user.*

# Skip tests
mvn install -DskipTests
```

### Scripts de Test API

```bash
# Test complet activités
bash test-activities-complete.sh

# Test conversations/chat
bash test-conversations.sh

# Test carte
bash test-map.sh

# Test programmes
bash test-programs.sh

# Test recherche
bash test-search.sh

# Test authentification rapide
bash quick-test.sh
```

---

## 📝 Logs

### Localisation des Logs

```bash
# Logs application (si démarré avec > app.log)
tail -f app.log

# Logs Spring Boot par défaut
ls logs/

# Logs système
journalctl -u pair-app
```

### Filtrer les Logs

```bash
# Erreurs seulement
grep ERROR app.log

# Warnings
grep WARN app.log

# Démarrage
grep "Started PairApplication" app.log

# Firebase
grep -i firebase app.log

# CORS
grep -i cors app.log

# Dernières 100 lignes
tail -100 app.log
```

---

## 🔐 Authentification / JWT

### Obtenir un Token

```bash
# Inscription
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "displayName": "Test User"
  }'

# Connexion
curl -X POST http://localhost:8090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'
```

### Utiliser un Token

```bash
# Remplacer <TOKEN> par votre token
curl -X GET http://localhost:8090/api/conversations \
  -H "Authorization: Bearer <TOKEN>"
```

### Refresh Token

```bash
curl -X POST http://localhost:8090/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "<REFRESH_TOKEN>"
  }'
```

---

## 🌐 Endpoints Utiles

### Health Check

```bash
# Vérifier que l'API répond
curl http://localhost:8090/actuator/health

# Catégories (endpoint public)
curl http://localhost:8090/api/categories

# Activités (endpoint public)
curl http://localhost:8090/api/activities
```

### Avec Authentification

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# Mes conversations
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8090/api/conversations

# Mon profil
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8090/api/users/me

# Mes activités
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8090/api/users/me/activities
```

---

## 🔧 Configuration

### Changer le Port

```bash
# Dans application.properties
server.port=8080

# Ou via ligne de commande
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

### Profils Spring

```bash
# Développement
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Production
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Test
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

### Variables d'Environnement

```bash
# Windows
set DB_PASSWORD=nouveauMotDePasse
mvn spring-boot:run

# Linux/Mac
export DB_PASSWORD=nouveauMotDePasse
mvn spring-boot:run
```

---

## 🐛 Debug

### Mode Debug

```bash
# Debug Maven
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# Puis attacher un debugger sur le port 5005
```

### Logs Verbeux

```bash
# Niveau DEBUG
mvn spring-boot:run -Ddebug

# Logging custom
mvn spring-boot:run -Dlogging.level.org.program.pair=DEBUG
```

### Heap Dump

```bash
# En cas d'erreur mémoire
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps"
```

---

## 📦 Déploiement

### Créer le JAR

```bash
# Build du JAR
mvn clean package -DskipTests

# Le JAR est dans target/
ls target/*.jar
```

### Lancer le JAR

```bash
# Exécuter
java -jar target/Pair-0.0.1-SNAPSHOT.jar

# Avec profil
java -jar target/Pair-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod

# En arrière-plan
nohup java -jar target/Pair-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```

---

## 🔄 Git

### Status

```bash
# Voir les changements
git status

# Voir les fichiers modifiés
git diff

# Logs
git log --oneline -10
```

### Commit

```bash
# Ajouter les fichiers modifiés
git add src/

# Commit
git commit -m "Description des changements"

# Push
git push origin master
```

---

## 📊 Performance

### Temps de Démarrage

```bash
# Mesurer le temps
time mvn spring-boot:run

# Voir dans les logs
grep "Started PairApplication" app.log
```

### Utilisation Mémoire

```bash
# Processus Java
tasklist | findstr "java.exe"

# Détails mémoire (Windows)
wmic process where "name='java.exe'" get ProcessId,WorkingSetSize
```

---

## 🛑 Résolution de Problèmes

### Port 8090 Déjà Utilisé

```bash
# Trouver le processus
netstat -ano | findstr ":8090"

# Tuer le processus (PID de la dernière colonne)
taskkill //F //PID <PID>
```

### Application Ne Démarre Pas

```bash
# Vérifier PostgreSQL
docker ps | grep postgres

# Vérifier les logs
tail -100 app.log | grep -i error

# Tester la connexion DB
psql -h localhost -U pair_user -d pair_db -c "SELECT 1"
```

### Erreur CORS

```bash
# Vérifier la config CORS
grep -A20 "corsConfigurationSource" src/main/java/org/program/pair/config/SecurityConfig.java

# Tester le preflight
curl -v -H "Origin: http://localhost:3000" \
     -H "Access-Control-Request-Method: GET" \
     -X OPTIONS \
     http://localhost:8090/api/conversations
```

---

## 📋 Checklist Démarrage

Avant de démarrer l'application:

- [ ] PostgreSQL est démarré
- [ ] Port 8090 est libre
- [ ] Java 17 installé (`java -version`)
- [ ] Maven installé (`mvn -version`)
- [ ] Variables d'environnement configurées

```bash
# Script de vérification
docker ps | grep postgres && \
netstat -ano | findstr ":8090" && \
java -version && \
mvn -version && \
echo "✅ Prêt à démarrer!"
```

---

## 🎯 Commandes les Plus Utiles

### Workflow Typique

```bash
# 1. Démarrer PostgreSQL
docker start pair-postgres

# 2. Démarrer l'application
mvn spring-boot:run > app.log 2>&1 &

# 3. Vérifier que ça tourne
curl http://localhost:8090/api/categories

# 4. Voir les logs
tail -f app.log

# 5. Arrêter proprement
bash stop-app.sh
```

---

## 📚 Aide

```bash
# Maven help
mvn help:help

# Spring Boot help
mvn spring-boot:help

# Liste des goals Maven
mvn help:describe -Dplugin=org.springframework.boot:spring-boot-maven-plugin

# Version
mvn -version
java -version
```

---

**Document créé**: 2026-06-24  
**Version**: 1.0.0  
**Application**: Pair Social Network
