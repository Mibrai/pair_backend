# Resend Email - Quick Start

Guide rapide pour configurer l'envoi d'emails via Resend.

## 🚀 Setup en 3 étapes

### 1. Obtenir une clé API Resend

```bash
# 1. Créer un compte: https://resend.com/signup
# 2. Vérifier le domaine: meetdo.fun
# 3. Créer une clé API dans le dashboard
# 4. Copier la clé (format: re_xxx)
```

### 2. Configurer Railway

```bash
railway variables set RESEND_ENABLED=true
railway variables set RESEND_API_KEY=re_xxxxxxxxxxxxxxxxxxxxxxxxxx
railway variables set RESEND_FROM_EMAIL=infos@meetdo.fun
railway variables set FRONTEND_URL=https://meetdo.fun
```

### 3. Déployer

```bash
git push origin main
# Railway redémarre automatiquement
```

## ✅ Vérification

```bash
# 1. Pas d'erreurs SMTP dans les logs
railway logs | grep -i smtp
# (devrait être vide)

# 2. Emails envoyés via Resend
railway logs | grep "Email sent successfully via Resend"

# 3. Dashboard Resend
# Ouvrir: https://resend.com/emails
# Vérifier qu'un email apparaît avec statut "Sent"
```

## 📝 Variables requises

| Variable | Valeur | Où l'obtenir |
|----------|--------|--------------|
| `RESEND_ENABLED` | `true` | - |
| `RESEND_API_KEY` | `re_xxx` | [Dashboard Resend](https://resend.com/api-keys) |
| `RESEND_FROM_EMAIL` | `infos@meetdo.fun` | Domaine vérifié dans Resend |
| `FRONTEND_URL` | `https://meetdo.fun` | URL de votre frontend |

## 🧪 Test

Créer un utilisateur de test:

```bash
curl -X POST https://api.meetdo.fun/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test1234!",
    "nom": "Test",
    "prenom": "User"
  }'
```

Vérifier dans:
1. **Logs Railway**: `railway logs --tail=50 | grep Resend`
2. **Dashboard Resend**: https://resend.com/emails

## 🔧 Développement local

Pour développer sans envoyer d'emails réels:

```bash
# Ne pas définir RESEND_ENABLED ou le mettre à false
export RESEND_ENABLED=false

# Les liens de vérification s'affichent dans les logs
mvn spring-boot:run

# Exemple de log:
# [DEV] Verification link for test@example.com: 
#       http://localhost:3000/verify-email?token=abc123
```

## 🆘 Troubleshooting

### Emails non reçus

```bash
# Vérifier la configuration
railway variables | grep RESEND

# Vérifier les logs d'erreur
railway logs | grep -i "resend api error"

# Vérifier le dashboard Resend
# https://resend.com/emails
# L'email apparaît-il ? Quel est son statut ?
```

### Erreur 401 Unauthorized

```bash
# Clé API invalide ou expirée
# Solution: Générer une nouvelle clé
railway variables set RESEND_API_KEY=re_nouvelle_cle
```

### Domaine non vérifié

1. Aller dans [Resend > Domains](https://resend.com/domains)
2. Vérifier que `meetdo.fun` est validé (✓ vert)
3. Si non vérifié, ajouter les DNS records fournis

## 📚 Documentation complète

- **Configuration détaillée**: [docs/guides/EMAIL_CONFIGURATION.md](docs/guides/EMAIL_CONFIGURATION.md)
- **Migration SMTP → Resend**: [docs/deployment/EMAIL_MIGRATION_RESEND.md](docs/deployment/EMAIL_MIGRATION_RESEND.md)
- **Variables d'environnement**: [docs/deployment/ENVIRONMENT_VARIABLES.md](docs/deployment/ENVIRONMENT_VARIABLES.md)
- **Code source**: `src/main/java/org/program/pair/domain/email/`

## 📊 Monitoring

### Dashboard Resend
- URL: https://resend.com/emails
- Métriques: Envoyés, livrés, bounced, ouverts, cliqués
- Quota: 3 000 emails/mois (plan gratuit)

### Logs applicatifs
```bash
# Succès
railway logs | grep "Email sent successfully"

# Échecs
railway logs | grep "Failed to send email"

# Erreurs API
railway logs | grep "Resend API error"
```

## 🔐 Sécurité

- ⚠️ Ne jamais commit `RESEND_API_KEY` dans Git
- ✅ Utiliser des clés différentes pour dev/prod
- ✅ Rotation des clés tous les 6 mois minimum

## 💰 Pricing

| Plan | Prix | Emails/mois | Support |
|------|------|-------------|---------|
| Free | $0 | 3 000 | Email |
| Pro | $20 | 50 000 | Priority |

**Estimation Pair**: 200 inscriptions/mois + 50 resets = **250 emails/mois**  
→ Plan gratuit largement suffisant

## 📞 Support

- **Documentation Resend**: https://resend.com/docs
- **Status page**: https://status.resend.com
- **Support Resend**: support@resend.com

---

**Créé**: 2026-07-02  
**Maintenu par**: Backend team
