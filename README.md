# Канта · Kanta

**Is your container full? Show the whole city, and watch how long it takes.**

Kanta is a native Android app for **Skopje, North Macedonia**. People use it to report problems with the city's public trash containers and small street cans:

- **Full**: a container that hasn't been emptied
- **Damaged, destroyed, burning or missing**
- **A container should be here**: suggestions that neighbours can vote on

Every report is tied to a specific container on a public map. The app shows how long problems stay unresolved, sends people to the nearest container that still has space, and ranks Skopje's ten municipalities by how quickly problems get fixed. It is available in Macedonian, Albanian and English.

The full product and technical spec is [`KANTA_SPEC.md`](KANTA_SPEC.md). It is the single source of truth for this project.

---

## Status

Work in progress. The app is built in numbered steps that follow the spec.

| Done | |
|---|---|
| Design system | Colours, type (Nunito), shapes, motion, custom map markers; light and dark |
| Backend | Supabase: Postgres + PostGIS, row-level security, every rule enforced in SQL |
| Map | MapLibre + OpenFreeMap vector tiles, markers drawn from GeoJSON layers, clustering, offline cache |
| Bottom sheet | A persistent sheet with three heights. It is the app's only menu |
| Sign-in | Email with a 6-digit code (no password). The session is stored encrypted |
| Report flow | Camera first. Faces are blurred and EXIF removed on the phone. Reports queue offline |
| Map your street | Neighbours check that the containers near them are on the map, add missing ones and confirm new ones |
| Admin | Review queue, unverified containers, coverage map |

| Still to come |
|---|
| Nearest containers with space (§5.2) · Suggest flow (§4.4) · My reports, impact and city stats · Settings and onboarding · Notifications · Release build |

---

## How it works

```
Android app (Kotlin, Jetpack Compose)
   │  map tiles ─────────────► OpenFreeMap (free, no key)
   │  data, auth, photos ────► Supabase
   │                              ├─ Postgres + PostGIS   containers, reports, suggestions
   │                              ├─ RPC functions        every write, every rule
   │                              ├─ Row-level security   nothing is written to a table directly
   │                              ├─ Storage              photos (blurred, ≤ 1 MB)
   │                              └─ pg_cron              hourly report expiry
   └─ Room cache + WorkManager ── works without a connection, sends later
```

**The server decides.** Every limit lives in SQL, so a modified app cannot get around it. That includes the 60 m report radius, 10 reports a day, 2 added containers per person, the duplicate check and the 50 m confirmation radius. The app only shows the result and explains any refusal.

**Privacy.** Before a photo leaves the phone, faces are found with on-device ML Kit and blurred, and the image is re-encoded to strip EXIF data. If face detection fails, the photo is refused rather than sent. Public views never show who reported something; they say "a neighbour".

### Stack

Kotlin · Jetpack Compose · Material 3 · Hilt · Coroutines/Flow · Navigation Compose · MapLibre Native · supabase-kt · Room · DataStore · WorkManager · CameraX · ML Kit face detection · Coil

Android 8.0+ (minSdk 26), targetSdk 37.

---

## Getting started

### 1. Backend

Create a free [Supabase](https://supabase.com) project and follow **[`supabase/README.md`](supabase/README.md)**. It covers running the SQL files in order, loading the containers and turning on email codes.

### 2. App configuration

```bash
cp local.properties.example local.properties
```

Fill in your project's **URL** and **publishable (anon) key** from *Project Settings → API*.

`local.properties` is git-ignored. Never put the `service_role` / secret key in the app: it bypasses every security rule.

### 3. Build and run

```bash
./gradlew :app:installDebug
```

Or open the project in Android Studio and press Run. Without Supabase credentials the app still starts, and the map shows "No backend configured".

A debug build also installs **Kanta Design**, a gallery of every colour, component and marker.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

These are app unit tests: status and distance rules, error mapping, the report flow and the sign-in flow.

```bash
python3 supabase/tests/map_your_street_test.py
```

This is an end-to-end test of the server rules. It runs as real test users inside one transaction that is always rolled back, so it leaves nothing behind. It needs `pip install "psycopg[binary]"` and the database connection string in `supabase/.db-url` (git-ignored).

---

## Project layout

```
app/src/main/java/mk/kanta/app/
  core/
    auth/           sign-in, encrypted session, "sign in, then continue what you started"
    data/           Supabase repository, DTOs, error mapping, Room cache, report upload queue
    designsystem/   colours, type, components, marker drawing
    image/          photo privacy: face blur, EXIF strip, resize
    location/       GPS and distance maths
  feature/
    map/            the main screen: map, layers, bottom sheet, container detail
    report/         camera → compose → send, add container, pin picker
    street/         Map your street (area checks)
    admin/          review queue, unverified list, coverage
    auth/ profile/  sign-in sheet, profile
supabase/
  migrations/       0001–0015, run in order
  seed/             municipalities + containers imported from OpenStreetMap
  tests/            server rule tests
tools/
  import_osm/       fetches Skopje's containers and municipality borders from OpenStreetMap
  map_style/        builds the light/dark map styles; see MAPUTNIK.md to restyle the map
```

---

## Data and credits

- Container locations and municipality borders: **© OpenStreetMap contributors**, licensed under the [ODbL](https://opendatacommons.org/licenses/odbl/). Map tiles: **[OpenFreeMap](https://openfreemap.org)**. The attribution is always shown on the map.
- The app font is **Nunito**, licensed under the SIL Open Font License (see `app/src/main/assets/fonts/`).
