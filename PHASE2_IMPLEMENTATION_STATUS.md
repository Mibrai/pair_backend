# 🚀 Phase 2 - État d'Implémentation

**Date de début**: 2026-06-23
**Status**: 🟡 En cours

---

## 📊 Vue d'Ensemble

### Décision Architecture: Full-Text Search temporaire

**Contexte**: pgvector disponible mais non installé sur PostgreSQL 18.4

**Solution adoptée**:
1. ✅ Implémenter recherche avec **PostgreSQL Full-Text Search** (tsvector)
2. ✅ Maintenir architecture découplée via interface `SearchEngine`
3. 🔄 Migration vers pgvector quand disponible (sans réécriture majeure)

**Avantages**:
- ✅ Pas de dépendance externe bloquante
- ✅ Performance correcte pour <100k programmes
- ✅ Toutes les features LLM (intent extraction) fonctionnent
- ✅ Migration pgvector simple plus tard

---

## ✅ Module 1: Infrastructure Recherche

### Base de Données
- [x] Table `search_logs` créée
- [x] Colonne `search_vector` ajoutée à `programs`
- [x] Index GIN sur `programs.search_vector`
- [x] Triggers auto-update pour indexation full-text
- [x] Support stemming français
- [ ] ~~pgvector installation~~ (reporté)
- [ ] ~~Colonnes embedding~~ (reporté)

### Code Backend
- [ ] Package `domain.search` créé
- [ ] DTOs: SearchRequest, SearchResponse, SearchIntent, SearchResultDto
- [ ] Interface `SearchEngine`
- [ ] `FullTextSearchEngine` implementation
- [ ] `LlmIntentExtractor` (Anthropic Claude)
- [ ] `SemanticSearchService`
- [ ] `SearchController`
- [ ] Tests unitaires

---

## ⏳ Module 2: Système de Progression

### Base de Données
- [ ] Table `progressions` à créer
- [ ] Indexes (program_id, user_id, created_at)

### Code Backend
- [ ] Entité `Progression`
- [ ] `ProgressionRepository`
- [ ] `ProgressionService` (calcul streak)
- [ ] `ProgressionController`
- [ ] DTOs (6 au total)
- [ ] Tests

---

## ⏳ Module 3: Upload Médias S3

### Configuration
- [ ] Dépendances Maven (AWS S3, Tika, Thumbnailator)
- [ ] Variables environnement AWS
- [ ] Bucket S3 configuré

### Code Backend
- [ ] `StorageService` (validation MIME, ré-encodage)
- [ ] `ProgramMediaService`
- [ ] `ProgramMediaController`
- [ ] Tests validation / upload

---

## ⏳ Module 4: Indexation Automatique

