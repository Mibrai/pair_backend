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

## Media storage (REQUIRES a persistent volume)
- `STORAGE_PATH` - absolute path where uploaded media is written. Set to
  `/data/uploads` by the Dockerfile; override only if the volume is mounted
  elsewhere.

**A volume is mandatory, not optional.** Uploaded files (avatars, program
covers) are written to the filesystem, while only their URL is stored in the
database. Without a volume, that filesystem is the container's ephemeral write
layer: every redeploy wipes the bytes and leaves the database pointing at files
that no longer exist. This is exactly what happened on 2026-08-11 — every single
`GET /api/media/files/**` failed in production, and program duplication failed
with it.

Setup, in the Railway dashboard:
1. Service > Settings > Volumes > **New Volume**
2. Mount path: `/data`
3. Redeploy.

Verify it worked — the startup logs say so explicitly:
- `Storage persisted across restarts (initialized on ...)` → the volume is
  mounted and survived. This is what you want to see on **every** redeploy.
- `Storage contains no persistence marker` → first boot, **or** a wiped volume.
  Seeing it twice in a row means media is not being persisted.
- `Storage path 'uploads' is relative` → `STORAGE_PATH` is unset; files are going
  to the ephemeral layer.

Migrating to object storage (S3/R2/GCS) removes the need for a volume:
`StorageService` is an interface and `LocalStorageService` its only
implementation, so a second implementation requires no change to any caller.

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

## Push notifications (Firebase Cloud Messaging)

```
FIREBASE_ENABLED=true
FIREBASE_CREDENTIALS_PATH=/app/config/firebase-service-account.json
```

Both are required together. `FIREBASE_ENABLED` defaults to `false`, and while it
is false **no push is ever sent** — `NoOpPushNotificationService` is wired in
place of the real one. Nothing else in the app changes, which is precisely what
made this easy to miss: notifications and messages keep being stored, the API
keeps answering, and only the phone stays silent.

Since `aps.badge` is what keeps the icon badge accurate while the app is closed,
a missing variable here shows up as *"the badge is stuck on its last value"*,
not as an error.

**Checking a deployment.** The startup log settles it in one line:

- `Firebase initialized successfully (push notifications enabled)` → push is on;
- no such line → `FIREBASE_ENABLED` is not `true`, and push is off.

With `FIREBASE_ENABLED=true`, an unreadable or missing credentials file now
**fails startup** instead of downgrading to silence — a broken push setup is
visible at deploy time.

**Getting the credentials file:** Firebase console > Project settings > Service
accounts > Generate new private key. The JSON must be mounted on the persistent
volume (or baked into the image); `classpath:firebase-service-account.json` also
works if it ships inside the jar.

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
