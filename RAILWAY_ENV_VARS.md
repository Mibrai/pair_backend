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
  `/app/uploads` by the Dockerfile, matching the volume mount path. **If you ever
  move the volume, change this variable in the same breath** — the app writing to
  a path the volume does not cover is indistinguishable, at runtime, from having
  no volume at all.

**A volume is mandatory, not optional.** Uploaded files (avatars, program
covers) are written to the filesystem, while only their URL is stored in the
database. Without a volume, that filesystem is the container's ephemeral write
layer: every redeploy wipes the bytes and leaves the database pointing at files
that no longer exist. This is exactly what happened on 2026-08-11 — every single
`GET /api/media/files/**` failed in production, and program duplication failed
with it.

Setup, in the Railway dashboard:
1. Service > Settings > Volumes > **New Volume**
2. Mount path: **`/app/uploads`** — it must match `STORAGE_PATH` exactly.
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
FIREBASE_CREDENTIALS_BASE64=<the service account JSON, base64-encoded>
```

`FIREBASE_ENABLED` defaults to `false`, and while it is false **no push is ever
sent** — `NoOpPushNotificationService` is wired in place of the real one.
Nothing else in the app changes, which is precisely what makes this easy to
miss: notifications and messages keep being stored, the API keeps answering, and
only the phone stays silent.

Since `aps.badge` is what keeps the icon badge accurate while the app is closed,
a missing variable here shows up as *"the badge is stuck on its last value"*,
not as an error.

**Why base64 and not a file path.** The service account JSON holds a private
key. On Railway it can come neither from a file — the container disk is rebuilt
on every deploy — nor from the classpath, which would mean committing a secret
to a public repository. So it travels base64-encoded in an environment
variable. `FIREBASE_CREDENTIALS_PATH` still works and is the local-development
route, where dropping a file costs nothing; it takes effect only when
`FIREBASE_CREDENTIALS_BASE64` is empty.

**Setting it, in this order.** Encode first, flip the switch last — with
`FIREBASE_ENABLED=true` and no usable credentials, startup fails on purpose, and
there is no reason to spend a red deploy on it:

```bash
base64 -i ~/Downloads/service-account.json | tr -d '\n' | pbcopy

railway service                       # select pair_backend_service
railway variables --set "FIREBASE_CREDENTIALS_BASE64=<paste>"
railway variables --set "FIREBASE_ENABLED=true"
```

The `tr -d '\n'` matters: a pasted value that carries a trailing newline is the
most common failure here. The app trims it anyway, and names the variable in the
error if the value still fails to decode.

**Checking a deployment.** The startup log settles it in one line:

- `Firebase initialized successfully (push notifications enabled, source: base64)`
  → push is on, credentials came from the environment variable;
- `... source: /some/path` → it fell back to the file route, which on Railway
  means the base64 variable is missing or empty;
- no such line → `FIREBASE_ENABLED` is not `true`, and push is off.

With `FIREBASE_ENABLED=true`, missing or unreadable credentials **fail startup**
instead of downgrading to silence — a broken push setup is visible at deploy
time.

**Getting the credentials file:** Firebase console > Project settings > Service
accounts > Generate new private key. Never commit it; encode it as above.

**iOS also needs an APNs key, and its absence is silent.** Without it, pushes
reach Android and not iOS, **with no error on the backend side** — the same kind
of silence that made the icon-badge bug take weeks to pin down. Firebase console
> Project settings > Cloud Messaging > Apple app configuration: the APNs key
must be listed there with its Key ID.

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
