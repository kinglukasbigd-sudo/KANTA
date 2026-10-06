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

Kanta green mixed with a warm editorial base: ivory paper and green-tinted carbon ink, oat hairlines, tiger gold and ember for the loud moments, highlighter mint as the dark theme's green. Every text pairing is at least WCAG AA (4.5:1).

| Token | Light | Dark | Use |
|---|---|---|---|
| `brand` | `#1F5A3A` forest | `#E2FFCC` highlighter mint | Primary buttons, active states, logo |
| `brandContainer` | `#E2FFCC` mint | `#26331F` | Selected chips and Small/Big tiles |
| `accent` | `#FAAE33` tiger gold | `#FAAE33` | Tiny highlights only (badges, impact counter) |
| `background` | `#FAF9F5` ivory | `#161B13` carbon ink | App background |
| `surface` | `#FFFFFF` | `#1E241B` | Sheets, cards |
| `surfaceMuted` | `#F0EEE6` | `#2D3329` forest charcoal | Chips, input fields, status pills |
| `onSurface` | `#161B13` | `#F0EEE6` | Primary text |
| `onSurfaceMuted` | `#655A4D` driftwood | `#A3AD9E` sage | Secondary text |
| `outline` | `#E3DACC` oat | `#343B30` | Hairlines (1dp) |
| `outlineStrong` | `#B0AEA5` stone | `#4F5849` | Borders of tappable things: outlined buttons, tiles |
| `error` | `#C21F52` | `#F0628F` | Error text and icons |

**Marker colours (the only loud colours in the app):**

| Meaning | Colour |
|---|---|
| Size not known yet — OK | `#2D3329` forest charcoal, mint `#E2FFCC` glyph |
| Big container — OK | `#2E7D4F` green |
| Small can — OK | `#FAAE33` tiger gold |
| FULL (any type) | `#DC5000` ember |
| BROKEN / damaged / burning | `#D1255C` chili |
| DESTROYED | `#87867F` stone grey (filled) |
| MISSING | `#87867F` grey outline only (hollow, dashed) |
| Suggestion ("container should be here") | `#1F5A3A` brand-green hollow circle with "+" |

Every marker sits on a ring: white on light tiles, ivory `#F0EEE6` on dark ones (that is what keeps the charcoal universal marker visible at night), with a soft ink hairline outside it on light tiles so gold and grey hold their edge. Full markers get a 2dp ring and render 15% larger. The glyph is ink on gold and grey, white on the darker fills.

### 3.2 Typography

- Fonts (Google Fonts, both cover Macedonian Cyrillic incl. Ѓ Ќ Ѕ Џ and Albanian ë/ç), bundled as variable resource fonts:
  - **Manrope** for everything people read — headings set tight and heavy, body open.
  - **JetBrains Mono** for the field-guide chrome: section captions, container codes, distances, timestamps, counts.
- Scale (sp): Display 34/800 (−0.8 tracking) · Title 22/800 (−0.4) · Headline 18/700 (−0.2) · Body 16/400 · Label 15/600 · Caption mono 11–12/500 (+0.4–0.8 tracking, small uppercase section labels)
- Numbers in stats use tabular figures (Manrope `tnum`; the mono face is tabular by nature).

### 3.3 Shape, spacing, elevation, motion

- Corner radii: 8dp (chips/inputs), 12dp (buttons, Small/Big tiles), 16dp (cards), 24dp (bottom sheet top corners). Status pills and compact actions are rounded; buttons are rounded rectangles, 52dp tall. Outlined buttons and tiles use `outlineStrong`; selected chips and tiles are `brandContainer` with a 1.5dp `brand` border.
- Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 / 48 dp. Screen side padding 20dp.
- Elevation: almost none. Pages and the bottom sheet are ivory (`background`); content sits on white cards (`surface`) with a 1dp oat hairline, and lists are **grouped inside one card** (rows inset, hairlines between them) instead of running edge to edge. Only things floating over the map (the sheet, the map controls, banners) get the soft shadow (y 8, blur 24, 8% black).
- Page structure: every full screen opens with a top bar (square 44dp outlined back button, actions on the right) and a page title — a mono uppercase caption in `brand` over a Display title, optional muted subtitle. Section captions are mono uppercase in `onSurfaceMuted`. Icons in rows and menus sit on tinted rounded-square tiles. Messages (offline, errors, thanks) are banners: a card with a tinted icon tile and ink text.
- Motion: 200–300ms, `FastOutSlowIn` / spring (dampingRatio 0.85). Markers fade/scale in. Success = small check animation + light haptic. Respect "remove animations" system setting.
- Icons: rounded outline icon set (Material Symbols Rounded), 24dp, 1.75 weight feel.
- Haptics: light tick on selection, confirm pattern on successful send.

