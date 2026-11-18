# Run your own FlowLite sync server

You do not need a server to use FlowLite. Dictation runs entirely on your device and your history stays there.

A sync server only does one job. It keeps your transcript history, personal dictionary and settings the same across your phone and computers. If you use FlowLite on one device, skip this page.

Your server belongs to you. Nobody else, including the FlowLite author, can see what you sync to it.

## What you need

- A small Linux server with a public IP address. Any provider works (Hetzner, Contabo, DigitalOcean, Vultr and others). The smallest plan is plenty, around 5 euros a month, 1 GB of RAM.
- A domain or subdomain you control, for example `sync.yourname.com`.
- About 15 minutes.

## Step 1. Point your domain at the server

In your domain's DNS settings, add an `A` record:

| Name | Type | Value |
|---|---|---|
| `sync` (or whatever you like) | A | your server's IP address |

DNS changes can take a few minutes to take effect.

## Step 2. Install Docker

Log in to the server over SSH and run:

```sh
curl -fsSL https://get.docker.com | sh
```

## Step 3. Download FlowLite's server files

```sh
git clone https://github.com/mchristo839/flowlite.git
cd flowlite/server
cp .env.example .env
nano .env
```

Fill in `.env`:

- `DOMAIN`: the address from step 1, for example `sync.yourname.com`, without `https://`
- `PB_SUPERUSER_EMAIL` and `PB_SUPERUSER_PASSWORD`: the admin login for the server dashboard
- `FLOWLITE_EMAIL` and `FLOWLITE_PASSWORD`: the login you will type into the app

Use long passwords. Save with `Ctrl+O`, then `Enter`, then `Ctrl+X`.

## Step 4. Start it

```sh
./setup.sh
```

The script builds the server, gets an HTTPS certificate automatically and creates both logins. When it finishes it prints the address to use in the app.

Check it works by opening `https://sync.yourname.com/api/health` in a browser. You should see `"message": "API is healthy."`.

## Step 5. Connect the app

On each device, enter:

- Server address: `https://sync.yourname.com`
- Email and password: the `FLOWLITE_` values from `.env`

Where to find it:

- **Android:** open FlowLite, Settings, Sync, then tap **Sign in**.
- **Linux, Windows and Mac:** open FlowLite, the **Sync** page in the sidebar, then click **Sign in**.

Signing in also tests the connection. If it fails, the message tells you whether the address is wrong, the server is down, or the email or password is wrong. Your history starts syncing straight away.

## Looking after it

- **Add another person:** `./add-user.sh their@email.com their-password`. Each login sees only its own data.
- **Reset a password:** run the same command with the existing email. This also signs that login out on every device, which is what to do if a phone or laptop is lost. Logins otherwise last 30 days and renew themselves while the app syncs.
- **Back up:** `./backup.sh /path/to/backups` writes a dated copy and keeps the last 7. To run it every night at 3am, add this line with `crontab -e`:
  `0 3 * * * /path/to/flowlite/server/backup.sh /path/to/backups`
- **Update:** `git pull && ./setup.sh`. Do this before connecting the Windows or Mac app to a server set up before 18 November 2026, so the server accepts their transcripts.
- **Dashboard:** `https://sync.yourname.com/_/`, sign in with the admin login.

## Already run a reverse proxy?

If ports 80 and 443 are taken by your own Caddy, nginx or Traefik, set `HTTPS=none` in `.env` and run `./setup.sh`. The server then listens on `127.0.0.1:8090` only. Point your proxy at that address and serve it over HTTPS.

## Troubleshooting

| Problem | Fix |
|---|---|
| The app says it cannot reach the server | Open `https://your-domain/api/health` in a browser. If that fails, check DNS (step 1) and run `docker compose logs caddy`. |
| No HTTPS certificate | Ports 80 and 443 must be open in your provider's firewall. Caddy needs both to get a certificate. |
| Wrong email or password in the app | Run `./add-user.sh` with the email to set a new password. |
| Anything else | `docker compose logs pocketbase` shows the server log. |

## Privacy

The server stores the text of your transcripts, your dictionary and app settings. It never receives audio. Data is readable only by the login that created it and by whoever holds the admin login.
