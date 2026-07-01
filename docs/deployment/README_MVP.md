# Pair - MVP Social Network

**Version**: 1.0.0-MVP  
**Date**: 2026-06-23  
**Statut**: ✅ Production Ready

---

## 🎯 Qu'est-ce que Pair?

Pair est un **réseau social géolocalisé** pour trouver des partenaires d'activités sportives, culturelles et de loisirs.

**Concept**: "Je veux faire du yoga ce week-end" → Pair trouve les programmes et personnes près de toi.

---

## ✨ Fonctionnalités MVP

### Pour les Utilisateurs
- 🔐 **Authentification** - Inscription, connexion JWT
- 👤 **Profil** - Bio, activités, niveau, géolocalisation
- 🗺️ **Carte Interactive** - Voir utilisateurs et programmes près de soi
- 🎯 **Programmes** - Créer des activités récurrentes ou ponctuelles
- 🔍 **Recherche Intelligente** - "yoga débutant samedi matin" avec LLM
- 💬 **Chat Temps Réel** - WebSocket pour discuter
- 🏆 **Badges** - 17 badges pour gamification
- ⭐ **Recommandations** - Recommander des pairs de confiance
- 📝 **Avis** - Évaluer les programmes avec 5 critères
- 🚨 **Signalements** - Signaler contenu inapproprié

### Pour les Modérateurs
- 🛡️ **Modération** - Traiter les signalements
- 📊 **Dashboard** - Swagger UI pour administration

---

## 🏗️ Architecture Technique

### Stack
- **Backend**: Spring Boot 3.4.1 (Java 17)
- **Database**: PostgreSQL 14+ avec PostGIS
- **Auth**: JWT avec refresh tokens
- **AI**: Claude Sonnet 4.6 (recherche NL)
- **WebSocket**: STOMP
- **API Docs**: SpringDoc OpenAPI 3.0
- **Storage**: Local filesystem (MVP)

### Modules
```
Phase 1 (100%): Auth, Users, Activities, Programs, Map, Chat
Phase 2 (96%):  Search LLM, Progressions, Media
Phase 3 (100%): Badges, Recommendations, Reviews, Reports
Phase 4 (0%):   Notifications, Jobs, Redis, RGPD [Post-MVP]
```

### API
- **72 REST endpoints**
- **1 WebSocket** (/ws)
- **17 tables PostgreSQL**
- **Swagger UI**: http://localhost:8090/swagger-ui/index.html

---

## 🚀 Quick Start

### Prérequis
```bash
- Java 17 (Azul Zulu 17.0.14)
- PostgreSQL 14+ avec PostGIS
- Maven 3.9+
```

### 1. Base de Données
```bash
# Créer database
createdb pair_db

# Activer extensions
psql -d pair_db -c "CREATE EXTENSION postgis;"
psql -d pair_db -c "CREATE EXTENSION pg_trgm;"
psql -d pair_db -c "CREATE EXTENSION \"uuid-ossp\";"
```

### 2. Configuration
Créer `src/main/resources/application-local.properties`:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/pair_db
spring.datasource.username=postgres
spring.datasource.password=<your-password>

jwt.secret=YXByaWNvZGV2YXBwbGljYXRpb25wYWlyYXV0aGVudGljYXRpb25zZWNyZXRrZXk=
llm.api-key=<your-anthropic-api-key>
```

### 3. Build & Run
```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/Pair-0.0.1-SNAPSHOT.jar
```

L'application démarre sur **http://localhost:8090**

### 4. Test
```bash
# Sanity check
curl http://localhost:8090/api/badges

# Swagger UI
open http://localhost:8090/swagger-ui/index.html

# Register
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@pair.com",
    "username": "testuser",
    "password": "Test1234!",
    "firstName": "Test",
    "lastName": "User",
    "displayName": "TestUser"
  }'
