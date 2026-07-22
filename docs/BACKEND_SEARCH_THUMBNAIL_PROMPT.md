# Prompt à coller dans le Claude Code du dépôt backend

---

Amélioration à apporter à la **recherche** (`POST /api/search`) : le champ `thumbnailUrl` des résultats de type `program` doit privilégier l'**image de couverture dédiée du programme** (`Program.imageUrl`, migration V37) plutôt que le premier élément de la galerie `media`.

## Contexte

Depuis V37, un programme porte un champ `imageUrl` (image de couverture unique, distincte de la galerie `media[]` et de `organizerAvatarUrl`). Le frontend mobile l'utilise déjà comme cover sur la page détail. Mais dans les résultats de recherche, le `SearchResultDto.thumbnailUrl` est actuellement rempli à partir de la **galerie `media`** (`media[0]`), pas de `imageUrl`.

Conséquence observée en live (query « Yoga », `POST /api/search` avec `lat`/`lng`) :

| Programme | a un `imageUrl` ? | a un `media[0]` ? | `thumbnailUrl` renvoyé |
|---|---|---|---|
| Hatha Yoga für Einsteiger | oui | oui (unsplash) | ✅ URL (= media[0]) |
| Yoga für Fortgeschrittene | oui | non | ❌ `null` |
| Vinyasa Flow | oui | non | ❌ `null` |

Donc un programme qui a une **couverture uploadée** mais pas de galerie ne renvoie **aucune vignette** en recherche, alors qu'il a une image parfaitement valide. Côté app, sa tuile de résultat retombe sur un placeholder générique.

## Ce qu'il faut changer

Dans le service qui construit les `SearchResultDto` pour les programmes (probablement un `SearchService` / mapper `Program → SearchResultDto`), renseigner `thumbnailUrl` avec une **priorité** :

```
thumbnailUrl = program.imageUrl
             ?: (program.media.isEmpty ? null : program.media.first.url)
```

C'est-à-dire : `imageUrl` d'abord, repli sur le premier média de la galerie, sinon `null`. C'est le même ordre de priorité que celui appliqué côté frontend sur la page détail (`imageUrl` puis galerie), pour que recherche et détail soient cohérents.

## Contraintes

- Ne change **pas** le nom du champ (`thumbnailUrl`) ni le contrat du DTO : le frontend le consomme déjà. C'est uniquement la **source** de la valeur qui change.
- `imageUrl` est une URL API-relative (`/api/media/files/program_image/…`) qui peut nécessiter une auth à la lecture — le frontend gère déjà ce cas (chargement via jeton frais). Renvoie l'URL telle quelle, comme pour les avatars.
- Vérifie que les autres endroits qui produisent un `thumbnailUrl` de programme (s'il y en a plusieurs) appliquent la même priorité, pour éviter les incohérences.

## Test attendu

Ajoute/complète un test du mapping recherche : un programme **avec `imageUrl` et sans `media`** doit produire un `SearchResultDto.thumbnailUrl == program.imageUrl` (et non `null`). Un programme avec les deux doit renvoyer `imageUrl` en priorité.

## Reproduction (API déployée)

```bash
BASE="https://pairbackend-production-35fe.up.railway.app/api"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  -d '{"email":"<compte de test>","password":"<mot de passe>"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

# lat/lng sont requis par POST /search :
curl -s -X POST "$BASE/search" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"Yoga","lat":48.1351,"lng":11.582,"limit":10}' \
  | python3 -c "import json,sys; [print(r['title'], '->', r.get('thumbnailUrl')) for r in json.load(sys.stdin)['results'] if r.get('resultType')=='program']"
# Attendu après correctif : chaque programme ayant un imageUrl renvoie une thumbnailUrl non nulle.
```