### 3.4 Map markers (custom drawn, not default pins)

Shape tells size at a glance, even where the glyph inside is too small to read; colour stays free to tell status. The same three marks are drawn in lists, sheets and the Small/Big tiles (one painter for map and app).
- **Size not known yet** (every bin starts here, §4.6 "Bin size"): **round badge, 14dp**, with the universal bin pictogram (handle, lid, ribbed body).
- **Small can** (street bins): **tall rounded tile, 11×15dp**, with a slim street can.
- **Big container** (municipal 1,100 L containers, Pakomak recycling containers): **wide rounded tile, 18×13dp**, with a wheeled container.
- Sizes are at zoom 16 and scale with zoom. Fill colour = status colour from 3.1. OK unknown = charcoal, OK big = green, OK small = gold.
- Recycling containers that are not small cans show a small ringed dot on their bottom-left corner in their material colour (glass green, paper blue, plastic gold) — only at zoom ≥ 17.
- **No numbers on the map — no clustering, at any zoom.** Every container is always drawn; its size follows the zoom:
  - zoom < 14: a tiny **4dp dot** in its status colour (charcoal = size unknown, green = big OK, gold = small OK, ember = full, chili = broken, grey = destroyed/missing). A hairline ring in the marker ring colour, no shape.
  - zoom 14–15.5: the dot grows slightly, to **6dp**.
  - zoom ≥ 15.5: the real marks — round for size unknown, tall for small cans, wide for big containers — scaling up smoothly as you zoom in.
  - Dots and shapes **cross-fade** (≈ 15.2–15.8); nothing pops. The shape variants (badge at 16, recycling dot at 17) cross-fade the same way.
- **Draw order:** problem markers (red, orange) always draw on top of OK ones; the selected marker on top of all.
- Markers never cover the chrome: the Kanta label, profile button and map buttons always sit above the map.
- **Calm basemap.** Warm ivory ground with sage parks in light, carbon ink with dark forest in dark. Roads are three quiet tones, never white or bright in dark mode — dark: minor `#232A1F`, main `#2B3326`, motorway/trunk `#323B2C` on the `#161B13` ground; light: minor `#FFFFFF` on the `#F2EFE7` ground, main `#E7E2D6`, motorway/trunk `#DDD6C8`. Casings take the road's own colour; motorway/trunk are only slightly wider than main roads. **No road shields, highway refs or any icon behind text.** Every street and place name is plain text with no box: dark `#84907F` (sage) with a 1px `#161B13` halo, light `#6C5F51` (driftwood) with a `#F2EFE7` halo; neighbourhood names stay small uppercase. `tools/map_style/build_styles.py` enforces this and refuses to write a style with an off-palette colour, an icon, or (dark) anything brighter than the label text.
- Selected marker: scales 1.4× with a soft halo in brand colour.
- A report < 1h old: one-time gentle pulse ring.
- **Verified vs unverified (4.6).** Governing principle: **filled = it exists, hollow = it's gone.** The two dashed
  treatments on the map must never be confusable:
  - **MISSING** — hollow shape, **no fill**, dashed grey (`#87867F`) outline, the glyph faint inside. The container is gone.
  - **UNVERIFIED** — normal **filled** shape in its normal type/status colour at **85% opacity**, its ring **dashed**
    instead of solid, and a tiny **"?" badge** (ink on the ring colour) in the top-right corner. The badge is
    drawn only at **zoom ≥ 16**, so at city zoom an unverified container still reads as an ordinary container.
  An unverified container can hold any status, so UNVERIFIED composes with the status colours above rather than
  replacing them; a user-added container reported missing is hollow like any other missing one.
- Implement markers as MapLibre symbol/circle layers from a GeoJSON source (NOT hundreds of Android views) so it stays fast with 10,000+ points. Generate marker bitmaps once and register them as style images.

---

## 4. App structure & screens

### 4.1 Main screen = the map
- Full-screen map, centred on the user (fallback: Skopje centre 41.9965, 21.4314, zoom 14).
- Minimal overlay: top-left "Kanta" wordmark on a floating chip with a brand dot; top-right square profile button; map buttons bottom-right above the sheet (below). All three are white, 12dp corners, with the soft shadow.
- **Bottom sheet** (the only menu), always present:

