# 🚀 MapVisibilityIntegrationTest - Démarrage Rapide

## ✅ Statut

**Implémentation**: ✅ TERMINÉE  
**Compilation**: ✅ RÉUSSIE  
**Fichier**: `src/test/java/org/program/pair/integration/MapVisibilityIntegrationTest.java`

---

## 📝 Ce qui a été implémenté

### 4 Tests Critiques de Sécurité

1. ✅ **utilisateurMasque_neDoitJamaisApparaitreSurLaCarte**
   - Valide que `locationPublic=false` → utilisateur invisible

2. ✅ **compteDesactive_neDoitJamaisApparaitreSurLaCarte**
   - Valide que `is_active=false` → utilisateur invisible

3. ✅ **utilisateurHorsRayon_neDoitPasApparaitre**
   - Valide que la recherche géographique respecte le rayon

4. ✅ **positionAffichee_doitEtreFlouttee_pasExacte**
   - Valide que les coordonnées sont floutées (anti-stalking)

### 5 Méthodes Helper Réutilisables

1. `registerAndLogin(email)` → Enregistre et connecte un utilisateur
2. `updateLocation(token, lat, lng)` → Met à jour la position
3. `updateProfile(token, fields)` → Met à jour le profil
4. `deactivateAccount(token)` → Désactive le compte
5. `getMapUsers(token, lat, lng, radius)` → Récupère les utilisateurs sur la carte

---

## 🏃 Exécuter les Tests (3 étapes)

### Étape 1: Démarrer Docker
```bash
# Vérifier que Docker fonctionne
docker ps
```

### Étape 2: Exécuter les tests

**Windows**:
```cmd
run-map-visibility-tests.bat
```

**Linux/macOS/Git Bash**:
```bash
./run-map-visibility-tests.sh
```

**Ou avec Maven direct**:
```bash
mvn test -Dtest=MapVisibilityIntegrationTest
```

### Étape 3: Analyser les résultats

- ✅ **Tous passent** → Modèle de confiance validé
- ❌ **Des échecs** → Bugs de sécurité à corriger dans le backend

---

## 📚 Documentation Complète

- **Guide utilisateur**: `MAP_VISIBILITY_TESTS_README.md`
- **Rapport détaillé**: `TEST_VALIDATION_REPORT.md`
- **Résumé technique**: `MAP_VISIBILITY_IMPLEMENTATION_SUMMARY.md`

---

## ⚠️ Important

Ces tests valident le **CŒUR de la sécurité** de l'application.  
**Aucun compromis** n'est acceptable si un test échoue.

---

**Date**: 2026-06-30  
**Statut**: ✅ PRÊT
