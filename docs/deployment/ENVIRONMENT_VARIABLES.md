# Variables d'environnement

Guide de référence des variables d'environnement pour le déploiement de Pair.

## Table des matières

- [Base de données](#base-de-données)
- [Redis](#redis)
- [Email (Resend)](#email-resend)
- [Firebase](#firebase)
- [JWT](#jwt)
- [Application](#application)
- [Storage](#storage)

---

## Base de données

### PostgreSQL (Railway)

Variables automatiquement fournies par Railway lors de l'attachement du service `postgres_db`:

| Variable | Description | Exemple |
|----------|-------------|---------|
| `PGHOST` | Host PostgreSQL | `monorail.proxy.rlwy.net` |
| `PGPORT` | Port PostgreSQL | `12345` |
| `PGDATABASE` | Nom de la base | `railway` |
| `PGUSER` | Utilisateur | `postgres` |
| `PGPASSWORD` | Mot de passe | `xxx` |

**Configuration Spring Boot**:
```properties
spring.datasource.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
spring.datasource.username=${PGUSER}
spring.datasource.password=${PGPASSWORD}
```

**Vérification**:
```bash
railway variables | grep PG
```

---

## Redis

### Configuration Redis (Optionnel)

| Variable | Requis | Défaut | Description |
|----------|--------|--------|-------------|
| `REDIS_ENABLED` | Non | `false` | Active Redis pour le cache |
| `REDIS_HOST` | Si enabled | `localhost` | Host Redis |
| `REDIS_PORT` | Si enabled | `6379` | Port Redis |

**Exemple production**:
```bash
REDIS_ENABLED=true
REDIS_HOST=redis-12345.railway.app
REDIS_PORT=6379
```

**Exemple développement**:
```bash
REDIS_ENABLED=false
```

---

## Email (Resend)

### Configuration Resend API

| Variable | Requis | Défaut | Description |
|----------|--------|--------|-------------|
| `RESEND_ENABLED` | **Oui** | `false` | Active l'envoi d'emails via Resend |
| `RESEND_API_KEY` | **Oui si enabled** | - | Clé API Resend (format: `re_xxx`) |
| `RESEND_FROM_EMAIL` | Non | `infos@meetdo.fun` | Email expéditeur |
| `RESEND_FROM_NAME` | Non | `MeetDo` | Nom expéditeur |

**Production**:
```bash
RESEND_ENABLED=true
RESEND_API_KEY=re_xxxxxxxxxxxxxxxxxxxxxxxxxx
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
```

**Développement local**:
```bash
RESEND_ENABLED=false
# Les liens de vérification sont affichés dans les logs
```

**Obtenir une clé API**:
1. Créer un compte sur [resend.com](https://resend.com)
2. Vérifier le domaine `meetdo.fun`
3. Créer une API key dans le dashboard
4. Copier la clé (commence par `re_`)

**Documentation**: [Configuration Email](../guides/EMAIL_CONFIGURATION.md)

---

## Firebase

### Firebase Admin SDK

| Variable | Requis | Description |
|----------|--------|-------------|
| `FIREBASE_CREDENTIALS_JSON` | **Oui** | Contenu du fichier `serviceAccountKey.json` (format JSON minifié) |

**Format**:
```bash
FIREBASE_CREDENTIALS_JSON='{"type":"service_account","project_id":"pair-xxx","private_key_id":"xxx",...}'
```

**Obtention**:
1. Console Firebase > Project Settings > Service Accounts
2. Generate New Private Key
3. Télécharger le JSON
4. Minifier: `cat serviceAccountKey.json | jq -c . | pbcopy`
5. Coller dans Railway

**Validation**:
```bash
# Vérifier que la variable est définie
railway variables | grep FIREBASE_CREDENTIALS_JSON
# (doit afficher la variable sans révéler le contenu complet)
```

---

## JWT

### JSON Web Token

| Variable | Requis | Description |
|----------|--------|-------------|
| `JWT_SECRET` | **Oui** | Secret pour signer les tokens JWT (min 32 caractères) |

**Génération**:
```bash
# Générer un secret aléatoire sécurisé
openssl rand -base64 32
# ou
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
```

**Configuration**:
```bash
JWT_SECRET=votre-secret-tres-long-et-securise-minimum-32-caracteres
```

**Sécurité**:
- ⚠️ Ne jamais commit le secret dans Git
- ⚠️ Utiliser des secrets différents pour dev/staging/prod
- ⚠️ Changer le secret = invalide tous les tokens existants

---

## Application

### Configuration générale

| Variable | Requis | Défaut | Description |
|----------|--------|--------|-------------|
| `PORT` | Non | `8080` | Port d'écoute HTTP (Railway le définit automatiquement) |
| `FRONTEND_URL` | **Oui** | `http://localhost:3000` | URL du frontend pour CORS et liens dans emails |
| `SPRING_PROFILES_ACTIVE` | Non | `default` | Profile Spring Boot actif |

**Production**:
```bash
PORT=8080  # Fourni par Railway
FRONTEND_URL=https://meetdo.fun
SPRING_PROFILES_ACTIVE=railway
```

**Développement**:
```bash
PORT=8080
FRONTEND_URL=http://localhost:3000
SPRING_PROFILES_ACTIVE=local
```

---

## Storage

### Stockage des fichiers

| Variable | Requis | Défaut | Description |
|----------|--------|--------|-------------|
| `UPLOAD_DIR` | Non | `uploads/` | Répertoire de stockage des uploads |
| `MAX_FILE_SIZE` | Non | `10MB` | Taille maximale d'un fichier |
| `MAX_REQUEST_SIZE` | Non | `10MB` | Taille maximale de la requête |

**Configuration**:
```bash
UPLOAD_DIR=/app/uploads
MAX_FILE_SIZE=10MB
MAX_REQUEST_SIZE=10MB
```

**Railway**: Le répertoire `/app/uploads` est persisté entre redéploiements si configuré dans `railway.json`.

---

## Configuration Railway complète

### Commandes Railway CLI

```bash
# Définir toutes les variables essentielles
railway variables set RESEND_ENABLED=true
railway variables set RESEND_API_KEY=re_xxx
railway variables set JWT_SECRET=$(openssl rand -base64 32)
railway variables set FRONTEND_URL=https://meetdo.fun
railway variables set FIREBASE_CREDENTIALS_JSON='{"type":"service_account",...}'

# Lister toutes les variables
railway variables

# Supprimer une variable
railway variables delete VARIABLE_NAME
```

### Variables fournies automatiquement par Railway

Railway fournit automatiquement:
- `PORT` - Port assigné dynamiquement
- `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` - Si service PostgreSQL attaché
- `REDIS_URL` - Si service Redis attaché

---

## Checklist déploiement

### Variables minimales requises

- [ ] `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` (via service Railway)
- [ ] `JWT_SECRET` (généré avec `openssl rand -base64 32`)
- [ ] `FRONTEND_URL` (URL production du frontend)
- [ ] `RESEND_ENABLED=true`
- [ ] `RESEND_API_KEY` (depuis dashboard Resend)
- [ ] `FIREBASE_CREDENTIALS_JSON` (serviceAccountKey minifié)

### Variables optionnelles

- [ ] `RESEND_FROM_EMAIL` (défaut: `infos@meetdo.fun`)
- [ ] `RESEND_FROM_NAME` (défaut: `MeetDo`)
- [ ] `REDIS_ENABLED` (défaut: `false`)
- [ ] `UPLOAD_DIR` (défaut: `uploads/`)

---

## Environnements

### Développement local

Créer un fichier `.env` (non commité):

```bash
# Base de données
PGHOST=localhost
PGPORT=5432
PGDATABASE=pair_dev
PGUSER=postgres
PGPASSWORD=postgres

# JWT
JWT_SECRET=dev-secret-minimum-32-caracteres

# Email (désactivé en dev)
RESEND_ENABLED=false

# Firebase (utiliser credentials de dev)
FIREBASE_CREDENTIALS_JSON='{"type":"service_account","project_id":"pair-dev",...}'

# Frontend
FRONTEND_URL=http://localhost:3000
```

### Staging

```bash
# Base de données (Railway staging)
PGHOST=staging-db.railway.app
# ... autres variables PG fournies par Railway

# JWT (secret staging)
JWT_SECRET=staging-secret-different-de-prod

# Email (Resend avec domaine de test)
RESEND_ENABLED=true
RESEND_API_KEY=re_staging_xxx
RESEND_FROM_EMAIL=test@staging.meetdo.fun

# Firebase (credentials staging)
FIREBASE_CREDENTIALS_JSON='{"type":"service_account","project_id":"pair-staging",...}'

# Frontend
FRONTEND_URL=https://staging.meetdo.fun
```

### Production

```bash
# Base de données (Railway production)
PGHOST=prod-db.railway.app
# ... autres variables PG fournies par Railway

# JWT (secret production fort)
JWT_SECRET=<long-random-secret-different-staging-dev>

# Email (Resend production)
RESEND_ENABLED=true
RESEND_API_KEY=re_prod_xxx
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo

# Firebase (credentials production)
FIREBASE_CREDENTIALS_JSON='{"type":"service_account","project_id":"pair-prod",...}'

# Frontend
FRONTEND_URL=https://meetdo.fun

# Redis (si activé)
REDIS_ENABLED=true
REDIS_HOST=prod-redis.railway.app
REDIS_PORT=6379
```

---

## Sécurité

### Bonnes pratiques

1. **Secrets distincts par environnement**
   - Dev ≠ Staging ≠ Production
   - Rotation régulière (6 mois minimum)

2. **Ne jamais commit de secrets**
   - Vérifier `.gitignore` contient `.env`
   - Utiliser `git-secrets` pour prévenir les leaks

3. **Variables sensibles**
   - `JWT_SECRET`: 32+ caractères aléatoires
   - `RESEND_API_KEY`: Créer des clés avec permissions minimales
   - `FIREBASE_CREDENTIALS_JSON`: Ne pas exposer en clair

4. **Validation au démarrage**
   - L'application vérifie les variables critiques
   - Log d'erreur si configuration manquante
   - Refuse de démarrer si variables essentielles absentes

### Vérification de sécurité

```bash
# Vérifier qu'aucun secret n'est commité
git log --all --full-history --source --all --oneline | grep -i "secret\|password\|key"

# Scanner les secrets dans le repo
git secrets --scan

# Vérifier les variables Railway
railway variables | grep -v "VALUE"  # Ne pas afficher les valeurs
```

---

## Troubleshooting

### Variable non définie

**Symptôme**: Application crash au démarrage avec `NullPointerException` ou `IllegalArgumentException`

**Solution**:
```bash
# Lister les variables
railway variables

# Définir la variable manquante
railway variables set VARIABLE_NAME=value

# Redémarrer
railway up
```

### Mauvais format de variable

**Symptôme**: `JsonParseException` pour `FIREBASE_CREDENTIALS_JSON`

**Solution**:
```bash
# Re-minifier le JSON
cat serviceAccountKey.json | jq -c . > minified.json

# Copier et définir
railway variables set FIREBASE_CREDENTIALS_JSON="$(cat minified.json)"
```

### Variable non prise en compte

**Symptôme**: Changement de variable sans effet

**Solution**:
```bash
# Forcer un redéploiement
railway up --force

# Ou redémarrer le service
railway restart
```

---

## Documentation complémentaire

- [Configuration Email Resend](../guides/EMAIL_CONFIGURATION.md)
- [Guide de déploiement Railway](./DEPLOYMENT_GUIDE.md)
- [Migration Email SMTP → Resend](./EMAIL_MIGRATION_RESEND.md)

---

**Dernière mise à jour**: 2026-07-02  
**Maintenu par**: Backend team