**Three snap states**, smooth spring between them (§3.3), no jumping:
- **Collapsed** — exactly the drag handle and the Full / Report / Suggest row, above the navigation bar.
- **Half** — halfway between collapsed and expanded.
- **Expanded** — the sheet's top stops **below** the Kanta label and profile button (top of sheet = status bar + top overlay height + 8dp), and the sheet reaches the very bottom of the screen with no gap, drawn behind the navigation bar with correct insets. At every position the sheet's bottom edge is at or below the screen's, so the map never shows underneath it. Content taller than the sheet scrolls inside it.

**Map buttons** (suggestions toggle, my location): bottom right, one grouped vertical control bar (48dp buttons, hairlines between them; active ones tinted brand), 16dp above the sheet's top edge and 16dp from the right edge. They move with the sheet while it is dragged and fade out as it rises past half. The map attribution rides the sheet's top edge on the left the same way.

**Rotation and tilt** (like Google Maps): a two-finger twist rotates the map freely; a two-finger drag up/down tilts it, at most 45°. Pinch-zoom and pan work exactly as before — rotation needs a deliberate twist (threshold ~20°, raised further while pinching) so zooming never turns the map by accident.
- **Compass button:** in the same stack, above my-location, shown only while the map is rotated or tilted. A small north arrow turns with the map. Tap → smoothly back to north and flat (300 ms); it fades out once the map faces north again. TalkBack: "Reset map to north".
- **My-location button, two modes**, its icon shows which is on: 1st tap centres on me; 2nd tap = **follow mode** — the map turns with the direction the phone is facing (compass heading) and the location dot shows a small heading cone. Any pan, or a tap on the compass, exits follow mode; a tap while following stops it and faces north.
- **Markers stay upright:** bin marks, dots and suggestion pins never rotate or lean with the map — they always face the screen. Street names follow their streets as MapLibre does by default.

**Collapsed state:** a drag handle and a row of **three big action choices**:
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
- **Never interrupt the user's intent.** The "Map your street" prompt (4.6) — or any other post-login prompt — only appears once the action that triggered the login has been completed or cancelled. It is never shown between login and that action.
- Profile: display name, municipality, language, theme, notification toggles, sign out, delete account (required by Play Store).

### 4.3 Report flow — camera first, 3 taps
1. Tap **Full** or **Report** → in-app camera opens instantly (CameraX, big shutter, torch toggle).
2. After shot: sheet with photo thumbnail, auto-detected nearest container ("Container SK-00412 · 12 m", tap to change on a mini map, or "Container not on map — add it" → opens the **Add container flow** from 4.6, including its 30 m GPS rule, duplicate check and the 2-container lifetime limit), and for **Report** the status chips: Damaged · Destroyed · Burning · Missing · Trash dumped around. For **Full** the status is already set.
3. Optional note (max 280 chars) → **Send**. Success animation, then:
   - for **Full**: immediately show **"Nearest containers with space"** (see 5.2).
   - for others: "Thanks — X neighbours reported this too."

Rules: GPS must be within 60 m of the container (else show friendly error + "Move closer" or "Add a new container here" → also the Add container flow from 4.6, subject to the same 2-container limit). If an open report of the same kind already exists on that container → becomes a "me too" confirmation automatically (tell the user). Offline → queue in Room, upload with WorkManager, show "Will send when online".

### 4.4 Suggest flow
Tap **Suggest** → map in pick mode with a centre crosshair → confirm spot → reason chips: No container nearby · Existing ones always full · New building/neighbourhood · People dump trash here → optional photo + note → Send. If an open suggestion exists within 50 m, offer "Vote for this one instead" (one tap).

### 4.6 "Map your street" — help complete the map (added later, part of v1)

**Goal:** every logged-in user helps check that the containers and cans near them are on the map. This spreads the work of mapping all of Skopje across many people instead of only the admin (Ivan).

**When it appears**
- Right after the user's first login (after choosing a display name) — but only once the action that triggered the login has been completed or cancelled, never in the middle of it (see 4.2, "never interrupt the user's intent"). If the user logged in to send a report, they finish the report first and see this afterwards. Again later only if the user hasn't done a check in 30 days AND is in an area with no recent check (see "area checks"). Never more than once per app session. Always skippable ("Later").
- Also permanently available in the bottom sheet under "My reports & profile" → "Map your street".

