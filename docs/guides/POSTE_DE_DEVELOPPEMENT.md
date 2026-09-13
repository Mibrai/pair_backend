# Poste de développement — ce qu'il faut poser soi-même

Depuis P-BS-12, le dépôt ne contient plus ni magasin de clés ni mot de passe de base :
ils étaient publiés. Le profil `dev` les attend du poste.

## Base de données

```bash
export DB_PASSWORD='<le mot de passe de votre base locale>'
```

Sans `DB_PASSWORD`, l'application refuse de démarrer — elle ne retombe plus sur un mot
de passe écrit dans la configuration.

## HTTPS de développement (8090) et HTTP clair (8091)

Générer une fois le magasin de clés auto-signé, dans `local/` (ignoré par git) :

```bash
mkdir -p local
keytool -genkeypair -alias pair -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore local/pair-keystore.p12 -validity 365 -dname "CN=localhost"
export SSL_KEYSTORE_PASSWORD='<le mot de passe choisi à la génération>'
```

Puis lancer avec le profil `dev`. Le connecteur HTTP clair 8091 ne s'ouvre qu'en `dev`.
Un autre emplacement de magasin se donne par `SSL_KEYSTORE_PATH=file:/chemin/vers.p12`.

## Tests

Rien à poser : le profil `test` coupe le SSL et reçoit sa base de Testcontainers.
