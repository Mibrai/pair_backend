# Pair — Guide d'utilisation des spécifications

## Fichiers disponibles

| Fichier | Lignes | Contenu |
|---------|--------|---------|
| `pair-data-model-spec.md` | 1240 | Entités JPA, migrations SQL, repositories, index |
| `pair-phase1-spec.md` | 1615 | Auth JWT, profil, activités, programmes, carte, chat WebSocket |
| `pair-phase2-spec.md` | 711 | Recherche LLM, embeddings pgvector, progressions, upload S3 |
| `pair-phase3-spec.md` | 476 | Badges, recommandations pairs, avis programmes, signalements |
| `pair-phase4-spec.md` | 678 | Notifications push, jobs Quartz, Redis, RGPD |

---

## Comment utiliser ces fichiers

### Option A — Un fichier par session de travail (recommandé)

Commencer par le modèle de données, puis une phase à la fois, dans l'ordre
ci-dessous, en validant chaque étape avant la suivante.

### Option B — Contexte complet

Lire le modèle de données et la phase visée ensemble, pour implémenter une
phase en ayant le modèle sous les yeux.

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
llm.model=<modèle>

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