**The check flow**
1. Card/sheet: "Are all containers near you on the map?" with a small map of a **150 m radius** around the user, showing existing markers clearly, a legend of the three marks, and a short hint: "A circle means nobody has given the size yet, a tall tile is a small can, a wide tile is a big container."
2. Three answers:
   - **"Yes, everything is there"** → saves an area check (confirms coverage). Thank-you micro-animation.
   - **"One is missing"** → Add container flow (below).
   - **"One on the map is not here"** → user taps that marker → creates a `missing` report for it (normal report flow, photo required).
3. Bins inside the radius that still need someone — not yet confirmed, or size not known yet — are listed: unverified ones with a button **"Yes, it's here"** (one tap, within 50 m), and every one within 50 m with **Small can / Big container** (see "Bin size" below).

**Add container flow (the same flow is used everywhere a container is added, including "Container not on map — add it" in the report flow 4.3)**
- Photo required (camera only, same privacy processing), GPS must be within **30 m** of the pin, user drags the pin to the exact spot.
- Choose type with the same Small/Big tiles as on a bin's sheet; for big: category General / Glass / Paper / Plastic. The adder's choice is the bin's size from the start and counts as their answer.
- Duplicate check: if a container of the same kind already exists within **10 m** (big) or **5 m** (small) → "Is it this one?" with the existing marker; user must confirm it's different to continue.
- **Limit: each normal account can add at most 2 containers in total (lifetime).** Show remaining count before adding: "You can add 2 containers" / "You can add 1 more container". The limit is enforced on the server, never only in the app.
- When the limit is reached: explain kindly "You've already added your 2 containers — thank you! If another one is missing, send it for review." → the user can send a **container request** (photo + pin + type) that does NOT appear on the map; it goes into the admin review queue. Max 3 requests per user per day.
- New user-added containers start as `verified = false`: drawn with a **dashed outline** in the normal type/status colour. They become verified when **2 other users** tap "Yes, it's here", or an admin verifies them. Unverified containers can be reported like any other.
- If 2 users report an unverified container as `missing`, it is removed from the map (soft delete) and the adder's trust_score goes down by 1. When a user's added container gets verified, their trust_score goes up by 1.
- **Bin size.** No source can be trusted to say how big a bin is (Mapillary has one class for every trash can; OSM in Skopje barely tags it), so **every bin starts with its size unknown** and gets the universal marker (3.4). The person standing next to it says **Small can** or **Big container** — on the bin's detail sheet, where it is the first thing shown while the size is unknown, and in "Map your street":
  - the **first answer marks the bin right away**; after that the size is the **majority** of everyone's answers, a tie keeping what the map shows — so two people who disagree with the first one switch it;
  - one answer per person per bin; answering again replaces it;
  - answering needs the person within **50 m** (same as "Yes, it's here"); signed out, tapping signs in first and then sends the answer; on an unverified bin, answering also counts as "Yes, it's here";
  - an **admin's** answer sets the size at once, from anywhere;
  - the size each import claimed is kept (`source_kind`) but never shown as the size; imports keep arriving unknown.

**Admin (Ivan)**
- `profiles.role` = 'user' | 'admin'. Set admin manually in Supabase for Ivan's account.
- Admins have **no add limit**, their containers are verified immediately, and they see a hidden **Admin** row in the bottom sheet with: review queue of container requests (approve → becomes a verified container / reject), list of unverified containers (verify / delete), and a **coverage map** layer showing where area checks happened (green = checked in last 90 days, empty = never checked) so Ivan knows which parts of Skopje still need mapping. On any container's detail sheet an admin's Small/Big answer sets the size at once, from anywhere.

