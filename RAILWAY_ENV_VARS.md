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

### SendGrid (Recommended for Railway)
Railway blocks SMTP ports, so we use SendGrid API instead:

```
SENDGRID_ENABLED=true
SENDGRID_API_KEY=your_sendgrid_api_key_here
SENDGRID_FROM_EMAIL=infos@meetdo.fun
SENDGRID_FROM_NAME=MeetDo
FRONTEND_URL=https://your-frontend-domain.com
```

**How to get SendGrid API Key:**
1. Sign up at https://sendgrid.com (free tier: 100 emails/day)
2. Go to Settings > API Keys
3. Create a new API key with "Mail Send" permission
4. Copy the key and add it to Railway variables

**Domain verification (optional but recommended):**
- Verify your domain `meetdo.fun` in SendGrid for better deliverability
- Go to Settings > Sender Authentication > Domain Authentication

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
