# Charger les données de test sur Railway

Ce guide explique comment charger les données de test dans votre base de données Railway.

## 📋 Prérequis

- Accès à votre projet Railway
- DATABASE_URL de votre base PostgreSQL Railway
- `psql` (PostgreSQL client) OU Python 3 avec `psycopg2`

## 🔑 Étape 1: Récupérer le DATABASE_URL

### Méthode 1: Dashboard Railway
1. Allez sur [railway.app](https://railway.app)
2. Sélectionnez votre projet **pairbackend-production**
3. Cliquez sur votre service PostgreSQL
4. Onglet **Variables**
5. Copiez la valeur de `DATABASE_URL`

### Méthode 2: Railway CLI
```bash
railway variables | grep DATABASE_URL
```

Le format de l'URL est :
```
postgresql://postgres:PASSWORD@HOST:PORT/railway
```

## 🚀 Étape 2: Charger les données

### Option A: Avec psql (recommandé)

```bash
cd F:/Projekt/Pair/pair_backend
./load_railway_data.sh "postgresql://postgres:PASSWORD@HOST:PORT/railway"
```

### Option B: Avec Python

```bash
cd F:/Projekt/Pair/pair_backend
pip install psycopg2-binary  # Si pas déjà installé
python load_railway_data.py "postgresql://postgres:PASSWORD@HOST:PORT/railway"
```

### Option C: Manuellement avec psql

```bash
cd F:/Projekt/Pair/pair_backend
psql "postgresql://postgres:PASSWORD@HOST:PORT/railway" -f railway_seed_data.sql
```

## 📊 Données insérées

Le script insère **10 occurrences** pour chaque table principale :

| Table | Nombre | Description |
|-------|--------|-------------|
| `users` | 10 | Utilisateurs avec localisations à Paris |
| `categories` | 10 | Catégories d'activités |
| `activities` | 10 | Activités (Football, Yoga, Running, etc.) |
| `user_activities` | 10 | Activités des utilisateurs |
| `programs` | 10 | Programmes créés par les utilisateurs |
| `schedules` | 10 | **Horaires avec localisations GPS** |
| `program_media` | 10 | Images des programmes |
| `conversations` | 10 | Conversations entre utilisateurs |
| `conversation_members` | 20 | 2 membres par conversation |
| `messages` | 10 | Messages dans les conversations |

## 🔐 Comptes de test

Tous les comptes utilisent le même mot de passe : **`Test1234!`**

| Email | Nom | Activité principale | Localisation |
|-------|-----|---------------------|--------------|
| `alice@pair.test` | Alice Dupont | Running | Champ de Mars |
| `bob@pair.test` | Bob Martin | Football | Paris Nord |
| `claire@pair.test` | Claire Lebrun | Yoga | Paris Est |
| `david@pair.test` | David Moreau | Cyclisme | Bois de Boulogne |
| `emma@pair.test` | Emma Wilson | Natation | Molitor |
| `frank@pair.test` | Frank Dubois | Karaté | Paris Nord |
| `grace@pair.test` | Grace Lambert | Randonnée | Paris Centre |
| `hugo@pair.test` | Hugo Bernard | Basketball | Paris Sud |
| `isabelle@pair.test` | Isabelle Petit | Méditation | Marais |
| `julien@pair.test` | Julien Roux | Judo | Paris Nord |

## ✅ Vérification

Après le chargement, vérifiez que les données sont bien présentes :

### Test 1: Vérifier les utilisateurs
```bash
curl "https://pairbackend-production-35fe.up.railway.app/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@pair.test","password":"Test1234!"}'
```

Si vous obtenez un token JWT, c'est bon ! ✅

### Test 2: Vérifier les activités sur la carte
```bash
curl "https://pairbackend-production-35fe.up.railway.app/api/map/activities"
```

Vous devriez voir 10 activités avec leurs localisations :
```json
{
  "activities": [
    {
      "activityId": "cccccccc-0000-0000-0000-000000000001",
      "activityName": "Football",
      "categoryName": "Sports collectifs",
      "categoryIcon": "team",
      "lat": 48.8456,
      "lng": 2.2755,
      ...
    },
    ...
  ],
  "defaultCenter": {
    "lat": 48.857,
    "lng": 2.347,
    "zoom": 13
  }
}
```

## 🗺️ Localisations des activités

Les schedules sont situés à différents endroits de Paris :

1. **Champ de Mars** (Running) - 48.8566, 2.2945
2. **Terrain Javel** (Football) - 48.8456, 2.2755
3. **Studio Zen Paris 11** (Yoga) - 48.8600, 2.3800
4. **Bois de Boulogne** (Cyclisme) - 48.8623, 2.2411
5. **Piscine Molitor** (Natation) - 48.8476, 2.2550
6. **Dojo Paris 13** (Karaté) - 48.8300, 2.3650
7. **Gare de Lyon** (Randonnée départ) - 48.8450, 2.3730
8. **Parc de Bercy** (Basketball) - 48.8360, 2.3810
9. **Marais** (Méditation) - 48.8590, 2.3590
10. **Gymnase Clichy** (Judo) - 48.8670, 2.3450

## 🔄 Recharger les données

Si vous voulez réinitialiser les données, supprimez-les d'abord :

```sql
DELETE FROM messages;
DELETE FROM conversation_members;
DELETE FROM conversations;
DELETE FROM program_media;
DELETE FROM schedules;
DELETE FROM programs;
DELETE FROM user_activities;
DELETE FROM activities;
DELETE FROM categories;
DELETE FROM users WHERE email LIKE '%@pair.test';
```

Puis relancez le script de chargement.

## ⚠️ Notes importantes

1. **ON CONFLICT DO NOTHING** : Le script utilise cette clause, donc si les données existent déjà, elles ne seront pas dupliquées
2. **Horaires futurs** : Les schedules sont créés avec des dates dans le futur (NOW() + INTERVAL)
3. **Localisations réelles** : Toutes les coordonnées GPS sont des lieux réels à Paris
4. **UUIDs fixes** : Les IDs sont préfixés pour faciliter le debug (aa=users, bb=categories, etc.)

## 🐛 Dépannage

### Erreur: "psql: command not found"
- Installez PostgreSQL client ou utilisez la méthode Python

### Erreur: "connection refused"
- Vérifiez que le DATABASE_URL est correct
- Vérifiez que votre IP est autorisée dans Railway (généralement pas de restriction)

### Erreur: "duplicate key value"
- Les données existent déjà. Supprimez-les d'abord ou ignorez l'erreur (ON CONFLICT DO NOTHING)

### Erreur: "password authentication failed"
- Vérifiez que le mot de passe dans DATABASE_URL est correct
- Récupérez à nouveau l'URL depuis Railway

## 📞 Support

Si vous rencontrez des problèmes :
1. Vérifiez les logs Railway : `railway logs`
2. Vérifiez la connexion : `railway connect`
3. Consultez la documentation Railway : https://docs.railway.app/

## ✨ C'est fait !

Une fois les données chargées, votre backend Railway sera complètement fonctionnel avec :
- 10 utilisateurs de test
- 10 activités visibles sur la carte
- Des programmes avec horaires et localisations
- Des conversations et messages de test

Vous pouvez maintenant tester l'application frontend et voir les activités apparaître sur la carte! 🗺️
