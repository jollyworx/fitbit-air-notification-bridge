# Fitbit Air Notification Bridge

[简体中文](README.zh-CN.md)

An experimental, unofficial Android bridge that turns selected phone notifications into short, recognizable haptic patterns on Fitbit Air.

The project is based on Fitbit's Apache-2.0-licensed [Golden Gate](https://github.com/Fitbit/golden-gate) transport stack. It uses BLE, Gattlink, DTLS/CoAP and a locally processed Android `NotificationListenerService`. No cloud relay is required.

> **Alpha software.** The protocol has been validated on one Fitbit Air. It is not an official Fitbit or Google product. Do not use it for medical, emergency, or safety-critical alerts.

## Current capabilities

- Connect to Fitbit Air over Golden Gate/Gattlink.
- Establish the device's BOOTSTRAP DTLS session.
- Read the current haptic intensity through `PUT /settings`.
- Produce a bounded short-vibration group by changing the intensity and restoring it after 800 ms.
- Verify that the final device setting matches the original setting.
- Monitor up to five Android application packages.
- Assign one of five patterns to each application: `single`, `double`, `triple`, `long_gap`, or `urgent`.
- Filter group summaries, background-work notifications, self-notifications and recent duplicate content.
- Preconfigure SMS, the default phone app, WhatsApp, Messenger and Outlook. Android's selected SMS and phone apps are detected when there are no saved rules.
- Forward recognized incoming-call notifications, including ongoing ones, with the `urgent` pattern. Suppress repeated ringing updates for the same notification key until removal. Modern CallStyle metadata is used; older call notifications need a full-screen intent and no running chronometer.
- Persist the selected Air address. Enable the foreground bridge explicitly from the app; its visible status notification remains while it waits for alerts. No BLE connection or wake lock is held while idle.
- Scan, connect, establish DTLS, run and verify the pattern, then disconnect immediately so Google Health can reconnect.
- Retry a temporarily unavailable Air twice with short backoff delays.

Example rules:

```text
com.google.android.apps.messaging=single
com.google.android.dialer=urgent
com.whatsapp=double
com.facebook.orca=triple
com.microsoft.office.outlook=long_gap
```

## Important limitations

- The v10013 notification path and patterns are device-verified. The v10015 modern Android foreground-service lifecycle and on-demand coexistence path still need device validation alongside Google Health.
- Only `arm64-v8a` is built at present.
- A pattern is composed of verified **toggle/restore haptic groups**. It is not arbitrary motor waveform control.
- Fitbit Air cannot be actively owned by this app and Google Health at the same time. v10014 time-shares it instead of maintaining a permanent connection.
- If Google Health is syncing, alert delivery may be delayed while the bridge waits for the Air to advertise again.
- The current saved Bluetooth address may need to be selected again if the device rotates its BLE address.
- Incoming-call delivery depends on the phone/app posting a recognizable notification and still needs validation on the user's device. Apps with a custom call notification lacking CallStyle metadata or a full-screen intent may be skipped. One bounded pattern is queued per recognized ringing call; this does not continuously vibrate until answer or cancel a pattern already started when the call is answered.

## Basic use

1. Install the app and grant Nearby devices/Bluetooth access and permission to show its status notification.
2. Tap **Select and save Fitbit Air** once. Google Health may need to be idle for this initial scan.
3. Check the five prefilled `package=pattern` rules and grant Notification access. Existing saved rules are preserved. Other editions such as WhatsApp Business need their own package name.
4. Tap **Bridge inschakelen** in the Dutch interface and check the visible status notification. Use the one-shot `single` test (which also enables the bridge). The status panel records discovery, retry, success, or failure.
5. Leave Google Health running and send a real notification from each configured application with the screen locked. Re-enable the bridge after a reboot, force-stop or manual stop. Android/OnePlus background restrictions and competing Bluetooth connections can affect delivery; this remains device-unverified.
6. Tap **Bridge uitschakelen** to stop forwarding. An active task is allowed to finish its setting restoration first; queued alerts are cleared.

## Privacy and security

- Notification content is inspected only in memory for duplicate suppression.
- Rules are stored locally with Android `SharedPreferences`.
- Notification bodies, device keys and user data are not sent to a server by this project.
- This repository intentionally contains no Google Health APK, extracted proprietary code, credentials or device keys.

## Build

The Android app is under `platform/android/goldengate`. The inherited Golden Gate build currently uses Gradle 8.2, Android Gradle Plugin 8.2.2, Kotlin 1.9.22, JDK 17, Android SDK/target 34, NDK 23.0.7599858 and CMake 3.10.2.

Build the Golden Gate Android core first, then run:

```bash
cd platform/android/goldengate
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The workflow verifies the signature, target SDK 34, arm64-only native libraries and 16 KB ELF alignment. Native libraries are compressed for compatibility with this AGP version. This verification is not a security audit or a device test.

The debug APK is written to `platform/android/goldengate/app/build/outputs/apk/debug/app-debug.apk`.

See [Golden Gate's Android build documentation](docs/src/platforms/android.md) for native toolchain setup.

## Roadmap

1. Validate v10014 success rate and latency while Google Health is idle, syncing, foreground, and background.
2. Add Companion Device presence support and resilient handling of BLE address rotation.
3. Replace package-name text rules with an installed-app picker for 3–5 rules.
4. Add a visual pattern editor, cooldown controls and a safe preview button.
5. Publish reproducible GitHub Actions builds and signed release APKs.

## License

Apache License 2.0. The original Golden Gate copyright and license notices are retained. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the [upstream README](docs/UPSTREAM_GOLDEN_GATE_README.md).
