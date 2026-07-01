# 🚀 Guide Rapide - Exécution Tests Pair

## ✅ État Actuel
- **Tests unitaires** : ✅ 24/24 PASSENT (100%)
- **Tests d'intégration** : ⏳ En attente Docker

---

## 📋 Étapes pour Exécuter TOUS les Tests

### 1️⃣ Démarrer Docker Desktop

#### Windows
1. **Chercher** "Docker Desktop" dans le menu Démarrer
2. **Lancer** l'application
3. **Attendre** que l'icône Docker soit verte (en bas à droite)
4. **Vérifier** :
   ```bash
   docker ps
   ```
   Si cette commande fonctionne → Docker est prêt ✅

#### Si Docker n'est pas installé
- Télécharger : https://www.docker.com/products/docker-desktop/
- Installer et redémarrer Windows si nécessaire

---

### 2️⃣ Exécuter les Tests

#### Option A : Tous les tests (recommandé)
```bash
cd "C:\Users\paric\Downloads\core-spring-labfiles\core-spring-labfiles\Pair"
mvn clean verify
```

**Ce que ça fait** :
- ✅ Compile le projet
- ✅ Exécute les 24 tests unitaires
- ✅ Exécute les 24 tests d'intégration
- ✅ Démarre automatiquement PostgreSQL et Redis (Testcontainers)
- ✅ Arrête les containers après les tests

**Durée estimée** : 2-3 minutes

---

#### Option B : Tests unitaires seuls (SANS Docker)
```bash
mvn test -Dtest="AuthServiceTest,BadgeServiceTest,ChatServiceTest,ProgramServiceTest,PeerRecommendationServiceTest,ReviewServiceTest,UserServiceTest"
```

**Durée** : ~20 secondes

---

#### Option C : Tests d'intégration seuls
```bash
mvn test -Dtest="*IntegrationTest"
```

**Durée** : ~2-3 minutes

---

### 3️⃣ Générer le Rapport de Couverture

```bash
mvn clean verify jacoco:report
```

**Ouvrir le rapport** :
```bash
# Windows
start target/site/jacoco/index.html

# Ou manuellement
# Naviguer vers : target/site/jacoco/index.html
```

**Objectifs de couverture** :
- 🎯 **80%+** sur les services (logique métier)
- 🎯 **100%** sur les règles critiques (visibilité, crédibilité, sécurité)

---

## 📊 Résultats Attendus

### Succès Complet
```
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Si Échec
```
[ERROR] Tests run: 48, Failures: X, Errors: Y
[INFO] BUILD FAILURE
```

**Action** :
1. ❗ **Ne JAMAIS modifier un test pour le faire passer**
2. ✅ Lire le rapport d'erreur dans `target/surefire-reports/`
3. ✅ Corriger le code métier (pas le test)
4. ✅ Re-exécuter les tests

---

## 🐛 Problèmes Courants

### Problème 1 : Docker ne démarre pas
**Erreur** : `Status 503: Docker Desktop is unable to start`

**Solutions** :
1. Redémarrer Docker Desktop
2. Redémarrer Windows
3. Vérifier que la virtualisation est activée dans le BIOS
4. Réinstaller Docker Desktop

---

### Problème 2 : Tests d'intégration très lents
**Cause** : Premier téléchargement des images Docker

**Solution** : Normal la première fois (télécharge `pgvector/pgvector:pg16` + `redis:7-alpine`)
- Première exécution : ~5-10 minutes
- Exécutions suivantes : ~2-3 minutes

---

### Problème 3 : Port déjà utilisé
**Erreur** : `Address already in use`

**Solution** :
```bash
# Arrêter tous les containers Docker
docker stop $(docker ps -aq)

