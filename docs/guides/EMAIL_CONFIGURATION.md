# Configuration Email avec Resend

## Vue d'ensemble

L'application Pair utilise **Resend** comme service d'envoi d'emails via API REST, remplaçant l'ancienne configuration SMTP (Hostinger). Cette migration a été réalisée pour:

- Éviter les problèmes de connectivité SMTP sur Railway (ports bloqués)
- Améliorer la fiabilité et la rapidité d'envoi
- Faciliter le débogage avec l'interface Resend
- Réduire les timeouts et erreurs de connexion

## Architecture

### Services Email

Le système d'email est composé de deux couches:

```
┌─────────────────────────────────────────┐
│         Application Layer               │
├─────────────────────────────────────────┤
│  EmailService                           │
│  - sendVerificationEmail()              │
│  - sendPasswordResetEmail()             │
│  - sendNotificationEmail()              │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│  ResendEmailService                     │
│  - sendHtmlEmail()                      │
│  - sendTextEmail()                      │
│  - sendEmail() [text + html]            │
└──────────────────┬──────────────────────┘
                   │
                   ▼
        ┌──────────────────┐
        │   Resend API     │
        │ api.resend.com   │
        └──────────────────┘
```

### Fichiers concernés

| Fichier | Rôle |
|---------|------|
| `src/main/java/org/program/pair/shared/email/EmailService.java` | Service principal utilisé par l'application |
| `src/main/java/org/program/pair/domain/email/ResendEmailService.java` | Intégration API Resend |
| `src/main/java/org/program/pair/domain/email/EmailTemplateService.java` | Génération de templates HTML |
| `src/main/resources/application-railway.properties` | Configuration production Railway |

## Configuration

### 1. Variables d'environnement

#### Variables requises (Production)

```bash
# Activation de Resend
RESEND_ENABLED=true

# Clé API Resend (obtenir sur https://resend.com/api-keys)
RESEND_API_KEY=re_xxxxxxxxxxxxxxxxxxxxxxxxxx

# Configuration expéditeur
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo

# URL frontend pour les liens dans les emails
FRONTEND_URL=https://meetdo.fun
```

#### Variables optionnelles (Développement local)

```bash
# Si RESEND_ENABLED=false, les emails ne sont pas envoyés
# mais les liens sont affichés dans les logs
RESEND_ENABLED=false

# URL locale pour tester les liens
FRONTEND_URL=http://localhost:3000
```

### 2. Configuration Railway

Dans le dashboard Railway, ajouter les variables d'environnement:

```
RESEND_ENABLED=true
RESEND_API_KEY=<votre-clé-api>
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
FRONTEND_URL=https://meetdo.fun
```

### 3. Configuration application.properties

Le fichier `application-railway.properties` contient:

```properties
# Désactiver l'auto-configuration Spring Boot Mail (SMTP)
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
management.health.mail.enabled=false

# Configuration Resend (lue depuis variables d'environnement)
resend.enabled=${RESEND_ENABLED:false}
resend.api-key=${RESEND_API_KEY:}
resend.from-email=${RESEND_FROM_EMAIL:infos@meetdo.fun}
resend.from-name=${RESEND_FROM_NAME:MeetDo}

# URL frontend pour les liens dans les emails
app.frontend-url=${FRONTEND_URL:http://localhost:3000}
```

## Obtention d'une clé API Resend

### Étape 1: Créer un compte Resend

