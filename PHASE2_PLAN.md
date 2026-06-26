# 📋 Phase 2 - Plan d'Implémentation

## 🎯 Objectif
Ajouter recherche intelligente en langage naturel, système de progression, et upload de médias sécurisés.

---

## 📦 Modules à Implémenter

### Module 1: Recherche Sémantique (Priorité 1)
- [ ] Activer et configurer pgvector
- [ ] Ajouter dépendances (WebFlux, OWASP Sanitizer)
- [ ] Créer LlmIntentExtractor (Anthropic Claude)
- [ ] Créer EmbeddingService (OpenAI embeddings)
- [ ] Créer SemanticSearchService
- [ ] Créer SearchController
- [ ] Ajouter colonne embedding aux tables Activity et Program
- [ ] Créer table search_logs
- [ ] Tests de recherche

### Module 2: Système de Progression (Priorité 2)
- [ ] Créer table progressions
- [ ] Créer entité Progression
- [ ] Créer ProgressionService (calcul streak, métriques)
- [ ] Créer ProgressionController
- [ ] DTOs (CreateProgressionRequest, ProgressionEntryDto, etc.)
- [ ] Tests progression

### Module 3: Upload Médias S3 (Priorité 3)
- [ ] Ajouter dépendances (AWS S3, Tika, Thumbnailator)
- [ ] Configurer AWS S3 credentials
- [ ] Créer StorageService (validation, ré-encodage)
- [ ] Créer ProgramMediaService
- [ ] Créer ProgramMediaController
- [ ] Tests upload

### Module 4: Indexation Automatique (Priorité 4)
- [ ] Créer EmbeddingIndexingListener
- [ ] Events JPA (ProgramSavedEvent, ActivitySavedEvent)
- [ ] Mise à jour asynchrone des embeddings
- [ ] Tests indexation

---

## 🔧 Prérequis Techniques

### 1. pgvector Extension
```sql
-- Vérifier si pgvector est disponible
SELECT * FROM pg_available_extensions WHERE name = 'vector';

-- Installer (requiert superuser)
CREATE EXTENSION IF NOT EXISTS vector;

-- Vérifier installation
\dx vector
```

### 2. Nouvelles Dépendances Maven
```xml
<!-- WebFlux pour appels API LLM -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- OWASP HTML Sanitizer -->
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

<!-- AWS S3 SDK -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.0</version>
</dependency>
```

### 3. Variables d'Environnement
```properties
# LLM API (Anthropic Claude)
llm.api-url=https://api.anthropic.com/v1/messages
llm.api-key=${ANTHROPIC_API_KEY}
llm.model=claude-sonnet-4-6

# Embeddings API (OpenAI)
embedding.api-url=https://api.openai.com/v1/embeddings
embedding.api-key=${OPENAI_API_KEY}
embedding.model=text-embedding-3-small

# AWS S3
aws.s3.bucket=${S3_BUCKET_NAME}
aws.s3.region=${AWS_REGION:eu-west-1}
aws.s3.cdn-base-url=${CDN_BASE_URL}
aws.access-key-id=${AWS_ACCESS_KEY_ID}
aws.secret-access-key=${AWS_SECRET_ACCESS_KEY}
```

---

## 📊 Nouvelles Tables SQL

### 1. search_logs
```sql
CREATE TABLE search_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    raw_query TEXT NOT NULL,
    parsed_intent TEXT,
    query_embedding vector(1536),
    results_count INT,
    searched_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_search_logs_user ON search_logs(user_id);
CREATE INDEX idx_search_logs_date ON search_logs(searched_at DESC);
```

### 2. progressions
```sql
CREATE TABLE progressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id UUID NOT NULL REFERENCES programs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(150),
    content TEXT,
    metrics FLOAT[],
    metric_labels TEXT[],
    is_public BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_progression_program FOREIGN KEY (program_id) 
        REFERENCES programs(id) ON DELETE CASCADE,
    CONSTRAINT fk_progression_user FOREIGN KEY (user_id) 
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_progressions_program ON progressions(program_id, created_at DESC);
CREATE INDEX idx_progressions_user ON progressions(user_id, created_at DESC);
```

