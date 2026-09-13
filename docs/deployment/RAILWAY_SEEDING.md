# 🚂 Railway Database Seeding - Guide SQL

## 🎯 Vue d'ensemble

Chargement rapide de la base de données Railway avec des données de test allemandes via un simple script SQL.

## 🚀 Démarrage rapide

```bash
cat scripts/seed-railway-data.sql | railway run psql $DATABASE_URL
```

## 📊 Données créées

Le script crée **50 entrées** :

- ✅ **10 utilisateurs** (Berlin, München, Hamburg, Köln, Frankfurt, Stuttgart, Düsseldorf, Dortmund, Leipzig, Dresden)
- ✅ **10 activités utilisateur** (Yoga, Marathon, Escalade, Football, Natation, Tennis, Programmation, Photographie, Cuisine, Méditation)
- ✅ **10 programmes** avec descriptions en allemand
- ✅ **10 horaires** (semaine prochaine)
- ✅ **10 inscriptions** (table `user_programs`)

**Temps d'exécution : < 1 seconde**

## 🔑 Comptes de test

| Email | Mot de passe | Ville | Coordonnées |
|-------|--------------|-------|-------------|
| railway1@pair.app | Railway1234! | Berlin | 52.52°N, 13.40°E |
| railway2@pair.app | Railway1234! | München | 48.14°N, 11.58°E |
| railway3@pair.app | Railway1234! | Hamburg | 53.55°N, 9.99°E |
| railway4@pair.app | Railway1234! | Köln | 50.94°N, 6.96°E |
| railway5@pair.app | Railway1234! | Frankfurt | 50.11°N, 8.68°E |
| railway6@pair.app | Railway1234! | Stuttgart | 48.78°N, 9.18°E |
| railway7@pair.app | Railway1234! | Düsseldorf | 51.23°N, 6.77°E |
| railway8@pair.app | Railway1234! | Dortmund | 51.51°N, 7.47°E |
| railway9@pair.app | Railway1234! | Leipzig | 51.34°N, 12.37°E |
| railway10@pair.app | Railway1234! | Dresden | 51.05°N, 13.74°E |

## 🧹 Nettoyer les données

```bash
cat scripts/cleanup-railway-data.sql | railway run psql $DATABASE_URL
```

## ✅ Vérifier

```bash
railway run psql $DATABASE_URL -c "SELECT COUNT(*) FROM users WHERE email LIKE 'railway%';"
```

Résultat attendu : `10`

## 📁 Fichiers disponibles

| Fichier | Description |
|---------|-------------|
| `scripts/seed-railway-data.sql` | Script principal de chargement |
| `scripts/cleanup-railway-data.sql` | Nettoyage des données |
| `scripts/QUICK_START.md` | Guide ultra-rapide |
| `scripts/README.md` | Documentation complète |
| `scripts/README_SQL_SEEDING.md` | Détails SQL |

## 🔧 Prérequis

1. Railway CLI installé : `npm install -g @railway/cli`
2. Migrations Flyway exécutées
3. Activités de référence chargées (table `activities`)

## 📖 Documentation complète

Voir [`scripts/README.md`](scripts/README.md) pour plus de détails.

---

**Simple, rapide, efficace ! 🇩🇪**
