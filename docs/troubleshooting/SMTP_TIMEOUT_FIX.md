# Fix: SMTP Connection Timeout (Hostinger)

## Symptôme

```
org.springframework.mail.MailSendException: Mail server connection failed
Caused by: java.net.SocketTimeoutException: Connect timed out
at org.eclipse.angus.mail.util.MailConnectException: 
Couldn't connect to host, port: smtp.hostinger.com, 587; timeout 5000
```

## Cause

Railway bloque les ports SMTP sortants (587, 465, 25) pour prévenir le spam.

## Solution

✅ **Migré vers Resend API** (remplace SMTP par HTTPS/REST)

### Changements

1. **EmailService.java**: Utilise `ResendEmailService` au lieu de `JavaMailSender`
2. **Configuration**: Désactivé auto-configuration Spring Boot Mail
3. **Variables**: Remplacé `MAIL_*` par `RESEND_*`

### Configuration requise

```bash
# Railway variables
RESEND_ENABLED=true
RESEND_API_KEY=re_xxxxxxxxxxxx
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
```

### Vérification

```bash
# Plus d'erreurs SMTP dans les logs
railway logs | grep -i smtp
# (devrait être vide)

# Vérifier les envois Resend
railway logs | grep "Email sent successfully via Resend"
```

## Documentation complète

- [Configuration Email](../guides/EMAIL_CONFIGURATION.md)
- [Migration détaillée](../deployment/EMAIL_MIGRATION_RESEND.md)

## Date

**Résolu**: 2026-07-02  
**Impact**: Production (Railway)  
**Downtime**: 0
