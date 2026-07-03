# 🚀 Quick Start - Charger les données Railway

## Méthode la plus simple (Railway Dashboard)

1. **Allez sur Railway** : https://railway.app
2. **Ouvrez votre projet** : `pairbackend-production`
3. **Cliquez sur PostgreSQL** (base de données)
4. **Onglet "Query"** ou "Data"
5. **Copiez-collez** le contenu de `railway_seed_data.sql`
6. **Cliquez sur "Execute"** ou "Run"

✅ C'est fait! Les données sont chargées.

---

## Méthode alternative (Ligne de commande)

### Prérequis
Installez PostgreSQL client (contient `psql`) :
- **Windows** : https://www.postgresql.org/download/windows/
- **Mac** : `brew install postgresql`
- **Linux** : `apt install postgresql-client`

### Étapes

1. **Récupérez le DATABASE_URL**
   ```bash
   # Sur Railway Dashboard > PostgreSQL > Variables
   # Ou avec Railway CLI:
   railway variables | grep DATABASE_URL
   ```

2. **Exécutez le script**
   ```bash
   cd F:/Projekt/Pair/pair_backend
   psql "VOTRE_DATABASE_URL_ICI" -f railway_seed_data.sql
   ```

   Exemple :
   ```bash
   psql "postgresql://postgres:PASSWORD@monkeypox.railway.internal:5432/railway" -f railway_seed_data.sql
   ```

---

## Vérification

Une fois chargé, testez :

```bash
# Test 1: Login
curl -X POST "https://pairbackend-production-35fe.up.railway.app/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@pair.test","password":"Test1234!"}'

# Test 2: Activités sur la carte
curl "https://pairbackend-production-35fe.up.railway.app/api/map/activities"
```

Si vous voyez un token JWT (test 1) et 10 activités (test 2), c'est bon! ✅

---

## Comptes de test

| Email | Mot de passe |
|-------|--------------|
| `alice@pair.test` | `Test1234!` |
| `bob@pair.test` | `Test1234!` |
| `claire@pair.test` | `Test1234!` |
| `david@pair.test` | `Test1234!` |
| `emma@pair.test` | `Test1234!` |
| `frank@pair.test` | `Test1234!` |
| `grace@pair.test` | `Test1234!` |
| `hugo@pair.test` | `Test1234!` |
| `isabelle@pair.test` | `Test1234!` |
| `julien@pair.test` | `Test1234!` |

---

## Données chargées

✅ 10 utilisateurs avec localisations à Paris  
✅ 10 catégories d'activités  
✅ 10 activités (Football, Yoga, Running, etc.)  
✅ 10 programmes avec horaires  
✅ **10 schedules avec coordonnées GPS** (pour la carte)  
✅ 10 images  
✅ 10 conversations  
✅ 10 messages  

**Total**: ~70 entrées dans la base de données

---

## 🎯 C'est prêt!

Votre backend Railway contient maintenant des données de test.  
La page Map affichera **10 activités** avec leurs localisations à Paris! 🗺️
