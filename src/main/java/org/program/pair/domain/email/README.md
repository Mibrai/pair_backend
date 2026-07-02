# Package Email

Services d'envoi d'emails via l'API Resend.

## Classes

### ResendEmailService

Service bas-niveau pour interagir avec l'API Resend.

**Responsabilités**:
- Authentification Bearer token
- Appels HTTP POST /emails
- Gestion des erreurs API
- Logging des succès/échecs

**Usage**:
```java
@Autowired
private ResendEmailService resendEmailService;

boolean sent = resendEmailService.sendHtmlEmail(
    "user@example.com",
    "Welcome to Pair",
    "<h1>Hello!</h1>"
);
```

**Configuration**:
```properties
resend.enabled=true
resend.api-key=re_xxxx
resend.from-email=infos@meetdo.fun
resend.from-name=MeetDo
```

### EmailTemplateService

Service de génération de templates HTML pour emails transactionnels.

**Responsabilités**:
- Templates HTML stylisés
- Personnalisation avec données dynamiques
- Boutons CTA, headers, footers
- Utilise `ResendEmailService` pour l'envoi

**Usage**:
```java
@Autowired
private EmailTemplateService emailTemplateService;

emailTemplateService.sendWelcomeEmail("user@example.com", "John");
```

**Templates disponibles**:
- Welcome email (à implémenter)
- Notification digest (à implémenter)
- Custom HTML templates

## Relation avec EmailService

Le package `org.program.pair.shared.email.EmailService` est le wrapper utilisé par l'application. Il délègue à `ResendEmailService`.

```
Application
    ↓
EmailService (shared.email)
    ↓
ResendEmailService (domain.email)
    ↓
Resend API
```

## Configuration

### Environnement de développement

```bash
# .env ou application-local.properties
RESEND_ENABLED=false
```

Les emails ne sont pas envoyés, les liens sont affichés dans les logs:
```
[DEV] Verification link: http://localhost:3000/verify-email?token=abc123
```

### Environnement de production

```bash
# Railway variables
RESEND_ENABLED=true
RESEND_API_KEY=re_xxxx
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
FRONTEND_URL=https://meetdo.fun
```

## Tests

### Test unitaire ResendEmailService

```java
@Test
void shouldSendEmailViaResendApi() {
    // Mock WebClient
    when(webClientMock.post()).thenReturn(requestBodyUriSpecMock);
    
    boolean sent = resendEmailService.sendHtmlEmail(
        "test@example.com",
        "Test Subject",
        "<p>Test content</p>"
    );
    
    assertTrue(sent);
    verify(webClientMock).post();
}
```

### Test d'intégration

```java
@SpringBootTest
@TestPropertySource(properties = {
    "resend.enabled=true",
    "resend.api-key=re_test_key"
})
class EmailIntegrationTest {
    
    @Autowired
    private ResendEmailService resendEmailService;
    
    @Test
    void shouldSendRealEmail() {
        boolean sent = resendEmailService.sendHtmlEmail(
            "devtest@meetdo.fun",
            "Integration Test",
            "<h1>This is a test</h1>"
        );
        
        assertTrue(sent);
    }
}
```

## Gestion d'erreurs

### Erreurs API Resend

| Code | Cause | Action |
|------|-------|--------|
| 401 | Clé API invalide | Vérifier `RESEND_API_KEY` |
| 403 | Domaine non vérifié | Vérifier domaine dans dashboard |
| 429 | Rate limit dépassé | Implémenter retry avec backoff |
| 500 | Incident Resend | Vérifier status.resend.com |

### Erreurs loggées

```java
// Succès
log.info("Email sent successfully via Resend to: {} (ID: {})", to, emailId);

// Échec API
log.error("Resend API error: {} - {}", statusCode, errorBody);

// Échec général
log.error("Failed to send email to: {}", to, exception);
```

### Comportement sur erreur

L'envoi d'email est **non-bloquant**: si Resend échoue, l'opération principale (inscription, reset password) continue normalement.

