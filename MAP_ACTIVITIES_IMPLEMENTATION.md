# Implémentation de la page Map - Affichage des activités

## Vue d'ensemble

Cette implémentation permet d'afficher **toutes les activités** présentes dans la base de données sur une carte interactive. Chaque activité est représentée par un badge contenant :
- Une icône illustrant sa catégorie
- Le nom de la catégorie
- Le titre de l'activité
- La distance entre le lieu de l'activité et la position actuelle de l'utilisateur (si la géolocalisation est activée)

La carte s'affiche toujours, même sans géolocalisation. Dans ce cas, la zone avec le plus d'activités est localisée par défaut.

## Architecture

### 1. Modèle de données

#### Entités existantes
- **Activity**: L'activité (ex: Tennis, Yoga) - pas de localisation propre
- **Category**: La catégorie d'activité (ex: Sport, Bien-être) avec icône et palette de couleurs
- **Program**: Un programme créé par un utilisateur pour une activité
- **Schedule**: Les horaires et lieux d'un programme (contient la localisation via PostGIS)

#### Nouveaux DTOs créés

**MapActivityMarkerDto** (`/domain/map/dto/MapActivityMarkerDto.java`)
```java
public record MapActivityMarkerDto(
    UUID activityId,           // Identifiant unique de l'activité
    String activityName,       // Nom de l'activité
    String activitySlug,       // Slug pour l'URL
    String categoryName,       // Nom de la catégorie
    String categoryIcon,       // Icône de la catégorie (emoji ou URL)
    String categoryColorRamp,  // Palette de couleurs
    double lat,                // Latitude
    double lng,                // Longitude
    Double distanceKm,         // Distance en km (null si pas de géolocalisation)
    int programCount           // Nombre de programmes à cet emplacement
)
```

**MapActivitiesResponse** (`/domain/map/dto/MapActivitiesResponse.java`)
```java
public record MapActivitiesResponse(
    List<MapActivityMarkerDto> activities,
    DefaultMapCenter defaultCenter
) {
    public record DefaultMapCenter(
        double lat,    // Centre par défaut
        double lng,
        int zoom       // Niveau de zoom recommandé
    )
}
```

### 2. Logique métier

#### MapService.getAllActivitiesForMap()
Fichier: `/domain/map/MapService.java`

**Algorithme:**
1. Récupère toutes les activités de la base de données
2. Récupère tous les schedules (qui contiennent les localisations)
3. Construit une map Activity → List<Schedule>
4. Pour chaque activité:
   - Regroupe les schedules par localisation (arrondi à 1km)
   - Calcule la distance si les coordonnées utilisateur sont fournies
   - Crée un MapActivityMarkerDto
5. Calcule le centre par défaut basé sur la densité d'activités

**Méthodes principales:**
- `calculateDistance(lat1, lng1, lat2, lng2)`: Formule de Haversine pour calculer la distance orthodromique
- `calculateDefaultCenter(markers)`: Trouve la zone avec la plus haute densité d'activités via un grid clustering

### 3. Contrôleur

#### MapController.getAllActivitiesForMap()
Fichier: `/domain/map/MapController.java`

**Endpoint:**
```
GET /api/map/activities?userLat={latitude}&userLng={longitude}
```

**Paramètres:**
- `userLat` (optional): Latitude de l'utilisateur
- `userLng` (optional): Longitude de l'utilisateur

**Authentification:** Requise (JWT Bearer token)

**Réponse:** MapActivitiesResponse (JSON)

## Comportements spécifiques

### 1. Géolocalisation activée
- L'utilisateur fournit `userLat` et `userLng`
- Chaque activité affiche sa distance en kilomètres (arrondie à 1 décimale)
- Les activités peuvent être triées par distance côté frontend

### 2. Géolocalisation désactivée
- `userLat` et `userLng` ne sont pas fournis
- `distanceKm` est null pour toutes les activités
- La carte se centre automatiquement sur la zone avec le plus d'activités

### 3. Centre par défaut
Le centre par défaut est calculé de manière intelligente:
- Grid clustering avec des cellules de ~10km
- Trouve la cellule avec le plus d'activités
- Calcule le centre de cette cellule
- Ajuste le zoom en fonction de la densité:
  - `zoom: 13` → Plus de 20 activités
  - `zoom: 12` → 10-20 activités
  - `zoom: 11` → Moins de 10 activités

### 4. Regroupement des marqueurs
- Plusieurs programmes de la même activité au même endroit (à 1km près) sont regroupés
- Un seul marqueur est affiché avec `programCount` indiquant le nombre de programmes
- Évite la surcharge visuelle de marqueurs superposés

## Tests

### Tests d'intégration
Fichier: `/test/integration/MapActivitiesIntegrationTest.java`

Tests couverts:
1. ✅ Récupération sans géolocalisation (distances null)
2. ✅ Récupération avec géolocalisation (distances calculées)
3. ✅ Centre par défaut toujours valide
4. ✅ Authentification requise (401 sans token)
5. ✅ Informations de catégorie présentes dans les marqueurs

## Utilisation Frontend

### 1. Appel API

**Sans géolocalisation:**
```typescript
const response = await fetch('/api/map/activities', {
  headers: {
    'Authorization': `Bearer ${token}`
  }
});
const data: MapActivitiesResponse = await response.json();
```

**Avec géolocalisation:**
```typescript
navigator.geolocation.getCurrentPosition(async (position) => {
  const params = new URLSearchParams({
    userLat: position.coords.latitude.toString(),
    userLng: position.coords.longitude.toString()
  });
  
  const response = await fetch(`/api/map/activities?${params}`, {
    headers: {
      'Authorization': `Bearer ${token}`
    }
  });
  const data: MapActivitiesResponse = await response.json();
});
```

