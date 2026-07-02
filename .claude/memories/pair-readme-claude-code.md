# Pair — Guide d'utilisation des specs pour Claude Code

## Fichiers disponibles

| Fichier | Lignes | Contenu |
|---------|--------|---------|
| `pair-data-model-spec.md` | 1240 | Entités JPA, migrations SQL, repositories, index |
| `pair-phase1-spec.md` | 1615 | Auth JWT, profil, activités, programmes, carte, chat WebSocket |
| `pair-phase2-spec.md` | 711 | Recherche LLM, embeddings pgvector, progressions, upload S3 |
| `pair-phase3-spec.md` | 476 | Badges, recommandations pairs, avis programmes, signalements |
| `pair-phase4-spec.md` | 678 | Notifications push, jobs Quartz, Redis, RGPD |

---

## Comment utiliser ces fichiers avec Claude Code

### Option A — Un fichier par session (recommandé)

Commencer une session Claude Code avec le data model, puis une session
par phase dans l'ordre :

```bash
# Session 1 : modèle de données
claude < pair-data-model-spec.md

# Session 2 : phase 1 (après avoir validé le modèle)
claude < pair-phase1-spec.md

# Session 3 : phase 2
claude < pair-phase2-spec.md

# ...
```

### Option B — Contexte complet en une session

```bash
cat pair-data-model-spec.md pair-phase1-spec.md | claude
```

### Option C — Coller dans Claude.ai

Ouvrir le fichier, copier le contenu, coller en début de conversation
avec Claude, puis demander l'implémentation section par section.

---

## Ordre d'implémentation strict

```
pair-data-model-spec.md  →  Extensions PG + entités JPA + migrations Flyway
        ↓
pair-phase1-spec.md      →  Auth + profil + activités + carte + chat
        ↓
pair-phase2-spec.md      →  Recherche LLM + progressions + médias S3
        ↓
pair-phase3-spec.md      →  Badges + recommandations + avis + signalements
        ↓
pair-phase4-spec.md      →  Notifications + push Firebase + Redis + RGPD
```

---

## Variables d'environnement requises

```properties
# Base de données
DB_USER=pair_user
DB_PASSWORD=...

# JWT
jwt.secret=<base64-256bits>
jwt.access-token-expiry-ms=900000
jwt.refresh-token-expiry-ms=2592000000

# LLM (extraction d'intention)
llm.api-key=...
llm.model=claude-sonnet-4-6

# Embeddings
embedding.api-key=...
embedding.model=text-embedding-3-small

# Email (Postmark / SendGrid)
spring.mail.host=smtp.postmarkapp.com
spring.mail.port=587
spring.mail.username=...
spring.mail.password=...
email.from=noreply@pair.app
email.base-url=https://pair.app

# AWS S3
aws.s3.bucket=pair-media
aws.s3.region=eu-west-3
aws.s3.cdn-base-url=https://cdn.pair.app

# Firebase (push notifications)
firebase.credentials-path=firebase-service-account.json

# Redis
redis.host=localhost
redis.port=6379
```

