# Installation pgvector pour PostgreSQL 18.4

## 🔍 Problème Détecté

L'extension pgvector est listée comme disponible mais n'a pas de script d'installation:
```
FEHLER: Erweiterung »vector« hat kein Installationsskript
```

## 📥 Solution: Installer pgvector manuellement

### Option 1: Installation depuis les binaires (Recommandé - Windows)

1. **Télécharger pgvector pour PostgreSQL 18**
   ```
   https://github.com/pgvector/pgvector/releases
   ```
   Chercher: `pgvector-v0.8.3-pg18-windows-x64.zip`

2. **Extraire les fichiers**
   - `vector.dll` → `C:\Program Files\PostgreSQL\18\lib\`
   - `vector--0.8.3.sql` → `C:\Program Files\PostgreSQL\18\share\extension\`
   - `vector.control` → `C:\Program Files\PostgreSQL\18\share\extension\`

3. **Redémarrer PostgreSQL**
   ```cmd
   net stop postgresql-x64-18
   net start postgresql-x64-18
   ```

4. **Activer l'extension**
   ```sql
   psql -U postgres -d pair_db
   CREATE EXTENSION vector;
   ```

### Option 2: Compilation depuis les sources (Advanced)

1. **Installer les outils de build**
   - Visual Studio 2022 avec C++ workload
   - PostgreSQL development headers

2. **Cloner et compiler**
   ```bash
   git clone https://github.com/pgvector/pgvector.git
   cd pgvector
   nmake /F Makefile.win
   nmake /F Makefile.win install
   ```

3. **Redémarrer PostgreSQL et activer**

### Option 3: Utiliser PostgreSQL avec pgvector pré-installé

Alternatives si l'installation est trop complexe:
- **Supabase** (cloud PostgreSQL avec pgvector)
- **Neon** (serverless PostgreSQL avec pgvector)
- **AWS RDS PostgreSQL 15+** (pgvector disponible)

## ✅ Vérification de l'installation

```sql
-- 1. Vérifier que l'extension est disponible
SELECT * FROM pg_available_extensions WHERE name = 'vector';

-- 2. Installer l'extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 3. Vérifier l'installation
\dx vector

-- 4. Tester la création d'une colonne vector
CREATE TABLE test_vector (
    id SERIAL PRIMARY KEY,
    embedding vector(3)
);

INSERT INTO test_vector (embedding) VALUES ('[1,2,3]');

SELECT * FROM test_vector;

-- 5. Nettoyer le test
DROP TABLE test_vector;
```

## 🔧 Si pgvector n'est pas disponible

### Alternative: Continuer sans pgvector pour le développement

Pour la Phase 2, pgvector est utilisé pour la **recherche sémantique**.
Si l'installation est bloquée, options:

#### Option A: Mock les embeddings (développement local)
```java
@Profile("dev")
@Service
public class MockEmbeddingService extends EmbeddingService {
    @Override
    public float[] generateEmbedding(String text) {
        // Retourner un vecteur aléatoire pour dev
        float[] mock = new float[1536];
        Random r = new Random(text.hashCode());
        for (int i = 0; i < 1536; i++) {
            mock[i] = r.nextFloat();
        }
        return mock;
    }
}
```

#### Option B: Utiliser recherche texte full-text PostgreSQL
```sql
-- À la place de vector similarity, utiliser tsvector
ALTER TABLE programs ADD COLUMN search_vector tsvector;

CREATE INDEX idx_programs_search 
ON programs USING gin(search_vector);

-- Mise à jour automatique
CREATE TRIGGER tsvectorupdate 
BEFORE INSERT OR UPDATE ON programs
FOR EACH ROW EXECUTE FUNCTION
  tsvector_update_trigger(search_vector, 'pg_catalog.english', title, description);
```

#### Option C: Elasticsearch / OpenSearch
- Déployer Elasticsearch localement
- Indexer les programmes
- Utiliser les embeddings via Elasticsearch dense_vector

## 📊 Impact sur Phase 2

### Avec pgvector ✅
- Recherche sémantique complète
- Similarité cosinus entre embeddings
- Performance optimale avec ivfflat/hnsw

### Sans pgvector ⚠️
- Recherche par mots-clés uniquement
- Extraction d'intent LLM fonctionne toujours
- Pas de ranking par similarité sémantique
- Peut être ajouté plus tard sans réécriture majeure

## 🎯 Recommandation

1. **Court terme**: Développer Phase 2 sans pgvector
   - Utiliser recherche full-text PostgreSQL
   - Implémenter LlmIntentExtractor
   - Préparer l'architecture pour pgvector

2. **Moyen terme**: Installer pgvector
   - Option binaire Windows (plus simple)
   - Ou migrer vers cloud avec pgvector

3. **Architecture découplée**:
   - Interface `SearchEngine`
   - Implémentations: `PgVectorSearchEngine`, `FullTextSearchEngine`
   - Facile de basculer plus tard

## 📝 État Actuel

- ✅ PostgreSQL 18.4 installé
- ✅ PostGIS activé et fonctionnel
- ⚠️ pgvector disponible mais pas installé
- ✅ Alternative: Full-text search disponible

**Décision**: Continuer Phase 2 avec recherche full-text, architecture prête pour pgvector.
