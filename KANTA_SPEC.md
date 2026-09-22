# KANTA — Product & Technical Spec

> This file is the single source of truth for the Kanta app. Keep it in the project root.
> AI agent: read this whole file before every task. If a prompt conflicts with this file, ask before deviating.

---

## 1. What Kanta is

Kanta is a native Android app for **Skopje, North Macedonia** where citizens report problems with **official trash containers and small trash cans**:

- containers that are **full / not collected**
- containers that are **damaged, destroyed, burning or missing**
- places where **a new container should be placed** (suggestions + votes)

Every report is tied to a specific container on a public map. The app shows how long problems stay unresolved, sends people to the **nearest container that is not full**, and ranks Skopje's municipalities by how fast problems get fixed.

**One-line pitch:** "Is your container full? Show the whole city, and watch how long it takes."

**Not in scope:** general litter / wild dumps / cleanup events (another app, Chisto.mk, covers those). Donations and physical repairs come much later.

---

## 2. Platform & stack (fixed decisions)

| Area | Choice |
|---|---|
| Platform | Native Android only (minSdk 26, targetSdk latest stable) |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVVM + unidirectional data flow, Hilt DI, Coroutines + Flow, Repository pattern, single-activity + Navigation Compose |
| Map | **MapLibre Native Android** + **OpenFreeMap** vector tiles (no account, no API key). Style URL base: `https://tiles.openfreemap.org/styles/positron`. Custom style JSON lives in `app/src/main/assets/map/kanta_light.json` and `kanta_dark.json` so the look can be edited in one place (tools: Maputnik editor). Wrap the MapView in Compose via `AndroidView` (or the `maplibre-compose` library if stable). |
| Backend | **Supabase** (Postgres + PostGIS, Auth, Storage, RPC functions). Kotlin client: `supabase-kt` (postgrest, auth, storage, functions). |
| Auth | **Login required to report, suggest or vote.** Map, container details and city stats are viewable without login. Method: Supabase email one-time code (6-digit OTP). No Google/Firebase account needed. |
| Location | Fused Location Provider (play-services-location) — no API key |
| Camera | CameraX, in-app camera only (no gallery picks for reports) |
| Images | Compress on device to max 1600px long edge, JPEG ~80%, target ≤ 300 KB; strip ALL EXIF. On-device face blur with ML Kit Face Detection (bundled model). |
| Image loading | Coil 3 |
| Local storage | Room (cache + offline upload queue), DataStore (settings) |
| Background | WorkManager (upload queue retry, periodic "Fixed!" check) |
| Notifications | v1: local notifications from a periodic WorkManager sync (no Firebase). FCM later. |
| Languages | Macedonian (default), Albanian, English — all strings in `strings.xml` per locale, per-app language picker (Android 13+ API + fallback) |
| Theme | Light + dark, follows system, manual override in settings |

---

## 3. Design system — "Premium minimal"

**Feeling:** calm, premium, lots of breathing room, Apple-like restraint. The map is the hero. UI chrome is quiet, soft and rounded; colour is spent almost only on container markers and one primary action.

### 3.1 Colour tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` | `#1F5A3A` | `#7CC79A` | Primary buttons, active states, logo |
| `accent` | `#E0B41C` | `#F0C94A` | Tiny highlights only (badges, impact counter) |
| `background` | `#F3F5F2` | `#0F1411` | App background |
| `surface` | `#FFFFFF` | `#171D19` | Sheets, cards |
| `surfaceMuted` | `#EAEEE9` | `#1F2622` | Chips, input fields |
| `onSurface` | `#141A16` | `#E7ECE8` | Primary text |
| `onSurfaceMuted` | `#5E6A62` | `#98A49C` | Secondary text |
| `outline` | `#D8DED7` | `#2B342E` | Hairlines (1dp) |

**Marker colours (the only loud colours in the app):**

| Meaning | Colour |
|---|---|
| Big container — OK | `#2E7D4F` green |
| Small can — OK | `#E0B41C` yellow |
| FULL (any type) | `#E8772E` orange |
| BROKEN / damaged / burning | `#C0392B` red |
| DESTROYED | `#8A918C` grey (filled) |
| MISSING | `#8A918C` grey outline only (hollow, dashed) |
| Suggestion ("container should be here") | `#1F5A3A` brand-green hollow circle with "+" |

Orange (full) must stay clearly different from yellow (small can OK): full markers also get a 2dp white border (dark theme: `#0F1411` border) and render 15% larger.

### 3.2 Typography

