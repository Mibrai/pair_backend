# meetDo — Activer les push : credentials Firebase sur Railway

## Prompt à donner à Claude Code (backend `pair_backend`)

```
Le backend est déployé sur Railway, où l'on ne peut pas déposer de fichier
sur le disque du conteneur (reconstruit à chaque déploiement) ni committer
un secret dans le dépôt (le repo Mibrai/pair_backend est public).

FirebaseConfig ne sait aujourd'hui lire les identifiants que depuis un
chemin de fichier ou le classpath — aucune de ces deux voies n'est
utilisable en production. Ajoute une troisième source : une variable
d'environnement contenant le JSON de compte de service encodé en base64.

### 1. Nouvelle propriété

Dans src/main/resources/application.properties, à côté des deux lignes
Firebase existantes :

firebase.credentials-base64=${FIREBASE_CREDENTIALS_BASE64:}

Ne touche pas à firebase.enabled ni à firebase.credentials-path : les
deux restent valides pour l'usage local.

### 2. Modifier FirebaseConfig

Injecte la nouvelle propriété :

@Value("${firebase.credentials-base64:}")
private String credentialsBase64;

Puis fais évoluer la résolution des identifiants avec cet ordre de
priorité, sans casser le comportement existant :

1. firebase.credentials-base64 s'il est renseigné (production / Railway)
2. sinon firebase.credentials-path (développement local, comportement actuel)
3. si les deux sont vides → lever IllegalStateException, en mentionnant
   désormais LES DEUX variables possibles dans le message

Le décodage base64 :

byte[] decoded = Base64.getDecoder().decode(credentialsBase64.trim());
return new ByteArrayInputStream(decoded);

Le .trim() est important : une variable d'environnement copiée-collée
depuis un terminal traîne souvent un retour à la ligne, qui fait échouer
le décodage avec une erreur peu explicite.

### 3. Garder la qualité des messages d'erreur

Le fichier actuel a des messages d'erreur soignés et une javadoc qui
explique pourquoi l'échec est volontairement bruyant. Préserve cet esprit :

- Si le base64 est présent mais invalide (IllegalArgumentException au
  décodage), lève une erreur qui dit explicitement que la variable
  FIREBASE_CREDENTIALS_BASE64 est présente mais mal encodée — plutôt
  qu'une erreur générique d'initialisation Firebase. Un secret mal collé
  est l'erreur la plus probable en pratique, le message doit la nommer.
- Dans le message d'échec général, ne mentionne jamais le contenu de
  credentialsBase64 (c'est un secret), contrairement à credentialsPath
  qui peut rester dans le message puisque c'est un simple chemin.
- Ajoute au log de succès la source utilisée, sans divulguer le secret :
  log.info("Firebase initialized successfully (push notifications enabled, source: {})", source)
  où source vaut "base64" ou le chemin.

### 4. Mettre à jour la javadoc de classe

Le commentaire de classe explique les deux régimes (enabled true/false).
Complète-le pour mentionner les deux sources d'identifiants possibles et
laquelle sert en production, afin que la prochaine personne qui lit ce
fichier n'ait pas à deviner pourquoi il y a deux chemins.

### 5. Test

Ajoute un test unitaire couvrant :
- base64 valide → InputStream contenant bien le JSON attendu
- base64 avec espaces/retour à la ligne autour → décodage réussi (le trim)
- base64 invalide → exception avec un message qui nomme la variable
- les deux propriétés vides → IllegalStateException

N'ajoute pas de test qui initialiserait réellement FirebaseApp (appel
réseau, credentials réels) — teste uniquement la résolution de la source.

Ne modifie aucun autre fichier que FirebaseConfig, application.properties,
et le nouveau fichier de test.
```

---

## Ce que tu fais ensuite, toi (hors Claude Code)

### 1. Générer la clé de compte de service

console.firebase.google.com → projet `meetdo-76ab7` → ⚙️ **Paramètres du
projet** → onglet **Comptes de service** → **Générer une nouvelle clé
privée**.

Un fichier JSON se télécharge. Il contient une clé privée : ne jamais le
committer, ne jamais le partager.

### 2. L'encoder et l'injecter dans Railway

```bash
# Encode le JSON et le copie dans le presse-papier (sans retour à la ligne)
base64 -i ~/Downloads/meetdo-76ab7-firebase-adminsdk-XXXXX.json | tr -d '\n' | pbcopy
```

```bash
cd ~/IdeaProjects/pair_backend
railway service                       # sélectionner pair_backend_service
railway variables --set "FIREBASE_ENABLED=true"
railway variables --set "FIREBASE_CREDENTIALS_BASE64=<coller ici>"
```

> ⚠️ **Ordre important** : ne pose `FIREBASE_ENABLED=true` qu'**après**
> avoir renseigné le base64. Sinon le prochain démarrage échouera
> volontairement (c'est le comportement voulu de `FirebaseConfig`, mais
> autant éviter un déploiement rouge inutile).

### 3. Déployer et vérifier

```bash
railway up
railway logs --tail 100
```

Cherche dans les logs :
```
Firebase initialized successfully (push notifications enabled, source: base64)
```

Si le message `Firebase is disabled. Push notifications will not work.`
apparaît encore, c'est que `FIREBASE_ENABLED` n'est pas à `true` sur le
bon service.

### 4. Vérifier la clé APNs côté Firebase (iOS uniquement)

Sans elle, les push partent vers Android mais pas vers iOS, **sans erreur
visible côté backend** — le symptôme est silencieux et trompeur.

console.firebase.google.com → ⚙️ Paramètres du projet → onglet **Cloud
Messaging** → section **Apple app configuration** : la clé APNs doit y
être listée avec son Key ID.

Rappel : la clé `KT3C8A6WA4` ayant transité par une conversation, elle est
à révoquer sur developer.apple.com → Keys, et à remplacer par une nouvelle.

### 5. Test de bout en bout

Depuis l'app installée sur ton iPhone (compte de test
`lena.mueller@web.de`), déclenche une action qui produit une notification
— par exemple faire rejoindre l'un de tes créneaux par un second compte,
ou envoyer un message depuis un autre appareil.

Points à vérifier dans l'ordre :
1. Le jeton d'appareil est bien enregistré → `GET /api/notifications/devices`
   doit lister l'appareil
2. La notification arrive app **fermée** (le cas le plus révélateur)
3. Le badge d'icône affiche le bon nombre
4. Le tap sur la notification ouvre le bon écran
