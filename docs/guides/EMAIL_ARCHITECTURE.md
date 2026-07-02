# Architecture Email - Resend Integration

Documentation technique de l'architecture d'envoi d'emails via Resend.

---

## 📐 Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Layer                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌───────────────────┐     ┌────────────────────────┐           │
│  │ AuthController    │────▶│ EmailVerificationSvc   │           │
│  └───────────────────┘     └────────────┬───────────┘           │
│                                          │                        │
│  ┌───────────────────┐                  │                        │
│  │ PasswordResetCtrl │─────────────────▶│                        │
│  └───────────────────┘                  ▼                        │
│                                ┌─────────────────┐               │
│                                │  EmailService   │               │
│                                └────────┬────────┘               │
└─────────────────────────────────────────┼───────────────────────┘
                                          │
                                          │ delegates to
                                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Domain Layer                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│              ┌──────────────────────────┐                        │
│              │  ResendEmailService      │                        │
│              │  - sendHtmlEmail()       │                        │
│              │  - sendTextEmail()       │                        │
│              │  - sendEmail()           │                        │
│              └────────────┬─────────────┘                        │
│                           │                                       │
│                           │ uses                                  │
│                           ▼                                       │
│              ┌──────────────────────────┐                        │
│              │  WebClient (Reactive)    │                        │
│              │  - baseUrl: resend.com   │                        │
│              │  - auth: Bearer token    │                        │
│              └────────────┬─────────────┘                        │
└───────────────────────────┼───────────────────────────────────────┘
                            │
                            │ HTTPS POST
                            ▼
         ┌─────────────────────────────────────┐
         │       Resend API                    │
         │   https://api.resend.com/emails     │
         └─────────────────────────────────────┘
```

---

## 🔄 Flux d'envoi d'email

### 1. Inscription utilisateur (Email de vérification)

```
User Registration
      │
      ▼
┌──────────────────────┐
│  POST /api/auth/    │
│       register       │
└──────┬───────────────┘
       │
       │ 1. Create user
       ▼
┌──────────────────────┐
│  UserService         │
│  - save(user)        │
└──────┬───────────────┘
       │
       │ 2. Generate token
       ▼
┌──────────────────────────────┐
│  EmailVerificationService    │
│  - createToken()             │
└──────┬───────────────────────┘
       │
       │ 3. Send email
       ▼
┌──────────────────────┐
│  EmailService        │
│  .sendVerification() │
└──────┬───────────────┘
       │
       │ 4. Delegate to Resend
       ▼
┌──────────────────────────┐
│  ResendEmailService      │
│  .sendHtmlEmail()        │
└──────┬───────────────────┘
       │
       │ 5. POST /emails
       ▼
┌──────────────────────────┐
│  Resend API              │
│  - Queue email           │
│  - Return ID             │
└──────┬───────────────────┘
       │
       │ 6. Async delivery
       ▼
    [Gmail/Outlook/etc]
       │
       ▼
    User Inbox ✅
```

**Temps total**: < 1 seconde (API call) + quelques secondes (livraison)

### 2. Réinitialisation mot de passe

```
Password Reset Request
      │
      ▼
┌──────────────────────────┐
│  POST /api/auth/         │
│       forgot-password    │
└──────┬───────────────────┘
       │
       │ 1. Validate user exists
       ▼
┌──────────────────────┐
│  UserService         │
│  - findByEmail()     │
└──────┬───────────────┘
       │
       │ 2. Generate reset token
       ▼
┌──────────────────────────────┐
│  PasswordResetService        │
│  - createResetToken()        │
└──────┬───────────────────────┘
       │
       │ 3. Send reset email
       ▼
┌──────────────────────┐
│  EmailService        │
│  .sendPasswordReset()│
└──────┬───────────────┘
       │
       │ 4. Resend API
       ▼
┌──────────────────────────┐
│  ResendEmailService      │
│  .sendHtmlEmail()        │
└──────────────────────────┘
       │
       ▼
  [Resend delivery]