- Font: **Nunito** (Google Fonts, has Cyrillic + Latin Extended for Albanian ë/ç). Bundle as downloadable or resource fonts, weights 400/600/700/800.
- Scale (sp): Display 34/800 · Title 22/700 · Headline 18/700 · Body 16/400 · Label 14/600 · Caption 12/600 (+0.4 letter-spacing, used for small uppercase labels)
- Numbers in stats use tabular figures where available.

### 3.3 Shape, spacing, elevation, motion

- Corner radii: 12dp (chips/inputs), 20dp (cards), 28dp (bottom sheet top corners, big buttons = full pill).
- Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 / 48 dp. Screen side padding 20dp.
- Elevation: almost none. Separate things with background tone and 1dp hairlines. Only the bottom sheet and the primary floating button get a soft shadow (y 8, blur 24, 8% black).
- Motion: 200–300ms, `FastOutSlowIn` / spring (dampingRatio 0.85). Markers fade/scale in. Success = small check animation + light haptic. Respect "remove animations" system setting.
- Icons: rounded outline icon set (Material Symbols Rounded), 24dp, 1.75 weight feel.
- Haptics: light tick on selection, confirm pattern on successful send.

### 3.4 Map markers (custom drawn, not default pins)

- **Big container** (`amenity=waste_disposal`, underground or above-ground municipal containers, Pakomak recycling containers): **small rounded rectangle**, 14×10dp at zoom 16, scales with zoom.
- **Small can** (`amenity=waste_basket`, street bins): **small rounded triangle**, 10dp at zoom 16.
- Fill colour = status colour from 3.1. OK big = green, OK small = yellow.
- Recycling containers optionally show a 3dp inner dot in their material colour (glass green, paper blue, plastic yellow) — only at zoom ≥ 17.
- Clustering below zoom 14: soft circle with count; cluster colour = worst status inside (red > orange > green).
- Selected marker: scales 1.4× with a soft halo in brand colour.
- A report < 1h old: one-time gentle pulse ring.
- Implement markers as MapLibre symbol/circle layers from a GeoJSON source (NOT hundreds of Android views) so it stays fast with 10,000+ points. Generate marker bitmaps once and register them as style images.

---

## 4. App structure & screens

### 4.1 Main screen = the map
- Full-screen map, centred on the user (fallback: Skopje centre 41.9965, 21.4314, zoom 14).
- Minimal overlay: top-left small "Kanta" wordmark; top-right round profile/avatar button; right side: "my location" button.
- **Bottom sheet** (the only menu), always present:

**Collapsed state (peek ~140dp):** a drag handle and a row of **three big action choices**:
1. **Full** — "This container is full" (fastest path, orange icon)
2. **Report** — damaged / destroyed / burning / missing / trash dumped around it (red icon)
3. **Suggest** — "A container should be here" (green + icon)

**Half / expanded state** adds, below the three actions:
- **Near you** — "Nearest container that's not full: 80 m" shortcut + short list
- **My reports & profile** — your reports, impact counter, settings
- **City stats** — municipality ranking, response times
- **Suggestions** — top suggested places, vote

Tapping a container marker opens a **container detail sheet** (replaces the menu temporarily): type, ID (e.g. `SK-00412`), municipality, current status + how long, photo timeline of reports, buttons: "Me too, still full" / "It's been emptied" / "Report other problem" / "Navigate".

### 4.2 Auth
- Browsing is free. First time a user taps Full / Report / Suggest / vote → login sheet: email → 6-digit code → pick display name (+ optional municipality). Then continue the action they started (never lose their intent).
- Profile: display name, municipality, language, theme, notification toggles, sign out, delete account (required by Play Store).

### 4.3 Report flow — camera first, 3 taps
1. Tap **Full** or **Report** → in-app camera opens instantly (CameraX, big shutter, torch toggle).
2. After shot: sheet with photo thumbnail, auto-detected nearest container ("Container SK-00412 · 12 m", tap to change on a mini map, or "Container not on map — add it"), and for **Report** the status chips: Damaged · Destroyed · Burning · Missing · Trash dumped around. For **Full** the status is already set.
3. Optional note (max 280 chars) → **Send**. Success animation, then:
   - for **Full**: immediately show **"Nearest containers with space"** (see 5.2).
   - for others: "Thanks — X neighbours reported this too."

Rules: GPS must be within 60 m of the container (else show friendly error + "Move closer" or "Add a new container here"). If an open report of the same kind already exists on that container → becomes a "me too" confirmation automatically (tell the user). Offline → queue in Room, upload with WorkManager, show "Will send when online".

