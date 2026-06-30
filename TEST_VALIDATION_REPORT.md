# MapVisibilityIntegrationTest - Rapport d'Implémentation

## Statut: IMPLÉMENTÉ ✅

Date: 2026-06-30  
Fichier: `src/test/java/org/program/pair/integration/MapVisibilityIntegrationTest.java`

---

## Tests Critiques Implémentés

### 1. ✅ `utilisateurMasque_neDoitJamaisApparaitreSurLaCarte()`
**Objectif**: Valider que les utilisateurs avec `locationPublic=false` ne sont JAMAIS visibles sur la carte.

**Scénario**:
- UserA et UserB sont créés et positionnés géographiquement proches (Paris)
- UserB définit `locationPublic=false`
- UserA définit `locationPublic=true` et cherche autour de lui
- **Assertion**: UserB ne doit JAMAIS apparaître dans les résultats

**Règle de sécurité**: Respect absolu de la vie privée - pas de compromis.

---

### 2. ✅ `compteDesactive_neDoitJamaisApparaitreSurLaCarte()`
**Objectif**: Valider que les comptes désactivés (`is_active=false`) sont immédiatement masqués.

**Scénario**:
- UserA (actif) et UserB (à supprimer) sont créés
- UserB active sa position publique puis désactive son compte
- UserA cherche autour de lui
- **Assertion**: UserB ne doit JAMAIS apparaître dans les résultats

**Règle de sécurité**: Les comptes désactivés disparaissent immédiatement de toute API publique.

---

### 3. ✅ `utilisateurHorsRayon_neDoitPasApparaitre()`
**Objectif**: Valider que la recherche géographique respecte le rayon défini.

**Scénario**:
- UserA est à Paris (48.8566, 2.3522)
- UserB est à Marseille (43.2965, 5.3698) - ~660 km
- UserA cherche dans un rayon de 5 km
- **Assertion**: UserB ne doit PAS apparaître (hors du rayon)

**Règle métier**: La recherche géographique doit être précise et fiable.

---

### 4. ✅ `positionAffichee_doitEtreFlouttee_pasExacte()`
**Objectif**: Valider que les coordonnées affichées sont floutées (anti-stalking).

**Scénario**:
- UserB se positionne à des coordonnées exactes
- UserB définit `blurRadiusM=500`
- UserA cherche autour de cette position
- **Assertion**: Les coordonnées retournées ne doivent JAMAIS être exactement les coordonnées réelles

**Règle de sécurité**: Protection anti-stalking - floutage obligatoire des positions.

---

## Méthodes Helper Implémentées

### ✅ `registerAndLogin(String email): String`
- Enregistre un nouvel utilisateur avec l'email fourni
- Extrait automatiquement le `displayName` de l'email
- Se connecte immédiatement
- Retourne le token JWT d'accès

### ✅ `updateLocation(String token, double lat, double lng): void`
- Met à jour la localisation géographique de l'utilisateur
- Utilise l'endpoint `PUT /api/users/me/location`

### ✅ `updateProfile(String token, Map<String, Object> fields): void`
- Met à jour le profil avec des champs dynamiques
- Supporte: `locationPublic`, `blurRadiusM`, `displayName`, `bio`, `onlineStatusVisible`, `receiveMessages`
- Permet une configuration flexible dans les tests

### ✅ `deactivateAccount(String token): void`
- Désactive le compte de l'utilisateur
- Utilise l'endpoint `DELETE /api/users/me`
- Doit retourner `204 No Content`

### ✅ `getMapUsers(String token, double lat, double lng, int radius): List<MapUserDto>`
- Récupère la liste des utilisateurs visibles sur la carte
- Utilise l'endpoint `GET /api/map/users`
- Paramètres: `lat`, `lng`, `radiusMeters`
- Retourne une liste typée de `MapUserDto`

---

## Architecture Technique

### Stack de Test
- **Framework**: JUnit 5
- **Assertions**: AssertJ
- **HTTP Client**: Spring WebTestClient (réactif)
- **Base de données**: Testcontainers (PostgreSQL avec PostGIS + pgvector)
- **Héritage**: Étend `AbstractIntegrationTest`

### Dépendances
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

---

## Comment Exécuter les Tests

### Prérequis
1. **Docker Desktop doit être démarré** (nécessaire pour Testcontainers)
2. Java 17+
3. Maven 3.9+

### Commandes

#### Exécuter uniquement MapVisibilityIntegrationTest
```bash
mvn test -Dtest=MapVisibilityIntegrationTest
```