**Data model additions (section 6)**
```
profiles: + role text check in ('user','admin') default 'user'
          + containers_added int default 0
          + last_area_check_at timestamptz null
containers: + deleted_at timestamptz null   -- soft delete; all queries ignore deleted rows
container_confirmations(container_id uuid fk, user_id uuid fk, kind text check in ('exists'), created_at timestamptz,
  primary key(container_id, user_id))
container_requests(id uuid pk, user_id uuid fk, geom geography(point), kind text, category text, photo_path text not null,
  note text, state text check in ('pending','approved','rejected') default 'pending',
  created_container_id uuid null, created_at timestamptz, reviewed_at timestamptz null)
container_size_votes(container_id uuid fk, user_id uuid fk, kind text check in ('big','small'),
  created_at timestamptz, updated_at timestamptz, primary key(container_id, user_id))   -- 4.6 "Bin size"
area_checks(id uuid pk, user_id uuid fk, geom geography(point), radius_m int default 150,
  result text check in ('all_present','added','reported_missing'), created_at timestamptz)
```
**RPC additions:** `add_container(lon, lat, kind, category, photo_path, device_lon, device_lat)` (enforces 30 m, duplicate radius, the 2-container limit unless admin, updates containers_added), `my_add_allowance()` → remaining adds, `confirm_container_exists(container_id, lon, lat)` (50 m, not the adder, verifies at 2), `vote_container_size(container_id, lon, lat, kind)` (50 m; first answer sets an unknown bin, then majority; confirms an unverified bin), `submit_container_request(...)` (3/day), `submit_area_check(lon, lat, result)`, `should_prompt_area_check(lon, lat)` → bool, admin-only: `admin_review_request(id, approve bool)`, `admin_verify_container(id)`, `admin_delete_container(id)`, `admin_set_container_kind(id, kind)`, `admin_coverage(days int)` → GeoJSON of checks. RLS: container_requests readable only by their author and admins; admin RPCs check role = 'admin'.

**Map marker addition (section 3.4):** unverified containers keep their normal filled shape and type/status colour at 85% opacity, plus a 1.5dp dashed border and a small "?" badge at zoom ≥ 16. They must never be confused with MISSING, which is hollow. See 3.4 for the full rule and the governing principle (**filled = it exists, hollow = it's gone**).

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
15. Map your street (area check) + Add container flow
16. Admin: review queue, unverified list, coverage map (admins only)

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
  kind text check in ('big','small','unknown'),   -- 4.6 "Bin size": every bin starts 'unknown'
  source_kind text null check in ('big','small'), -- what an import claimed; kept, never shown
  category text check in ('general','glass','paper','plastic','mixed_recycling'),
  geom geography(point) not null, municipality_id smallint fk,
  status text default 'ok', status_since timestamptz,
  source text check in ('osm','user','city'), osm_id bigint null,
  added_by uuid null, verified bool default false, created_at timestamptz,
  deleted_at timestamptz null)   -- soft delete (4.6); every query and RPC ignores deleted rows
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
  lang text, trust_score int default 0, created_at timestamptz,
  role text check in ('user','admin') default 'user',        -- 4.6; set manually in Supabase for Ivan
  containers_added int default 0,                            -- 4.6; lifetime count, drives the 2-add limit
  last_area_check_at timestamptz null)                       -- 4.6; drives the 30-day re-prompt

-- 4.6 "Map your street"
container_confirmations(container_id uuid fk, user_id uuid fk, kind text check in ('exists'), created_at timestamptz,
  primary key(container_id, user_id))
container_requests(id uuid pk, user_id uuid fk, geom geography(point), kind text, category text, photo_path text not null,
  note text, state text check in ('pending','approved','rejected') default 'pending',
  created_container_id uuid null, created_at timestamptz, reviewed_at timestamptz null)
container_size_votes(container_id uuid fk, user_id uuid fk, kind text check in ('big','small'),
  created_at timestamptz, updated_at timestamptz, primary key(container_id, user_id))   -- 4.6 "Bin size"
area_checks(id uuid pk, user_id uuid fk, geom geography(point), radius_m int default 150,
  result text check in ('all_present','added','reported_missing'), created_at timestamptz)
