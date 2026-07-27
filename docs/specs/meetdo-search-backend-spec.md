# meetDo — Recherche sémantique gratuite et trilingue : BACKEND
## Spécification d'implémentation pour Claude Code

> **Alignement** : cette spec suppose l'état du projet décrit dans
> `MEETDO_IMPLEMENTATION.md` (25 juillet 2026) — namespace `org.program.pair`,
> PostgreSQL + PostGIS, Flyway à **V43**, 123 endpoints, hébergement Railway.
> Elle **remplace** toute version antérieure de la spec recherche sémantique
> qui supposait un client React/Mapbox — l'app réelle est en Flutter (voir
> le document frontend séparé).
>
> **Objectif** : supprimer les deux dépendances payantes du pipeline de
> recherche (API Anthropic pour l'intention, API OpenAI pour les
> embeddings), les remplacer par un pipeline **100% local et gratuit**
> couvrant **français, anglais et allemand**, sans casser le contrat
> d'API existant côté client.
>
> **Contrainte de compatibilité** : les DTOs `SearchIntent` et
> `SearchResponse` déjà exposés par `/search` ne changent pas de forme —
> seule leur méthode de production change. Le contrat OpenAPI
> (`/v3/api-docs`) reste stable, aucune régression pour le client Flutter.

---

## 1. Où ça se branche dans le code existant

