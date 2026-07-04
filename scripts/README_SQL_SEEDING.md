# 🗄️ Script SQL pour charger les données Railway

## 📋 Vue d'ensemble

Le fichier `seed-railway-data.sql` contient un script SQL complet pour charger la base de données Railway avec des données de test allemandes.

**Avantages du script SQL :**
- ✅ Pas besoin de compiler le code Java
- ✅ Exécution ultra-rapide (< 1 seconde)
- ✅ Facile à modifier et personnaliser
- ✅ Fonctionne avec n'importe quel client PostgreSQL
- ✅ Idéal pour le développement et les tests

## 🎯 Données créées

| Type | Quantité | Détails |
|------|----------|---------|
| 👥 Utilisateurs | 10 | Répartis dans 10 villes allemandes |
| 🏃 Activités utilisateur | 10 | Yoga, Course, Escalade, Football, etc. |
| 📋 Programmes | 10 | Avec descriptions en allemand |
| 📅 Horaires | 10 | Dates dans la semaine prochaine |
| ✅ Inscriptions (user_programs) | 10 | Chaque user inscrit au programme suivant |

**Total : ~50 entrées**

### Mot de passe

Tous les comptes utilisent le mot de passe : **`Railway1234!`**

Hash bcrypt : `$2a$10$vI8aWBnW3fID.ZQ4/zo1G.q1lRps.9cGLcZEiGDMVr5yUP1KUOYTa`

## 🚀 Méthode 1 : Railway CLI (Recommandé)

```bash
# Se connecter à Railway
railway login

# Lier le projet
railway link

# Exécuter le script
railway run psql $DATABASE_URL < scripts/seed-railway-data.sql
```

Ou en une ligne :
```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

## 🔧 Méthode 2 : Connection directe

### Avec psql

```bash
# Récupérer l'URL de la base
railway variables

# Se connecter et exécuter
psql "postgresql://user:pass@host:port/railway" < scripts/seed-railway-data.sql
```

### Avec la commande railway connect

```bash
# Ouvrir une session psql
railway connect postgres

# Dans psql, exécuter le fichier
\i scripts/seed-railway-data.sql
```

## 🪟 Windows

### PowerShell
```powershell
Get-Content scripts\seed-railway-data.sql | railway run psql $env:DATABASE_URL
```

### Git Bash
```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

### Avec psql Windows
```cmd
psql -h hostname -U username -d railway < scripts\seed-railway-data.sql
```

## 🌍 Villes et coordonnées

| Email | Ville | Latitude | Longitude | Activité |
|-------|-------|----------|-----------|----------|
| railway1@pair.app | Berlin | 52.5200 | 13.4050 | Yoga am Morgen |
| railway2@pair.app | München | 48.1351 | 11.5820 | Marathon Vorbereitung |
| railway3@pair.app | Hamburg | 53.5511 | 9.9937 | Kletter-Workshop |
| railway4@pair.app | Köln | 50.9375 | 6.9603 | Fußball Freundschaftsspiel |
| railway5@pair.app | Frankfurt | 50.1109 | 8.6821 | Schwimmtraining |
| railway6@pair.app | Stuttgart | 48.7758 | 9.1829 | Tennis Doppel |
| railway7@pair.app | Düsseldorf | 51.2277 | 6.7735 | Hackathon Wochenende |
| railway8@pair.app | Dortmund | 51.5136 | 7.4653 | Fotowalk durch die Stadt |
| railway9@pair.app | Leipzig | 51.3397 | 12.3731 | Kochkurs Asiatisch |
| railway10@pair.app | Dresden | 51.0504 | 13.7373 | Meditation am Abend |

## ✅ Vérification

Après l'exécution, le script affiche automatiquement un résumé :

```
         info          | count 
-----------------------+-------
 Utilisateurs créés:   |    10
 Activités utilisateur:|    10
 Programmes créés:     |    10
 Horaires créés:       |    10
 Inscriptions:         |    10
```

### Requêtes de vérification manuelle