- [ ] `ProgramSavedEvent`
- [ ] `EmbeddingIndexingListener` (full-text pour l'instant)
- [ ] Async processing
- [ ] Tests

---

## 📦 Dépendances Maven Ajoutées

```xml
<!-- Phase 2 Dependencies -->

<!-- WebFlux pour appels API LLM -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- OWASP HTML Sanitizer (déjà en Phase 1) -->
<dependency>
    <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
    <artifactId>owasp-java-html-sanitizer</artifactId>
    <version>20220608.1</version>
</dependency>

<!-- Apache Tika pour validation MIME -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.1</version>
</dependency>

<!-- Thumbnailator pour ré-encodage images -->
<dependency>
    <groupId>net.coobird</groupId>
    <artifactId>thumbnailator</artifactId>
    <version>0.4.20</version>
</dependency>

<!-- AWS S3 SDK (optionnel si cloud storage utilisé) -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.0</version>
</dependency>
```

**Status**: ⏳ À ajouter au pom.xml

---

## 🔑 Variables d'Environnement Requises

```properties
# LLM API (Anthropic Claude)
llm.api-url=https://api.anthropic.com/v1/messages
llm.api-key=${ANTHROPIC_API_KEY}
llm.model=claude-sonnet-4-6

# Option: OpenAI pour embeddings (quand pgvector disponible)
# embedding.api-url=https://api.openai.com/v1/embeddings
# embedding.api-key=${OPENAI_API_KEY}
# embedding.model=text-embedding-3-small

# AWS S3 (si upload médias cloud)
# aws.s3.bucket=${S3_BUCKET_NAME}
# aws.s3.region=${AWS_REGION:eu-west-1}
# aws.s3.cdn-base-url=${CDN_BASE_URL}
# aws.access-key-id=${AWS_ACCESS_KEY_ID}
# aws.secret-access-key=${AWS_SECRET_ACCESS_KEY}
```

**Status**: ⏳ À configurer

---

## 🎯 Prochaines Actions

### Immédiat (Session en cours)
1. [ ] Ajouter dépendances Maven Phase 2
2. [ ] Créer package `domain.search`
3. [ ] Implémenter DTOs de recherche
4. [ ] Créer `LlmIntentExtractor` (Anthropic)
5. [ ] Créer `FullTextSearchEngine`
6. [ ] Créer `SemanticSearchService`
7. [ ] Créer `SearchController`
8. [ ] Tests recherche basique

### Court Terme
1. [ ] Implémenter système Progression
2. [ ] Tests complets recherche
3. [ ] Documentation API

### Moyen Terme
1. [ ] Upload médias (si S3 disponible)
2. [ ] Migration vers pgvector (si installé)
3. [ ] Indexation automatique

---

## 📈 Métriques de Succès Phase 2

### Module Recherche
- [ ] Intent extraction fonctionne >80% cas
- [ ] Temps réponse <2s
- [ ] Clarifications pertinentes
- [ ] Suggestions alternatives utiles

### Module Progression
- [ ] Streak calculé correctement
- [ ] Métriques sauvegardées
- [ ] Visibilité public/privé

### Module Médias
- [ ] 100% fichiers validés (MIME)
- [ ] Images ré-encodées (strip EXIF)
- [ ] 0 fichier malveillant uploadé

---

## ⚠️ Points d'Attention

### API Keys
- ⚠️ **CRITIQUE**: API keys en variables d'environnement UNIQUEMENT
- ❌ JAMAIS dans le code source
- ✅ Utiliser `.env` local (gitignored)

### Coûts API
- Anthropic Claude Sonnet: ~$3/million tokens input
- OpenAI embeddings (futur): ~$0.1/million tokens
- À monitorer si volume élevé

### Performance
- Full-text search: ~50-200ms pour <100k rows
- LLM API call: ~500-1500ms
- Total: ~2s acceptable pour recherche

### Sécurité
- ✅ HTML sanitization (Phase 1)
- ✅ MIME validation (Tika)
- ✅ Image ré-encodage (strip EXIF)
- ⚠️ Rate limiting à ajouter sur `/api/search`

---

## 🔄 Comparaison: Full-Text vs pgvector

| Feature | Full-Text (actuel) | pgvector (futur) |
|---------|-------------------|------------------|
| Recherche par mots-clés | ✅ Excellent | ✅ Bon |
| Recherche sémantique | ⚠️ Limité | ✅ Excellent |
| Synonymes | ⚠️ Stemming uniquement | ✅ Embeddings |
| Performance <10k rows | ✅ Très rapide | ✅ Rapide |
| Performance >100k rows | ⚠️ Moyen | ✅ Très rapide (HNSW) |
| Setup complexity | ✅ Natif PostgreSQL | ⚠️ Extension externe |
| Migration effort | - | ✅ Faible (interface) |

**Décision**: Full-text suffit pour Phase 2 MVP, pgvector améliorera l'expérience plus tard.

---

## 📚 Documentation Créée

- [x] `PHASE2_PLAN.md` - Plan d'implémentation
- [x] `PGVECTOR_INSTALLATION.md` - Guide installation pgvector
- [x] `PHASE2_IMPLEMENTATION_STATUS.md` - État actuel (ce fichier)
- [x] `SQLHistory/08_setup_fulltext_search.sql` - Setup BDD
- [ ] Guide API recherche
- [ ] Tests documentation

---

## 🎉 Ready to Code!

**Prochaine étape**: Ajouter dépendances Maven et commencer Module 1 (Recherche)