```

Indexes: GIST on every `geom`; btree on `reports(container_id, state)`, `reports(user_id)`, `container_requests(state)`, `area_checks(created_at)`; partial index on `containers(deleted_at)` so the map query skips soft-deleted rows cheaply.

RPC functions (SQL, `security definer` where needed):
- `containers_in_bbox(min_lon, min_lat, max_lon, max_lat)` → lightweight rows for the map (id, kind, category, status, lon, lat, verified) — excludes rows with `deleted_at` not null; `verified` drives the dashed unverified marker (3.4)
- `nearest_containers(lon, lat, category, limit, max_m)` → uses `<->` KNN + `ST_DWithin`
- `nearest_container_to(lon, lat)` → for snapping a new report
- `submit_report(container_id, kind, photo_path, note, lon, lat)` → validates distance ≤ 60 m, rate limit (≤ 10 reports/user/day), dedupe into me-too, recomputes status
- `confirm_report(report_id, kind, photo_path)`
- `submit_suggestion(lon, lat, reason, note, photo_path)` → merges within 50 m
- `vote_suggestion(suggestion_id)`
- `my_impact()` → counts for impact counter
- `municipality_stats(days int default 30)` → ranking rows
- `my_resolved_since(ts)` → for the Fixed! notification worker

4.6 "Map your street" RPCs:
- `add_container(lon, lat, kind, category, photo_path, device_lon, device_lat)` → enforces the 30 m device-to-pin rule, the duplicate radius (10 m big / 5 m small, same kind), and the 2-container lifetime limit unless `role = 'admin'`; increments `profiles.containers_added`; inserts `verified = false` (admins: `verified = true`)
- `my_add_allowance()` → remaining adds for the current user
- `confirm_container_exists(container_id, lon, lat)` → 50 m rule, rejects the container's own adder, sets `verified = true` at 2 confirmations and gives the adder trust_score +1
- `vote_container_size(container_id, lon, lat, kind)` → 50 m rule; records the caller's Small/Big answer (one per person, replaceable); an unknown bin takes the first answer, after that the majority (tie keeps the current size); on an unverified bin it also counts as `confirm_container_exists` (§4.6 "Bin size")
- `submit_container_request(lon, lat, kind, category, photo_path, note)` → max 3 per user per day; never appears on the map
- `submit_area_check(lon, lat, result)` → writes `area_checks`, updates `profiles.last_area_check_at`
- `should_prompt_area_check(lon, lat)` → bool; true only if the user has no check in 30 days AND the area has no recent check
- Admin-only (all check `role = 'admin'`): `admin_review_request(id, approve bool)` (approve → creates a verified container and links `created_container_id`), `admin_verify_container(id)`, `admin_delete_container(id)` (soft delete), `admin_coverage(days int)` → GeoJSON of area checks
- Trigger: 2 `missing` reports on an unverified container soft-delete it (`deleted_at = now()`) and give the adder trust_score −1
- Triggers keep `containers.status` / `status_since` and `suggestions.votes` correct. A scheduled function (pg_cron) expires old reports every hour.

Row Level Security: everyone (anon) can SELECT containers (soft-deleted rows excluded), open/resolved reports (without user_id exposed — use a view), suggestions, municipalities. Only authenticated users can INSERT via RPCs; users can UPDATE/DELETE only their own profile. No direct client INSERT on reports/confirmations/containers/container_confirmations/container_requests/area_checks (RPC only). `container_requests` is readable only by its author and by admins. `profiles.role`, `containers_added` and `last_area_check_at` are never client-writable — only the RPCs above and manual admin action in Supabase may change them.

Storage: bucket `photos` (public read, authenticated write, max 1 MB, image/jpeg only). Paths: `reports/{user_id}/{uuid}.jpg`, for 4.6 `containers/{user_id}/{uuid}.jpg` and `container_requests/{user_id}/{uuid}.jpg`, and for 4.4 `suggestions/{user_id}/{uuid}.jpg` (optional suggestion photo, same privacy processing).

---

## 7. Seed data

Import Skopje's existing containers from OpenStreetMap via Overpass API (free, no account). If Overpass is unreachable, read the same OSM data from Overture Maps' monthly GeoParquet release instead (`tools/import_osm`, `--source overture`):

```
[out:json][timeout:180];
(
  nw["amenity"~"^(waste_disposal|waste_basket|recycling)$"](41.85,21.05,42.20,21.85);
  nw["bin"="yes"](41.85,21.05,42.20,21.85);
);
out center;
```
Map:
- waste_disposal → kind big / general. Skip `informal=yes`: it marks a dumping spot, not a container.
- recycling → kind big. Category from the `recycling:*` tags, where glass_bottles counts as glass, plastic_bottles and plastic_packaging as plastic, and cardboard as paper. Several materials, or none, give mixed_recycling. Skip `recycling_type=centre`.
- waste_basket → kind small / general.
- `bin=yes` (bus stops that have a bin) → kind small / general, at the stop. Skip it if a mapped waste_basket is within 20 m or another `bin=yes` point is within 10 m.

A way becomes the centre of its bounding box. Keep `osm_id` (negated for ways) and set `source='osm'`. Skip a row when a container added in the app is within the §4.6 duplicate radius (20 m for `bin=yes` points).

Municipality boundaries: the OSM `admin_level=7` relations ("Општина Карпош"). Level 8 is the City of Skopje as a whole. Keep only containers inside the ten boundaries, and assign `municipality_id` with `ST_Contains`. Generate codes `SK-00001…` in import order.

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