1. Aller sur [https://resend.com/signup](https://resend.com/signup)
2. Créer un compte avec votre email
3. Vérifier votre email

### Étape 2: Configurer votre domaine

1. Dans le dashboard Resend, aller dans **Domains**
2. Cliquer sur **Add Domain**
3. Entrer votre domaine: `meetdo.fun`
4. Ajouter les enregistrements DNS fournis par Resend:

```
Type: TXT
Name: _resend
Value: <fourni par Resend>

Type: MX
Priority: 10
Value: <fourni par Resend>
```

5. Attendre la vérification (quelques minutes à 48h)

### Étape 3: Créer une clé API

1. Dans le dashboard, aller dans **API Keys**
2. Cliquer sur **Create API Key**
3. Nom: `pair-backend-production`
4. Permissions: **Sending access**
5. Domain: `meetdo.fun`
6. Copier la clé (elle commence par `re_`)

> **Important**: La clé API n'est affichée qu'une seule fois. Si vous la perdez, créez-en une nouvelle.

### Étape 4: Vérifier l'expéditeur

Resend nécessite que l'adresse `from` soit vérifiée:

- **Domaine vérifié**: Vous pouvez utiliser n'importe quelle adresse `@meetdo.fun`
- **Email individuel**: Si votre domaine n'est pas vérifié, vérifiez l'email spécifique

## Implémentation technique

### ResendEmailService

La classe `ResendEmailService` encapsule toute l'intégration avec l'API Resend:

```java
@Service
@Slf4j
public class ResendEmailService {
    
    private final WebClient webClient;
    private final String fromEmail;
    private final String fromName;
    private final boolean enabled;
    
    public ResendEmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from-email:infos@meetdo.fun}") String fromEmail,
            @Value("${resend.from-name:MeetDo}") String fromName,
            @Value("${resend.enabled:false}") boolean enabled) {
        
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.enabled = enabled;
        
        // WebClient configuré avec l'authentification Bearer
        this.webClient = WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
    
    public boolean sendHtmlEmail(String to, String subject, String htmlContent) {
        if (!enabled) {
            log.debug("Resend disabled. Email would be sent to: {}", to);
            return false;
        }
        
        Map<String, Object> emailRequest = new HashMap<>();
        emailRequest.put("from", fromName + " <" + fromEmail + ">");
        emailRequest.put("to", new String[]{to});
        emailRequest.put("subject", subject);
        emailRequest.put("html", htmlContent);
        
        return sendEmail(emailRequest, to, subject);
    }
}
```

### EmailService (Wrapper)

Le service `EmailService` est utilisé par l'application et délègue à `ResendEmailService`:

```java
@Service
@Slf4j
public class EmailService {
    
    private final ResendEmailService resendEmailService;
    
    @Value("${email.base-url:http://localhost:3000}")
    private String baseUrl;
    
    public void sendVerificationEmail(String email, String token) {
        if (!resendEmailService.isEnabled()) {
            log.info("[DEV] Verification link: {}/verify-email?token={}", baseUrl, token);
            return;
        }
        
        String verifyUrl = baseUrl + "/verify-email?token=" + token;
        String html = """
            <h2>Vérifiez votre adresse email</h2>
            <p>Cliquez sur le lien suivant pour activer votre compte Pair :</p>
            <a href="%s" style="background:#4F46E5;color:white;padding:12px 24px;">
              Vérifier mon email
            </a>
            """.formatted(verifyUrl);
        
        resendEmailService.sendHtmlEmail(email, "Vérifiez votre adresse Pair", html);
    }
}
```

## Types d'emails envoyés

### 1. Email de vérification

**Déclencheur**: Inscription d'un nouvel utilisateur

**Endpoint**: `POST /api/auth/register`

**Contenu**:
- Lien de vérification valide 24h
- Bouton CTA stylisé
- Instructions claires

**Template**: `EmailService.sendVerificationEmail()`

### 2. Email de réinitialisation de mot de passe

**Déclencheur**: Demande de reset de mot de passe

**Endpoint**: `POST /api/auth/forgot-password`

**Contenu**:
- Lien de réinitialisation valide 30 minutes
- Message de sécurité si non demandé
- Bouton CTA stylisé

**Template**: `EmailService.sendPasswordResetEmail()`

### 3. Emails de notification (Futur)

**Déclencheur**: Événements dans l'application

**Note**: Actuellement en mode "digest" (groupés), pas d'envoi direct

**Template**: `EmailService.sendNotificationEmail()`

## Gestion des erreurs

### Comportement en cas d'échec

1. **API Resend indisponible**: 
   - L'erreur est loggée
   - L'opération principale (inscription, reset) **continue normalement**
   - L'utilisateur ne voit pas d'erreur
   
2. **Clé API invalide**:
   - Loggé au démarrage: `Resend is enabled but no API key is configured`
   - Erreur 4xx de l'API Resend loggée à chaque tentative

3. **Email invalide**:
   - Erreur 4xx de l'API Resend
   - L'utilisateur peut avoir créé son compte mais ne peut pas le vérifier

### Logs

```java
// Succès
log.info("Email sent successfully via Resend to: {} (ID: {})", to, emailId);

// Échec API
log.error("Resend API error: {} - {}", statusCode, errorBody);

// Échec général
log.error("Failed to send email to: {} with subject: {}", to, subject, exception);
```

### Monitoring

Pour surveiller l'envoi d'emails en production:

1. **Dashboard Resend**:
   - Voir tous les emails envoyés
   - Statut de livraison
   - Taux d'ouverture/clic
   - Logs d'erreurs

2. **Logs applicatifs**:
   ```bash
   # Railway logs
   railway logs | grep -i "email\|resend"
   ```

3. **Métriques clés**:
   - Nombre d'emails envoyés
   - Taux d'échec API
   - Temps de réponse API

## Mode développement

### Désactiver l'envoi d'emails

Dans votre environnement local, **ne pas définir** `RESEND_ENABLED` ou le mettre à `false`:

```bash
# .env ou variables d'environnement
RESEND_ENABLED=false
```

### Récupérer les liens de vérification

Quand Resend est désactivé, les liens sont affichés dans les logs:

```
INFO  [DEV] Verification link for user@example.com: http://localhost:3000/verify-email?token=abc123
```

Vous pouvez copier ce lien et le coller dans votre navigateur.

## Migration depuis SMTP Hostinger

### Anciennes variables (à supprimer)

Ces variables ne sont plus nécessaires:

```bash
# OBSOLÈTE - Ne plus utiliser
MAIL_HOST=smtp.hostinger.com
MAIL_PORT=587
MAIL_USERNAME=xxx
MAIL_PASSWORD=xxx
MAIL_FROM=infos@meetdo.fun
```

### Différences principales

| Aspect | SMTP (Avant) | Resend API (Maintenant) |
|--------|--------------|------------------------|
| Protocole | SMTP (port 587) | HTTPS/REST |
| Authentification | Username/Password | Bearer token |
| Timeouts | Fréquents (5s) | Rares |
| Ports bloqués | Oui sur Railway | Non (port 443) |
| Interface web | Limitée | Dashboard complet |
| Webhooks | Non | Oui (statut de livraison) |
| Débogage | Difficile | Logs détaillés |

### Avantages de Resend

1. **Fiabilité**: Pas de problèmes de ports bloqués
2. **Performance**: API REST plus rapide que SMTP
3. **Observabilité**: Dashboard avec statistiques détaillées
4. **Scalabilité**: Meilleure gestion des volumes élevés
5. **Développeur**: API moderne, SDKs disponibles

## Dépendances

### Maven (pom.xml)

```xml
<!-- WebClient pour appels HTTP -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- spring-boot-starter-mail encore présent mais non utilisé -->
<!-- Peut être retiré dans une future version -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

### Auto-configuration désactivée

```properties
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
```

Cela empêche Spring Boot de créer un bean `JavaMailSender` qui essaierait de se connecter via SMTP.

## Tests

### Test manuel en local

1. Activer Resend avec une clé de test:
   ```bash
   export RESEND_ENABLED=true
   export RESEND_API_KEY=re_test_xxxx
   ```

2. Créer un utilisateur:
   ```bash
   curl -X POST http://localhost:8080/api/auth/register \
     -H "Content-Type: application/json" \
     -d '{
       "email": "test@example.com",
       "password": "Test1234!",
       "nom": "Test",
       "prenom": "User"
     }'
   ```

3. Vérifier dans le dashboard Resend que l'email a été envoyé

### Tests unitaires

```java
@SpringBootTest
class EmailServiceTest {
    
