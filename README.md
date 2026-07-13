# SMS Relay

[Русская версия](README.ru.md)

SMS Relay is a small, open-source Android app and dependency-free Python server that
forward incoming SMS to selected Telegram accounts. SIM 1 and SIM 2 have independent
recipient allowlists. A Telegram user receives messages only after sharing and verifying
their own allowlisted phone number with the bot.

The project is designed for a stationary, inexpensive Android phone. It has no ads,
analytics, polling loop, cloud SDK, or third-party Android runtime dependencies.

## Architecture

```text
Mobile network -> Android SMS broadcast -> durable local queue -> HTTPS + TLS pin
               -> private relay server -> SQLite -> Telegram Bot API -> recipients
```

1. Android delivers `SMS_RECEIVED` to `SmsReceiver`.
2. Multipart SMS segments are combined by sender. The app determines the physical SIM
   slot and synchronously commits the message to its private local queue.
3. Android `JobScheduler` waits for a network and posts queued items to the relay over
   HTTPS. The bot token is used as the bearer credential. An optional SHA-256 certificate
   pin protects a self-signed IP certificate.
4. The server validates the token and message, writes the message and per-recipient
   delivery records to SQLite, commits the transaction, and only then acknowledges it.
5. A server worker sends the SMS through the Telegram Bot API. Failed deliveries retry
   with exponential backoff, independently for every recipient.
6. After every recipient has received the SMS, sender, body, and timestamp are erased
   from the server. The random message ID and delivery metadata remain for up to seven
   days for deduplication.

The foreground guard performs no polling or periodic network work. It keeps a low-priority
status notification and reduces process eviction. It does **not** override manufacturer
battery restrictions; the phone settings below are mandatory on Tecno HiOS.

## Repository layout

- `app/src/main/java/.../SmsReceiver.java`: parses incoming SMS and identifies the SIM.
- `SmsQueue.java`: synchronously stores up to 100 pending SMS in private app storage.
- `DeliveryJobService.java` and `DeliveryScheduler.java`: network-aware Android retries.
- `RelaySender.java`: HTTPS client and optional SHA-256 certificate pin validation.
- `RelayKeepAliveService.java`: idle foreground guard and persistent notification.
- `MainActivity.java` and `SettingsStore.java`: local configuration UI and storage.
- `server/sms_relay.py`: HTTP API, SQLite persistence, Telegram verification and delivery.
- `server/install.sh`: Debian/Ubuntu installation script.
- `server/nginx-sms-relay.conf`: TLS reverse proxy for `/sms`, `/configure`, and `/health`.
- `server/test_sms_relay.py`: server regression tests.

## Requirements

- Android 6.0 or newer with SMS support.
- A Telegram bot created with `@BotFather`.
- A public Debian/Ubuntu server with Python 3, Nginx, systemd, a static IP, and TCP port
  443 reachable from the phone.
- A domain is not required. The installer creates a self-signed certificate for the IP.

One server installation stores one bot token and one global SIM 1/SIM 2 route configuration.
Several phones may share it only when they intentionally use the same bot and identical
routes. Pressing **Save and sync** from either phone replaces the shared routes. Use a
separate relay instance for a different owner, bot, or route set.

## 1. Install the server

Clone the repository on the server and run the installer as root:

```sh
sudo apt update
sudo apt install -y git nginx openssl python3
git clone https://github.com/apfelinmv/sms-relay.git
cd sms-relay
sudo ./server/install.sh YOUR_PUBLIC_SERVER_IP
```

The script creates a restricted `sms-relay` system user and installs:

- `/opt/sms-relay/sms_relay.py`
- `/etc/systemd/system/sms-relay.service`
- `/etc/nginx/sites-available/sms-relay`
- `/etc/sms-relay/tls.crt` and `/etc/sms-relay/tls.key`
- `/var/lib/sms-relay/messages.sqlite3`

Copy the SHA-256 certificate fingerprint printed at the end. The Android app accepts it
with or without colons. Check the installation:

```sh
systemctl status sms-relay --no-pager
curl -k https://YOUR_PUBLIC_SERVER_IP/health
```

The expected health response is `{"ok":true}`. If this server already hosts websites or
uses port 443, review `server/nginx-sms-relay.conf` before installing: it is a
`default_server` configuration and must be merged with the existing Nginx setup.

The first valid **Save and sync** binds the server to that bot token. A later request with
a different token is rejected. To intentionally replace the bot, stop the service, back
up the database, and remove the `config` row and old recipient records from SQLite.

## 2. Create the Telegram bot

1. Open the official `@BotFather` account.
2. Send `/newbot`, choose a name, and copy the bot token.
3. Treat the token like a password. Never commit it to Git or include it in an APK.

Telegram bots cannot initiate a conversation with a user. Every recipient must complete
the verification in section 4 once.

## 3. Install and configure Android