```sql
-- Compter les utilisateurs
SELECT COUNT(*) FROM users WHERE email LIKE 'railway%@pair.app';

-- Voir les programmes avec leurs créateurs
SELECT 
  u.display_name, 
  p.title, 
  s.place_name,
  s.starts_at
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
LEFT JOIN schedules s ON s.program_id = p.id
WHERE u.email LIKE 'railway%@pair.app'
ORDER BY u.display_name;

-- Voir les coordonnées GPS
SELECT 
  email,
  display_name,
  ST_Y(location) as latitude,
  ST_X(location) as longitude
FROM users
WHERE email LIKE 'railway%@pair.app'
ORDER BY email;

-- Voir les inscriptions
SELECT 
  u.display_name as participant,
  p.title as program,
  up.joined_at,
  up.status
FROM user_programs up
JOIN users u ON up.user_id = u.id
JOIN programs p ON up.program_id = p.id
ORDER BY up.joined_at DESC;
```

## 🔄 Réexécution

Le script peut être exécuté plusieurs fois sans erreur. Si des utilisateurs avec les mêmes emails existent déjà, PostgreSQL lèvera une erreur de contrainte unique et ignorera ces insertions.

### Pour nettoyer et recommencer

```bash
# Utiliser le script de nettoyage automatique
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL

# Ou manuellement via psql
railway connect postgres
\i scripts/cleanup-railway-data.sql

# Puis réexécuter le script de seeding
\i scripts/seed-railway-data.sql
```

## 🛠️ Personnalisation

### Modifier les coordonnées

Changez les valeurs dans les INSERT INTO users :
```sql
ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)
```

### Modifier les dates

Les horaires utilisent `CURRENT_DATE + INTERVAL`:
```sql
(CURRENT_DATE + INTERVAL '1 week' + INTERVAL '9 hours')::timestamp
```

### Ajouter plus d'utilisateurs

Copiez-collez un bloc d'INSERT et changez :
- L'email (`railway11@pair.app`, etc.)
- Le nom et la bio
- Les coordonnées GPS

### Modifier le mot de passe

Générer un nouveau hash bcrypt :
```bash
# En ligne de commande
htpasswd -bnBC 10 "" VotreMotDePasse | tr -d ':\n'

# Ou avec Python
python3 -c "import bcrypt; print(bcrypt.hashpw(b'VotreMotDePasse', bcrypt.gensalt()).decode())"
```

## 🚨 Dépannage

### Erreur : "activity not found"

Les activités de référence doivent exister en premier. Vérifiez :
```sql
SELECT slug FROM activities WHERE slug IN 
  ('yoga', 'course-a-pied', 'escalade', 'football', 'natation', 
   'tennis', 'programmation', 'photographie', 'cuisine-du-monde', 'meditation');
```

Si elles manquent, exécutez d'abord le `ReferenceDataSeeder` ou créez-les manuellement.

### Erreur : "duplicate key value"

Les utilisateurs existent déjà. Voir la section "Pour nettoyer et recommencer" ci-dessus.

### Erreur : "relation does not exist"

Les tables n'existent pas. Exécutez les migrations Flyway :
```bash
./mvnw flyway:migrate
```

### Erreur PostGIS

Vérifiez que PostGIS est activé :
```sql
CREATE EXTENSION IF NOT EXISTS postgis;
SELECT PostGIS_Version();
```

## 📊 Performance

- **Temps d'exécution** : < 1 seconde
- **Transactions** : Tout s'exécute dans une seule transaction
- **Rollback** : Si une erreur survient, rien n'est inséré

## 🔐 Sécurité

⚠️ **Ne jamais exécuter ce script en production !**

- Les mots de passe sont identiques pour tous les comptes
- Les emails sont prévisibles
- Les données sont fictives

## 📚 Ressources

- Documentation PostgreSQL : https://www.postgresql.org/docs/
- PostGIS : https://postgis.net/documentation/
- Railway Docs : https://docs.railway.app/
- bcrypt : https://en.wikipedia.org/wiki/Bcrypt

---

**C'est tout ! 🎉 Votre base de données Railway est prête avec des données de test allemandes !**
