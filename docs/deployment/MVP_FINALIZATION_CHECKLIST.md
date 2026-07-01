# ✅ MVP Finalization Checklist

**Objectif**: Préparer l'application pour le déploiement production  
**Durée estimée**: 4 heures  
**Date**: 2026-06-23

---

## 🎯 Plan d'Action

### Phase 1: Tests Finaux (1.5h)

#### Module 3: Upload Médias (30min)
- [ ] Test upload image
- [ ] Test validation MIME
- [ ] Test taille max (10MB)
- [ ] Test types supportés (JPEG, PNG, WebP)
- [ ] Test serving fichiers
- [ ] Test suppression fichiers

#### Module 4: Indexation (30min)
- [ ] Test stats endpoint
- [ ] Test reindex batch
- [ ] Test auto-indexation (listener)
- [ ] Test recherche après création programme

#### Régression Tests (30min)
- [ ] Test authentification
- [ ] Test programmes CRUD
- [ ] Test chat
- [ ] Test progressions
- [ ] Test recherche

---

### Phase 2: Bug Fixes (1h)

#### Priorité Haute
- [ ] Fix timeout recherche "yoga" (si reproductible)
- [ ] Valider tous les endpoints retournent JSON valide
- [ ] Vérifier gestion erreurs cohérente

#### Priorité Moyenne
- [ ] Logs cleanup (remove debug logs)
- [ ] Error messages en français
- [ ] Validation messages clairs

---

### Phase 3: Sécurité & Performance (1h)

#### Rate Limiting (30min)
- [ ] Ajouter @RateLimit sur /api/search
- [ ] Ajouter @RateLimit sur /api/media/upload
- [ ] Configurer limites raisonnables

#### Sécurité (30min)
- [ ] Changer JWT secret par défaut
- [ ] Documenter changement obligatoire production
- [ ] Vérifier CORS configuration
- [ ] Valider toutes validations Jakarta actives

---

### Phase 4: Documentation (1.5h)

#### API Documentation (1h)
- [ ] Ajouter Swagger/OpenAPI
- [ ] Documenter tous les endpoints
- [ ] Exemples de requêtes/réponses
- [ ] Codes d'erreur

#### README Final (30min)
- [ ] Quick start guide
- [ ] Architecture overview
- [ ] API endpoints list
- [ ] Deployment instructions
- [ ] Environment variables

---

## 📝 Détails par Tâche

### 1. Tests Module 3 (Upload Médias)

**Script**: `test-media.sh`

```bash
#!/bin/bash
# Test upload image
# Test validation MIME
# Test serving
# Test delete
```

**Validation**:
- ✅ Upload réussi avec image valide
- ✅ Rejet fichier > 10MB
- ✅ Rejet type non supporté
- ✅ Fichier accessible via URL
- ✅ Suppression fonctionne

---

### 2. Tests Module 4 (Indexation)

**Validations**:
- ✅ Stats retourne counts corrects
- ✅ Reindex met à jour search_vector
- ✅ Nouveau programme indexé automatiquement
- ✅ Recherche trouve nouveau programme

---

### 3. Rate Limiting

**Implémentation**:

```java
// Option 1: Bucket4j (simple, in-memory)
@RateLimit(value = 10, timeWindow = 60) // 10 req/min
public SearchResponse search(...)

// Option 2: Manual (custom annotation)
@Component
public class RateLimitInterceptor {
    // Implement simple in-memory rate limiting
}
```

**Endpoints à limiter**:
- POST /api/search → 20 req/min
- POST /api/media/upload → 10 req/min
- POST /api/auth/register → 5 req/min
- POST /api/auth/login → 10 req/min

---

### 4. Swagger/OpenAPI

**Dépendance**:
```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>2.3.0</version>
</dependency>
```

**Configuration**:
```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI pairOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Pair API")
                .description("Réseau social pour activités sportives et culturelles")
                .version("1.0.0"));
    }
}
```

**Accès**: http://localhost:8090/swagger-ui.html

---

## 🚀 Production Checklist

### Avant Déploiement
- [ ] JWT_SECRET changé (CRITIQUE!)
- [ ] Database backup effectué
- [ ] Variables environnement configurées
- [ ] SSL/TLS configuré
- [ ] Reverse proxy (Nginx) configuré
- [ ] Monitoring configuré
- [ ] Logs rotation configurée

### Configuration Production
```properties
# Production
spring.profiles.active=production
spring.devtools.restart.enabled=false
logging.level.root=INFO
logging.level.org.program.pair=INFO

# Security
jwt.secret=${JWT_SECRET}  # MUST CHANGE!

# Database
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
```

### Healthchecks
- [ ] /actuator/health retourne 200
- [ ] Database connectée
- [ ] Storage accessible
- [ ] WebSocket fonctionnel

---

## 📊 Critères de Succès MVP

### Fonctionnel
- ✅ Tous les endpoints Phase 1 & 2 fonctionnels
- ✅ Tests automatisés passent
- ✅ Pas d'erreurs 500 non gérées
- ✅ Validation correcte des inputs

### Performance
- ✅ Temps réponse < 500ms (95% requêtes)
- ✅ Recherche < 1s
- ✅ Upload image < 5s
- ✅ WebSocket latence < 100ms

### Sécurité
- ✅ JWT sécurisé
- ✅ Passwords hashed (BCrypt)
- ✅ MIME validation
- ✅ HTML sanitization
- ✅ Rate limiting actif

### Stabilité
- ✅ Pas de memory leaks
- ✅ Connection pool configuré
- ✅ Exception handling complet
- ✅ Logs appropriés

---

## 📅 Timeline

### Jour 1 (2h)
- **Matin** (1h): Tests Module 3 & 4
- **Après-midi** (1h): Bug fixes

### Jour 2 (2h)
- **Matin** (1h): Rate limiting + Security
- **Après-midi** (1h): Documentation API

### Résultat
- ✅ MVP testé et documenté
- ✅ Prêt pour déploiement
- ✅ Production ready

---

## 🎯 Post-MVP (Semaine suivante)

### Déploiement
1. Setup serveur production
2. Configure database
3. Deploy application
4. Configure reverse proxy
5. Enable SSL
6. Monitoring

### Validation Production
- Test tous les endpoints
- Load testing (optionnel)
- Security scan
- Performance monitoring

### Communication
- Annonce lancement
- Documentation utilisateur
- Support setup

---

## ✅ Completion Criteria

**MVP est prêt quand**:
- [ ] Tous les tests passent
- [ ] Documentation API complète
- [ ] Rate limiting actif
- [ ] Sécurité validée
- [ ] Performance acceptable
- [ ] Deployment guide à jour

**Status actuel**: En cours ⏳  
**Status cible**: Prêt pour production ✅

---

## 📝 Notes

### Priorités
1. **Sécurité** > Tout le reste
2. **Stabilité** > Performance
3. **Documentation** > Features bonus

### MVP Philosophy
- ✅ Fonctionnel avant optimal
- ✅ Sécurisé avant performant
- ✅ Déployé avant parfait

### Après MVP
- Phase 3 (Trust layer)
- Optimisations
- Monitoring avancé
- Scaling

---

**Let's ship it!** 🚀