D'après la structure documentée, la recherche vit dans le groupe
d'endpoints `search` (5 routes : `/search`, `/search/popular`,
`/search/recent`, et deux autres non détaillées dans le document de
référence — à vérifier dans le code avant de commencer, ne pas les
dupliquer). Le groupe `indexation` (5 endpoints, « administration de
l'index de recherche ») est probablement l'emplacement le plus cohérent
pour exposer le déclenchement du backfill d'embeddings décrit en Partie 5
— **vérifier ce groupe avant de créer de nouveaux endpoints
d'administration**, il existe peut-être déjà une route réutilisable.

Package cible pour les nouvelles classes, par cohérence avec le namespace
existant :
```
org.program.pair.search.embedding.LocalEmbeddingService
org.program.pair.search.embedding.SentenceEmbeddingTranslator
org.program.pair.search.intent.RuleBasedIntentExtractor
```
(adapter au découpage réel du package `search`/`recherche` s'il existe déjà
sous un autre nom — vérifier avant de créer une nouvelle arborescence).

---

## 2. Principe général du remplacement

```
AVANT (payant)
  Texte → API Anthropic (intention) → API OpenAI (embedding 1536d) → pgvector

APRÈS (gratuit, local, trilingue)
  Texte → RuleBasedIntentExtractor (FR/EN/DE, sans réseau)
        → LocalEmbeddingService (ONNX embarqué, 384d, multilingue nativement)
        → pgvector (inchangé)
```

Deux renoncements assumés :

- **Pas de compréhension du langage naturel libre.** L'extraction de
  niveau/format/horaire/rayon devient un système de règles et de
  mots-clés FR/EN/DE, pas un LLM génératif. Couvre bien les phrases
  directes, moins bien les formulations très détournées ou un mélange de
  langues dans une même phrase.
- **Le matching sémantique, lui, reste réel et déjà nativement
  trilingue** — le modèle d'embeddings comprend le sens, pas seulement les
  mots, et fonctionne indifféremment quelle que soit la langue de la
  requête ou celle du contenu en base.

---

## 3. Le modèle d'embeddings local (ONNX + DJL)

### 3.1 Choix du modèle

**`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`**

- Couvre nativement français, anglais, allemand (et 47 autres langues)
- 384 dimensions (vs 1536 pour OpenAI — plus léger, largement suffisant)
- ~120 Mo en version quantisée INT8 (recommandée)
- Licence Apache 2.0, libre de redistribution
- CPU uniquement, pas de GPU requis

### 3.2 Dépendances Maven

```xml
<dependency>
  <groupId>ai.djl</groupId>
  <artifactId>api</artifactId>
  <version>0.31.1</version>
</dependency>
<dependency>
  <groupId>ai.djl.onnxruntime</groupId>
  <artifactId>onnxruntime-engine</artifactId>
  <version>0.31.1</version>
</dependency>
<dependency>
  <groupId>ai.djl.huggingface</groupId>
  <artifactId>tokenizers</artifactId>
  <version>0.31.1</version>
</dependency>
```

### 3.3 Export du modèle — étape locale, hors JAR, une seule fois

```bash
pip install optimum[exporters] onnx onnxruntime --quiet
optimum-cli export onnx \
  --model sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2 \
  --task feature-extraction \
  --optimize O2 \
  src/main/resources/models/embedding/

python -c "
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(
    'src/main/resources/models/embedding/model.onnx',
    'src/main/resources/models/embedding/model_quantized.onnx',
    weight_type=QuantType.QInt8
)
"
```

Résultat à committer :
```
src/main/resources/models/embedding/
├── model_quantized.onnx     (~120 Mo)
├── tokenizer.json
├── tokenizer_config.json
└── special_tokens_map.json
```

> ⚠️ **Git LFS NON SUPPORTÉ PAR RAILWAY — confirmé, ne pas emprunter cette
> voie.** Railway clone le repo au build mais ne résout jamais les
> pointeurs Git LFS : seul le pointeur texte (quelques octets) est
> récupéré, pas le contenu binaire réel. Committer le modèle via LFS
> ferait planter `LocalEmbeddingService` au démarrage (fichier "modèle"
> en réalité vide de sens).
>
> **Solution retenue : téléchargement au premier démarrage + volume
> persistant**, à l'image de ce qui a déjà été fait pour `postgres_db`.
>
> 1. Héberger `model_quantized.onnx` + les fichiers de tokenizer sur un
>    stockage externe simple : une **GitHub Release** du repo backend
>    (accepte des fichiers jusqu'à 2 Go, pas de LFS nécessaire) ou un
>    bucket **Cloudflare R2**.
> 2. Attacher un **volume Railway** au service backend (`pair_backend_service`
>    ou son nom réel), monté par exemple sur `/app/models` — même geste que
>    pour le volume `postgres_db-volume` déjà en place.
> 3. Au démarrage, `LocalEmbeddingService.init()` vérifie si le modèle est
>    déjà présent sur ce chemin de volume ; sinon il le télécharge une
>    seule fois, puis le réutilise à chaque redémarrage suivant sans
>    retéléchargement.

```java
@PostConstruct
public void init() throws ModelException, IOException {
    Path modelDir = Paths.get("/app/models/embedding");

    if (!Files.exists(modelDir.resolve("model_quantized.onnx"))) {
        log.info("Modèle absent du volume — téléchargement initial...");
        downloadModelFiles(modelDir);
    }

    Criteria<String, float[]> criteria = Criteria.builder()
        .setTypes(String.class, float[].class)
        .optModelPath(modelDir)
        .optEngine("OnnxRuntime")
        .optTranslator(new SentenceEmbeddingTranslator())
        .build();

    this.model = criteria.loadModel();
    this.predictor = model.newPredictor();
}

private void downloadModelFiles(Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    Map<String, String> files = Map.of(
        "model_quantized.onnx", modelBaseUrl + "/model_quantized.onnx",
        "tokenizer.json", modelBaseUrl + "/tokenizer.json",
        "tokenizer_config.json", modelBaseUrl + "/tokenizer_config.json",
        "special_tokens_map.json", modelBaseUrl + "/special_tokens_map.json"
    );
    for (var entry : files.entrySet()) {
        try (InputStream in = URI.create(entry.getValue()).toURL().openStream()) {
            Files.copy(in, targetDir.resolve(entry.getKey()),
                StandardCopyOption.REPLACE_EXISTING);
        }
    }
    log.info("Modèle téléchargé et persisté sur le volume.");
}
```

```properties
meetdo.embedding.model-base-url=${MODEL_BASE_URL:https://github.com/Mibrai/pair_backend/releases/download/models-v1}
```

> Le fichier reste committable en local pour l'export/quantisation (§3.3),
> mais **ne doit pas être ajouté au repo Git** — seulement publié en asset
> de Release GitHub, puis référencé par URL.

### 3.4 Service d'embedding local

```java
@Service
@Slf4j
public class LocalEmbeddingService {

    private static final int EMBEDDING_DIMENSION = 384;

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private final Object predictLock = new Object();

    @PostConstruct
    public void init() throws ModelException, IOException {
        log.info("Chargement du modèle d'embeddings local (ONNX, FR/EN/DE)...");
        long start = System.currentTimeMillis();

        Criteria<String, float[]> criteria = Criteria.builder()
            .setTypes(String.class, float[].class)
            .optModelPath(Paths.get(
                getClass().getResource("/models/embedding").toURI()))
            .optEngine("OnnxRuntime")
            .optTranslator(new SentenceEmbeddingTranslator())
            .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();

        log.info("Modèle d'embeddings chargé en {} ms", System.currentTimeMillis() - start);
    }

    @PreDestroy
    public void cleanup() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }

    public float[] generateEmbedding(String text) {
        String truncated = text.length() > 2000 ? text.substring(0, 2000) : text;
        try {
            synchronized (predictLock) {
                return predictor.predict(truncated);
            }
        } catch (TranslateException e) {
            log.error("Échec génération embedding local : {}", e.getMessage());
            return new float[EMBEDDING_DIMENSION];
        }
    }

    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    public int getDimension() {
        return EMBEDDING_DIMENSION;
    }
}
```

> En phase de test/bêta, le verrou `synchronized` simple suffit. Si le
> trafic de recherche augmente sensiblement, remplacer par un pool de
> plusieurs `Predictor` (DJL `PredictorPool`) — ne pas anticiper cette
> optimisation avant d'en observer le besoin réel dans les métriques
> Railway.

### 3.5 Translator — tokenisation, mean pooling, normalisation L2

```java
public class SentenceEmbeddingTranslator implements Translator<String, float[]> {

    private HuggingFaceTokenizer tokenizer;

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        tokenizer = HuggingFaceTokenizer.newInstance(
            Paths.get(ctx.getModel().getModelPath().toString(), "tokenizer.json"));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        NDManager manager = ctx.getNDManager();
        Encoding encoding = tokenizer.encode(input);

        NDArray idsArray  = manager.create(encoding.getIds()).expandDims(0);
        NDArray maskArray = manager.create(encoding.getAttentionMask()).expandDims(0);
        NDArray typeArray = manager.create(encoding.getTypeIds()).expandDims(0);

        ctx.setAttachment("attentionMask", encoding.getAttentionMask());
        return new NDList(idsArray, maskArray, typeArray);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray tokenEmbeddings = list.get(0); // [1, seq_len, 384]
        long[] mask = (long[]) ctx.getAttachment("attentionMask");

        NDManager manager = ctx.getNDManager();
        NDArray maskArray = manager.create(mask)
            .toType(DataType.FLOAT32, false)
            .expandDims(0).expandDims(-1);

        NDArray masked = tokenEmbeddings.mul(maskArray);
        NDArray summed = masked.sum(new int[]{1});
        NDArray counts = maskArray.sum(new int[]{1}).clip(1e-9, Double.MAX_VALUE);
        NDArray pooled = summed.div(counts);

        NDArray normalized = pooled.div(pooled.pow(2).sum(new int[]{1}, true).sqrt());
        return normalized.toFloatArray();
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
```

---

## 4. Extraction d'intention par règles — FR / EN / DE

`RuleBasedIntentExtractor` remplace l'appel LLM. L'activité elle-même
**n'est pas** extraite par règles : elle reste dans le texte brut transmis
tel quel à l'embedding, qui se charge du matching sémantique multilingue.

```java
@Service
public class RuleBasedIntentExtractor {

    private static final Map<String, ActivityLevel> LEVEL_KEYWORDS = Map.<String, ActivityLevel>ofEntries(
        // Français
        Map.entry("debutant", ActivityLevel.BEGINNER),
        Map.entry("debutante", ActivityLevel.BEGINNER),
        Map.entry("initiation", ActivityLevel.BEGINNER),
        Map.entry("je debute", ActivityLevel.BEGINNER),
        Map.entry("intermediaire", ActivityLevel.INTERMEDIATE),
        Map.entry("confirme", ActivityLevel.ADVANCED),
        Map.entry("confirmee", ActivityLevel.ADVANCED),
        Map.entry("avance", ActivityLevel.ADVANCED),
        Map.entry("expert", ActivityLevel.ADVANCED),
        Map.entry("niveau club", ActivityLevel.ADVANCED),
        // English
        Map.entry("beginner", ActivityLevel.BEGINNER),
        Map.entry("just starting", ActivityLevel.BEGINNER),
        Map.entry("new to this", ActivityLevel.BEGINNER),
        Map.entry("intermediate", ActivityLevel.INTERMEDIATE),
        Map.entry("advanced", ActivityLevel.ADVANCED),
        Map.entry("experienced", ActivityLevel.ADVANCED),
        Map.entry("expert level", ActivityLevel.ADVANCED),
        // Deutsch
        Map.entry("anfanger", ActivityLevel.BEGINNER),
        Map.entry("anfangerin", ActivityLevel.BEGINNER),
        Map.entry("einsteiger", ActivityLevel.BEGINNER),
        Map.entry("fortgeschritten", ActivityLevel.INTERMEDIATE),
        Map.entry("erfahren", ActivityLevel.ADVANCED),
        Map.entry("experte", ActivityLevel.ADVANCED),
        Map.entry("expertin", ActivityLevel.ADVANCED),
        Map.entry("profi", ActivityLevel.ADVANCED)
    );

    private static final Map<String, ActivityFormat> FORMAT_KEYWORDS = Map.<String, ActivityFormat>ofEntries(
        // Français
        Map.entry("seul a seul", ActivityFormat.DUO),
        Map.entry("en duo", ActivityFormat.DUO),
        Map.entry("juste a deux", ActivityFormat.DUO),
        Map.entry("un partenaire", ActivityFormat.DUO),
        Map.entry("une partenaire", ActivityFormat.DUO),
        Map.entry("en groupe", ActivityFormat.GROUP),
        Map.entry("plusieurs personnes", ActivityFormat.GROUP),
        Map.entry("groupe", ActivityFormat.GROUP),
        Map.entry("solo", ActivityFormat.SOLO),
        // English
        Map.entry("one on one", ActivityFormat.DUO),
        Map.entry("just the two of us", ActivityFormat.DUO),
        Map.entry("a partner", ActivityFormat.DUO),
        Map.entry("in a group", ActivityFormat.GROUP),
        Map.entry("group", ActivityFormat.GROUP),
        Map.entry("several people", ActivityFormat.GROUP),
        Map.entry("by myself", ActivityFormat.SOLO),
        // Deutsch
        Map.entry("zu zweit", ActivityFormat.DUO),
        Map.entry("ein partner", ActivityFormat.DUO),
        Map.entry("eine partnerin", ActivityFormat.DUO),
        Map.entry("in der gruppe", ActivityFormat.GROUP),
        Map.entry("gruppe", ActivityFormat.GROUP),
        Map.entry("mehrere personen", ActivityFormat.GROUP),
        Map.entry("allein", ActivityFormat.SOLO)
    );

    private static final List<Map.Entry<String, String>> TIME_HINTS = List.of(
        // Français
        Map.entry("ce week-end", "week-end"), Map.entry("week-end", "week-end"),
        Map.entry("weekend", "week-end"),
        Map.entry("ce matin", "matin"), Map.entry("le matin", "matin"),
        Map.entry("tot le matin", "matin"),
        Map.entry("ce soir", "soir"), Map.entry("le soir", "soir"),
        Map.entry("en soiree", "soir"),
        Map.entry("aujourd'hui", "aujourd'hui"), Map.entry("demain", "demain"),
        Map.entry("lundi", "lundi"), Map.entry("mardi", "mardi"),
        Map.entry("mercredi", "mercredi"), Map.entry("jeudi", "jeudi"),
        Map.entry("vendredi", "vendredi"), Map.entry("samedi", "samedi"),
        Map.entry("dimanche", "dimanche"),
        // English
        Map.entry("this weekend", "week-end"),
        Map.entry("this morning", "matin"), Map.entry("in the morning", "matin"),
        Map.entry("early morning", "matin"),
        Map.entry("this evening", "soir"), Map.entry("in the evening", "soir"),
        Map.entry("tonight", "soir"),
        Map.entry("today", "aujourd'hui"), Map.entry("tomorrow", "demain"),
        Map.entry("monday", "lundi"), Map.entry("tuesday", "mardi"),
        Map.entry("wednesday", "mercredi"), Map.entry("thursday", "jeudi"),
        Map.entry("friday", "vendredi"), Map.entry("saturday", "samedi"),
        Map.entry("sunday", "dimanche"),
        // Deutsch
        Map.entry("am wochenende", "week-end"), Map.entry("dieses wochenende", "week-end"),
        Map.entry("heute morgen", "matin"), Map.entry("morgens", "matin"),
        Map.entry("fruh morgens", "matin"),
        Map.entry("heute abend", "soir"), Map.entry("abends", "soir"),
        Map.entry("heute", "aujourd'hui"), Map.entry("morgen", "demain"),
        Map.entry("montag", "lundi"), Map.entry("dienstag", "mardi"),
        Map.entry("mittwoch", "mercredi"), Map.entry("donnerstag", "jeudi"),
        Map.entry("freitag", "vendredi"), Map.entry("samstag", "samedi"),
        Map.entry("sonntag", "dimanche")
    );

    private static final Pattern RADIUS_KM = Pattern.compile(
        "(\\d+)\\s*(?:km|kilometres?|kilometers?|kilometer)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RADIUS_MILES = Pattern.compile(
        "(\\d+)\\s*(?:mi|miles?)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> VAGUE_PHRASES = Set.of(
        "je veux faire du sport", "je cherche une activite", "je m'ennuie",
        "je veux sortir", "faire quelque chose", "je veux bouger",
        "trouve-moi quelque chose",
        "i want to do something", "i'm bored", "looking for an activity",
        "i want to go out", "i want to move", "find me something",
        "ich will etwas tun", "mir ist langweilig", "ich suche eine aktivitat",
        "ich will raus", "ich will mich bewegen", "finde mir etwas"
    );

    public SearchIntent extractIntent(String rawQuery) {
        String normalized = normalize(rawQuery);

        ActivityLevel level = findFirstMatch(normalized, LEVEL_KEYWORDS);
        ActivityFormat format = findFirstMatch(normalized, FORMAT_KEYWORDS);
        String timeHint = findTimeHint(normalized);
        Integer radiusMeters = extractRadiusMeters(normalized);
        boolean needsClarification = isTooVague(normalized);

        return new SearchIntent(
            null, null,
            level != null ? level.name() : null,
            format != null ? format.name() : null,
            radiusMeters != null ? radiusMeters : 5000,
            timeHint,
            needsClarification,
            needsClarification ? clarificationQuestionFor(rawQuery) : null
        );
    }

    private String normalize(String text) {
        String t = text.toLowerCase();
        t = t.replaceAll("[éèêë]", "e").replaceAll("[àâ]", "a")
             .replaceAll("[îï]", "i").replaceAll("[ôö]", "o")
             .replaceAll("[ûùü]", "u").replaceAll("ç", "c");
        return t.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss").trim();
    }

    private <T> T findFirstMatch(String text, Map<String, T> keywords) {
        return keywords.entrySet().stream()
            .filter(e -> text.contains(normalize(e.getKey())))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String findTimeHint(String text) {
        return TIME_HINTS.stream()
            .filter(e -> text.contains(normalize(e.getKey())))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private Integer extractRadiusMeters(String text) {
        Matcher km = RADIUS_KM.matcher(text);
        if (km.find()) return Integer.parseInt(km.group(1)) * 1000;
        Matcher mi = RADIUS_MILES.matcher(text);
        if (mi.find()) return Math.round(Integer.parseInt(mi.group(1)) * 1609f);
        return null;
    }

    private boolean isTooVague(String text) {
        boolean matchesVague = VAGUE_PHRASES.stream().anyMatch(p -> text.contains(normalize(p)));
        boolean isShort = text.split("\\s+").length <= 6;
        return matchesVague && isShort;
    }

    private String clarificationQuestionFor(String rawQuery) {
        String n = normalize(rawQuery);
        if (n.matches(".*\\b(i|want|looking|bored)\\b.*"))
            return "What kind of activity would you enjoy today?";
        if (n.matches(".*\\b(ich|will|suche|langweilig)\\b.*"))
            return "Welche Aktivität würde dir heute gefallen?";
        return "Quel type d'activité te ferait plaisir aujourd'hui ?";
    }
}
```

> ⚠️ **Ambiguïté connue** : l'allemand "morgen" seul est traité comme
> *"demain"*, tandis que "morgens"/"heute morgen"/"früh morgens" sont
> traités comme *"matin"*. Compromis à ajuster si les tests réels montrent
> trop de faux positifs.

### 4.1 Branchement dans `SemanticSearchService`

```java
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final RuleBasedIntentExtractor intentExtractor; // remplace l'ancien LLM
    private final LocalEmbeddingService embeddingService;    // remplace l'ancien OpenAI
    // ... le reste (recherche pgvector, ranking, EmptyStateActionDto déjà
    // en place si l'évolution meetDo précédente a été appliquée) ne change pas
}
```

Le contrat exposé côté client ne change pas : `SearchResponse`,
`SearchIntent`, et les actions d'état vide restent identiques dans leur
forme JSON. Le client Flutter (`features/search/`) n'a donc **aucune
modification obligatoire** à faire pour bénéficier du changement — voir le
document frontend pour les ajustements optionnels.

---

## 5. Migration des vecteurs (1536 → 384) et backfill

### 5.1 Migration Flyway — suite logique de V43

```sql
-- V44__migrate_embeddings_to_local_model.sql

DROP INDEX IF EXISTS idx_activities_embedding;
DROP INDEX IF EXISTS idx_programs_embedding;
DROP INDEX IF EXISTS idx_search_logs_embedding;

ALTER TABLE activities  ALTER COLUMN embedding TYPE vector(384) USING NULL;
ALTER TABLE programs    ALTER COLUMN embedding TYPE vector(384) USING NULL;
ALTER TABLE search_logs ALTER COLUMN query_embedding TYPE vector(384) USING NULL;

CREATE INDEX idx_activities_embedding
    ON activities USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_programs_embedding
    ON programs USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_search_logs_embedding
    ON search_logs USING hnsw (query_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

> Vérifier avant application que les noms de table/colonne ci-dessus
> correspondent bien au schéma réel — le document de référence liste les
> tables préexistantes par déduction de l'API, pas par lecture directe du
> schéma. Ajuster les noms si le schéma réel diverge.

### 5.2 Backfill des embeddings existants

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingBackfillRunner implements ApplicationRunner {

    private final ActivityRepository activityRepository;
    private final ProgramRepository programRepository;
    private final LocalEmbeddingService embeddingService;

    @Value("${meetdo.embedding.backfill-on-startup:false}")
    private boolean backfillEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!backfillEnabled) return;

        log.info("=== Backfill des embeddings (modèle local trilingue) ===");

        List<Activity> activities = activityRepository.findByEmbeddingIsNull();
        for (Activity a : activities) {
            String text = a.getName() + ". " + Optional.ofNullable(a.getDescription()).orElse("");
            activityRepository.updateEmbedding(a.getId(),
                embeddingService.toVectorString(embeddingService.generateEmbedding(text)));
        }
        log.info("{} activités ré-indexées", activities.size());

        List<Program> programs = programRepository.findActivePublicWithoutEmbedding();
        for (Program p : programs) {
            String text = p.getTitle() + ". "
                + Optional.ofNullable(p.getDescription()).orElse("") + ". "
                + p.getUserActivity().getActivity().getName();
            programRepository.updateEmbedding(p.getId(),
                embeddingService.toVectorString(embeddingService.generateEmbedding(text)));
        }
        log.info("{} programmes ré-indexés", programs.size());
        log.info("=== Backfill terminé ===");
    }
}
```

```properties
# Activer une seule fois après déploiement, repasser à false ensuite
meetdo.embedding.backfill-on-startup=true
```

Exposer, si le groupe `indexation` existant s'y prête, un endpoint
d'administration protégé permettant de redéclencher ce backfill à la
demande plutôt que par variable d'environnement + redémarrage — cohérent
avec le rôle déjà documenté de ce groupe d'endpoints.

---

## 6. Nettoyage des dépendances payantes

```properties
# À retirer de application-railway.properties
# llm.api-url=...
# llm.api-key=${ANTHROPIC_API_KEY:}
# embedding.api-url=...
# embedding.api-key=${OPENAI_API_KEY:}
```

```bash
railway service   # sélectionner le service backend (pair_backend_service)
railway variables --set "ANTHROPIC_API_KEY="
railway variables --set "OPENAI_API_KEY="
```

Supprimer ou marquer `@Deprecated` les anciennes classes d'appel API
(`LlmIntentExtractor`, ancien `EmbeddingService` basé WebClient), et
vérifier tous leurs points d'injection dans le reste du code — en
particulier tout composant de seed (référence à `ReferenceDataSeeder` /
`DemoDataSeeder` dans les specs antérieures, à localiser dans le code réel)
qui génère des embeddings au chargement des données de référence.

---

## 7. Impact mémoire sur Railway

| Élément | Impact |
|---|---|
| Modèle en RAM | ~150-200 Mo en continu |
| Démarrage | +2 à 5 s (`@PostConstruct`) |
| Latence par embedding | 20-80 ms CPU (plus rapide qu'un appel API externe) |

Vérifier dans **Metrics** du dashboard Railway, service backend, que la RAM
disponible couvre cette charge additionnelle après déploiement — augmenter
le plan si le service approche sa limite mémoire.

---

## Ordre d'implémentation

```
1. Localiser le package réel du domaine "search" côté backend avant de
   créer de nouvelles classes (éviter la duplication)
2. Exporter et quantiser le modèle (§3.3), publier les fichiers en asset
   d'une GitHub Release (ou Cloudflare R2) — NE PAS committer le .onnx
   dans le repo Git
3. Ajouter les dépendances DJL (§3.2)
4. Attacher un volume Railway au service backend (chemin type /app/models)
5. Implémenter le téléchargement au démarrage + SentenceEmbeddingTranslator
   + LocalEmbeddingService (§3.4-3.5)
6. Implémenter RuleBasedIntentExtractor (§4)
7. Brancher les deux services dans SemanticSearchService (§4.1) — vérifier
   qu'aucun test existant ne mock encore les anciennes classes LLM/OpenAI
8. Migration V44 (§5.1) — vérifier les noms réels de table/colonne d'abord
9. EmbeddingBackfillRunner + activation ponctuelle (§5.2)
10. Nettoyage variables Railway + classes obsolètes (§6)
11. Déploiement + vérification mémoire (§7)
12. Test manuel : quelques requêtes en français, anglais, allemand contre
    l'environnement Railway réel, comparer aux résultats attendus
```

## Tests à ajouter

```
LocalEmbeddingServiceTest
- vecteur de dimension 384, normalisé (norme L2 ≈ 1.0)
- "yoga" (FR/EN/DE) produit des embeddings mutuellement proches
  (similarité cosinus > 0.85)

RuleBasedIntentExtractorTest
- couverture des exemples FR/EN/DE donnés en §4
- une phrase mêlant deux langues ne lève jamais d'exception

SemanticSearchIntegrationTest (mise à jour de toute spec de test existante
pour ce domaine)
- remplacer les mocks LLM/OpenAI par les implémentations réelles
  (déterministes, aucun appel réseau à mocker)
- vérifier qu'une requête anglaise retrouve un programme titré en français
```
