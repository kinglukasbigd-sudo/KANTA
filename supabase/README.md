# Kanta — Supabase backend

Everything the app talks to. Plain `.sql` files, meant to be pasted into the
**Supabase SQL Editor** in order. No CLI or Docker needed.

---

## 1. Create the project

1. Go to <https://supabase.com/dashboard> → **New project**.
2. Pick a region close to Skopje — **Frankfurt (eu-central-1)** is the nearest.
3. Save the database password somewhere safe. You will not need it for the app,
   but you cannot recover it later.
4. Wait for the project to finish provisioning (~2 minutes).

---

## 2. Run the migrations, in this order

Open **SQL Editor → New query**, paste one file, press **Run**, check it says
success, then move to the next. Do not skip and do not reorder — each file
depends on the ones before it.

| # | File | What it does |
|---|---|---|
| 1 | `migrations/0001_extensions.sql` | PostGIS, pg_cron, pgcrypto |
| 2 | `migrations/0002_tables.sql` | All tables, constraints, indexes, auto-profile trigger |
| 3 | `migrations/0003_views.sql` | `reports_public`, `suggestions_public`, `containers_public` |
| 4 | `migrations/0004_status_logic.sql` | Status engine + triggers (spec §5.1) |
| 5 | `migrations/0005_rpc_map.sql` | Map reads: bbox, nearest, detail |
| 6 | `migrations/0006_rpc_reports.sql` | `submit_report`, `confirm_report`, expiry |
| 7 | `migrations/0007_rpc_suggestions.sql` | Suggestions + voting |
| 8 | `migrations/0008_rpc_stats.sql` | Impact counter, municipality ranking |
| 9 | `migrations/0009_rpc_map_your_street.sql` | §4.6 add / confirm / request / area checks |
| 10 | `migrations/0010_rpc_admin.sql` | Admin review queue, verify, coverage |
| 11 | `migrations/0011_cron.sql` | Hourly expiry job |
| 12 | `migrations/0012_account_deletion.sql` | `delete_my_account()` |
| 13 | `migrations/0013_rls.sql` | Row Level Security + function grants |
| 14 | `migrations/0014_storage.sql` | `photos` bucket + policies |
| 15 | `migrations/0015_map_your_street_fixes.sql` | §4.6 fixes: missing-report removal, request limit, "Yes, it's here" eligibility |
| 16 | `seed/0001_municipalities.sql` | The 10 Skopje municipalities |

> **If step 1 fails on `pg_cron`:** go to **Database → Extensions**, search
> `pg_cron`, enable it there, then re-run the file. Some projects need it
> enabled through the dashboard first.

After step 16, verify:

```sql
select count(*) from municipalities;                    -- 10
select count(*) from cron.job where jobname like 'kanta%';  -- 1
select id, public, file_size_limit from storage.buckets where id = 'photos';
```

---

## 3. Turn on email OTP (6-digit code)

The app signs people in with a 6-digit code, not a magic link (spec §2).

1. **Authentication → Sign In / Providers → Email**
   - **Enable Email provider**: on
   - **Confirm email**: on
   - **Secure email change**: on
2. **Authentication → Emails → Templates → Magic Link**
   - Replace the template body with something containing `{{ .Token }}`
     instead of `{{ .ConfirmationURL }}`. The presence of `{{ .Token }}` is
     what makes Supabase send a **code** rather than a link:

   ```html
   <h2>Канта</h2>
   <p>Твојот код за најава е:</p>
   <p style="font-size:28px;letter-spacing:4px;"><strong>{{ .Token }}</strong></p>
   <p>Кодот важи 1 час.</p>
   ```
3. **Authentication → Providers → Email → OTP Expiry**: **3600** seconds. The app
   uses this number to tell a *wrong* code from an *expired* one (Supabase returns
   the same error for both), so if you change it, change `OTP_EXPIRY_SECONDS` in
   `AuthErrorMapper.kt` to match.
4. **Authentication → Rate Limits → minimum interval between emails** (per
   address): set it to **30** seconds. The app offers "Send a new code" after 30 s;
   Supabase's default is 60 s, which would make the first early resend fail with
   "Too many codes requested". The app handles that politely either way — this
   just makes the button honest.
5. Leave **Confirm email** on — the OTP itself is the confirmation.

