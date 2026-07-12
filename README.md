# SMS Relay

A tiny, dependency-free Android app and Python server that forward incoming SMS to
Telegram. Recipients are selected independently for SIM 1 and SIM 2 and must verify
their own phone number with the bot before receiving anything.

## How it works

1. Android receives an SMS, identifies its physical SIM slot, and synchronously saves
   it to a local queue.
2. Android `JobScheduler` sends the queued item to the private HTTPS server when a
   network is available.
3. The server stores the item in SQLite before acknowledging it.
4. The server sends it to Telegram users whose verified phone numbers are allowed for
   that SIM slot. Delivery retries are tracked separately for every recipient.
5. After delivery, SMS text is erased from the server. Only the random message ID is
   retained for seven days to prevent duplicates.

The Android app has no permanent background service, polling, analytics, ads, or
third-party runtime libraries.

## Server installation

Requirements: Ubuntu/Debian, Python 3, Nginx, OpenSSL, systemd, a public static IP,
and ports 22 and 443 open. Port 443 must not already have another `default_server`.

```sh
sudo apt update
sudo apt install -y nginx openssl python3
sudo ./server/install.sh YOUR_SERVER_IP
```

The installer prints the TLS SHA-256 fingerprint needed by the Android app. It creates:

- `/opt/sms-relay/sms_relay.py`
- `/etc/systemd/system/sms-relay.service`
- `/etc/nginx/sites-available/sms-relay`
- `/etc/sms-relay/tls.crt` and `tls.key`
- `/var/lib/sms-relay/messages.sqlite3`

No Telegram token is stored in the repository or environment file. The first valid
configuration sent by the Android app binds the server to that bot token. To replace
the bot later, stop the service and remove the `config` row from SQLite.

## Create a Telegram bot

1. Open `@BotFather` in Telegram.
2. Run `/newbot` and copy the token.
3. Do not commit the token or send it to anyone.

## Build Android APK

Open the project in Android Studio, or install Android SDK 35 and run:

```sh
cp local.properties.example local.properties
# Edit sdk.dir. Optional relay defaults can also be set in this local-only file.
./gradlew lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Configure the phone

1. Install and open the APK.
2. Enter the HTTPS server URL, bot token, and TLS fingerprint printed by the installer.
3. Under SIM 1 and SIM 2, enter allowed recipient phone numbers in full international
   format, one per line, for example `+77001234567`.
4. Tap **Save and sync**, then grant SMS permission.
5. Every recipient opens the bot and taps **Share my phone number**. Telegram must share
   the user's own contact; arbitrary forwarded contacts are rejected.
6. Use **Test SIM 1** and **Test SIM 2** to verify each route.

A recipient may be listed for one SIM or both. Removing a number and syncing immediately
revokes its server-side recipient registration.

## Reliability and security

- Up to 100 SMS are retained locally during an outage.
- Android retries only when a network is available; the server independently retries
  Telegram with exponential backoff.
- HTTPS is mandatory. A self-signed IP certificate is protected by SHA-256 pinning.
- The server exposes no endpoint for reading stored SMS.
- Android backups are disabled for settings and the SMS queue.
- Telegram bot messages are not end-to-end encrypted. Anyone with the bot token can act
  as the bot, so rotate a token that has been exposed.
- On aggressive Android firmware, enable auto-start and unrestricted battery usage for
  SMS Relay. The app still remains idle when no SMS arrives.

## License

MIT