```

---

## 🏗️ Composants

### 1. EmailService (Application)

**Package**: `org.program.pair.shared.email`

**Responsabilité**: Interface de haut niveau pour l'application

```java
@Service
public class EmailService {
    private final ResendEmailService resendEmailService;
    
    // High-level business methods
    public void sendVerificationEmail(String email, String token);
    public void sendPasswordResetEmail(String email, String token);
    public void sendNotificationEmail(UUID userId, NotificationType type, ...);
}
```

**Dépendances**:
- `ResendEmailService` - Pour l'envoi réel
- `@Value("${email.base-url}")` - URL du frontend

**Caractéristiques**:
- Construit les URLs de vérification/reset
- Génère le HTML des emails
- Délègue l'envoi à ResendEmailService
- Gestion gracieuse des erreurs (non-bloquant)

### 2. ResendEmailService (Domain)

**Package**: `org.program.pair.domain.email`

**Responsabilité**: Intégration API Resend

```java
@Service
public class ResendEmailService {
    private final WebClient webClient;
    private final String fromEmail;
    private final String fromName;
    private final boolean enabled;
    
    // Low-level API methods
    public boolean sendHtmlEmail(String to, String subject, String html);
    public boolean sendTextEmail(String to, String subject, String text);
    public boolean sendEmail(String to, String subject, String text, String html);
}
```

**Dépendances**:
- `WebClient` - Client HTTP réactif
- Configuration Resend (API key, from email, enabled flag)

**Caractéristiques**:
- Authentification Bearer token
- Gestion des erreurs API (4xx, 5xx)
- Logging détaillé (succès/échecs)
- Mode désactivé pour développement

### 3. EmailTemplateService (Domain)

**Package**: `org.program.pair.domain.email`

**Responsabilité**: Génération de templates HTML

```java
@Service
public class EmailTemplateService {
    private final ResendEmailService resendEmailService;
    
    // Template generation
    public boolean sendWelcomeEmail(String to, String userName);
    public boolean sendCustomEmail(String to, String template, Map<String, Object> data);
}
```

**Caractéristiques**:
- Templates HTML stylisés
- Variables dynamiques
- Réutilisable pour différents types d'emails

---

## 🔌 Intégration Resend API

### Configuration WebClient

```java
WebClient webClient = WebClient.builder()
    .baseUrl("https://api.resend.com")
    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
    .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
    .build();
```

### Request Format

```java
POST /emails
Authorization: Bearer re_xxxxxxxxxxxx
Content-Type: application/json

{
  "from": "MeetDo <infos@meetdo.fun>",
  "to": ["user@example.com"],
  "subject": "Vérifiez votre adresse Pair",
  "html": "<h1>Hello!</h1><p>Click here...</p>"
}
```

### Response Format (Success)

```json
{
  "id": "e3a2b1c4-d5e6-7890-abcd-ef1234567890",
  "from": "MeetDo <infos@meetdo.fun>",
  "to": "user@example.com",
  "created_at": "2026-07-02T10:30:00.000Z"
}
```

### Response Format (Error)

```json
{
  "statusCode": 401,
  "error": "Unauthorized",
  "message": "Invalid API key"
}
```

---

## 🔐 Sécurité

### 1. Authentification

```
Client → Spring Security → AuthController
                              ↓
                        [JWT validated]
                              ↓
                        EmailService
                              ↓
                        ResendEmailService
                              ↓
           [Bearer token] → Resend API
```

**Couches de sécurité**:
1. JWT valide l'utilisateur
2. Permissions vérifiées par Spring Security
3. Bearer token authentifie avec Resend
4. Resend vérifie domaine expéditeur

### 2. Validation des emails

```java
@Email(message = "Email invalide")
private String email;
```

Spring Validation vérifie:
- Format RFC 5322
- Présence du @
- Domaine valide

### 3. Rate Limiting

**Niveau application**: À implémenter

```java
@RateLimiter(name = "email", fallbackMethod = "emailRateLimitFallback")
public boolean sendEmail(...) { ... }
```

**Niveau Resend**: 
- Rate limits API selon le plan
- Prévention d'abus automatique

### 4. Protection des tokens

```java
// Token de vérification
String token = UUID.randomUUID().toString();
verificationTokens.put(token, userId);