### 4.4 Suggest flow
Tap **Suggest** → map in pick mode with a centre crosshair → confirm spot → reason chips: No container nearby · Existing ones always full · New building/neighbourhood · People dump trash here → optional photo + note → Send. If an open suggestion exists within 50 m, offer "Vote for this one instead" (one tap).

### 4.5 Screens list
1. Splash (brand mark, < 1 s)
2. Onboarding (3 calm screens: Report → City sees it → Track the fix; location permission; notification permission)
3. Map (main) + bottom sheet menu
4. Container detail sheet
5. Camera
6. Report compose sheet
7. Nearest-alternatives sheet
8. Suggest pick-on-map + compose
9. Suggestions list (sorted by votes, filter by municipality)
10. Login (email, code, name)
11. My reports & profile (impact counter, list with statuses)
12. City stats (ranking + detail per municipality)
13. Settings (language, theme, notifications, privacy policy link, delete account)
14. Empty / error / offline states for every list and the map

---

## 5. Core logic

### 5.1 Container status
Derived by the backend from open reports (never set by the client directly):
- `ok` — no open reports
- `full` — open `full` report with ≥ 2 distinct confirmations (reporter + 1 me-too) within 6h, OR 1 report by a trusted user (trust_score ≥ 5)
- `broken` — open `damaged` or `burning` report
- `destroyed` — open `destroyed` report confirmed by ≥ 2 users
- `missing` — open `missing` report confirmed by ≥ 2 users
Priority if several: destroyed > missing > broken > full > ok.

A single unconfirmed `full` report shows on the container detail as "1 person says it's full" but the marker only turns orange at threshold (keeps the map honest).

Resolution: a `resolved` confirmation with photo from any user, or 2 without photo → report `resolved_at = now()`. Auto-expire: `full` reports with no confirmation for 24h close as `expired`; other kinds after 14 days.

### 5.2 Full → nearest alternatives
When a container is/gets `full`, show the 5–10 nearest containers of the **same category** (general waste vs each recycling material; small cans may substitute for nothing — only suggest big containers for bags) whose status is not full/destroyed/missing, sorted by distance, max 600 m, highlight those ≤ 300 m. Each row: shape icon, status, distance in metres, "Navigate" (opens external maps app via geo: intent — no API needed). If none: honest empty state "No free container within 600 m — your report helps the city see this area needs more."

Also: "Where can I throw this?" shortcut in the sheet's Near you section = same list from user's current location.

### 5.3 Suggestions
One vote per user per suggestion. Merge radius 50 m. Status: `open` → `sent` (included in a monthly report) → `placed` (a real container was added nearby; link it) or `rejected`. When `placed`, all voters get a "Fixed!"-style notification.

### 5.4 Motivation (no points spam — calm & meaningful)
- **Impact counter** on profile: "Your reports helped get **12** containers emptied · **3** repaired · **1** new container placed." Only counts reports that actually got resolved.
- **Neighbourhood ranking** in City stats: the 10 municipalities of Skopje (Centar, Karpoš, Aerodrom, Gazi Baba, Kisela Voda, Čair, Butel, Gjorče Petrov, Saraj, Šuto Orizari) ranked by median hours-to-resolve (last 30 days), plus open reports count and active reporters. Show trend arrows vs previous 30 days. Neutral wording — data, not blame.
- **"Fixed!" notifications**: when a report you made or confirmed is resolved → notification "The container on [street] you reported was emptied after 31 h. Thank you." with before/after photos in the app.

---

## 6. Data model (Supabase / Postgres + PostGIS)

```
municipalities(id smallint pk, name_mk text, name_sq text, name_en text, geom geography(multipolygon))
containers(id uuid pk, code text unique  -- e.g. SK-00412,
  kind text check in ('big','small'),
  category text check in ('general','glass','paper','plastic','mixed_recycling'),
  geom geography(point) not null, municipality_id smallint fk,
  status text default 'ok', status_since timestamptz,
  source text check in ('osm','user','city'), osm_id bigint null,
  added_by uuid null, verified bool default false, created_at timestamptz)
reports(id uuid pk, container_id uuid fk, user_id uuid fk,
  kind text check in ('full','damaged','destroyed','burning','missing','dumped_around'),
  photo_path text not null, note text check (char_length(note) <= 280),
  state text check in ('open','resolved','expired') default 'open',
  created_at timestamptz, resolved_at timestamptz null)
report_confirmations(id uuid pk, report_id uuid fk, user_id uuid fk,
  kind text check in ('me_too','resolved'), photo_path text null, created_at timestamptz,
  unique(report_id, user_id, kind))
suggestions(id uuid pk, user_id uuid fk, geom geography(point), reason text, note text, photo_path text null,
  votes int default 1, state text check in ('open','sent','placed','rejected') default 'open',
  placed_container_id uuid null, municipality_id smallint, created_at timestamptz)
suggestion_votes(suggestion_id uuid fk, user_id uuid fk, created_at timestamptz, primary key(suggestion_id, user_id))
profiles(id uuid pk references auth.users, display_name text, municipality_id smallint null,
  lang text, trust_score int default 0, created_at timestamptz)
```