    @MockBean
    private ResendEmailService resendEmailService;
    
    @Autowired
    private EmailService emailService;
    
    @Test
    void shouldSendVerificationEmail() {
        when(resendEmailService.isEnabled()).thenReturn(true);
        when(resendEmailService.sendHtmlEmail(any(), any(), any())).thenReturn(true);
        
        emailService.sendVerificationEmail("test@example.com", "token123");
        
        verify(resendEmailService).sendHtmlEmail(
            eq("test@example.com"),
            eq("Vérifiez votre adresse Pair"),
            contains("verify-email?token=token123")
        );
    }
}
```

## Limites et quotas Resend

### Plan gratuit

- **3 000 emails/mois**
- Domaine personnalisé
- Logs 30 jours
- Support email

### Plan Pro (20$/mois)

- **50 000 emails/mois**
- Webhooks
- Logs 90 jours
- Support prioritaire

### Surveiller l'utilisation

Dans le dashboard Resend > **Usage**, vérifier:
- Nombre d'emails envoyés ce mois
- Pourcentage du quota utilisé
- Projections pour le mois

## Troubleshooting

### Problème: Emails non reçus

**Causes possibles**:
1. Domaine non vérifié dans Resend
2. Email dans les spams
3. Clé API invalide
4. `RESEND_ENABLED=false`

**Solution**:
```bash
# Vérifier les logs
railway logs --tail=100 | grep -i resend

