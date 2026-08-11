# Fitbit Air protocol notes

These notes document behavior observed through clean-room interoperability testing. They do not contain Google Health application code, credentials, or device keys.

## Verified transport

```text
Android app
  -> BLE GATT
  -> Golden Gate Gattlink
  -> DTLS session using the device BOOTSTRAP identity
  -> CoAP
  -> Fitbit Air
```

## Verified haptic setting exchange

The current haptic setting can be requested with `PUT /settings` and a request containing `SETTING_TYPE_HAPTICS`.

Observed response for `HIGH`:

```text
0A040A020803
```

Changing the active intensity with `POST /settings` causes a short preview vibration. Repeating the same value does not cause the preview.

The bounded haptic primitive used by this project is:

```text
read original intensity
POST alternate intensity
wait 800 ms
POST original intensity
read and verify original intensity
```

On the tested device, `HIGH -> LOW -> HIGH` produced a weak short vibration followed by a strong short vibration and left the setting at `HIGH`.

## Pattern semantics

One pattern group is one complete toggle/restore primitive. Multiple groups are separated by an additional delay:

| Pattern | Groups | Additional delays between groups |
|---|---:|---|
| `single` | 1 | none |
| `double` | 2 | 650 ms |
| `triple` | 3 | 650 ms, 650 ms |
| `long_gap` | 2 | 1800 ms |
| `urgent` | 4 | 450 ms, 1350 ms, 450 ms |

Actual perceived timing also includes BLE/CoAP response latency. Patterns therefore require device testing and are not real-time waveforms.

## Connection ownership and v10014 time-sharing

Interoperability testing indicates that Fitbit Air accepts only one active Gattlink/DTLS owner. A persistent bridge connection therefore prevents Google Health from connecting, and vice versa.

v10014 changes the notification path to:

```text
notification listener (disconnected)
  -> foreground one-shot task
  -> wait for the saved Air address to advertise
  -> Gattlink + BOOTSTRAP DTLS
  -> execute and verify pattern
  -> close the Golden Gate peer and BLE connection
  -> stop the foreground task
```

The task makes at most three attempts. The scan and connection timeouts deliberately bound how long the bridge competes with Google Health. This time-sharing behavior is pending device validation.
