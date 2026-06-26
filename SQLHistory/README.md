# SQLHistory

Ce dossier contient tous les scripts SQL exécutés manuellement sur la base de données `pair_db`.

## 🚀 Quick Start

**Pour configurer la base de données complètement, exécutez simplement:**

```bash
cd SQLHistory
./execute-setup.bat
```

Ou manuellement:
```bash
set PGPASSWORD=Pair2026!
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U pair_user -d pair_db -f SETUP_COMPLETE.sql
```

## 📁 Scripts disponibles

### SETUP_COMPLETE.sql ⭐ (RECOMMANDÉ)
**Script consolidé qui configure tout en une seule exécution:**
- Active les extensions (PostGIS, pgvector)
- Crée la table `categories`
- Crée la table `activities` avec support pgvector
- Crée la table `user_activities`
- Insère 4 catégories de test
- Insère 12 activités de test

### Scripts individuels (historiques)
- `create-missing-tables.sql` - Création des tables
- `seed-activities.sql` - Insertion des données de test
- `fix-activities.sql` - Correction de la table activities
- `00_EXECUTE_ALL.sql` - Ancien script master (déprécié)

## 📊 Données créées

### Catégories (4)
- Sport ⚽ (blue)
- Musique 🎵 (purple)
- Art 🎨 (pink)
- Jeux 🎮 (green)

### Activités (12)
**Sport:** Tennis, Football, Running, Yoga, Basketball  
**Musique:** Guitare, Piano, Chant  
**Art:** Peinture, Photographie  
**Jeux:** Échecs, Poker

## ✅ Vérification

Après exécution, testez les endpoints:

```bash
# Liste des catégories
curl http://localhost:8090/api/categories

# Liste des activités
curl http://localhost:8090/api/activities

# Activités d'une catégorie spécifique
curl "http://localhost:8090/api/activities?categoryId=11111111-1111-1111-1111-111111111111"
```

**Scripts de test disponibles:**
- `test-activities-complete.sh` - Tests complets du système d'activités
- `test-programs.sh` - Tests des programmes et créneaux

## ⚙️ Configuration requise

- PostgreSQL 18.4
- Extensions: PostGIS, pgvector
- Base de données: `pair_db`
- Utilisateur: `pair_user`
- Mot de passe: `Pair2026!`

## 📝 Notes importantes

- Tous les scripts sont **idempotents** (peuvent être ré-exécutés sans erreur)
- Les IDs sont fixes pour faciliter les tests
- La table `users` doit déjà exister (créée lors du setup initial)
- Ces scripts complètent les migrations Flyway qui n'ont pas pu s'exécuter automatiquement