// Expiration 24h
scheduler.schedule(() -> verificationTokens.remove(token), 24, TimeUnit.HOURS);
```

---

## 📊 Gestion d'erreurs

### Stratégie globale

```
┌─────────────────┐
│  Email Send     │
└────────┬────────┘
         │
         ▼
   ┌──────────┐
   │ Success? │───Yes──▶ [Log success]
   └────┬─────┘
        │ No
        ▼
   ┌────────────────┐
   │ Log error      │
   │ Don't rethrow  │
   └────────────────┘
        │
        ▼
   ┌─────────────────────┐
   │ Main operation OK   │
   │ (user created, etc) │
   └─────────────────────┘
```

**Principe**: L'envoi d'email ne doit **jamais** faire échouer l'opération principale.

### Codes d'erreur Resend

| Code | Cause | Action |
|------|-------|--------|
| 400 | Requête invalide | Vérifier format JSON |
| 401 | API key invalide | Régénérer clé |
| 403 | Domaine non vérifié | Vérifier domaine dans dashboard |
| 422 | Email invalide | Valider format email |
| 429 | Rate limit | Implémenter retry avec backoff |
| 500 | Erreur Resend | Vérifier status.resend.com |

### Logging

```java
// Succès
log.info("Email sent successfully via Resend to: {} (ID: {})", to, emailId);

// Échec API
log.error("Resend API error: {} - {}", statusCode, errorBody);

// Échec général
log.error("Failed to send email to: {}", to, exception);
```

---

## 🎛️ Configuration

### Environnements

```
┌──────────────────────────────────────────────────┐
│              Development                          │
├──────────────────────────────────────────────────┤
│ RESEND_ENABLED=false                             │
│ → Logs links instead of sending                  │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│              Staging                              │
├──────────────────────────────────────────────────┤
│ RESEND_ENABLED=true                              │
│ RESEND_API_KEY=re_staging_xxx                    │
│ RESEND_FROM_EMAIL=test@staging.meetdo.fun        │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│              Production                           │
├──────────────────────────────────────────────────┤
│ RESEND_ENABLED=true                              │
│ RESEND_API_KEY=re_prod_xxx                       │
│ RESEND_FROM_EMAIL=infos@meetdo.fun               │
└──────────────────────────────────────────────────┘
```

### Properties Mapping

```properties
# application-railway.properties
resend.enabled=${RESEND_ENABLED:false}
resend.api-key=${RESEND_API_KEY:}
resend.from-email=${RESEND_FROM_EMAIL:infos@meetdo.fun}
resend.from-name=${RESEND_FROM_NAME:MeetDo}
```

```java
// ResendEmailService.java
@Value("${resend.enabled:false}") boolean enabled
@Value("${resend.api-key:}") String apiKey
@Value("${resend.from-email:infos@meetdo.fun}") String fromEmail
@Value("${resend.from-name:MeetDo}") String fromName
```

---

## 📈 Monitoring

### Métriques à surveiller

```
┌────────────────────────────────────┐
│         Application Logs           │
├────────────────────────────────────┤
│ - Emails sent successfully         │
│ - Emails failed to send            │
│ - API errors (4xx, 5xx)            │
│ - Latency per email                │
└────────────────────────────────────┘
           │
           ▼
