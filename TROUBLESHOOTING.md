# SMS Relay troubleshooting and reliability

[Русская версия](TROUBLESHOOTING.ru.md)

This document explains the locked-screen failures observed on a Tecno Spark 30C running
HiOS, how they were diagnosed, and what SMS Relay 1.2.4 does to prevent missed or duplicate
messages. The Android implementation is generic; the Hiber-specific recovery steps apply
only to Tecno/Infinix/itel firmware that exposes the same Transsion components.

## Observed failures

### SMS reached the phone but Telegram received it only after unlock

The system Messages application already showed the SMS. SMS Relay did not run while the
screen was locked. Unlocking or opening the application immediately released the delayed
work and the message was forwarded.

This distinguished the problem from mobile reception, Telegram, the relay server, and the
local SIM route: Android had stored the message, but vendor power management had frozen the
application process.

### Android battery settings looked correct

The regular Android page already showed unrestricted battery use. Auto-start was allowed,
background data was enabled, inactivity suspension was disabled, and the foreground
notification was visible. These standard controls did not update a separate HiOS Hiber
allowlist.

### The problem appeared after a HiOS update

The UI could continue showing **No restrictions** while Hiber's runtime state no longer
matched it. The app therefore worked for days, then stopped processing locked-screen SMS
after an automatic firmware update or reboot.

### Some vendor SMS events were empty or incomplete

On the affected firmware, `SMS_RECEIVED` could wake the application without supplying a
usable SMS payload. The complete message was nevertheless present in Android's SMS inbox.
Depending only on the broadcast payload could therefore lose an otherwise recoverable SMS.

### One SMS could be forwarded twice

Earlier builds could accept the same message once from the broadcast payload and once from
the inbox observer. Because those paths did not share the Android inbox row ID, a timing
race could create two local message IDs. This was fixed in 1.2.4.

## Root cause: HiOS Hiber

Device logs showed the proprietary HiOS freezer applying a restriction to the SMS Relay
UID shortly after the screen was locked. Relevant log entries included variants of:

```text
Hiber/sceneManager: freeze uid: <UID> com.example.smstotelegram
Hiber/broadcast restricted
```

The normal Android unrestricted-battery setting and Android's Doze allowlist were already
correct. Reapplying **No restrictions** in the separate Battery Lab screen refreshed Hiber's
internal no-freeze state. Locked-screen tests then succeeded without opening the app.

On the tested firmware, the internal state can be inspected through an undocumented vendor
command:

```sh
adb shell pm list packages -U com.example.smstotelegram
adb shell dumpsys activity hiber get_app_mode YOUR_UID
```

`mode=1` represented the no-freeze mode on that device. The vendor command may not exist or
may use different meanings on another firmware, so it is diagnostic information rather than
a portable setup API. Do not hard-code the UID; Android assigns it per installation.

The supported recovery is:

1. Open **Settings -> Battery / Battery Lab -> App power saving management**.
2. Select **SMS Relay**.
3. Choose **Intelligent management** once.
4. Choose **No restrictions** again.
5. Open SMS Relay and verify that its three status lines are positive.
6. Lock the phone for several minutes and send a real SMS.

If the Battery Lab page is hidden, ADB can open the vendor activity:

```sh
adb shell am start -a com.transsion.batterylab.app_saving
```

Repeat the toggle after a HiOS update or when forwarding starts only after unlock. A USSD
balance request is not an SMS and cannot validate this path.

## What version 1.2.4 changed

SMS Relay now uses several independent recovery paths:

1. `SMS_RECEIVED` immediately starts the foreground guard and requests inbox recovery.
2. With `READ_SMS` granted, the Android inbox row is the authoritative source. The vendor
   broadcast payload is parsed only as a fallback when inbox access is unavailable.
3. The service observes both the general SMS URI and the inbox-specific URI.
4. Recovery runs immediately and again after 750 milliseconds and 3 seconds, covering
   providers that commit the inbox row after broadcasting the event.
5. While the foreground service is alive, it performs a short local inbox check every
   30 seconds. This does not contact the server and holds no wake lock; Android may delay the
   callback during deep sleep.
6. A persisted `JobScheduler` task checks the inbox roughly every 15 minutes as a delayed
   fallback. Its schedule is refreshed when the service starts.
7. Recovery also runs after boot, first user unlock, application replacement, and opening
   the app.
8. An inbox message ID is derived from the stable installation ID plus Android's inbox row
   ID. Reading one row through multiple paths produces one queued message, while two separate
   identical SMS have different row IDs and are both forwarded.
9. The SMS is synchronously committed to private local storage before network delivery is
   scheduled. Network and Telegram failures are retried without deleting the phone's SMS.

These layers mitigate empty broadcasts, provider timing differences, process recreation,
temporary network loss, server restarts, and duplicate event delivery. They cannot override
a vendor freezer that prevents Android from running the app at all, which is why the Battery
Lab setting remains required.

## Why the app remains lightweight

- No analytics, advertisements, cloud SDK, or third-party Android runtime libraries.
- No network polling. HTTPS is used only for configuration and queued SMS delivery.
- No wake lock and no continuously running worker thread.
- Local checks query only inbox row IDs above the last processed ID.
- The foreground service has a low-priority silent notification.
- Android `JobScheduler` waits for connectivity instead of retrying in a tight loop.

## Recovery and delivery guarantees

- The phone's SMS database is never deleted or modified by SMS Relay.
- Up to 100 unsent messages are kept in the app's private queue.
- A queued SMS remains across process death and reboot.
- The server commits the message and recipient delivery rows before acknowledging the phone.
- Every recipient retries independently with exponential backoff.
- Message content is removed from the server after all recipients have received it.

No Android application can provide an absolute guarantee. Forwarding is impossible if the
modem never receives the SMS, Android never writes it to the inbox, the user force-stops the
app, permissions are revoked, storage is full, or the vendor firmware blocks every receiver,
service, observer, and scheduled job. Telegram and the relay server are also external
dependencies.

## Verification checklist

After installation, reboot, APK update, or firmware update:

1. Open SMS Relay and confirm `Configured: yes`, `SMS permission: granted`, and
   `Foreground guard: active`.
2. Confirm the persistent **SMS Relay active** notification exists.
3. Confirm Auto-start, background data, unrestricted battery use, and Battery Lab
   **No restrictions**.
4. Lock the phone for at least five minutes.
5. Send two separate SMS with identical text. Both should arrive exactly once.
6. Disable the phone's Internet, send an SMS, restore Internet, and confirm delayed delivery.
7. Verify SIM 1 and SIM 2 separately when both are configured.

For ADB diagnostics, the active foreground service can be checked with:

```sh
adb shell dumpsys activity services com.example.smstotelegram/.RelayKeepAliveService
```

An empty result means the service is not currently active. Open the app, recheck the required
settings, and run a locked-screen SMS test.
