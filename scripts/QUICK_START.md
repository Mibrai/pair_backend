# 🚀 Quick Start - Chargement des données Railway

## ✅ Version simplifiée

Le script a été simplifié pour ne créer que les données essentielles :
- ✅ Utilisateurs
- ✅ Activités utilisateur
- ✅ Programmes
- ✅ Horaires
- ✅ Inscriptions (user_programs)

## Exécution en 1 ligne

```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

Ou avec Railway connect :

```bash
railway connect postgres
\i scripts/seed-railway-data.sql
```

## Résultat attendu

```
         info          | count 
-----------------------+-------
 Utilisateurs créés:   |    10
 Activités utilisateur:|    10
 Programmes créés:     |    10
 Horaires créés:       |    10
 Inscriptions:         |    10
```

## Comptes de test

| Email | Mot de passe | Ville |
|-------|--------------|-------|
| railway1@pair.app | Railway1234! | Berlin |
| railway2@pair.app | Railway1234! | München |
| railway3@pair.app | Railway1234! | Hamburg |
| railway4@pair.app | Railway1234! | Köln |
| railway5@pair.app | Railway1234! | Frankfurt |
| railway6@pair.app | Railway1234! | Stuttgart |
| railway7@pair.app | Railway1234! | Düsseldorf |
| railway8@pair.app | Railway1234! | Dortmund |
| railway9@pair.app | Railway1234! | Leipzig |
| railway10@pair.app | Railway1234! | Dresden |

## Nettoyer les données

```bash
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL
```

## Vérifier les données

```sql
railway connect postgres

-- Voir les utilisateurs
SELECT email, display_name FROM users WHERE email LIKE 'railway%';

-- Voir les programmes
SELECT u.display_name, p.title, s.place_name
FROM programs p
JOIN user_activities ua ON p.user_activity_id = ua.id
JOIN users u ON ua.user_id = u.id
LEFT JOIN schedules s ON s.program_id = p.id
WHERE u.email LIKE 'railway%';
```

## En cas d'erreur

### "relation does not exist"

Exécutez les migrations Flyway :
```bash
./mvnw flyway:migrate
```

### "duplicate key value"

Les données existent déjà. Nettoyez d'abord :
```bash
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL
```

### "activity not found"

Les activités de référence manquent. Vérifiez :
```sql
SELECT slug FROM activities LIMIT 5;
```

Si vide, chargez les données de référence d'abord.

---

**C'est tout ! 🎉**
