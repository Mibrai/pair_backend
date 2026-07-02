# 🌐 Pair - Social Network Application

**Réseau social de proximité pour partager des activités et rencontrer des personnes partageant vos passions.**

[![Status](https://img.shields.io/badge/Status-Production%20Ready-success)]()
[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)]()

---

## 🚀 Quick Start

### Backend (5 minutes)

```bash
# 1. PostgreSQL
docker start pair-postgres

# 2. Application
mvn spring-boot:run

# 3. Vérifier
curl http://localhost:8090/api/categories
```

### Frontend (5 minutes)

```bash
# 1. Lire la doc
cat FRONTEND_QUICKSTART.md

# 2. Copier la config
cp frontend-config.json src/config/

# 3. Créer service API
# Voir FRONTEND_QUICKSTART.md pour le code
```

**Tout fonctionne!** ✅

---

## 📚 Documentation

### Pour Démarrer

| Document | Description |
|----------|-------------|
| **`DOCUMENTATION_INDEX.md`** ⭐ | Index complet (commencer ici!) |
| **`RESEND_QUICKSTART.md`** ⭐ | Configuration email Resend (3 étapes) |
| **`FRONTEND_QUICKSTART.md`** ⭐ | Frontend en 5 minutes |
| **`COMMANDES_UTILES.md`** ⭐ | Toutes les commandes backend |
| `frontend-config.json` | Configuration complète |
| `api-endpoints.md` | 52 endpoints documentés |

### Guides Complets

- `docs/guides/EMAIL_CONFIGURATION.md` - Configuration email Resend (détaillé)
- `docs/deployment/ENVIRONMENT_VARIABLES.md` - Variables d'environnement
- `FRONTEND_SETUP.md` - Setup React/Vue/Angular (745 lignes)
- `AUTHENTICATION_GUIDE.md` - JWT détaillé
- `DEPLOYMENT_GUIDE.md` - Déploiement production

### Troubleshooting

- `docs/troubleshooting/SMTP_TIMEOUT_FIX.md` - Erreurs SMTP (✅ Résolu)
- `CORS_FIX.md` - Erreurs CORS (✅ Résolu)
- `FIREBASE_FIX.md` - Firebase optionnel (✅ Résolu)
- `REDIS_FIX.md` - Redis optionnel (✅ Résolu)

**Voir `DOCUMENTATION_INDEX.md` pour la liste complète.**

---

## 🎯 Fonctionnalités

### Phase 1 & 2 (✅ Complètes)

- ✅ **Authentification** JWT avec refresh tokens
- ✅ **Profils utilisateurs** avec avatars
- ✅ **Activités** recherchables (sports, musique, art, jeux...)
- ✅ **Programmes** avec créneaux horaires
- ✅ **Carte interactive** avec géolocalisation
- ✅ **Chat temps réel** via WebSocket
- ✅ **Recherche intelligente** multi-entités
- ✅ **Système progression** avec métriques
- ✅ **Upload médias** (images, vidéos, PDF)
- ✅ **Notifications** (in-app + email)

### Phase 3 & 4 (Planifiées)

- ⏳ Badges & gamification
- ⏳ Recommandations entre pairs
- ⏳ Système d'avis
- ⏳ Modération
- ⏳ Push notifications (Firebase)
- ⏳ Cache distribué (Redis)

---

## 🛠️ Stack Technique

### Backend

- **Java 17** - Language
- **Spring Boot 4.1.0** - Framework
- **PostgreSQL 16** - Database (avec PostGIS)
- **JWT** - Authentification
- **WebSocket/STOMP** - Chat temps réel
- **Flyway** - Migrations DB
- **Maven** - Build tool

### Optionnel (Phase 4)

- **Redis** - Cache & rate limiting
- **Firebase** - Push notifications
- **Quartz** - Jobs planifiés

### Frontend (Compatible)

- React, Vue, Angular, Next.js...
- Axios ou Fetch
- STOMP.js pour WebSocket

---

## 📊 Architecture

```
src/main/java/org/program/pair/
├── config/              Configuration (Security, CORS, WebSocket)
├── domain/
│   ├── auth/           Authentification & JWT
│   ├── user/           Gestion utilisateurs
│   ├── activity/       Activités & catégories
│   ├── program/        Programmes & créneaux
│   ├── map/            Carte & géolocalisation
│   ├── chat/           Chat temps réel
│   ├── search/         Recherche intelligente
│   ├── progression/    Système progression
│   ├── media/          Upload & stockage fichiers
│   └── notification/   Notifications
├── repository/         Spring Data JPA
├── shared/
│   ├── security/       JWT filters & providers
│   ├── email/          Service email
│   └── exception/      Gestion erreurs
└── PairApplication.java
```

---

## 🌐 API Endpoints

### Public (Sans JWT)

```
GET  /api/categories                ✅ Public
GET  /api/activities                ✅ Public
POST /api/auth/register             ✅ Public
POST /api/auth/login                ✅ Public
```

### Authentifié (Avec JWT)

```
GET  /api/users/me                  🔒 Profile
GET  /api/conversations             🔒 Chat
GET  /api/programs                  🔒 Programmes
GET  /api/map/users                 🔒 Carte
POST /api/search                    🔒 Recherche
```

**Total**: 52 endpoints (voir `api-endpoints.md`)

---

## 🧪 Tests

```bash
# Tests automatisés
bash test-conversations.sh
bash test-activities-complete.sh
bash test-map.sh
bash test-programs.sh
bash test-search.sh

# Test rapide auth
bash quick-test.sh
```

---

## 🔐 Sécurité

- ✅ **JWT** avec refresh tokens
- ✅ **CORS** configuré
- ✅ **CSRF** protection
- ✅ **Rate limiting**
- ✅ **SQL injection** prevention (JPA)
- ✅ **XSS** protection (OWASP sanitizer)
- ✅ **Password hashing** (BCrypt cost 12)
- ✅ **HTTPS** ready

---

## 📦 Installation

### Prérequis

- Java 17
- Maven 3.8+
- PostgreSQL 16+ (avec PostGIS)
- Docker (optionnel mais recommandé)

### Setup PostgreSQL

```bash
# Avec Docker (recommandé)
docker run -d --name pair-postgres \
  -e POSTGRES_USER=pair_user \
  -e POSTGRES_PASSWORD=Pair2026! \
  -e POSTGRES_DB=pair_db \
  -p 5432:5432 \
  postgis/postgis:16-3.4

# Extensions
docker exec -it pair-postgres psql -U pair_user -d pair_db \
  -c "CREATE EXTENSION IF NOT EXISTS postgis;"
```

### Build & Run

```bash
# Compilation
mvn clean compile

# Lancer l'application
mvn spring-boot:run

# Ou avec le JAR
mvn clean package
java -jar target/Pair-0.0.1-SNAPSHOT.jar
```

---

## 🔧 Configuration

### Variables d'Environnement

```properties
# Database
DB_USER=pair_user
DB_PASSWORD=Pair2026!

# JWT
JWT_SECRET=<base64-encoded-secret>

# Email (optionnel)
SPRING_MAIL_USERNAME=<smtp-user>
SPRING_MAIL_PASSWORD=<smtp-password>

# LLM API (Phase 2)
ANTHROPIC_API_KEY=<api-key>

# Firebase (optionnel Phase 4)
FIREBASE_ENABLED=false
FIREBASE_CREDENTIALS_PATH=

# Redis (optionnel Phase 4)
REDIS_ENABLED=false
```

Voir `application.properties` pour tous les paramètres.

---

## 🚀 Déploiement

Voir `DEPLOYMENT_GUIDE.md` pour:
- Configuration production
- SSL/HTTPS
- Docker deployment
- Cloud deployment (AWS, Azure, GCP)
- Monitoring
- Backups

---

## 🤝 Contribution

### Structure des Commits

```
<type>: <description>

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

**Types**: `feat`, `fix`, `docs`, `refactor`, `test`

---

## 📈 Status du Projet

- ✅ **Phase 1**: Complète (100%)
- ✅ **Phase 2**: Complète (96%)
- ⏳ **Phase 3**: Planifiée (0%)
- ⏳ **Phase 4**: Planifiée (0%)

**Voir `CURRENT_STATUS.md` pour les détails.**

---

## 🐛 Support

### En Cas de Problème

1. Vérifier `DOCUMENTATION_INDEX.md`
2. Chercher dans les guides troubleshooting
3. Vérifier les logs: `tail -f app.log`
4. Tester avec les scripts: `bash test-*.sh`

### Erreurs Courantes

- **403 Forbidden**: JWT manquant → `AUTHENTICATION_GUIDE.md`
- **CORS Error**: Backend arrêté → `CORS_FIX.md`
- **Redis Error**: Déjà résolu → `REDIS_FIX.md`
- **Firebase Error**: Déjà résolu → `FIREBASE_FIX.md`

---

## 📄 License

Propriétaire - Tous droits réservés

---

## 👥 Équipe

Développé avec ❤️ et Claude Sonnet 4.5

---

## 📞 Contact

Pour toute question, consulter `DOCUMENTATION_INDEX.md` ou créer un issue avec les logs.

---

**Version**: 1.0.0  
**Date**: 2026-06-24  
**Status**: ✅ Production Ready
