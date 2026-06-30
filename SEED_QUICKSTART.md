# Guide de démarrage rapide - Système de Seeds

## 🚀 Démarrage immédiat

### En développement (avec données demo)

```bash
# Option 1 : Maven
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Option 2 : Gradle
./gradlew bootRun --args='--spring.profiles.active=dev'

# Option 3 : JAR
java -jar target/pair-backend.jar --spring.profiles.active=dev
```

**Résultat** : L'application crée automatiquement :
- ✅ 10 catégories d'activités
- ✅ 38 activités avec hiérarchie
- ✅ 14 badges de confiance et d'accomplissement
- ✅ 20 utilisateurs demo (demo1@pair.app à demo20@pair.app)
- ✅ ~40 programmes avec horaires et lieux
- ✅ Embeddings pour la recherche sémantique

### En production (sans données demo)

```bash
java -jar pair-backend.jar --spring.profiles.active=prod
```

**Résultat** : L'application crée uniquement les données de référence (catégories, activités, badges).
**Sécurité** : Les données demo sont **bloquées** en production. Tentative = crash immédiat.

## 📊 Vérifier que tout fonctionne

### 1. Vérifier les logs au démarrage

Recherchez ces lignes dans les logs :

```
✅ Catégories : 10 créées, 0 déjà présentes
✅ Activités : 38 créées, 0 déjà présentes
✅ Badges : 14 créés, 0 déjà présents
✅ 20 comptes de démonstration créés
```

### 2. Tester la carte interactive

```bash
curl http://localhost:8090/api/map/users?lat=48.8566&lng=2.3522&radiusKm=10
```

**Attendu** : Liste de 15-20 utilisateurs demo avec positions floutées.

### 3. Tester la recherche sémantique

```bash
curl -X POST http://localhost:8090/api/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "je veux faire du yoga",
    "lat": 48.8566,
    "lng": 2.3522,
    "radiusKm": 10
  }'
```

**Attendu** : Au moins un programme de yoga retourné (demo1@pair.app - Camille).

### 4. Se connecter avec un compte demo

```bash
curl -X POST http://localhost:8090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "demo1@pair.app",
    "password": "Demo1234!"
  }'
```

**Attendu** : JWT token retourné.

## 🔄 Réinitialiser les données demo

### Pendant le développement

```bash
# Supprimer et recréer tous les comptes demo
curl -X POST http://localhost:8090/api/admin/seed/demo/reset
```

**Utile quand** :
- Vous voulez repartir de zéro
- Vous avez modifié les profils demo
- Les données sont corrompues lors de tests

## 🛑 Tester le garde-fou de sécurité

### Test 1 : Bloquer les données demo en production

1. Modifier `src/main/resources/application-prod.properties` :
   ```properties
   pair.seed.demo-data.enabled=true  # ⚠️ NE JAMAIS FAIRE EN VRAI
   ```

2. Lancer en production :
   ```bash
   java -jar pair-backend.jar --spring.profiles.active=prod
   ```

3. **Résultat attendu** : 
   ```
   ERROR REFUS DE SÉCURITÉ : pair.seed.demo-data.enabled=true détecté en profil 'prod'.
   Les données de démonstration ne doivent jamais être créées en production.
   
   Exception in thread "main" java.lang.IllegalStateException
   ```

✅ L'application **refuse de démarrer**. Sécurité validée.

4. Restaurer la configuration :
   ```properties
   pair.seed.demo-data.enabled=false
   ```

## 📝 Comptes demo disponibles