┌────────────────────────────────────┐
│         Resend Dashboard           │
├────────────────────────────────────┤
│ - Total emails sent                │
│ - Delivery rate                    │
│ - Bounce rate                      │
│ - Complaint rate                   │
│ - Open rate (if tracking enabled)  │
│ - Click rate (if tracking enabled) │
└────────────────────────────────────┘
```

### Alertes recommandées

1. **Taux d'échec > 5%**
   - Vérifier clé API
   - Vérifier quota Resend
   - Vérifier status.resend.com

2. **Latence API > 2s**
   - Incident Resend possible
   - Vérifier logs Resend

3. **Bounce rate > 10%**
   - Problème de délivrabilité
   - Vérifier DNS (SPF/DKIM/DMARC)

4. **Quota > 80%**
   - Planifier upgrade
   - Optimiser nombre d'envois

---

## 🔄 Évolutions futures

### 1. Webhooks Resend

Recevoir des notifications sur le statut de livraison:

```
Resend ──▶ POST /api/webhooks/resend ──▶ Application
           {
             "type": "email.delivered",
             "data": {
               "email_id": "xxx",
               "to": "user@example.com",
               "delivered_at": "2026-07-02T..."
             }
           }
```

**Implémentation**:
```java
@PostMapping("/api/webhooks/resend")
public void handleResendWebhook(@RequestBody ResendWebhookEvent event) {
    switch (event.getType()) {
        case "email.delivered" -> handleDelivered(event);
        case "email.bounced" -> handleBounced(event);
        case "email.complained" -> handleComplaint(event);
        case "email.opened" -> handleOpened(event);
        case "email.clicked" -> handleClicked(event);
    }
}
```

### 2. Templates visuels Resend

Utiliser l'éditeur Resend pour créer des templates:

```java
Map<String, Object> emailRequest = Map.of(
    "from", "MeetDo <infos@meetdo.fun>",
    "to", new String[]{to},
    "template_id", "verification_email_v2",
    "template_data", Map.of(
        "userName", user.getPrenom(),
        "verificationUrl", url,
        "expiresIn", "24 heures"
    )
);
```

### 3. Queue asynchrone

Pour des volumes élevés:

```
Application ──▶ RabbitMQ/Redis Queue ──▶ Email Worker ──▶ Resend API
                     (buffer)              (consumer)
```

**Avantages**:
- Meilleure résilience
- Retry automatique
- Lissage des pics de charge

### 4. A/B Testing

Tester différentes versions d'emails:

```java
String template = abTestService.selectTemplate("verification_email");
// 50% reçoivent version A
// 50% reçoivent version B

// Tracking des conversions
emailAnalytics.trackConversion(emailId, "email_verified");
```

---

## 🧪 Tests

### Tests unitaires

```java
@Test
void shouldDelegateToResendService() {
    // Arrange
    when(resendEmailService.isEnabled()).thenReturn(true);
    when(resendEmailService.sendHtmlEmail(any(), any(), any())).thenReturn(true);
    
    // Act
    emailService.sendVerificationEmail("test@example.com", "token123");
    
    // Assert
    verify(resendEmailService).sendHtmlEmail(
        eq("test@example.com"),
        eq("Vérifiez votre adresse Pair"),
        contains("verify-email?token=token123")
    );
}
```

### Tests d'intégration

```java
@SpringBootTest
@TestPropertySource(properties = {
    "resend.enabled=true",
    "resend.api-key=re_test_key"
})
class EmailIntegrationTest {
    
    @Autowired
    private EmailService emailService;
    
    @Test
    void shouldSendRealEmail() {
        // Requires valid Resend test API key
        emailService.sendVerificationEmail(
            "devtest@meetdo.fun",
            UUID.randomUUID().toString()
        );
        
        // Verify in Resend dashboard
    }
}
```

---

## 📚 Références

- [Documentation complète](./EMAIL_CONFIGURATION.md)
- [Guide de migration](../deployment/EMAIL_MIGRATION_RESEND.md)
- [Variables d'environnement](../deployment/ENVIRONMENT_VARIABLES.md)
- [API Resend](https://resend.com/docs)

---

**Version**: 1.0  
**Dernière mise à jour**: 2026-07-02  
**Maintenu par**: Backend team
