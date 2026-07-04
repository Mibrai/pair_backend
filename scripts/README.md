# 📂 Scripts Railway - Guide complet

## 🎯 Fichiers disponibles

| Fichier | Description | Utilisation |
|---------|-------------|-------------|
| **seed-railway-data.sql** | Script SQL principal pour charger les données | ⭐ Recommandé |
| **cleanup-railway-data.sql** | Nettoyer les données de test | Avant rechargement |
| **QUICK_START.md** | Guide ultra-rapide | Commencer ici |
| **README_SQL_SEEDING.md** | Documentation complète SQL | Pour les détails |
| seed-railway.sh | Script bash automatique | Alternative |
| seed-railway.bat | Script Windows | Alternative |

## 🚀 Démarrage rapide (3 étapes)

### 1. Charger les données

```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

### 2. Vérifier

```bash
railway connect postgres
SELECT email, display_name FROM users WHERE email LIKE 'railway%';
```

### 3. Se connecter

- **Email :** railway1@pair.app à railway10@pair.app
- **Mot de passe :** Railway1234!

## 📊 Données créées

- ✅ 10 utilisateurs (Berlin, München, Hamburg, Köln, Frankfurt, Stuttgart, Düsseldorf, Dortmund, Leipzig, Dresden)
- ✅ 10 activités utilisateur (Yoga, Marathon, Escalade, Football, Natation, Tennis, Programmation, Photographie, Cuisine, Méditation)
- ✅ 10 programmes avec descriptions en allemand
- ✅ 10 horaires (semaine prochaine)
- ✅ 10 inscriptions (table `user_programs`)

**Total : ~50 entrées en < 1 seconde**

## 🧹 Nettoyage

Pour supprimer toutes les données Railway :

```bash
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL
```

## 🔧 Dépannage

### Erreur "relation does not exist"

Les tables n'existent pas. Exécutez les migrations :
```bash
./mvnw flyway:migrate
```

### Erreur "duplicate key"

Les données existent déjà. Nettoyez d'abord :
```bash
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL
```

### Erreur "activity not found"

Les activités de référence manquent. Vérifiez avec :
```sql
SELECT COUNT(*) FROM activities;
```

Si zéro, chargez les données de référence en premier.

## 📖 Documentation

- **[QUICK_START.md](QUICK_START.md)** - Commencer en 5 minutes
- **[README_SQL_SEEDING.md](README_SQL_SEEDING.md)** - Documentation SQL complète
- **[../RAILWAY_QUICKSTART.md](../RAILWAY_QUICKSTART.md)** - Guide général Railway
- **[../docs/RAILWAY_SEEDING.md](../docs/RAILWAY_SEEDING.md)** - Documentation détaillée

## 💡 Exemples d'utilisation

### Charger + Vérifier en une commande

```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL && \
railway run psql $DATABASE_URL -c "SELECT COUNT(*) FROM users WHERE email LIKE 'railway%';"
```

### Nettoyer + Recharger

```bash
cat scripts/cleanup-railway-data.sql scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

### Voir les programmes créés

```bash
railway run psql $DATABASE_URL -c "
SELECT u.display_name, p.title, s.place_name 
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
LEFT JOIN schedules s ON s.program_id = p.id
WHERE u.email LIKE 'railway%'
ORDER BY u.email;"
```

### Compter toutes les données

```bash
railway run psql $DATABASE_URL -c "
SELECT 
  (SELECT COUNT(*) FROM users WHERE email LIKE 'railway%') as users,
  (SELECT COUNT(*) FROM user_activities ua JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%') as activities,
  (SELECT COUNT(*) FROM programs p JOIN user_activities ua ON p.user_activity_id = ua.id JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%') as programs,
  (SELECT COUNT(*) FROM schedules s JOIN programs p ON s.program_id = p.id JOIN user_activities ua ON p.user_activity_id = ua.id JOIN users u ON ua.user_id = u.id WHERE u.email LIKE 'railway%') as schedules,
  (SELECT COUNT(*) FROM user_programs) as enrollments;"
```

## 🌍 Coordonnées GPS

Tous les utilisateurs sont en **Allemagne** avec des coordonnées réelles :

| Ville | Latitude | Longitude |
|-------|----------|-----------|
| Berlin | 52.5200 | 13.4050 |
| München | 48.1351 | 11.5820 |
| Hamburg | 53.5511 | 9.9937 |
| Köln | 50.9375 | 6.9603 |
| Frankfurt | 50.1109 | 8.6821 |
| Stuttgart | 48.7758 | 9.1829 |
| Düsseldorf | 51.2277 | 6.7735 |
| Dortmund | 51.5136 | 7.4653 |
| Leipzig | 51.3397 | 12.3731 |
| Dresden | 51.0504 | 13.7373 |

## 🔐 Sécurité

⚠️ **Ces scripts sont pour le développement et les tests uniquement !**

- Ne jamais exécuter en production
- Tous les mots de passe sont identiques
- Les emails sont prévisibles

## ✨ Avantages du script SQL

- ⚡ **Ultra-rapide** : < 1 seconde d'exécution
- 🎯 **Simple** : Une seule commande
- 🔧 **Facile à modifier** : SQL standard
- 🔄 **Reproductible** : Même résultat à chaque fois
- 🧪 **Idéal pour les tests** : Charger/nettoyer rapidement

---

**Besoin d'aide ? Consultez [QUICK_START.md](QUICK_START.md) pour démarrer !** 🚀