#### Exécuter tous les tests d'intégration
```bash
mvn verify -Dtest=*IntegrationTest
```

#### Avec rapport de couverture
```bash
mvn clean verify jacoco:report
# Rapport disponible dans target/site/jacoco/index.html
```

---

## Validation de la Compilation

✅ **Compilation réussie** (2026-06-30 21:51:51)
```
[INFO] BUILD SUCCESS
[INFO] Total time:  12.396 s
```

Le code compile sans erreurs ni warnings (hormis une dépréciation mineure dans WebSocketChatIntegrationTest non liée à ce test).

---

## Checklist de Validation

### Implémentation ✅
- [x] Test 1: utilisateurMasque_neDoitJamaisApparaitreSurLaCarte
- [x] Test 2: compteDesactive_neDoitJamaisApparaitreSurLaCarte
- [x] Test 3: utilisateurHorsRayon_neDoitPasApparaitre
- [x] Test 4: positionAffichee_doitEtreFlouttee_pasExacte
- [x] Helper: registerAndLogin
- [x] Helper: updateLocation
- [x] Helper: updateProfile
- [x] Helper: deactivateAccount
- [x] Helper: getMapUsers

### Qualité du Code ✅
- [x] Documentation complète (Javadoc sur toutes les méthodes)
- [x] Assertions claires et explicites
- [x] Utilisation de WebTestClient (réactif, adapté à Spring Boot)
- [x] Respect des conventions de nommage du projet
- [x] Intégration avec AbstractIntegrationTest
- [x] Compilation sans erreurs

### Sécurité ✅
- [x] Validation du modèle de confiance (vie privée)
- [x] Protection anti-stalking (floutage)
- [x] Sécurité des comptes désactivés
- [x] Isolation des tests (pas d'effets de bord)

---

## Prochaines Étapes

1. **Démarrer Docker Desktop**
2. **Exécuter les tests**:
   ```bash
   mvn test -Dtest=MapVisibilityIntegrationTest
   ```
3. **Analyser les résultats**:
   - Si tous les tests passent ✅ → Le modèle de confiance est validé
   - Si des tests échouent ❌ → Identifier et corriger les bugs dans le backend

4. **Correction de bugs potentiels**:
   - Vérifier que `MapService.getUsersOnMap()` filtre bien `locationPublic=false`
   - Vérifier que `MapService.getUsersOnMap()` filtre bien `is_active=false`
   - Vérifier que le floutage géographique est appliqué (blurRadiusM)
   - Vérifier que la recherche géographique respecte le rayon (ST_DWithin)

---

## Importance Critique

⚠️ **CES TESTS SONT CRITIQUES POUR LA SÉCURITÉ ET LA VIE PRIVÉE** ⚠️

**TOUS les tests doivent passer** avant de passer à l'étape suivante. Il n'y a **AUCUN compromis** acceptable sur :
- La vie privée des utilisateurs (locationPublic)
- La sécurité des comptes désactivés
- Le floutage géographique (anti-stalking)

Si un test échoue, c'est un **BUG DE SÉCURITÉ CRITIQUE** qui doit être corrigé immédiatement.

---

## Fichiers Modifiés

### Nouveaux fichiers
- ✅ `src/test/java/org/program/pair/integration/MapVisibilityIntegrationTest.java` (229 lignes)

### Fichiers existants utilisés
- `src/test/java/org/program/pair/AbstractIntegrationTest.java`
- `src/main/java/org/program/pair/domain/map/dto/MapUserDto.java`
- `src/main/java/org/program/pair/domain/user/dto/UpdateLocationRequest.java`
- `src/main/java/org/program/pair/domain/user/dto/UpdateProfileRequest.java`
- `src/main/java/org/program/pair/domain/auth/dto/RegisterRequest.java`
- `src/main/java/org/program/pair/domain/auth/dto/LoginRequest.java`
- `src/main/java/org/program/pair/domain/auth/dto/AuthResponse.java`

---

## Contact

Pour toute question sur l'implémentation des tests:
- Vérifier la spec: `src/main/resources/memories/pair-tests-validation-spec.md` (lignes 727-809)
- Consulter les autres tests d'intégration: `src/test/java/org/program/pair/`

---

**Date de création**: 2026-06-30  
**Statut**: ✅ IMPLÉMENTÉ ET COMPILÉ  
**Tests exécutés**: ⏳ EN ATTENTE DE DOCKER