# Relancer les tests
mvn clean verify
```

---

### Problème 4 : Mémoire insuffisante
**Erreur** : `Out of memory`

**Solution** : Augmenter la RAM allouée à Docker
1. Ouvrir Docker Desktop
2. Settings → Resources → Memory
3. Augmenter à au moins **4GB**
4. Apply & Restart

---

## 📁 Fichiers de Sortie

### Rapports de Tests
```
target/surefire-reports/
├── TEST-*.xml              # Résultats JUnit XML
├── *.txt                   # Logs détaillés par test
└── index.html              # Rapport HTML
```

### Rapport de Couverture
```
target/site/jacoco/
├── index.html              # Page principale
├── org.program.pair/       # Packages
└── jacoco.xml              # Données XML
```

### Logs
```
target/
├── jacoco.exec             # Données binaires JaCoCo
└── maven.log               # Log Maven complet
```

---

## ⚡ Commandes Rapides

### Nettoyage
```bash
mvn clean
```

### Tests unitaires uniquement
```bash
mvn test -Dtest="*ServiceTest,!RateLimiterServiceTest"
```

### Tests d'intégration uniquement
```bash
mvn test -Dtest="*IntegrationTest"
```

### Un seul test
```bash
mvn test -Dtest=AuthServiceTest
```

### Une seule méthode de test
```bash
mvn test -Dtest=AuthServiceTest#register_devraitRejeter_siEmailDejaUtilise
```

### Skip tests
```bash
mvn clean install -DskipTests
```

### Verbose mode
```bash
mvn test -X
```

---

## 📋 Checklist de Validation

### Avant Déploiement

- [ ] Docker Desktop démarré et fonctionnel
- [ ] `mvn clean verify` → BUILD SUCCESS
- [ ] Rapport JaCoCo généré
- [ ] Couverture ≥ 80% sur services
- [ ] Couverture = 100% sur règles critiques
- [ ] Aucun test ignoré (Skipped: 0)
- [ ] Checklist sécurité manuelle exécutée (`SECURITY_CHECKLIST.md`)
- [ ] Tests Postman/curl validés
- [ ] Aucun warning de sécurité critique

---

## 🎯 Validation Complète

### Étape 1 : Tests Automatisés
```bash
# Nettoyer
mvn clean

# Tout exécuter avec couverture
mvn verify jacoco:report

# Vérifier le résultat
echo $?  # Doit être 0 (succès)
```

### Étape 2 : Rapport de Couverture
```bash
# Ouvrir le rapport
start target/site/jacoco/index.html

# Vérifier :
# - org.program.pair.domain.auth > 80%
# - org.program.pair.domain.user > 80%
# - org.program.pair.domain.program > 80%
# - org.program.pair.domain.chat > 80%
# - org.program.pair.domain.review > 80%
```

### Étape 3 : Checklist Manuelle
```bash
# Démarrer l'application
mvn spring-boot:run

# Dans un autre terminal, exécuter SECURITY_CHECKLIST.md
# Utiliser Postman ou curl pour chaque point
```

---

## 🏆 Critères de Réussite

| Critère | Objectif | Statut |
|---------|----------|--------|
| Compilation | BUILD SUCCESS | ✅ Validé |
| Tests unitaires | 24/24 PASS | ✅ Validé |
| Tests intégration | 24/24 PASS | ⏳ Nécessite Docker |
| Couverture services | ≥ 80% | ⏳ |
| Couverture critique | = 100% | ⏳ |
| Checklist sécurité | 26/26 OK | ⏳ |
| Performance | < 5 min | ⏳ |

---

## 📞 Support

### Logs Utiles
```bash
# Logs Maven complets
mvn test > logs.txt 2>&1

# Logs Docker
docker logs <container_id>

# Logs Testcontainers
cat target/surefire-reports/*.txt
```

### Debugging
```bash
# Mode debug Maven
mvn test -X -Dtest=AuthServiceTest

# Mode debug Java
mvn test -Dmaven.surefire.debug
# Puis attacher debugger sur port 5005
```

---

## ✅ Résumé

**Pour exécuter tous les tests** :
1. Démarrer Docker Desktop
2. Exécuter `mvn clean verify`
3. Vérifier BUILD SUCCESS
4. Consulter `target/site/jacoco/index.html`

**Temps total** : ~3-5 minutes (première fois : ~10 minutes)

**Résultat attendu** : 48/48 tests PASS ✅

---

**Guide créé le** : 30 juin 2026  
**Version** : 1.0  
**Projet** : Pair Backend Tests
