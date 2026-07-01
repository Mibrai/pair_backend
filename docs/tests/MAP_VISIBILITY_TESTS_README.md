# MapVisibilityIntegrationTest - Guide d'Utilisation

## 🎯 Objectif

Ces tests valident le **CŒUR du modèle de confiance** de l'application Pair :
- **Vie privée** : Les utilisateurs masqués ne sont jamais visibles
- **Sécurité** : Les comptes désactivés disparaissent immédiatement
- **Anti-stalking** : Les positions sont toujours floutées

## 📁 Fichiers Créés

```
Pair/
├── src/test/java/org/program/pair/integration/
│   └── MapVisibilityIntegrationTest.java        (229 lignes)
├── run-map-visibility-tests.sh                  (Script Linux/macOS)
├── run-map-visibility-tests.bat                 (Script Windows)
└── TEST_VALIDATION_REPORT.md                    (Rapport détaillé)
```

## 🚀 Exécution Rapide

### Option 1: Scripts automatisés

#### Windows
```cmd
run-map-visibility-tests.bat
```

#### Linux/macOS/Git Bash
```bash
./run-map-visibility-tests.sh
```

### Option 2: Maven direct

```bash
# Test MapVisibilityIntegrationTest uniquement
mvn test -Dtest=MapVisibilityIntegrationTest

# Tous les tests d'intégration
mvn verify -Dtest=*IntegrationTest

# Avec rapport de couverture JaCoCo
mvn clean verify jacoco:report
```

## ⚙️ Prérequis

### Obligatoire
1. **Docker Desktop démarré** (nécessaire pour Testcontainers)
   - Les tests utilisent PostgreSQL avec PostGIS + pgvector
   - Testcontainers démarre automatiquement les conteneurs

2. **Java 17+**
   ```bash
   java -version
   ```

3. **Maven 3.9+**
   ```bash
   mvn -version
   ```

### Vérification de Docker

```bash
# Vérifier que Docker fonctionne
docker ps

# Si erreur, démarrer Docker Desktop puis réessayer
```

## 📊 Tests Implémentés

### Test 1: `utilisateurMasque_neDoitJamaisApparaitreSurLaCarte`
**Validation**: Les utilisateurs avec `locationPublic=false` sont invisibles.

```
Scénario:
1. UserA et UserB à Paris (proches)
2. UserB définit locationPublic=false
3. UserA cherche autour de lui
4. Assertion: UserB n'apparaît JAMAIS
```

### Test 2: `compteDesactive_neDoitJamaisApparaitreSurLaCarte`
**Validation**: Les comptes désactivés disparaissent immédiatement.

```
Scénario:
1. UserB active sa position publique
2. UserB désactive son compte
3. UserA cherche autour de lui
4. Assertion: UserB n'apparaît JAMAIS
```

### Test 3: `utilisateurHorsRayon_neDoitPasApparaitre`
**Validation**: La recherche géographique respecte le rayon.

```
Scénario:
1. UserA à Paris, UserB à Marseille (~660 km)
2. UserA cherche dans un rayon de 5 km
3. Assertion: UserB n'apparaît PAS (hors rayon)
```

### Test 4: `positionAffichee_doitEtreFlouttee_pasExacte`
**Validation**: Les positions sont floutées (anti-stalking).

```
Scénario:
1. UserB se positionne à des coordonnées exactes
2. UserB définit blurRadiusM=500
3. UserA cherche à proximité
4. Assertion: Coordonnées affichées ≠ coordonnées réelles
```

## 🛠️ Méthodes Helper

Le fichier inclut des méthodes réutilisables pour tous les tests de carte :

| Méthode | Description |
|---------|-------------|
| `registerAndLogin(email)` | Enregistre et connecte un utilisateur, retourne le token JWT |
| `updateLocation(token, lat, lng)` | Met à jour la position géographique |
| `updateProfile(token, fields)` | Met à jour le profil (locationPublic, blurRadiusM, etc.) |
| `deactivateAccount(token)` | Désactive le compte |
| `getMapUsers(token, lat, lng, radius)` | Récupère les utilisateurs visibles sur la carte |

### Exemple d'utilisation dans un nouveau test

```java
@Test
void monNouveauTest() {
    // Créer et connecter un utilisateur
    String token = registerAndLogin("test@pair.app");
    
    // Positionner l'utilisateur à Paris
    updateLocation(token, 48.8566, 2.3522);
    
    // Activer la visibilité publique
    updateProfile(token, Map.of("locationPublic", true));
    
    // Chercher autour de Paris dans un rayon de 10 km
    List<MapUserDto> users = getMapUsers(token, 48.8566, 2.3522, 10000);
    
    // Assertions
    assertThat(users).isNotEmpty();
}
```

## 📈 Interprétation des Résultats