Indexes: GIST on every `geom`; btree on `reports(container_id, state)`, `reports(user_id)`.

RPC functions (SQL, `security definer` where needed):
- `containers_in_bbox(min_lon, min_lat, max_lon, max_lat)` → lightweight rows for the map (id, kind, category, status, lon, lat)
- `nearest_containers(lon, lat, category, limit, max_m)` → uses `<->` KNN + `ST_DWithin`
- `nearest_container_to(lon, lat)` → for snapping a new report
- `submit_report(container_id, kind, photo_path, note, lon, lat)` → validates distance ≤ 60 m, rate limit (≤ 10 reports/user/day), dedupe into me-too, recomputes status
- `confirm_report(report_id, kind, photo_path)`
- `submit_suggestion(lon, lat, reason, note, photo_path)` → merges within 50 m
- `vote_suggestion(suggestion_id)`
- `my_impact()` → counts for impact counter
- `municipality_stats(days int default 30)` → ranking rows
- `my_resolved_since(ts)` → for the Fixed! notification worker
- Triggers keep `containers.status` / `status_since` and `suggestions.votes` correct. A scheduled function (pg_cron) expires old reports every hour.

Row Level Security: everyone (anon) can SELECT containers, open/resolved reports (without user_id exposed — use a view), suggestions, municipalities. Only authenticated users can INSERT via RPCs; users can UPDATE/DELETE only their own profile. No direct client INSERT on reports/confirmations (RPC only).

Storage: bucket `photos` (public read, authenticated write, path `reports/{user_id}/{uuid}.jpg`, max 1 MB, image/jpeg only).

---

## 7. Seed data

Import Skopje's existing containers from OpenStreetMap via Overpass API (free, no account):

```
[out:json][timeout:120];
area["name:en"="Skopje"]["boundary"="administrative"]->.a;
(
  node["amenity"="waste_disposal"](area.a);
  node["amenity"="waste_basket"](area.a);
  node["amenity"="recycling"]["recycling_type"="container"](area.a);
);
out body;
```
Map: waste_disposal → kind big / general; recycling → kind big / category from `recycling:glass|paper|plastic` tags; waste_basket → kind small / general. Keep `osm_id`, `source='osm'`. Municipality boundaries: OSM `admin_level=8` relations inside Skopje (import as GeoJSON). Assign `municipality_id` with `ST_Contains`. Generate codes `SK-00001…` in import order.

Attribution: show "© OpenStreetMap contributors · OpenFreeMap" small on the map (required).

---

## 8. Quality bar ("first class")

- Cold start to interactive map < 2 s on a mid-range phone; map pans at 60 fps with 10k markers.
- Every screen has designed loading (skeletons, not spinners), empty and error states with a clear next action.
- All touch targets ≥ 48dp. TalkBack labels on every icon button and marker ("Big container, full for 31 hours"). Contrast AA in both themes.
- No hard-coded strings; all three languages complete.
- Privacy: EXIF stripped, faces blurred, no reporter identity shown publicly (only display name on own profile; public views show "a neighbour").
- Delete account removes profile and anonymises reports.
- Unit tests for status logic, distance rules, ViewModels; UI tests for report flow.
- Release build with R8, signed AAB, Play Store listing assets, privacy policy page.

---

## 9. Agent rules

1. Read this spec before every task. Work in small, compiling steps; run the build after each step and fix errors before continuing.
2. Never put secrets in code. Supabase URL and anon key go in `local.properties` → `BuildConfig`.
3. Use only libraries named here unless you explain why another is needed.
4. Follow the design tokens exactly (colours, radii, spacing, font). No default Material purple anywhere.
5. At the end of each task: list what you built, what's left, and how I can test it on my phone.
