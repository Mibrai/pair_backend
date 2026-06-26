# 🧪 Guide de Test - Application Pair

## 🚀 Prérequis

### 1. Vérifier que l'application tourne
```bash
curl http://localhost:8090/
# Devrait retourner la page d'accueil HTML
```

### 2. Vérifier la base de données
```bash
# Les tables doivent exister
cd SQLHistory
./execute-setup.bat  # Windows
# OU exécuter manuellement les scripts SQL
```

---

## 📋 Plan de Test par Système

### ✅ **1. Authentification JWT**

#### Tests à effectuer:
- [ ] Inscription avec email/mot de passe
- [ ] Connexion et obtention du token
- [ ] Refresh token
- [ ] Accès endpoints protégés avec token
- [ ] Rejet sans token (401)

#### Script de test:
```bash
# 1. Inscription
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email":"test@example.com",
    "password":"Test1234!",
    "displayName":"Test User"
  }'

# Devrait retourner: accessToken, refreshToken, userId

# 2. Connexion
curl -X POST http://localhost:8090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email":"test@example.com",
    "password":"Test1234!"
  }'

# 3. Test endpoint protégé (devrait échouer sans token)
curl http://localhost:8090/api/users/me
# Devrait retourner 403 Forbidden

# 4. Test avec token valide
curl http://localhost:8090/api/users/me \
  -H "Authorization: Bearer [VOTRE_TOKEN]"
# Devrait retourner le profil utilisateur
```

---

### ✅ **2. Profil Utilisateur**

#### Tests à effectuer:
- [ ] Récupérer mon profil (GET /api/users/me)
- [ ] Mettre à jour profil (PUT /api/users/me)
- [ ] Mettre à jour localisation (PUT /api/users/me/location)
- [ ] Voir profil public d'un autre user (GET /api/users/{id})
- [ ] Désactiver compte (DELETE /api/users/me)

#### Script de test:
```bash
# Exécuter le script automatisé
bash SQLHistory/test-profile.sh

# OU manuellement:
TOKEN="[VOTRE_TOKEN]"

# 1. Mon profil
curl http://localhost:8090/api/users/me \
  -H "Authorization: Bearer $TOKEN"

# 2. Mettre à jour bio
curl -X PUT http://localhost:8090/api/users/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bio":"Passionné de sport!"}'

# 3. Mettre à jour position (Paris)
curl -X PUT http://localhost:8090/api/users/me/location \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"lat":48.8566,"lng":2.3522}'
```

**Validation:**
- ✅ Bio mise à jour visible
- ✅ Location avec coordonnées GPS
- ✅ Profil public ne montre pas l'email

---

### ✅ **3. Système d'Activités**

#### Tests à effectuer:
- [ ] Lister catégories (public)
- [ ] Lister activités (public)
- [ ] Rechercher activités par nom
- [ ] Filtrer par catégorie
- [ ] Ajouter activité à mon profil
- [ ] Modifier niveau/format
- [ ] Supprimer activité
- [ ] Toggle visibilité carte

#### Script de test automatisé:
```bash
bash SQLHistory/test-activities-complete.sh
```

#### Tests manuels:
```bash
# 1. Lister catégories (pas besoin de token)
curl http://localhost:8090/api/categories
# Devrait retourner: Sport, Musique, Art, Jeux

# 2. Lister activités
curl http://localhost:8090/api/activities?size=10

# 3. Rechercher "Tennis"
curl "http://localhost:8090/api/activities?search=tennis"

# 4. Filtrer par catégorie Sport
curl "http://localhost:8090/api/activities?categoryId=11111111-1111-1111-1111-111111111111"

# Avec authentification:
TOKEN="[VOTRE_TOKEN]"

# 5. Mes activités
curl http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN"

# 6. Ajouter Tennis
curl -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "visibleOnMap":true,
    "level":"INTERMEDIATE",
    "format":"BOTH"
  }'
```

**Validation:**
- ✅ 4 catégories disponibles
- ✅ Minimum 5 activités (Tennis, Football, Running, Yoga, Basketball)
- ✅ Recherche fonctionne
- ✅ Activité ajoutée visible dans mon profil

---

### ✅ **4. Programmes & Créneaux**

#### Tests à effectuer:
- [ ] Créer un programme
- [ ] Ajouter un créneau avec lieu
- [ ] Lister mes programmes
- [ ] Modifier programme (statut ACTIVE)
- [ ] Supprimer créneau
- [ ] Archiver programme

#### Script de test automatisé:
```bash
bash SQLHistory/test-programs.sh
```