### 3. Ajouter colonnes embedding
```sql
-- Activities
ALTER TABLE activities 
ADD COLUMN IF NOT EXISTS embedding vector(1536);

CREATE INDEX IF NOT EXISTS idx_activities_embedding 
ON activities USING ivfflat (embedding vector_cosine_ops);

-- Programs
ALTER TABLE programs 
ADD COLUMN IF NOT EXISTS embedding vector(1536);

CREATE INDEX IF NOT EXISTS idx_programs_embedding 
ON programs USING ivfflat (embedding vector_cosine_ops);
```

---

## 🎯 Ordre d'Implémentation

### Étape 1: Activer pgvector (CRITIQUE)
1. Vérifier disponibilité pgvector
2. Installer extension
3. Ajouter colonnes embedding
4. Créer indexes ivfflat

### Étape 2: Ajouter Dépendances Maven
1. Mettre à jour pom.xml
2. Recompiler projet
3. Vérifier pas de conflits

### Étape 3: Module Recherche (Core)
1. Créer package `domain.search`
2. DTOs: SearchRequest, SearchResponse, SearchIntent, SearchResultDto
3. EmbeddingService (OpenAI API)
4. LlmIntentExtractor (Anthropic API)
5. SemanticSearchService
6. SearchController
7. Tests unitaires

### Étape 4: Module Progression
1. Créer package `domain.progression`
2. Entité Progression
3. ProgressionRepository avec queries custom
4. ProgressionService (calcul streak)
5. ProgressionController
6. Tests

### Étape 5: Module Médias
1. Configurer AWS S3
2. StorageService
3. ProgramMediaService
4. ProgramMediaController
5. Tests upload/validation

### Étape 6: Indexation Auto
1. Events Spring (@EventListener)
2. EmbeddingIndexingListener
3. Async processing
4. Tests

---

## ⚠️ Points d'Attention

### Sécurité
- ✅ Validation MIME avec Tika (magic bytes)
- ✅ Ré-encodage images (strip EXIF)
- ✅ HTML sanitization déjà en place (Phase 1)
- ✅ Rate limiting sur endpoints recherche
- ⚠️ API keys en variables d'environnement (JAMAIS dans le code)

### Performance
- ⚠️ Embeddings API: ~500ms par requête
- ⚠️ pgvector ivfflat index: nécessite >1000 rows pour être efficace
- ✅ Async processing pour indexation
- ✅ Caching des embeddings

### Coûts API
- OpenAI embeddings: ~$0.0001 / 1K tokens
- Anthropic Claude: ~$0.003 / 1K tokens (input)
- À monitorer si volume élevé

---

## 🧪 Tests à Créer

### Recherche Sémantique
- [ ] Test extraction intent basique
- [ ] Test extraction intent avec clarification
- [ ] Test génération embedding
- [ ] Test recherche vide (suggestions)
- [ ] Test recherche avec résultats
- [ ] Test filtrage par rayon

### Progression
- [ ] Test création entrée
- [ ] Test calcul streak (jours consécutifs)
- [ ] Test métriques numériques
- [ ] Test visibilité public/privé

### Médias
- [ ] Test upload image valide
- [ ] Test rejet fichier malveillant
- [ ] Test rejet fichier trop volumineux
- [ ] Test ré-encodage JPEG

---

## 📈 Métriques de Succès

- [ ] Recherche sémantique fonctionne en <2s
- [ ] 90%+ des intents correctement extraits
- [ ] 0 fichiers malveillants uploadés
- [ ] Embeddings générés pour 100% des programmes publics
- [ ] Streak progression calculé correctement

---

## 🚀 Prêt à Démarrer

**Prochaine action**: Vérifier et activer pgvector
