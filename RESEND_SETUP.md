# 📧 Configuration Resend pour MeetDo

## Pourquoi Resend ?

- ✅ **Configuration ultra-simple** (5 minutes)
- ✅ **Pas de suspension surprise** comme SendGrid
- ✅ **Interface moderne** et intuitive
- ✅ **100 emails/jour gratuits**
- ✅ **Excellente délivrabilité**
- ✅ **API simple et bien documentée**

Railway bloque les ports SMTP, donc nous utilisons l'API HTTP de Resend qui fonctionne parfaitement.

---

## 🚀 Configuration en 3 étapes (5 minutes)

### **Étape 1 : Créer un compte Resend** (2 min)

1. Allez sur https://resend.com
2. Cliquez sur **"Start Building"** ou **"Get Started"**
3. Inscrivez-vous avec :
   - Email professionnel (de préférence)
   - Ou GitHub (connexion rapide)
4. Vérifiez votre email si nécessaire

---

### **Étape 2 : Obtenir votre clé API** (1 min)

1. Une fois connecté, vous arrivez sur le Dashboard
2. Cliquez sur **"API Keys"** dans le menu
3. Cliquez sur **"Create API Key"**
4. Donnez un nom : `MeetDo Production`
5. Sélectionnez les permissions :
   - **Sending access** : ✅ (obligatoire)
   - Autres : non nécessaires
6. Cliquez sur **"Add"**
7. **Copiez la clé immédiatement** (commence par `re_...`)
   ```
   re_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```

💡 **Astuce** : Gardez la clé dans un fichier temporaire, vous en aurez besoin pour Railway

---

### **Étape 3 : Vérifier votre domaine** (2 min)

**Important** : Resend nécessite la vérification du domaine pour envoyer des emails.

#### Option A : Vérification complète (Recommandé)

1. Dans Resend, allez dans **"Domains"**
2. Cliquez sur **"Add Domain"**
3. Entrez votre domaine : `meetdo.fun`
4. Resend vous donne des enregistrements DNS à ajouter
5. Connectez-vous à **Hostinger** (votre hébergeur DNS)
6. Ajoutez les enregistrements DNS fournis par Resend :

```
Type: TXT
Name: _resend
Value: [fourni par Resend]
TTL: Auto

Type: MX
Name: @
Value: feedback-smtp.resend.com
Priority: 10
TTL: Auto

Type: TXT
Name: @
Value: [SPF record fourni]
TTL: Auto
```

7. Revenez sur Resend et cliquez sur **"Verify"**
8. ✅ Domaine vérifié (peut prendre 5-10 minutes)

#### Option B : Mode test (Pour démarrer rapidement)

Si vous voulez tester immédiatement sans DNS :
- Resend vous donne un domaine de test : `onboarding@resend.dev`
- Vous pouvez envoyer à votre propre email pour tester
- **Limitation** : Vous ne pouvez envoyer qu'à l'email avec lequel vous êtes inscrit

---

### **Étape 4 : Configurer Railway** (1 min)

1. Allez sur https://railway.app
2. Sélectionnez votre projet **pair_backend**
3. Cliquez sur votre service backend
4. Allez dans l'onglet **"Variables"**
5. Ajoutez ces variables :

```bash
RESEND_ENABLED=true
RESEND_API_KEY=re_votre_cle_ici
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
FRONTEND_URL=https://votre-domaine.com
```

6. Cliquez sur **"Add"** ou **"Save"**
7. Railway va automatiquement **redéployer** l'application

⏱️ Le redéploiement prend environ 2-3 minutes

---

## ✅ Vérification que ça fonctionne

### Dans les logs Railway :

```
✅ Resend email service initialized with from: MeetDo <infos@meetdo.fun>
✅ Email sent successfully via Resend to: user@example.com (ID: xxx)
```

### Erreurs possibles :

```
❌ Resend is enabled but no API key is configured
➡️ Vérifiez que RESEND_API_KEY est bien défini dans Railway

❌ Resend API error: 403
➡️ Vérifiez que votre clé API est correcte

❌ Resend API error: 422 - Domain not verified
➡️ Vous devez vérifier votre domaine dans Resend (Étape 3)
```

---

## 🧪 Tester l'envoi d'emails

Une fois déployé, vous pouvez tester :

### Via l'interface Resend :
1. Dashboard Resend > **"Emails"**
2. Vous verrez tous les emails envoyés
3. Statuts :
   - ✅ **Delivered** : Email livré avec succès
   - ⏳ **Processing** : En cours d'envoi
   - ❌ **Bounced** : Email invalide ou rejeté

