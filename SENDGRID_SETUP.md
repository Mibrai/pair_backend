# 📧 Configuration SendGrid pour MeetDo

## Pourquoi SendGrid ?

Railway (et la plupart des hébergeurs cloud) bloquent les ports SMTP (25, 465, 587) pour prévenir le spam. SendGrid utilise une API HTTP qui n'est pas bloquée.

---

## 🚀 Étape 1 : Créer un compte SendGrid

1. Allez sur https://sendgrid.com
2. Cliquez sur "Start for Free"
3. Créez votre compte (100 emails/jour gratuits)
4. Vérifiez votre email

---

## 🔑 Étape 2 : Obtenir votre clé API

1. Connectez-vous à SendGrid
2. Allez dans **Settings** > **API Keys**
3. Cliquez sur **Create API Key**
4. Donnez un nom : `MeetDo Production`
5. Choisissez **Full Access** ou au minimum :
   - **Mail Send** : Full Access
6. Cliquez sur **Create & View**
7. **IMPORTANT** : Copiez la clé immédiatement (vous ne pourrez plus la voir après)

---

## ✉️ Étape 3 : Vérifier votre expéditeur

### Option A : Single Sender Verification (Rapide)
1. Allez dans **Settings** > **Sender Authentication**
2. Cliquez sur **Verify a Single Sender**
3. Remplissez le formulaire :
   - From Email: `infos@meetdo.fun`
   - From Name: `MeetDo`
   - Reply To: `infos@meetdo.fun`
   - Company: `MeetDo`
   - Address, City, Country...
4. Cliquez sur **Create**
5. Vérifiez l'email reçu sur `infos@meetdo.fun`

### Option B : Domain Authentication (Recommandé pour production)
1. Allez dans **Settings** > **Sender Authentication**
2. Cliquez sur **Authenticate Your Domain**
3. Entrez votre domaine : `meetdo.fun`
4. Choisissez votre DNS host (probablement Hostinger)
5. Copiez les enregistrements DNS fournis
6. Ajoutez-les dans votre configuration DNS Hostinger :
   - Type: CNAME
   - Host: `em1234.meetdo.fun` (exemple)
   - Value: fourni par SendGrid
   - Répétez pour tous les enregistrements
7. Attendez la vérification (peut prendre jusqu'à 48h)

---

## 🔧 Étape 4 : Configurer Railway

1. Allez sur Railway : https://railway.app
2. Sélectionnez votre projet `pair_backend`
3. Cliquez sur votre service backend
4. Allez dans l'onglet **Variables**
5. Ajoutez ces variables :

```bash
SENDGRID_ENABLED=true
SENDGRID_API_KEY=SG.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
SENDGRID_FROM_EMAIL=infos@meetdo.fun
SENDGRID_FROM_NAME=MeetDo
FRONTEND_URL=https://votre-domaine-frontend.com
```

6. Railway va automatiquement redéployer

---

## ✅ Étape 5 : Tester l'envoi d'emails

Une fois déployé, vous pouvez tester en utilisant les endpoints API :

### Test via code Java
```java
@Autowired
private EmailTemplateService emailTemplateService;

// Envoyer un email de bienvenue
emailTemplateService.sendWelcomeEmail("test@example.com", "John Doe");

// Envoyer une réinitialisation de mot de passe
emailTemplateService.sendPasswordResetEmail("test@example.com", "John Doe", "token123");
```

### Vérifier les logs Railway
Les logs montreront :
- ✅ `Email sent successfully to: test@example.com`
- ❌ `Failed to send email. Status: 403` (si problème d'authentification)

---

## 📊 Étape 6 : Surveiller vos envois

1. Dashboard SendGrid : https://app.sendgrid.com
2. Allez dans **Activity**
3. Vous verrez :
   - Delivered : Emails livrés ✅
   - Bounced : Emails rejetés ❌
   - Opened : Emails ouverts 👀
   - Clicked : Liens cliqués 🖱️

---

## 🎨 Templates d'emails disponibles

L'application inclut ces templates prêts à l'emploi :

1. **Welcome Email** : Email de bienvenue pour nouveaux utilisateurs
2. **Password Reset** : Réinitialisation de mot de passe
3. **Email Verification** : Vérification d'adresse email
4. **New Message Notification** : Notification de nouveau message

Tous les templates ont une version **text** et **HTML**.

---

## 💰 Limites du plan gratuit

- **100 emails/jour**
- **2,000 contacts**
- Statistiques de base
- Support par email

Si vous dépassez 100 emails/jour, considérez :
- **Essentials** : $19.95/mois - 50,000 emails/mois
- **Pro** : $89.95/mois - 100,000 emails/mois

---

## 🔒 Sécurité

- ✅ Ne commitez **JAMAIS** votre clé API dans le code
- ✅ Utilisez **toujours** des variables d'environnement
- ✅ Créez des clés API **spécifiques** par environnement (dev/staging/prod)
- ✅ Supprimez les clés API non utilisées
- ✅ Activez **l'authentification à deux facteurs** sur SendGrid

---

## 🆘 Dépannage

### Erreur : "The from email does not match a verified Sender Identity"
➡️ **Solution** : Vérifiez votre expéditeur (Single Sender ou Domain)

### Erreur : "Forbidden"
➡️ **Solution** : Vérifiez que votre clé API a la permission "Mail Send"

### Emails non reçus
➡️ **Vérifiez** :
1. Dashboard SendGrid > Activity
2. Dossier spam/courrier indésirable
3. Logs Railway pour les erreurs

### Emails marqués comme spam
➡️ **Solutions** :
1. Vérifiez l'authentification de domaine
2. Ajoutez SPF et DKIM records
3. Évitez les mots "spam" dans le contenu
4. Utilisez un "from" professionnel

---

## 📚 Ressources

- [Documentation SendGrid](https://docs.sendgrid.com/)
- [SendGrid Java Library](https://github.com/sendgrid/sendgrid-java)
- [Best Practices](https://docs.sendgrid.com/ui/sending-email/email-best-practices)
- [Deliverability Guide](https://sendgrid.com/resource/email-deliverability-guide/)
