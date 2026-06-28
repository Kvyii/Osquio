# Osquio — Architecture

This document explains how every major piece of the system fits together. It is structured as a series of layers — from database up to UI — followed by a walkthrough of the two most important end-to-end flows.

---

## Table of Contents

1. [System overview](#1-system-overview)
2. [Database (Supabase Postgres)](#2-database-supabase-postgres)
3. [Backend logic (Edge Functions)](#3-backend-logic-edge-functions)
4. [Auth](#4-auth)
5. [Real-time layer (Supabase Realtime)](#5-real-time-layer-supabase-realtime)
6. [Push notifications (Firebase Cloud Messaging)](#6-push-notifications-firebase-cloud-messaging)
7. [Android app — data layer](#7-android-app--data-layer)
8. [Android app — UI layer](#8-android-app--ui-layer)
9. [Steam integration](#9-steam-integration)
10. [Auto-update](#10-auto-update)
11. [End-to-end flows](#11-end-to-end-flows)

---

## 1. System overview

```
┌─────────────────────────────────────────────────────┐
│                   Android client                    │
│  Compose UI ← ViewModels ← Repositories             │
│                      │                              │
│         Supabase KMP client (Kotlin)                │
└──────────┬──────────────────────┬───────────────────┘
           │                      │
    Postgres + Auth          Edge Functions
    + Realtime WS            (Deno / TypeScript)
    (Supabase)                    │
                            Firebase Admin SDK
                                  │
                        FCM V1 API (Google)
                                  │
                        ┌─────────▼──────────┐
                        │  Android device(s) │
                        │  MyFirebaseService │
                        └────────────────────┘
```

There is no custom server. All backend logic lives in Supabase (managed Postgres, Auth, and Realtime) and Deno Edge Functions. The Android app talks directly to Supabase over HTTPS and WebSocket. Firebase Cloud Messaging is the only other external service.

---

## 2. Database (Supabase Postgres)

All persistent state lives in a single Postgres database hosted by Supabase.

### Tables

#### `users`
Mirrors Supabase Auth users with app-specific fields.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | App-level user id |
| `auth_id` | UUID | FK → `auth.users.id` (Supabase Auth) |
| `steam_id` | text | Steam vanity URL or 64-bit ID |
| `display_name` | text | Cached from Steam |
| `avatar_url` | text? | Cached from Steam |
| `cache_at` | timestamptz? | When Steam data was last fetched |
| `fcm_token` | text? | Current FCM registration token |
| `is_admin` | bool | Grants admin controls |
| `cooldown_until` | timestamptz? | Beacon creation blocked until this time |
| `created_at` | timestamptz | Row creation time |

#### `summons`
One row per beacon. Only one row can have `status = 'open'` at a time.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `created_by` | UUID | FK → `users.id` |
| `game_time` | timestamptz | When the game is scheduled |
| `expires_at` | timestamptz | Same as `game_time` currently |
| `status` | text | `open` · `expired` · `cancelled` |
| `cancelled_by` | UUID? | FK → `users.id`, set on manual cancel |
| `created_at` | timestamptz | |

#### `rsvps`
One row per user per summon. Unique on `(summon_id, user_id)` — upsert semantics.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `summon_id` | UUID | FK → `summons.id` |
| `user_id` | UUID | FK → `users.id` |
| `response` | text | `yes` · `no` · `yes_at_time` |
| `response_time` | timestamptz? | Populated when response is `yes_at_time` |
| `responded_at` | timestamptz | Server time of last update |

#### `summon_history`
Immutable archive created when a summon closes. The raw `summons`/`rsvps` tables can be purged without losing this record.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `summon_id` | UUID | Copy of original summon id |
| `summoner_id` | UUID | Copy of `created_by` |
| `game_time` | timestamptz | |
| `created_at` | timestamptz | When beacon was created |
| `closed_at` | timestamptz | When beacon expired/was cancelled |
| `status` | text | `expired` · `cancelled` |
| `cancelled_by` | UUID? | |
| `snapshot` | jsonb | Frozen state — see below |

**Snapshot JSON structure:**
```json
{
  "respondents": [
    {
      "user_id": "uuid",
      "display_name": "string",
      "avatar_url": "string | null",
      "response": "yes | no | yes_at_time",
      "response_time": "timestamp | null",
      "responded_at": "timestamp"
    }
  ],
  "non_respondents": [
    {
      "user_id": "uuid",
      "display_name": "string",
      "avatar_url": "string | null"
    }
  ]
}
```

History, stats, and rankings are all computed from these snapshots — not from live rsvps rows.

#### `config`
Single-row global settings table.

| Column | Type | Default |
|---|---|---|
| `summon_cooldown_seconds` | int | 900 (15 min) |
| `max_summon_ahead_minutes` | int | 120 — stored but not currently enforced by the client (quick-fill buttons cap at 60 min) |

---

## 3. Backend logic (Edge Functions)

All business-critical logic runs in Deno Edge Functions deployed to Supabase. They run under the service role (bypasses Row Level Security) and hold secrets the Android client never sees.

### `notify-summon`

**Triggered by:** Supabase webhook on `summons INSERT`, or direct HTTP call from the app (for re-beacons).

**What it does:**
1. Looks up all users with a non-null `fcm_token`, excluding the summoner.
2. Formats `game_time` into `HH:mm` (Australia/Sydney timezone).
3. Exchanges the `FIREBASE_SERVICE_ACCOUNT` JSON for a short-lived OAuth2 access token.
4. Sends a high-priority FCM V1 data message to each device with:
   - `type = "summon"`
   - `summon_id`, `game_time`, `summoner_name`

The Android client decides whether to show a notification based on whether the user has already responded.

### `validate-rsvp`

**Triggered by:** HTTP POST from the Android app (requires a valid user bearer token).

**What it does:**
1. Validates the request: summon exists, status is `open`, response value is legal, `yes_at_time` is before `game_time`.
2. Upserts the `rsvps` row (unique on `summon_id + user_id`).
3. Sends a silent FCM data message (`type = "rsvp_update"`) to all users so their live lobbies refresh.

RSVP is routed through an Edge Function rather than direct Postgres insert so validation is enforced server-side — the Android client cannot bypass it.

### `close-summon`

**Triggered by:** Supabase webhook on `summons UPDATE` (fires when status changes to `expired` or `cancelled`).

**What it does:**
1. Joins `rsvps` and `users` to build the full respondent list.
2. Computes `non_respondents` as all users minus those who have a row in `rsvps`.
3. Inserts a row into `summon_history` with the frozen snapshot.
4. If the summon was cancelled (not just expired), sets `cooldown_until` on the creator's `users` row.
5. Sends a silent FCM `rsvp_update` to trigger a UI refresh on all devices.

### `create-member`

**Triggered by:** Manual HTTP call by an admin (not automated).

**What it does:**
1. Verifies the caller is authenticated and has `is_admin = true`.
2. Creates a Supabase Auth user with the email convention `displayname@osquio.kvi`.
3. Attempts to fetch their Steam profile.
4. Inserts a `users` row.
5. Rolls back the Auth user if anything fails.

---

## 4. Auth

Supabase Auth (email/password provider) is the identity layer.

- Every `users` row has an `auth_id` that links to `auth.users`.
- The Android app holds a session JWT (auto-refreshed by the Supabase client). Every Supabase Postgrest and Edge Function call is authenticated with this JWT.
- On the client, usernames are converted to emails automatically: entering `kv` → `kv@osquio.kvi`.
- New members are created by an admin via the `create-member` Edge Function — there is no self-registration.
- Row Level Security is **not** the primary security boundary. Edge Functions that perform privileged operations (upsert rsvp, archive summon) run under the service role but verify the caller's identity themselves.

---

## 5. Real-time layer (Supabase Realtime)

Supabase Realtime wraps Postgres logical replication and exposes it as a WebSocket channel. The Android app subscribes once an active summon is found.

**Channel name:** `lobby-{summon_id}`

**Tables monitored:** `rsvps`, `summons`

**Events:** INSERT, UPDATE, DELETE

When any change arrives, `SummonViewModel` re-fetches the current summon and all its rsvps from Postgres and rebuilds the UI state. The WebSocket event is purely a trigger — it does not carry the full payload.

This powers the live lobby: when someone on another device submits a response, every open lobby screen updates within seconds.

---

## 6. Push notifications (Firebase Cloud Messaging)

FCM is used for two distinct purposes:

| Message type | `type` field | Visible to user? | Purpose |
|---|---|---|---|
| Beacon alert | `summon` | Yes — loud, full-screen | Notify group that a game is called |
| Silent refresh | `rsvp_update` | No | Trigger live lobby reload |

### Token lifecycle

1. On first launch (or token rotation), `MyFirebaseService.onNewToken()` writes the FCM token to `users.fcm_token` in Supabase.
2. On every login and session restore, `MainActivity.registerFcmToken()` proactively fetches the current token from Firebase and updates the DB. This catches cases where the token changed while the app was uninstalled/reinstalled.

### Beacon alert flow

1. Edge Function sends FCM data message with `type = summon`.
2. `MyFirebaseService.onMessageReceived()` receives it.
3. Checks whether the current user has already responded to this `summon_id` via `RsvpRepository.rsvpsForSummon()`. If they have, notification is skipped.
4. Builds a `NotificationCompat` on channel `beacon_alert_channel`:
   - Custom sound: `res/raw/beacon_sound.mp3` (Dota "game ready" sound)
   - Vibration pattern: `[0, 500, 200, 500, 200, 500, 200, 1000]`
   - `VISIBILITY_PUBLIC` (shows on lock screen)
   - Full-screen intent → `BeaconAlertActivity`
   - Two quick-action buttons ("Yes", "No") backed by `PendingIntent` → `RsvpActionReceiver`
5. On Android 14+ the user must explicitly grant `USE_FULL_SCREEN_INTENT` permission; Settings has a button that deep-links there.

### Silent refresh flow

1. Edge Function (validate-rsvp or close-summon) sends FCM data message with `type = rsvp_update`.
2. `MyFirebaseService` calls `refreshLobby()` — an internal broadcast or state push — which prompts `SummonViewModel` to re-fetch.

### Notification channel

Android notification channels are configured once at channel creation time. Changing sound or vibration after first creation has no effect; the channel must be deleted (uninstall app) and recreated. Channel `beacon_alert_channel` is configured with `IMPORTANCE_HIGH`, the custom sound URI, and the vibration pattern.

---

## 7. Android app — data layer

```
UI / ViewModel
      │
  Repository
      │
  ┌───┴────────────────────────────┐
  │  Supabase KMP client           │
  │  (Auth · Postgrest · Realtime  │
  │   · Functions)                 │
  └────────────────────────────────┘
```

**`SupabaseClient.kt`** — a singleton created at app start. Holds the authenticated session. All repositories share this single instance.

**Repositories** are plain Kotlin objects (singletons). They make suspend calls and return domain models. No caching — every call hits the network.

| Repository | Responsibility |
|---|---|
| `UserRepository` | CRUD on `users`; FCM token registration; Steam cache refresh |
| `SummonRepository` | Beacon lifecycle; calls `notify-summon` Edge Function for re-beacons |
| `RsvpRepository` | Submits RSVPs via `validate-rsvp` Edge Function; fetches rsvps by summon |
| `HistoryRepository` | Reads `summon_history`; date-range filtering |
| `ConfigRepository` | Reads and writes `config` |

**Data models** are `@Serializable` Kotlin data classes that map 1:1 to Postgres columns. Supabase Postgrest deserialises JSON into them automatically.

---

## 8. Android app — UI layer

The app follows a standard MVVM pattern with Jetpack Compose.

```
MainActivity
└── NavGraph (bottom-bar navigation)
    ├── SummonScreen  ← SummonViewModel
    ├── StatsScreen   ← StatsViewModel
    ├── RankingsScreen ← RankingsViewModel
    ├── HistoryScreen ← HistoryViewModel
    └── SettingsScreen ← SettingsViewModel

BeaconAlertActivity  (full-screen, shown over lock screen)
```

**ViewModels** hold a `MutableStateFlow<UiState>` where `UiState` is a sealed class with `Loading`, `Loaded`, and `Error` variants. Composables collect this flow and render accordingly.

**Navigation** uses Jetpack Compose Navigation with five fixed bottom-bar routes. Auth state is managed in `MainActivity`: unauthenticated → `LoginScreen`; authenticated → `NavGraph`.

**Theme** — four themes (Midnight, Twilight, Dawn, Sponke) managed by `ThemeManager`, a singleton that persists the selection to `SharedPreferences`. Theme is applied at `OsquioApp` level so the entire Compose tree re-renders on change.

**`BeaconAlertActivity`** is a separate `ComponentActivity` that renders over the lock screen. It shows the beacon details and Yes/No buttons. Tapping a button submits the RSVP directly (not via the notification action) then finishes the activity.

---

## 9. Steam integration

Steam profiles are fetched from the public XML endpoint — no API key required.

1. On login/session restore, `MainActivity` checks `users.cache_at`. If null or older than 7 days, it calls `SteamApi.fetchProfile(steamId)`.
2. `SteamApi` parses the XML response and extracts `display_name` and `avatarFull` URL.
3. The result is written back to `users.display_name`, `users.avatar_url`, and `users.cache_at` via `UserRepository.updateSteamCache()`.
4. Avatar images are loaded lazily by Coil wherever they appear in the UI.

Avatars are only a URL in the database — Coil fetches from Steam's CDN at render time. The 7-day cache window limits Steam API traffic; manual refresh is available in Settings.

---

## 10. Auto-update

There is no Play Store. Updates are distributed as signed APKs on GitHub Releases.

1. On launch, `UpdateChecker` calls the GitHub Releases API and compares the latest release tag against `BuildConfig.VERSION_NAME`.
2. If newer, a dialog is shown. The user taps "Update" → `UpdateChecker` downloads the APK to a `FileProvider`-backed cache directory.
3. An install intent is fired via the `FileProvider` URI. Android handles the package installer UI.
4. `REQUEST_INSTALL_PACKAGES` permission is required for this flow.

The GitHub repo owner and name are baked into `BuildConfig` at compile time (`GITHUB_REPO_OWNER`, `GITHUB_REPO_NAME`).

---

## 11. End-to-end flows

### Calling a game

```
User taps "Beacon!"
  → SummonViewModel.createSummon()
      → SummonRepository.createSummon()
          → INSERT into summons (status='open')
          → INSERT into rsvps (creator, response='yes')
  ← Returns new summon

Supabase webhook fires (summons INSERT)
  → notify-summon Edge Function
      → Reads all users.fcm_token (excluding creator)
      → Gets Firebase OAuth token from service account
      → POSTs FCM V1 data message to each device

Each recipient's device:
  → MyFirebaseService.onMessageReceived()
      → Checks rsvps for this summon_id
      → User hasn't responded → show notification
          → Full-screen BeaconAlertActivity OR notification drawer
          → Quick-action Yes/No buttons via RsvpActionReceiver

Creator's device:
  → SummonViewModel Realtime subscription fires
      → Re-fetches summon + rsvps
      → Lobby updates in real time as responses come in
```

### Submitting an RSVP

```
User taps "Yes" (in BeaconAlertActivity, notification button, or lobby)
  → RsvpRepository.upsertRsvp(summonId, userId, "yes", null)
      → HTTP POST to validate-rsvp Edge Function (with auth JWT)
          → Validates: summon open, response valid
          → UPSERT into rsvps
          → Sends silent FCM rsvp_update to all devices

Each device with lobby open:
  → MyFirebaseService receives rsvp_update
      → Triggers lobby refresh in SummonViewModel
          → Re-fetches rsvps
          → UI updates (new name appears in "Yes" column)
```

### Beacon closing

```
game_time arrives → Supabase scheduled job sets summons.status = 'expired'
  (OR admin taps "Cancel Beacon" → status = 'cancelled')

Supabase webhook fires (summons UPDATE)
  → close-summon Edge Function
      → Joins rsvps + users → builds snapshot JSON
      → INSERT into summon_history (immutable archive)
      → If cancelled: UPDATE users SET cooldown_until = now() + cooldown_seconds
      → Sends silent FCM rsvp_update → all lobbies dismiss

Stats, Rankings, and History are all derived from summon_history.snapshot
```