Download the universal APK from
[the latest GitHub release](https://github.com/apfelinmv/sms-relay/releases/latest), copy it
to the phone, open it, and allow installation from that source when Android asks. Android
Studio is not required for the release APK.

Open SMS Relay and enter:

1. **Server URL:** `https://YOUR_PUBLIC_SERVER_IP`
2. **Telegram bot token:** the complete token from `@BotFather`.
3. **SIM 1 recipients:** allowed Telegram account phone numbers, one per line, in full
   international format such as `+77001234567`.
4. **SIM 2 recipients:** a separate list for SMS received by the second SIM. Leave it
   empty when unused.
5. **TLS certificate SHA-256:** the fingerprint printed by `install.sh`. It may be left
   empty only when the server uses a certificate trusted by Android.

Tap **Save and sync**, grant SMS and notification permissions, and keep the
**SMS Relay active** notification enabled. Use **Test SIM 1** and **Test SIM 2** after the
recipients finish Telegram verification.

## 4. Verify each Telegram recipient

1. The recipient opens the new bot and sends `/start`.
2. The bot displays **Share my phone number**.
3. The recipient taps the button and shares their own Telegram contact.
4. The server normalizes the number and compares it with the SIM allowlists.
5. The bot replies **Access granted** only on an exact allowlist match.

Forwarding another person's contact is rejected because the contact's Telegram user ID
must match the sender. Removing a number in Android and pressing **Save and sync** revokes
that recipient and cancels their pending, undelivered messages.

## 5. Required phone settings

Android manufacturers may suppress SMS broadcasts even when the standard battery screen
says the app is unrestricted. Configure all available items:

1. Allow **SMS** and **Notifications** permissions.
2. Enable **Auto-start** for SMS Relay.
3. Set battery use to **Unrestricted** / **Do not optimize**.
4. Allow background mobile data and Wi-Fi.
5. Disable **Pause app activity if unused**.
6. Keep the persistent **SMS Relay active** notification enabled.
7. Reopen SMS Relay once after reboot or a firmware update and check that the status says
   `Configured: yes`, `SMS permission: granted`, and `Foreground guard: active`.

### Tecno HiOS: critical extra setting

On tested Tecno HiOS firmware, auto-start, the regular Android unrestricted battery mode,
and locking the app in Recents were not sufficient. HiOS still logged
`Hiber/broadcast restricted` and skipped the SMS receiver.

Open:

**Settings -> Battery / Battery Lab -> App power saving management -> SMS Relay -> No restrictions**

The exact English labels may differ by firmware. The screen title is commonly
**App power saving management**. Choose **No restrictions**, not **Intelligent management**.
When the menu is hidden, connect ADB and open it directly:

```sh
adb shell am start -a com.transsion.batterylab.app_saving
```

Then select **SMS Relay -> No restrictions**. Locking the app in Recents may be kept as an
additional measure, but it does not replace this Battery Lab setting.

Perform a real test with the screen locked for several minutes. A USSD balance request is
not an SMS and cannot test `SMS_RECEIVED`.

## Reliability limits

- The local queue holds 100 messages. If more than 100 remain unsent, the oldest item is
  removed. Each SMS body is limited to 3,500 characters.
- The app saves locally before scheduling network work. Reboot and temporary loss of
  Internet do not erase queued messages.
- Android and the server retry independently. The server backoff starts at 30 seconds and
  is capped at one hour.
- Random message IDs make repeated uploads idempotent.
- The app is not the default SMS application and does not delete messages from the phone.
- If firmware blocks the `SMS_RECEIVED` broadcast before it reaches the app, that SMS
  cannot enter the local queue or be recovered automatically. Correct battery settings
  are therefore essential.
- USSD dialogs, push notifications, messenger messages, and cell broadcast alerts are not
  ordinary SMS and are not forwarded.

## Security model

- HTTPS is mandatory. A self-signed IP certificate is authenticated by its SHA-256 pin.
- The bot token is the API credential and is stored in Android private app storage and the
  server SQLite database. It is not embedded in the public APK or repository.
- The server has no endpoint for reading stored SMS. Nginx access logging is disabled for
  relay endpoints.
- SMS content is cleared from the server after delivery to all recipients. Pending content
  remains until delivery so retries can work.
- Android backup and data extraction are disabled for configuration and queue data.
- Telegram bot chats use Telegram's cloud encryption, not end-to-end encryption. Telegram,
  a compromised bot token, a compromised server, or a compromised recipient account can
  expose messages. Rotate an exposed bot token and rebuild the server configuration.
- SMS OTP forwarding inherently expands the number of systems that can access a code. Use
  a dedicated server, strong SSH authentication, OS updates, and the smallest recipient
  allowlist possible.

## Keeping a stationary phone healthy

- Keep it in a cool, ventilated place away from sunlight and soft furnishings.
- Prefer the manufacturer's charge-limit/battery-care feature. If unavailable, a timer or
  smart plug can avoid holding the battery at 100% continuously.
- Keep Wi-Fi and mobile signal stable; the SIM must remain registered to receive SMS.
- Reboot occasionally, then open SMS Relay and run a test. Recheck Battery Lab after HiOS
  updates because vendor settings may be reset.

## Build from source

Install Android SDK 35, then:

```sh
cp local.properties.example local.properties
# Edit sdk.dir for your machine.
./gradlew clean lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. For a stable
upgrade path, maintainers should keep a private signing key and create
`signing.properties` from `signing.properties.example`:

```sh
./gradlew -PrelayDefaultUrl= -PrelayDefaultPin= clean lintRelease assembleRelease
```

Never publish the signing key, `signing.properties`, a bot token, recipient numbers, a
server password, or private defaults.

## Tests

```sh
python3 -m unittest server/test_sms_relay.py
./gradlew clean lintDebug assembleDebug
```

## License

MIT