### ✅ Tous les tests passent

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Signification**: Le modèle de confiance est correctement implémenté.
- Les utilisateurs masqués ne sont jamais visibles ✅
- Les comptes désactivés disparaissent immédiatement ✅
- Le filtrage géographique fonctionne ✅
- Le floutage des positions est actif ✅

### ❌ Des tests échouent

```
[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
[ERROR] utilisateurMasque_neDoitJamaisApparaitreSurLaCarte  
        Time elapsed: 2.341 s  <<< FAILURE!
```

**Signification**: **BUG DE SÉCURITÉ CRITIQUE** détecté.

#### Actions immédiates

1. **Identifier le test qui échoue**
   ```bash
   # Réexécuter avec logs détaillés
   mvn test -Dtest=MapVisibilityIntegrationTest -X
   ```

2. **Analyser l'assertion qui échoue**
   - Exemple: `userB` apparaît alors que `locationPublic=false`
   - → Bug dans le filtre SQL de `MapService.getUsersOnMap()`

3. **Zones à vérifier**

   | Test échoué | Zone à inspecter |
   |-------------|------------------|
   | `utilisateurMasque_...` | Filtre `WHERE location_public = true` |
   | `compteDesactive_...` | Filtre `WHERE is_active = true` |
   | `utilisateurHorsRayon_...` | Fonction PostGIS `ST_DWithin(...)` |
   | `positionAffichee_...` | Logique de floutage dans `MapService` |

4. **Corriger le bug**
   - **NE JAMAIS adapter le test** pour qu'il passe
   - Corriger le code backend
   - Réexécuter jusqu'à ce que le test passe

## 🔍 Debugging

### Voir les logs SQL

Ajouter dans `application-test.properties` :

```properties
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

### Inspecter la base de données pendant les tests

```bash
# Lister les conteneurs Testcontainers en cours
docker ps

# Se connecter à PostgreSQL (trouver le port dans les logs)
docker exec -it <container_id> psql -U test -d pair_test

# Vérifier les données
SELECT id, email, location_public, is_active FROM users;
```

### Augmenter le timeout des tests

Si les tests échouent par timeout :

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <junit.jupiter.execution.timeout.default>5m</junit.jupiter.execution.timeout.default>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

## 📚 Références

- **Spécification**: `src/main/resources/memories/pair-tests-validation-spec.md` (lignes 727-809)
- **Rapport détaillé**: `TEST_VALIDATION_REPORT.md`
- **Tests existants**: `src/test/java/org/program/pair/ChatFlowIntegrationTest.java` (exemple de structure)

## ⚠️ Points Critiques

### Aucun compromis sur la sécurité

Ces tests ne sont **PAS optionnels**. Ils valident des règles de sécurité critiques :

1. **Vie privée** : Un utilisateur masqué ne doit JAMAIS être exposé
2. **Comptes désactivés** : Doivent disparaître immédiatement de toutes les APIs
3. **Floutage** : Protection anti-stalking obligatoire

### Ne jamais modifier un test pour le faire passer

❌ **Mauvaise pratique** :
```java
// NE JAMAIS FAIRE ÇA
assertThat(results).noneMatch(u -> u.displayName().equals("userB") && results.size() > 0);
                                                                    // ^^^ condition ajoutée pour forcer le passage
```

✅ **Bonne pratique** :
- Le test échoue → Identifier le bug dans le backend
- Corriger le bug
- Le test passe naturellement

## 🎓 Pour Aller Plus Loin

### Ajouter de nouveaux tests de carte

1. Créer une nouvelle méthode `@Test` dans `MapVisibilityIntegrationTest`
2. Réutiliser les helpers existants (`registerAndLogin`, `updateLocation`, etc.)
3. Écrire les assertions avec AssertJ

### Exemple: Tester le filtre par activité

```java
@Test
void recherche_parActivite_doitRetournerUniquementLesCorrespondances() {
    String token = registerAndLogin("runner@pair.app");
    updateLocation(token, 48.8566, 2.3522);
    
    // Créer un programme de course
    UUID runningActivityId = UUID.fromString("...");
    
    // Chercher uniquement les coureurs
    List<MapUserDto> runners = getMapUsers(
        token, 48.8566, 2.3522, 5000, runningActivityId);
    
    assertThat(runners)
        .allMatch(u -> u.visibleActivities()
            .stream()
            .anyMatch(a -> a.activityId().equals(runningActivityId)));
}
```

## 📞 Support

En cas de problème :

1. **Vérifier Docker** : `docker ps` doit fonctionner
2. **Vérifier Java** : `java -version` doit afficher Java 17+
3. **Vérifier Maven** : `mvn -version` doit fonctionner
4. **Consulter les logs** : `target/surefire-reports/`
5. **Réexécuter avec debug** : `mvn test -Dtest=MapVisibilityIntegrationTest -X`

---

**Date de création**: 2026-06-30  
**Version**: 1.0  
**Statut**: ✅ Prêt à l'emploi  