### 2. Affichage sur la carte

**Initialisation:**
```typescript
// Centrer la carte sur le centre par défaut
const map = new Map({
  center: [data.defaultCenter.lat, data.defaultCenter.lng],
  zoom: data.defaultCenter.zoom
});
```

**Affichage des marqueurs:**
```typescript
data.activities.forEach(activity => {
  const marker = new Marker([activity.lat, activity.lng]);
  
  const badge = `
    <div class="activity-badge" style="background: ${activity.categoryColorRamp}">
      <div class="icon">${activity.categoryIcon}</div>
      <div class="category">${activity.categoryName}</div>
      <div class="name">${activity.activityName}</div>
      ${activity.distanceKm ? `<div class="distance">${activity.distanceKm} km</div>` : ''}
      ${activity.programCount > 1 ? `<div class="count">${activity.programCount} programmes</div>` : ''}
    </div>
  `;
  
  marker.bindPopup(badge);
  marker.addTo(map);
});
```

### 3. Gestion de la géolocalisation

```typescript
function MapPage() {
  const [userLocation, setUserLocation] = useState<{lat: number, lng: number} | null>(null);
  
  useEffect(() => {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          setUserLocation({
            lat: position.coords.latitude,
            lng: position.coords.longitude
          });
        },
        (error) => {
          console.log('Géolocalisation non activée');
          // Charger quand même la carte avec le centre par défaut
        }
      );
    }
  }, []);
  
  // Fetch activities avec ou sans userLocation
}
```

## Performance et optimisations

### Backend
- ✅ Index PostGIS sur `schedules.location` (GIST)
- ✅ Regroupement des activités par localisation (évite les doublons)
- ✅ Calcul efficace du centre par défaut via grid clustering

### Frontend (recommandations)
- 🔄 Clustering des marqueurs pour grandes quantités (ex: Leaflet.markercluster)
- 🔄 Cache des activités (refresh périodique ou sur action utilisateur)
- 🔄 Virtualisation si liste des activités également affichée
- 🔄 Lazy loading des images d'icônes

## Sécurité

### Authentification
- ✅ Endpoint protégé par JWT
- ✅ Seuls les utilisateurs authentifiés peuvent accéder

### Données exposées
- ✅ Pas de données personnelles d'utilisateurs
- ✅ Seules les localisations publiques des programmes sont affichées
- ✅ Pas de risque de tracking individuel

## Extensions futures possibles

### Filtres
```java
// Ajouter des paramètres de filtrage
@GetMapping("/activities")
public MapActivitiesResponse getAllActivitiesForMap(
    @RequestParam(required = false) Double userLat,
    @RequestParam(required = false) Double userLng,
    @RequestParam(required = false) List<UUID> categoryIds,  // Filtrer par catégories
    @RequestParam(required = false) Integer maxDistanceKm,   // Limiter la distance
    @RequestParam(required = false) String searchQuery       // Recherche textuelle
)
```

### Pagination
Pour de très grandes quantités d'activités:
```java
@RequestParam(defaultValue = "0") int page,
@RequestParam(defaultValue = "100") int size
```

### Bounds
Charger uniquement les activités visibles sur la carte:
```java
@RequestParam double northEast_lat,
@RequestParam double northEast_lng,
@RequestParam double southWest_lat,
@RequestParam double southWest_lng
```

## Fichiers modifiés/créés

### Nouveaux fichiers
- ✅ `src/main/java/org/program/pair/domain/map/dto/MapActivityMarkerDto.java`
- ✅ `src/main/java/org/program/pair/domain/map/dto/MapActivitiesResponse.java`
- ✅ `src/test/java/org/program/pair/integration/MapActivitiesIntegrationTest.java`
- ✅ Frontend doc: `pair_frontend/src/api/MAP_ACTIVITIES_ENDPOINT.md`
- ✅ Ce README: `MAP_ACTIVITIES_IMPLEMENTATION.md`

### Fichiers modifiés
- ✅ `src/main/java/org/program/pair/domain/map/MapService.java`
  - Ajout de `getAllActivitiesForMap()`
  - Ajout de `calculateDistance()`
  - Ajout de `calculateDefaultCenter()`
  
- ✅ `src/main/java/org/program/pair/domain/map/MapController.java`
  - Ajout de l'endpoint `/api/map/activities`

## Migration de base de données

**Aucune migration nécessaire** ✅

L'implémentation utilise la structure existante:
- Table `activities` (déjà présente)
- Table `categories` (déjà présente)
- Table `programs` (déjà présente)
- Table `schedules` (déjà présente avec GEOMETRY(Point))

## Compilation et tests

### Compilation
```bash
cd F:/Projekt/Pair/pair_backend
mvn clean compile
```

### Tests
```bash
mvn test -Dtest=MapActivitiesIntegrationTest
```

### Lancer l'application
```bash
mvn spring-boot:run
```

### Tester l'endpoint manuellement
```bash
# Sans géolocalisation
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/map/activities

# Avec géolocalisation
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  "http://localhost:8080/api/map/activities?userLat=48.8566&userLng=2.3522"
```

## Support

Pour toute question ou bug, référez-vous à:
- Documentation API: `/api-docs` (Swagger UI)
- Code source: `/domain/map/MapService.java`
- Tests: `/test/integration/MapActivitiesIntegrationTest.java`
- Frontend doc: `pair_frontend/src/api/MAP_ACTIVITIES_ENDPOINT.md`