```java
try {
    resendEmailService.sendHtmlEmail(...);
} catch (Exception e) {
    // Loggé mais pas re-throw
    log.error("Email failed but user created", e);
}
```

## Monitoring

### Métriques

- Nombre d'emails envoyés (dashboard Resend)
- Taux de succès/échec
- Latence moyenne API
- Quota utilisé

### Logs à surveiller

```bash
# Vérifier les succès
grep "Email sent successfully" logs/app.log

# Vérifier les échecs
grep "Failed to send email" logs/app.log

# Vérifier les erreurs API
grep "Resend API error" logs/app.log
```

## Évolutions futures

### Webhooks Resend

Recevoir des notifications sur l'état de livraison:

```java
@PostMapping("/api/webhooks/resend")
public void handleResendWebhook(@RequestBody ResendWebhookEvent event) {
    switch (event.getType()) {
        case "email.delivered" -> handleDelivered(event);
        case "email.bounced" -> handleBounced(event);
        case "email.complained" -> handleComplaint(event);
    }
}
```

### Templates visuels Resend

Utiliser les templates créés dans le dashboard plutôt que HTML embarqué:

```java
Map<String, Object> emailRequest = new HashMap<>();
emailRequest.put("template_id", "verification_email_v2");
emailRequest.put("template_data", Map.of(
    "userName", user.getPrenom(),
    "verificationUrl", url
));
```

### Retry avec exponential backoff

En cas d'erreur temporaire (5xx), réessayer:

```java
@Retryable(
    value = {RestClientException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
public boolean sendEmailWithRetry(...) {
    return resendEmailService.sendHtmlEmail(...);
}
```

### Email queuing

Pour des volumes élevés, utiliser une queue asynchrone:

```java
@Async
public CompletableFuture<Boolean> sendEmailAsync(String to, String subject, String html) {
    return CompletableFuture.completedFuture(
        resendEmailService.sendHtmlEmail(to, subject, html)
    );
}
```

## Dépendances

### Maven

```xml
<!-- WebClient pour API REST -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Lombok pour @Slf4j -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
</dependency>
```

### Spring Beans

```java
@Bean
public ResendEmailService resendEmailService(
    @Value("${resend.api-key}") String apiKey,
    @Value("${resend.from-email}") String fromEmail,
    @Value("${resend.from-name}") String fromName,
    @Value("${resend.enabled}") boolean enabled
) {
    return new ResendEmailService(apiKey, fromEmail, fromName, enabled);
}
```

Auto-configuré par Spring Boot via `@Service`.

## Sécurité

### Protection clé API

- ❌ Ne jamais commit `RESEND_API_KEY` dans Git
- ✅ Utiliser variables d'environnement
- ✅ Rotation régulière des clés (6 mois)
- ✅ Clés différentes dev/prod

### Validation des emails

```java
@Email(message = "Email invalide")
private String email;
```

Spring Validation vérifie le format avant l'envoi.

### Rate limiting

Pour éviter l'abus:

```java
@RateLimiter(name = "email", fallbackMethod = "emailRateLimitFallback")
public boolean sendEmail(String to, String subject, String html) {
    return resendEmailService.sendHtmlEmail(to, subject, html);
}
```

## Documentation

- [Guide complet de configuration](../../../../docs/guides/EMAIL_CONFIGURATION.md)
- [Documentation migration SMTP→Resend](../../../../docs/deployment/EMAIL_MIGRATION_RESEND.md)
- [Troubleshooting SMTP](../../../../docs/troubleshooting/SMTP_TIMEOUT_FIX.md)
- [API Resend](https://resend.com/docs)

## Contact

Pour questions techniques sur ce package:
- Vérifier la documentation ci-dessus
- Consulter les logs applicatifs
- Vérifier le dashboard Resend: https://resend.com/emails

---

**Package créé**: 2026-07-02  
**Maintenu par**: Backend team  
**Version**: 1.0