#### Tests manuels:
```bash
TOKEN="[VOTRE_TOKEN]"

# 1. D'abord ajouter une activité (nécessaire)
UA_RESPONSE=$(curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","visibleOnMap":true}')

# Extraire l'ID de l'activité utilisateur
UA_ID=$(echo "$UA_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# 2. Créer programme
PROG=$(curl -s -X POST http://localhost:8090/api/programs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"userActivityId\":\"$UA_ID\",
    \"title\":\"Tennis tous les mercredis\",
    \"description\":\"Sessions régulières de tennis\",
    \"isPublic\":true
  }")

PROG_ID=$(echo "$PROG" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Programme créé: $PROG_ID"

# 3. Ajouter créneau
curl -X POST "http://localhost:8090/api/programs/$PROG_ID/schedules" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "placeName":"Tennis Club Paris",
    "placeType":"PUBLIC",
    "lat":48.8566,
    "lng":2.3522,
    "addressPublic":"15 Rue de la Paix, 75002 Paris",
    "startsAt":"2026-07-01T18:00:00Z",
    "maxParticipants":4
  }'

# 4. Voir le programme avec ses créneaux
curl "http://localhost:8090/api/programs/$PROG_ID" \
  -H "Authorization: Bearer $TOKEN"
```

**Validation:**
- ✅ Programme créé avec statut DRAFT
- ✅ Créneau avec géolocalisation
- ✅ Adresse publique visible
- ✅ Programme visible dans la liste

---

### ✅ **5. Carte Interactive**

#### Tests à effectuer:
- [ ] Rechercher users dans un rayon
- [ ] Filtrer par activité
- [ ] Vérifier floutage positions
- [ ] Vérifier statut en ligne
- [ ] Vérifier badges activités

#### Script de test automatisé:
```bash
bash SQLHistory/test-map.sh
```

#### Tests manuels:
```bash
TOKEN="[VOTRE_TOKEN]"

# 1. IMPORTANT: D'abord mettre à jour sa position
curl -X PUT http://localhost:8090/api/users/me/location \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"lat":48.8566,"lng":2.3522}'

# 2. Rechercher users à Paris (rayon 5km)
curl "http://localhost:8090/api/map/users?lat=48.8566&lng=2.3522&radiusMeters=5000" \
  -H "Authorization: Bearer $TOKEN"

# 3. Filtrer par Tennis uniquement
curl "http://localhost:8090/api/map/users?lat=48.8566&lng=2.3522&radiusMeters=5000&activityId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" \
  -H "Authorization: Bearer $TOKEN"

# 4. Recherche plus précise (1km)
curl "http://localhost:8090/api/map/users?lat=48.8606&lng=2.3364&radiusMeters=1000" \
  -H "Authorization: Bearer $TOKEN"
```

**Validation:**
- ✅ Trouve plusieurs utilisateurs
- ✅ Positions floutées (pas exactement 48.8566/2.3522)
- ✅ isOnline: true pour users actifs < 5min
- ✅ visibleActivities contient les activités
- ✅ Ne retourne pas l'utilisateur lui-même

---

### ✅ **6. Chat (Infrastructure)**

#### État actuel:
- ✅ Tables créées
- ✅ WebSocket configuré
- ⚠️ Service nécessite ajustements finaux

#### Tests à effectuer (quand finalisé):
- [ ] Créer conversation
- [ ] Envoyer message via REST
- [ ] Envoyer message via WebSocket
- [ ] Recevoir message en temps réel
- [ ] Marquer comme lu
- [ ] Voir conversations avec unread count

---

## 🎯 Checklist de Validation Complète

### Données de Base
```bash
# Vérifier les données de test
export PGPASSWORD=Pair2026!
"/c/Program Files/PostgreSQL/18/bin/psql.exe" -h localhost -U pair_user -d pair_db <<EOF
SELECT 'Categories' as table_name, COUNT(*) FROM categories
UNION ALL
SELECT 'Activities', COUNT(*) FROM activities
UNION ALL
SELECT 'Users', COUNT(*) FROM users
UNION ALL
SELECT 'User Activities', COUNT(*) FROM user_activities
UNION ALL
SELECT 'Programs', COUNT(*) FROM programs
UNION ALL
SELECT 'Schedules', COUNT(*) FROM schedules;
EOF
```

**Attendu:**
- Categories: 4+
- Activities: 5+
- Users: 5+ (si seed data exécuté)
- User Activities: varie
- Programs: varie
- Schedules: varie

### Endpoints Publics (sans authentification)
```bash
# Doivent fonctionner SANS token
curl http://localhost:8090/                     # ✅ Page accueil
curl http://localhost:8090/api/categories       # ✅ 4 catégories
curl http://localhost:8090/api/activities       # ✅ Liste activités
```