### Via votre application :
Les templates sont automatiquement disponibles :
- Email de bienvenue (inscription)
- Réinitialisation de mot de passe
- Vérification d'email
- Notification de nouveau message

---

## 💰 Tarification Resend

### Plan Gratuit (Free)
- ✅ **100 emails/jour**
- ✅ **3,000 emails/mois**
- ✅ Tous les domaines vérifiés
- ✅ Support par email
- ✅ API complète

### Plan Pro (si vous dépassez)
- **$20/mois** pour **50,000 emails/mois**
- Priorité support
- Analytics avancés

💡 **100 emails/jour** est largement suffisant pour démarrer !

---

## 📊 Dashboard Resend

Le dashboard vous montre en temps réel :
- 📨 **Emails envoyés** (delivered, bounced, etc.)
- 📈 **Statistiques** par jour/semaine/mois
- 🔍 **Détails par email** (contenu, destinataire, timestamps)
- 🚫 **Bounces** : emails rejetés
- ⚠️ **Complaints** : marqués comme spam

---

## 🎨 Templates d'emails inclus

L'application inclut ces templates prêts à l'emploi :

1. **Welcome Email** 🎉
   - Email de bienvenue pour nouveaux utilisateurs
   - Design moderne avec gradient violet

2. **Password Reset** 🔑
   - Lien de réinitialisation sécurisé
   - Expire en 1 heure

3. **Email Verification** ✉️
   - Vérification d'adresse email
   - Expire en 24 heures

4. **New Message Notification** 💬
   - Alerte de nouveau message
   - Lien direct vers la conversation

Tous les templates ont une version **text** ET **HTML** pour maximiser la compatibilité.

---

## 🔒 Sécurité

### ✅ Bonnes pratiques :
- Ne **jamais** commiter la clé API dans le code
- Utiliser **toujours** des variables d'environnement
- Créer des clés API **différentes** par environnement (dev/prod)
- Activer **l'authentification à deux facteurs** sur Resend

### 🔐 Régénérer une clé compromise :
1. Dashboard Resend > **API Keys**
2. Cliquez sur les **3 points** à côté de la clé
3. **Delete**
4. Créez une nouvelle clé
5. Mettez à jour Railway immédiatement

---

## 🆘 Dépannage

### ❌ "Domain not verified"
**Solution** : 
1. Vérifiez votre domaine dans Resend > Domains
2. Ajoutez les enregistrements DNS dans Hostinger
3. Attendez 5-10 minutes
4. Cliquez sur "Verify" dans Resend

### ❌ "API key is invalid"
**Solution** :
1. Vérifiez que la clé commence par `re_`
2. Pas d'espaces avant/après la clé
3. Vérifiez qu'elle est bien copiée entièrement
4. Régénérez une nouvelle clé si nécessaire

### ❌ Emails non reçus
**Solutions** :
1. Vérifiez le **dossier spam** du destinataire
2. Dashboard Resend > Emails > Vérifiez le statut
3. Vérifiez que le domaine est vérifié
4. Consultez les logs Railway

### ❌ Emails marqués comme spam
**Solutions** :
1. Vérifiez l'authentification SPF/DKIM du domaine
2. Évitez les mots "spam" dans le sujet/contenu
3. Utilisez un ratio text/HTML équilibré
4. N'envoyez pas trop d'emails d'un coup

---

## 📚 Ressources

- [Documentation Resend](https://resend.com/docs)
- [API Reference](https://resend.com/docs/api-reference)
- [Best Practices](https://resend.com/docs/best-practices)
- [Guide d'authentification domaine](https://resend.com/docs/dashboard/domains/introduction)

---

## 🎯 Résumé rapide

```bash
# 1. Créer compte : https://resend.com
# 2. Obtenir clé API : Dashboard > API Keys > Create
# 3. Vérifier domaine : Dashboard > Domains > Add Domain
# 4. Configurer Railway :

RESEND_ENABLED=true
RESEND_API_KEY=re_xxxxxxxxxx
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
FRONTEND_URL=https://meetdo.fun

# 5. Deploy automatique ✅
```

---

## ✨ Pourquoi Resend > SendGrid ?

| Critère | Resend | SendGrid |
|---------|--------|----------|
| Configuration | ⭐⭐⭐⭐⭐ Ultra-simple | ⭐⭐ Complexe |
| Nouveaux comptes | ✅ Pas de suspension | ❌ Suspensions fréquentes |
| Interface | 🎨 Moderne | 📊 Ancienne |
| Documentation | 📖 Claire | 📚 Dense |
| Emails gratuits | 100/jour | 100/jour |
| Support | 💬 Réactif | 📧 Lent |

**Verdict** : Resend est parfait pour MeetDo ! 🚀