```

---

## 📚 Documentation

### Fichiers Clés
- `MVP_DEPLOYMENT_READY.md` - Guide déploiement production
- `PHASE3_VALIDATION.md` - Validation Phase 3
- `CURRENT_STATUS.md` - État global du projet
- `test-phase3.sh` - Script de test Phase 3

### API Endpoints

**Auth** (6)
```
POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/refresh
GET    /api/auth/verify-email
POST   /api/auth/forgot-password
POST   /api/auth/reset-password
```

**Users** (8)
```
GET    /api/users/me
PUT    /api/users/me
GET    /api/users/{id}
PUT    /api/users/me/location
PUT    /api/users/me/preferences
GET    /api/users/search
...
```

**Activities** (5)
```
GET    /api/categories
GET    /api/activities
GET    /api/activities/{id}
...
```

**Programs** (12)
```
POST   /api/programs
GET    /api/programs
GET    /api/programs/{id}
PUT    /api/programs/{id}
DELETE /api/programs/{id}
...
```

**Map** (2)
```
GET    /api/map/users
GET    /api/map/programs
```

**Chat** (7 + WebSocket)
```
GET    /api/conversations
POST   /api/conversations
GET    /api/conversations/{id}/messages
POST   /api/conversations/{id}/messages
WebSocket /ws
...
```

**Search** (1)
```
GET    /api/search?query=yoga&lat=48.8&lng=2.3
```

**Progressions** (5)
```
POST   /api/progressions
GET    /api/progressions
GET    /api/progressions/{id}
...
```

**Media** (3)
```
POST   /api/media/upload
GET    /api/media/{id}
DELETE /api/media/{id}
```

**Badges** (5)
```
GET    /api/badges
GET    /api/badges/me
GET    /api/badges/users/{id}
POST   /api/badges/me/evaluate
...
```

**Recommendations** (7)
```
POST   /api/recommendations
GET    /api/recommendations/received
GET    /api/recommendations/given
...
```

**Reviews** (4)
```
POST   /api/reviews
GET    /api/reviews/programs/{id}
GET    /api/reviews/me
...
```

**Reports** (4)
```
POST   /api/reports
GET    /api/reports/me
GET    /api/reports/pending (MODERATOR)
PUT    /api/reports/{id}/review (MODERATOR)
```

**Total**: 72 endpoints REST + 1 WebSocket

---

## 🗄️ Data Model

### Tables (17)
1. **users** - Utilisateurs avec géolocalisation
2. **categories** - Catégories d'activités (Sport, Musique, Art, Jeux)
3. **activities** - Activités prédéfinies (Tennis, Yoga, etc.)
4. **user_activities** - Activités des users avec niveau
5. **programs** - Programmes créés par users
6. **schedules** - Horaires récurrents
7. **program_media** - Photos/vidéos programmes
8. **conversations** - Conversations chat
9. **conversation_members** - Membres conversations
10. **messages** - Messages chat
11. **badges** - Définitions badges
12. **badge_awards** - Attribution badges
13. **peer_recommendations** - Recommandations pairs
14. **reviews** - Avis programmes
15. **reports** - Signalements
16. **progressions** - Suivi progressions
17. **search_logs** - Logs recherches

### Données Initiales
Les migrations Flyway créent:
- ✅ 4 catégories (Sport, Musique, Art, Jeux)
- ✅ 5 activités de base
- ✅ 17 badges par défaut

---

## 🔒 Sécurité

### Implémenté
- ✅ JWT authentication avec refresh tokens
- ✅ BCrypt password hashing
- ✅ Rate limiting (20 req/min search, 10 req/min upload)
- ✅ Input validation (@Valid Jakarta)
- ✅ HTML sanitization (OWASP)
- ✅ MIME type validation
- ✅ SQL injection protection (JPA)
- ✅ CORS configured
- ✅ Role-based access (@PreAuthorize)

### Recommandations Production
- 🔐 HTTPS obligatoire
- 🔐 Secret JWT production forte (256 bits)
- 🔐 Environment variables pour secrets
- 🔐 Firewall database
- 🔐 Monitoring logs
- 🔐 Backup automatiques

---

## 📊 Tests

### Scripts Disponibles
```bash
# Phase 1
./test-activities-complete.sh
./test-programs.sh
./test-map.sh
./test-chat.sh

