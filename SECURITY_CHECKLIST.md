# Checklist sécurité — Pair Backend

## Authentification
- [ ] Un token JWT expiré est bien rejeté (401)
- [ ] Un token JWT modifié manuellement (signature invalide) est rejeté
- [ ] Le refresh token ne peut pas être utilisé comme access token
- [ ] Les mots de passe en base sont bien hashés (vérifier en BDD directement)
- [ ] Le rate limiting bloque après N tentatives de login

## Visibilité & vie privée
- [ ] Un utilisateur avec locationPublic=false n'apparaît jamais sur /api/map/users
- [ ] Un compte désactivé (is_active=false) n'apparaît dans aucun endpoint public
- [ ] L'adresse d'un Schedule PRIVATE sans showExactAddress reste null dans la réponse
- [ ] La position sur la carte est toujours flouttée (jamais lat/lng exacts)
- [ ] Le statut "en ligne" respecte onlineStatusVisible

## Contenu utilisateur
- [ ] Un payload XSS dans bio/description/message est neutralisé
- [ ] Un payload SQL dans n'importe quel champ texte ne casse rien
- [ ] Un upload de fichier non-image (renommé .jpg) est rejeté
- [ ] Un upload de fichier > 5MB est rejeté

## Crédibilité
- [ ] Impossible de laisser un avis sur son propre programme
- [ ] Impossible de laisser un avis sans conversation préalable
- [ ] Impossible de laisser 2 avis sur le même programme
- [ ] Impossible de se recommander soi-même
- [ ] Impossible de recommander sans conversation préalable

## Chat
- [ ] Impossible de rejoindre une conversation dont on n'est pas membre
- [ ] Un message ne peut pas être envoyé à un utilisateur receiveMessages=false
- [ ] La connexion WebSocket échoue sans token valide

## Infrastructure
- [ ] HTTPS est forcé en production (redirection HTTP → HTTPS)
- [ ] Les variables sensibles (DB, JWT secret, clés API) ne sont pas en dur dans le code
- [ ] Les logs ne contiennent jamais de mot de passe ni de token complet
- [ ] La stack trace n'est jamais exposée dans une réponse API en production
