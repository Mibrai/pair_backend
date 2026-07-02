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

## Mail Configuration (Hostinger)
Required variables for email functionality:

```
MAIL_HOST=smtp.hostinger.com
MAIL_PORT=587
MAIL_USERNAME=infos@meetdo.fun
MAIL_PASSWORD=Kamerun237@MeetDo
MAIL_FROM=infos@meetdo.fun
```

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
