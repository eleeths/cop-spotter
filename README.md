# Cop Spotter — Android

A passive proximity pager for Android. Your phone's own radios listen for the
Bluetooth and Wi-Fi signatures that police equipment broadcasts — bodycams,
TASERs, Flock camera accessories, radio gear — and for two other things worth
knowing about: hidden trackers and card skimmers.

**Receive-only, by design.** Cop Spotter listens to public broadcast
advertisements that devices intentionally transmit for discovery. It does not
intercept, decrypt, record, or interfere with any communication, and it
transmits nothing.

## What it detects

103 verified hardware fingerprints, in three classes:

| Class | Count | Alert |
|---|---|---|
| Police gear (Axon bodycams & vehicle systems, TASER, Flock accessories, Motorola and other radio gear, WatchGuard / Digital Ally in-car video, DJI drones and Remote ID, Stryker LIFEPAK, Cradlepoint patrol routers, Geotab telematics, Utility BodyWorn) | 84 | Red/blue strobe + beep + vibrate |
| Hidden trackers (AirTag, SmartTag, Tile and similar) | 10 | Purple — close-range only, pulse quickens as you get closer so you can walk it down |
| Card skimmers (Bluetooth serial modules used by pump/reader skimmers) | 9 | Amber — close-range only |

The signature database is built from public registries — Bluetooth SIG company
IDs, IEEE hardware prefixes, and the known broadcast fingerprints of the gear
itself.

## Using it

- **START WATCH** begins the patrol. Contacts appear in real time — a contact
  shows only while it has been heard in the last few seconds, then the panel
  returns to CLEAR.
- Police alerts gate at −80 dBm; trackers and skimmers gate at −70 dBm, so the
  quiet classes only fire when the device is genuinely close.

## Honest limits

Bluetooth carries a room or two, through a wall if you're lucky. Cop Spotter
knows gear is *close* — it can't point down the block. Gear advertises in
bursts to save battery, so a quiet device can be missed. This is an awareness
tool, not a safety device. Know your local laws; the responsibility for where
and how you use it is yours.

## Get it

- **Free APK:** https://eleeth.com/cop-spotter-app.apk (direct download,
  sideload)
- **F-Droid:** submission in progress
- **Support the project (pay what you want):**
  https://buy.stripe.com/14A7sLd267BE49780W3wQ3y

There is also a hardware version — a pocket ESP32-C6 unit with the same
signature engine and a single alert light — at https://eleeth.com.

## Building

- JDK 17, Gradle 8.7, Android SDK platform 34 / build-tools 34
- `gradle assembleDebug --offline` (dependencies resolve from the standard
  Google/Maven repositories when not offline)
- Host-side signature-engine tests live in `hosttest/` (plain `javac`/`java`,
  no device needed)

## License

MIT — see [LICENSE](LICENSE).
