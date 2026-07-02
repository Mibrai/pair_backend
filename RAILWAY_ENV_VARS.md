# Railway Environment Variables

Configure these variables in your Railway project settings.

## Database (Auto-configured by Railway Postgres service)
- `PGHOST` - Provided by Railway
- `PGPORT` - Provided by Railway
- `PGDATABASE` - Provided by Railway
- `PGUSER` - Provided by Railway
- `PGPASSWORD` - Provided by Railway

## Server
- `PORT` - 8080 (default)

## Mail Configuration

### Resend (Recommended for Railway)
Railway blocks SMTP ports, so we use Resend API instead:

```
RESEND_ENABLED=true
RESEND_API_KEY=re_your_api_key_here
RESEND_FROM_EMAIL=infos@meetdo.fun
RESEND_FROM_NAME=MeetDo
FRONTEND_URL=https://your-frontend-domain.com
```

**How to get Resend API Key:**
1. Sign up at https://resend.com (free tier: 100 emails/day)
2. Go to API Keys > Create API Key
3. Give it "Sending access" permission
4. Copy the key (starts with `re_`) and add it to Railway variables

**Domain verification (required):**
- Verify your domain `meetdo.fun` in Resend for sending
- Go to Domains > Add Domain
- Add DNS records provided by Resend to Hostinger
- See RESEND_SETUP.md for detailed guide

## Redis (Optional)
```
REDIS_ENABLED=false
REDIS_HOST=localhost
REDIS_PORT=6379
```

## Seeding
```
SEED_REFERENCE_DATA_ENABLED=true
SEED_DEMO_DATA_ENABLED=true
```

## How to configure in Railway:

1. Go to your Railway project
2. Select your backend service
3. Go to "Variables" tab
4. Add the mail variables listed above
5. Redeploy the service