# Vérifier le dashboard Resend
# - L'email apparaît-il comme "sent" ?
# - Y a-t-il une erreur de bounce/spam ?
```

### Problème: Erreur 401 Unauthorized

**Cause**: Clé API invalide ou expirée

**Solution**:
1. Générer une nouvelle clé dans le dashboard Resend
2. Mettre à jour `RESEND_API_KEY` dans Railway
3. Redéployer l'application

### Problème: Erreur 429 Too Many Requests

**Cause**: Quota dépassé ou rate limiting

**Solution**:
- Vérifier l'utilisation dans le dashboard
- Upgrade vers un plan supérieur si nécessaire
- Implémenter un système de queue pour lisser les envois

### Problème: Liens cassés dans les emails

**Cause**: `FRONTEND_URL` mal configuré

**Solution**:
```bash
# Vérifier la variable
railway variables

# Corriger si nécessaire
railway variables set FRONTEND_URL=https://meetdo.fun
```

## Sécurité

### Protection de la clé API

1. **Ne jamais commit la clé dans Git**
2. **Utiliser des variables d'environnement** (Railway secrets)
3. **Rotation régulière** des clés (tous les 6 mois)
4. **Clés différentes** par environnement (dev/staging/prod)

### Validation des emails

Avant d'envoyer, l'application valide:
- Format email via `@Email` annotation
- Existence du domaine (DNS)
- Longueur maximale

### Protection contre le spam

Resend implémente automatiquement:
- SPF/DKIM/DMARC (si domaine configuré)
- Détection de bounce
- Gestion des unsubscribe
- Rate limiting

## Évolutions futures

### Webhooks Resend

Pour suivre l'état de livraison des emails:

1. Créer un endpoint webhook:
   ```java
   @PostMapping("/api/webhooks/resend")
   public void handleResendWebhook(@RequestBody ResendWebhookEvent event) {
       // Gérer delivered, bounced, complained, opened, clicked
   }
   ```

2. Configurer dans Resend dashboard:
   - URL: `https://api.meetdo.fun/api/webhooks/resend`
   - Événements: delivered, bounced, complained

### Templates dans Resend

Utiliser les templates visuels de Resend plutôt que du HTML embarqué:

```java
Map<String, Object> emailRequest = new HashMap<>();
emailRequest.put("from", "MeetDo <infos@meetdo.fun>");
emailRequest.put("to", new String[]{to});
emailRequest.put("template_id", "verification_email");
emailRequest.put("template_data", Map.of(
    "verificationUrl", verifyUrl,
    "userName", userName
));
```

### Analytics

Tracker les métriques email:
- Taux d'ouverture
- Taux de clic
- Temps moyen de vérification
- Emails bounced par domaine

## Références

- [Documentation API Resend](https://resend.com/docs)
- [Dashboard Resend](https://resend.com/dashboard)
- [Guide vérification domaine](https://resend.com/docs/dashboard/domains/introduction)
- [Rate limits](https://resend.com/docs/api-reference/rate-limits)
- [Webhooks](https://resend.com/docs/dashboard/webhooks/introduction)

## Support

### Issues courantes

- **Logs vides**: Augmenter le niveau de log à DEBUG pour `org.program.pair.domain.email`
- **Emails lents**: Vérifier la latence API Resend dans le dashboard
- **Caractères spéciaux**: S'assurer que le HTML est encodé en UTF-8

### Contact

- **Resend Support**: support@resend.com
- **Documentation interne**: Ce fichier
- **Équipe backend**: Vérifier les logs Railway

---

**Dernière mise à jour**: 2026-07-02
**Auteur**: Documentation générée lors de la migration SMTP → Resend
**Version**: 1.0