### Endpoints Protégés (avec authentification)
Tous ces endpoints nécessitent `Authorization: Bearer [TOKEN]`:
- `/api/users/me` - Profil
- `/api/users/me/activities` - Mes activités
- `/api/programs` - Mes programmes
- `/api/map/users` - Carte
- `/api/conversations` - Chat

---

## 🔧 Outils de Test Recommandés

### 1. **cURL** (ligne de commande)
✅ Déjà utilisé dans ce guide

### 2. **Postman**
1. Importer collection:
   - Créer requête POST `/api/auth/register`
   - Sauvegarder token dans variable d'environnement
   - Utiliser `{{token}}` dans headers

### 3. **Scripts Bash Automatisés**
```bash
# Dans SQLHistory/
./test-activities-complete.sh  # ✅ Tests activités
./test-programs.sh             # ✅ Tests programmes
./test-map.sh                  # ✅ Tests carte
```

### 4. **WebSocket Client** (pour le chat)
- [WebSocket Test Client](https://www.piesocket.com/websocket-tester)
- URL: `ws://localhost:8090/ws/chat`
- Header: `Authorization: Bearer [TOKEN]`

---

## 📊 Scénario de Test Complet E2E

### Scénario: "Trouver un partenaire de Tennis"

```bash
#!/bin/bash

echo "=== Scénario E2E: Trouver partenaire Tennis ==="

# 1. Créer 2 utilisateurs
USER1=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@test.com","password":"Test1234!","displayName":"Alice"}')
TOKEN1=$(echo "$USER1" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

USER2=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"bob@test.com","password":"Test1234!","displayName":"Bob"}')
TOKEN2=$(echo "$USER2" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

# 2. Alice se positionne à Paris et ajoute Tennis
curl -s -X PUT http://localhost:8090/api/users/me/location \
  -H "Authorization: Bearer $TOKEN1" \
  -H "Content-Type: application/json" \
  -d '{"lat":48.8566,"lng":2.3522}' > /dev/null

curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN1" \
  -H "Content-Type: application/json" \
  -d '{"activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","visibleOnMap":true,"level":"INTERMEDIATE"}' > /dev/null

# 3. Bob se positionne près d'Alice et ajoute Tennis
curl -s -X PUT http://localhost:8090/api/users/me/location \
  -H "Authorization: Bearer $TOKEN2" \
  -H "Content-Type: application/json" \
  -d '{"lat":48.8570,"lng":2.3525}' > /dev/null

curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN2" \
  -H "Content-Type: application/json" \
  -d '{"activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","visibleOnMap":true,"level":"ADVANCED"}' > /dev/null

# 4. Alice cherche des joueurs de Tennis à proximité
echo "Alice cherche des partenaires de Tennis..."
curl -s "http://localhost:8090/api/map/users?lat=48.8566&lng=2.3522&radiusMeters=2000&activityId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" \
  -H "Authorization: Bearer $TOKEN1" | grep "Bob"

# 5. Alice crée un programme
echo "Alice crée un programme Tennis..."
# [code programme...]

echo "✅ Scénario E2E terminé!"
```

---

## 🐛 Dépannage

### Erreur 403 Forbidden
- ✅ Vérifier que le token est valide
- ✅ Vérifier format header: `Authorization: Bearer [TOKEN]`
- ✅ Token expiré? Se reconnecter

### Erreur 500 Internal Error
- ✅ Vérifier logs application
- ✅ Vérifier que les tables existent
- ✅ Vérifier que l'user a une location (pour carte)

### Carte retourne 0 utilisateurs
- ✅ Exécuter `SQLHistory/04_seed_map_test_data.sql`
- ✅ Vérifier votre position est définie
- ✅ Augmenter le rayon de recherche

### Application ne démarre pas
```bash
taskkill //F //IM java.exe
cd Pair
./mvnw clean compile
./mvnw spring-boot:run
```

---

## ✅ Checklist Finale

Avant de valider la Phase 1:

- [ ] L'application démarre sans erreur
- [ ] Inscription/Connexion fonctionnent
- [ ] Profil utilisateur complet
- [ ] 4 catégories visibles
- [ ] 5+ activités disponibles
- [ ] Peut ajouter activité à profil
- [ ] Peut créer programme
- [ ] Peut ajouter créneau
- [ ] Carte trouve des utilisateurs
- [ ] Positions sont floutées
- [ ] Tous les scripts de test passent

**Si tout est ✅ → Phase 1 VALIDÉE! 🎉**