On the client this is `signInWith(OTP) { email = ... }` followed by
`verifyEmailOtp(type = OtpType.Email.EMAIL, email, token)`.

> **Sending limits.** Supabase's built-in SMTP is rate-limited to a handful of
> emails per hour and is not for real users. Before you hand the app to anyone,
> set your own SMTP under **Project Settings → Authentication → SMTP Settings**
> (Resend, Postmark and Brevo all have free tiers). Without this, testing with
> more than one or two addresses will silently start failing.

---

## 4. Make yourself an admin

Spec §4.6: `profiles.role` is set manually, never through the app.

Sign in once from the app so your row exists, then run:

```sql
update profiles
   set role = 'admin'
 where id = (select id from auth.users where email = 'ivan.st.1404@gmail.com');
```

Check it:

```sql
select p.role, u.email from profiles p join auth.users u on u.id = p.id;
```

---

## 5. Put the keys in the app

**Project Settings → API**:

- **Project URL** → `SUPABASE_URL`
- **anon / public** key → `SUPABASE_ANON_KEY`

Paste both into `local.properties` in the project root (git-ignored):

```
SUPABASE_URL=https://xxxxxxxxxxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOi...
```

Never use the `service_role` key in the app — it bypasses every policy in
`0013_rls.sql`.

---

## 6. How the security model works

Worth understanding before changing anything:

- **Nothing writes to a table directly.** Every write is an RPC marked
  `SECURITY DEFINER`, so it runs as the function owner and is not subject to
  RLS. The tables themselves grant no INSERT/UPDATE/DELETE to `anon` or
  `authenticated` at all.
- **Reports are never readable raw** — only through `reports_public`, which does
  not select `user_id`. There is deliberately no SELECT policy on `reports`.
- **Profile columns are locked by GRANT, not by trust.** A user holds
  `UPDATE (display_name, municipality_id, lang)` and nothing else, so `role`,
  `trust_score` and `containers_added` cannot be raised by a crafted request.
- **Every limit lives in SQL**: the 60 m report radius, 10 reports/day, the 30 m
  add radius, the 10 m / 5 m duplicate radius, 2 containers per lifetime, 3
  requests/day, verification at 2 confirmations. The app shows these limits for
  a good experience; the database is what enforces them.

### Error codes

RPCs raise stable SQLSTATEs instead of English messages, so the app can show
Macedonian, Albanian or English text. `KantaErrorMapper` on the Kotlin side maps
these to string resources.

| Code | Meaning |
|---|---|
| `KA001` | Too far from the container (> 60 m) |
| `KA002` | Daily report limit reached (10) |
| `KA003` | A container of the same kind is already here |
| `KA004` | Container add allowance used up (2 lifetime) |
| `KA005` | Too far from the pin to add (> 30 m) |
| `KA006` | Daily container-request limit reached (3) |
| `KA007` | Admin only |
| `KA008` | Already done (duplicate confirmation or vote) |
| `KA009` | Cannot confirm your own container |
| `KA010` | Too far to confirm (> 50 m) |
| `KA011` | Not signed in |
| `KA012` | Container not found or deleted |
| `KA013` | Report not found or no longer open |
| `KA014` | Suggestion not found |
| `KA015` | Container request not found or already reviewed |

---

## 7. After the migrations

- **Containers and boundaries** come from OpenStreetMap:
  `python3 tools/import_osm/import_osm.py`, then run the generated
  `seed/containers.sql` here. Note that Skopje's municipalities are OSM
  **admin_level=7**, not 8 as spec §7 says — the importer handles it. Until the
  boundaries are loaded, `municipality_at()` returns NULL and city stats group
  everything as unassigned; expected, not a bug.
- **Re-running migrations.** The files are written to be idempotent
  (`create or replace`, `if not exists`, `on conflict`), so re-running the
  whole sequence **in order** is safe. Re-run in order rather than picking single
  files: 0015 reshapes functions first defined in 0005 and 0009, and always has
  to run after them.
- **Testing the rules.** `python3 supabase/tests/map_your_street_test.py` walks
  every §4.6 rule (2-add limit, requests, 30 m, duplicates, "Yes, it's here",
  missing removal, admin review, area checks) as real test users, inside one
  transaction that is always rolled back — it leaves nothing in the database.
  Needs `pip install "psycopg[binary]"` and the connection string in
  `supabase/.db-url` (git-ignored; the Session pooler URI from **Connect**).
