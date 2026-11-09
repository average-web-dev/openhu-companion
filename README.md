# OpenHU companion

Android app for [OpenHU](https://github.com/average-web-dev/openhu): the music of the head unit (USB sticks and its
own storage, the music plugin `org.openhu.media`) appears as a media app in Android Auto. Browse the library (albums,
artists, playlists, songs) on the car screen, choose a song, and the head unit plays it. Play/pause, next and previous
work, the media card shows the head unit's song, and voice requests ("play … on OpenHU") search the library.

Concept and decisions: openhu#158.

## How it connects

Nothing to set up:

1. The phone is paired with the head unit over Bluetooth, which calls in Android Auto need anyway.
2. When Android Auto starts, it binds the app's media service (`CompanionService`). The app then listens on its
   RFCOMM service (UUID `57b07c95-708d-46ae-a658-9f296dfd034c`), and only bonded devices can connect.
3. Android Auto tells the head unit the phone's Bluetooth address. OpenHU connects to the app's service on exactly
   that phone. If the app is not installed, the connection fails and nothing happens.
4. The app announces itself (`hello`: app, version, label). After that the link carries OpenHU's API
   (`docs/api.md` in openhu) as JSON, one message per line: `media.current`, `music.query` → `music.result`,
   `music.command`. OpenHU recognizes Android Auto's media app by the announced label. It keeps the audio focus
   with the phone and does not treat the app's playback as phone music.

Larger data (uploads) will go over WiFi later. Bluetooth carries the status and the control.

## Code

| File | |
|---|---|
| `HeadUnitLink.kt` | RFCOMM service, JSON lines, requests and queries |
| `HeadUnitPlayer.kt` | Media3 player without sound. Its state is OpenHU's `media.current`, its buttons become `music.command`. It fetches the state again 1.2 s after the last tap, because Android Auto shows its own guess first. |
| `CompanionService.kt` | `MediaLibraryService`: tabs and lists from `music.query`. No notification, no foreground service. |
| `Library.kt`, `Protocol.kt` | Media ids, query mapping, messages (pure, unit tested) |
| `VoiceSearch.kt` | "Play … on OpenHU": the best matching song, or continue on an empty request |
| `MainActivity.kt` | Status of the link, the Bluetooth permission and the extension apps |
| `ExtensionService.kt`, `ExtensionAccess.kt`, `Extensions.kt` | The extension service, the user's answers per app, the scope rules (pure, unit tested) |

Texts in English and German (`res/values`, `res/values-de`).

## Extension apps

A plugin can have its own entry in Android Auto: a small extension app (openhu-companion#3). Extensions talk to OpenHU only through the companion, which holds the Bluetooth link.

- **Screen:** sideloaded apps get an entry in Android Auto only as media apps, so an extension's screen is a media tree. Android Auto shows the root's children as tabs; inside, items with a title and a subtitle are values, and a tap on any item that is not playable asks for its children (an action, its result as a page). Never offer playable items: Android Auto then expects playback and the extension would take the now playing card. An extension may also ship a Play build with Car App Library templates (like Home Assistant); the protocol is the same.
- **Connection:** bind `org.openhu.companion.EXTENSION` (package `org.openhu.companion`, no permission) and exchange `Message`s (`what` 1) with a JSON string under `json` in the `Bundle`, replies to `replyTo`. Use `extensions/client` (`OpenHUExtension.kt`, one file without dependencies, may be copied).
- **Messages:** extension → companion `hello` {plugin}, `subscribe` {topics}, `request` {id, method: `bus.get`, params: {topic}}, `emit` {topic, data}; companion → extension `state` {connected, access, name}, `event` {topic, data}, `response` {id, result | error}, `error` {error}.
- **Scope:** OpenHU publishes `companion.extensions` (from the plugins' manifests: plugin, package, name, topics, commands). An app gets only the topics and commands of the plugin that names its package, and only after the user allowed it once (notification, or the companion's screen; bound to the app's signing certificate). `access`: `allowed`, `pending`, `denied`, `notDeclared`.
- **Installing** extensions is up to the user (sideload, a store); the companion only lists which plugins want an app and whether it is installed.

The official extensions are modules under `extensions/` (`vehicle`: the vehicle plugin's values).

## Build

In Docker (image `openhu-companion:dev`, `docker/Dockerfile`):

```
docker/dev.sh build     # debug APK, unit tests, lint
docker/dev.sh install   # build and install the debug APK on the phone (adb on the host)
docker/dev.sh bundle    # release bundle for Play: app/build/outputs/bundle/release/app-release.aab
docker/dev.sh gradle :extensions:vehicle:assembleDebug   # an extension (extensions/vehicle/build/outputs/apk/debug/)
```

CI builds the debug APK and an unsigned release bundle for every push.

## Google Play

The app is distributed through Google Play, because Android Auto accepts more than media apps only from Play.
Without Play, the app can only be sideloaded with "Unknown sources" in Android Auto's developer settings.

One-time setup:

1. **Upload key** (keep it safe and out of the repo; Play App Signing holds the real app key):
   ```
   keytool -genkeypair -v -keystore ~/openhu-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then create `keystore.properties` in the checkout (ignored by git):
   ```
   storeFile=/home/<you>/openhu-upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```
   `docker/dev.sh bundle` mounts only the checkout, so put the keystore inside it (`*.jks` is ignored) or adapt the path.
2. **Play Console → Create app:** name "OpenHU", app, free.
3. **App content:**
   - Privacy policy URL: https://github.com/average-web-dev/openhu-companion/blob/main/PRIVACY.md
   - Data safety: no data collected or shared.
   - Ads: none.
   - Target audience: 18+.
   - Content rating questionnaire.
4. **Advanced settings → Form factors → Android Auto:** add it and accept the Android Auto addendum.
5. **Testing → Internal testing:** create a release and upload the signed `app-release.aab`. The first upload has to
   be done by hand. Add testers by email, then open the opt-in link on the phone and install from Play.
6. Every new upload needs a higher `versionCode` (`app/build.gradle.kts`).

Internal testing has no Android Auto review. Closed testing has a review that doesn't block the release. Production
needs the full review against the car app quality guidelines. New personal developer accounts must also run a closed
test (12 testers, 14 days) before production.

## License

GPL-3.0-or-later, like OpenHU.