| Email | Nom | Activités |
|-------|-----|-----------|
| demo1@pair.app | Camille Bertrand | Yoga, Céramique |
| demo2@pair.app | Karim Haddad | Course à pied, Trail |
| demo3@pair.app | Léa Moreau | Échecs, Jeux de société |
| demo4@pair.app | Thomas Girard | Guitare, Jam session |
| demo5@pair.app | Sophie Lefebvre | Escalade, Randonnée |
| demo6@pair.app | Antoine Petit | Photographie |
| demo7@pair.app | Marine Dubois | Cuisine du monde |
| demo8@pair.app | Hugo Rousseau | Programmation |
| demo9@pair.app | Inès Benali | Bénévolat environnement |
| demo10@pair.app | Nicolas Faure | Danse |
| demo11@pair.app | Emma Laurent | Natation |
| demo12@pair.app | Lucas Martin | Football |
| demo13@pair.app | Sarah Cohen | Méditation |
| demo14@pair.app | Alexandre Dubois | VTT |
| demo15@pair.app | Chloé Bernard | Tennis |
| demo16@pair.app | Maxime Roux | Jeux vidéo |
| demo17@pair.app | Julie Petit | Peinture |
| demo18@pair.app | Pierre Leroy | Jeux de rôle |
| demo19@pair.app | Anaïs Moreau | Œnologie |
| demo20@pair.app | Benjamin Girard | Écriture |

**Mot de passe pour tous** : `Demo1234!`

## 🔧 Configuration avancée

### Changer le centre géographique des utilisateurs demo

Par défaut : Paris (48.8566, 2.3522)

```properties
# Dans application-dev.properties
seed.center-lat=45.7640
seed.center-lng=4.8357
```

Les utilisateurs demo seront dispersés dans un rayon de ~8km autour de Lyon.

### Désactiver les seeds

```properties
# Dans application.properties ou application-{profile}.properties
pair.seed.reference-data.enabled=false
pair.seed.demo-data.enabled=false
```

L'application démarre sans créer aucune donnée.

### Changer l'API d'embeddings

```properties
# Dans application.properties
embedding.api-url=https://api.openai.com/v1/embeddings
embedding.api-key=${OPENAI_API_KEY}
embedding.model=text-embedding-3-small
```

Les embeddings sont générés automatiquement pour :
- ✅ Les activités de référence (asynchrone, 200ms de throttle)
- ✅ Les programmes demo (synchrone)

## 🐛 Résolution de problèmes

### Problème : "Aucune donnée créée"

**Cause** : Seeds désactivés dans la configuration.

**Solution** :
```properties
pair.seed.reference-data.enabled=true
pair.seed.demo-data.enabled=true  # En dev/staging uniquement
```

### Problème : "Embeddings null dans la base"

**Cause** : API d'embeddings non configurée ou clé invalide.

**Solution** :
1. Vérifier la clé API :
   ```bash
   echo $OPENAI_API_KEY
   ```
2. Ajouter la clé dans `.env` ou `application.properties` :
   ```properties
   embedding.api-key=sk-proj-...
   ```
3. Relancer l'application.

### Problème : "Duplicate key value violates unique constraint"

**Cause** : Tentative de créer des données déjà existantes (idempotence cassée).

**Solution** :
1. Vérifier les logs : "X créées, Y déjà présentes"
2. Si les logs disent "0 créées, X déjà présentes", tout va bien (comportement normal)
3. Si erreur malgré tout, vérifier que les méthodes `existsByName`, `existsBySlug`, `existsByCode` sont bien implémentées dans les repositories

### Problème : "Cannot create demo data in production"

**Cause** : Tentative d'activer les données demo en production (garde-fou activé).

**Solution** :
1. C'est **normal et souhaité** ! Le système vous protège.
2. En production, seules les données de référence sont autorisées.
3. Désactiver `pair.seed.demo-data.enabled` en production.

## 📚 Documentation complète

Pour plus de détails, voir :
- `SEED_IMPLEMENTATION_SUMMARY.md` : Documentation technique complète
- `src/main/resources/memories/pair-seed-data-spec.md` : Spécification originale

## 💡 Conseils

1. **Premier lancement** : Laisser les seeds activés pour avoir des données de test.
2. **Développement quotidien** : Garder les données demo (pas besoin de reset à chaque fois).
3. **Tests E2E** : Utiliser `/api/admin/seed/demo/reset` pour repartir d'un état propre.
4. **Production** : Vérifier que `pair.seed.demo-data.enabled=false` avant déploiement.
5. **CI/CD** : Tester avec `--spring.profiles.active=prod` pour valider les garde-fous.

---

🎉 **Prêt à développer !** Les données de référence et de démonstration sont automatiquement créées au premier démarrage.