# Phase 2
./test-search.sh
./test-progressions.sh

# Phase 3
./test-phase3.sh
```

### Tests Manuels Validés ✅
- ✅ Registration + Login
- ✅ CRUD Programmes
- ✅ Recherche géographique
- ✅ Chat WebSocket
- ✅ Badges system
- ✅ Recommendations
- ✅ Reviews
- ✅ Reports

---

## 🚀 Déploiement

Voir **MVP_DEPLOYMENT_READY.md** pour guide complet.

### Résumé
```bash
# 1. Build
mvn clean package -DskipTests

# 2. Configure environment variables
export DB_USER=pair_user
export DB_PASSWORD=<secret>
export JWT_SECRET=<secret>
export ANTHROPIC_API_KEY=<key>

# 3. Run
java -jar target/Pair-0.0.1-SNAPSHOT.jar

# 4. Nginx reverse proxy + SSL
# 5. Monitoring
```

---

## 📈 Statistiques

### Code Quality
- **Fichiers Java**: 200
- **Lignes de code**: ~18,000
- **Compilation**: ✅ BUILD SUCCESS
- **Architecture**: Clean (DDD, layered)
- **DTOs**: 100% des endpoints
- **Exceptions**: Centralisées

### Performance
- **Index DB**: 25+ optimisés
- **Pagination**: Toutes les listes
- **Rate limiting**: Endpoints critiques
- **Connection pooling**: HikariCP
- **Caching**: En mémoire (Phase 4: Redis)

---

## 🛠️ Développement

### Structure du Projet
```
src/main/java/org/program/pair/
├── config/          # Configuration Spring
├── domain/          # Business logic
│   ├── auth/
│   ├── user/
│   ├── activity/
│   ├── program/
│   ├── map/
│   ├── chat/
│   ├── search/
│   ├── progression/
│   ├── media/
│   ├── badge/
│   ├── recommendation/
│   ├── review/
│   └── report/
├── repository/      # JPA repositories
├── shared/          # Shared utilities
│   ├── dto/
│   ├── exception/
│   ├── security/
│   └── email/
└── PairApplication.java
```

### Ajout d'un Endpoint
1. Créer DTO request/response dans `domain/<module>/dto/`
2. Ajouter méthode service dans `<Module>Service.java`
3. Ajouter endpoint dans `<Module>Controller.java`
4. Annoter avec `@Operation` (Swagger)
5. Valider avec `@Valid`
6. Tester manuellement

---

## 🔮 Roadmap

### Phase 4 (Post-MVP)
- 📧 Notifications push (Firebase)
- 📅 Jobs planifiés (Quartz)
- 🚀 Redis caching
- 🔒 RGPD complet
- 📊 Analytics (Mixpanel)
- 🔍 Monitoring (Prometheus)

### Améliorations Futures
- 🧠 pgvector pour recherche sémantique
- ☁️ S3 pour médias
- 🌐 Internationalisation (i18n)
- 📱 API mobile optimisée
- 🎨 Thèmes utilisateur
- 🔔 Notifications in-app en temps réel

---

## 📞 Support

### Issues
- GitHub: https://github.com/pair/issues
- Email: support@pair.app

### Documentation
- API Docs: `/swagger-ui/index.html`
- OpenAPI Spec: `/v3/api-docs`
- Guides: `/docs/`

---

## 📄 Licence

MIT License - Voir LICENSE.md

---

## 🎉 Remerciements

Application développée avec:
- Spring Boot
- PostgreSQL + PostGIS
- Anthropic Claude
- Swagger/OpenAPI
- Docker
- GitHub

---

**Pair MVP - Connectons les gens autour d'activités! 🚀✨**
