# Dota Summon App (Osquio) — Project Plan

## Overview

A closed-group Android app for coordinating Dota 2 sessions. One user sends a summon for a specific game time, all group members get a push notification, and they respond with their availability. A live lobby shows who's in, a stats page tracks behaviour, and a history calendar shows all past summons.

---

## App Navigation (Tabs)

1. **Summon** — active summon lobby, or summon creation if none active
2. **Stats** — per-user stats visible to everyone
3. **Rankings** — live badges leaderboard
4. **History** — calendar view of all past summons
5. **Settings** — admin controls, config

---

## Core Features

- **Summon request** — any member can call a game at a specific time (up to 2 hours ahead). Quick-fill buttons for 15 min, 30 min, 1 hour. Only one active summon at a time
- **Push notifications** — all members alerted via FCM even when app is closed. Loud/high priority
- **RSVP responses** — Yes / No / Yes at [time]. "Yes at X time" via scroll time picker in 5 min increments with quick-fill buttons. Only valid if within the summon window
- **Live lobby view** — real-time list of respondents and non-respondents
- **Summon cooldown** — 15 min global lockout on cancellation (self or admin-cancelled). Summoner sees countdown timer. No penalty for natural expiry
- **Summon history** — calendar view of all past summons, forever. Tap a day → slide to list of summons that day → tap a summon → see full detail (summoner, respondents, non-respondents, responses)
- **Stats page** — per-user stats visible to all. Summons sent, accepted, rejected, ignored. Filterable by this month or all time
- **Badges / rankings** — live badges recalculated from stats. Can be lost. See Badges section
- **Steam profiles** — display name and avatar fetched via public Steam profile URL, cached in DB. No Steam API key required
- **Admin control** — only admin can add/remove users and create accounts for the group
- **Auto-update checker** — checks GitHub releases on launch, prompts download if newer APK exists

---

## Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Android app | Kotlin + Jetpack Compose | Modern, less boilerplate |
| Backend / DB | Supabase | Hosted Postgres, REST API, Realtime, Auth, Edge Functions — all free tier |
| Authentication | Supabase Auth (email + password) | Admin-controlled, no self-registration |
| Push notifications | Firebase Cloud Messaging (FCM) V1 | Free, reliable, native Android |
| Steam integration | Public Steam profile URL | Username + avatar only, no API key needed |
| Distribution | GitHub repo + APK | No Play Store needed for a closed group |

---

## Architecture

```
Android App  ──REST──►  Supabase DB  ◄──triggers──  Edge Function  ──►  FCM  ──►  Android App
     │                      │                                                          │
     │                   Realtime                                                      │
     └──────────────── websocket ◄─────────────────────────────────────────────────────┘
```

### Flow: Summon sent

1. User picks game time (quick-fill or time picker) → app checks `config` cooldown and `users.cooldown_until`
2. If clear, inserts row into `summons` table
3. Supabase Edge Function fires on DB insert → fetches all `fcm_token`s from `users`
4. Edge Function calls FCM V1 API for each token — loud/high priority notification
5. All group members receive push notification
6. Members tap notification → app opens → lobby view subscribes to Supabase Realtime
7. Each member taps response → upserts into `rsvps` table
8. All clients see lobby update live via websocket

### Flow: Summon cancelled or expired

1. Summon status set to `cancelled` or `expired`
2. Edge Function fires → takes snapshot of all rsvps, non-respondents → writes to `summon_history` with full JSON snapshot
3. If cancelled (self or admin): write `now() + 15 minutes` to `summoner.cooldown_until`
4. If expired naturally: no cooldown written
5. Stats recalculated from `summon_history`

---

## Authentication

### Approach: Admin-controlled email + password via Supabase Auth

The group is closed and trust-controlled. Only the admin can create accounts — there is no self-registration. This prevents anyone from faking another member's identity.

### How accounts are created

1. Admin opens Settings → "Add member"
2. Admin enters the new member's details: Steam ID/vanity URL, display name, and assigns them a temporary password
3. App creates a Supabase Auth account (email + password) via the service role key (Edge Function)
4. A corresponding row is inserted into the `users` table, linked via `auth_id`
5. Admin shares the credentials with the friend (WhatsApp, Discord, etc.)
6. Friend opens app → logs in → optionally changes password

### Email convention

Since this is a private group, emails don't need to be real. Use a consistent fake domain:
```
friendname@osquio.kvi
```
The email is just a Supabase Auth identifier — no emails are ever sent to it.

### Supabase Auth setup

- Disable email confirmation in Supabase Auth settings (no real emails, so confirmations would fail)
- Disable sign-ups (only admin can create accounts via service role)
- Sessions are JWT tokens managed by the Supabase Android SDK automatically

### Kotlin login flow

```kotlin
// Login
val session = supabase.auth.signInWith(Email) {
    email    = "friendname@osquio.kvi"
    password = "their_password"
}

// Get current user's profile from users table
val user = supabase.from("users")
    .select()
    .eq("auth_id", supabase.auth.currentUserOrNull()?.id ?: "")
    .single()
    .decodeAs<User>()
```

### Session persistence

Supabase Android SDK handles session persistence automatically — users stay logged in across app restarts. No manual token management needed.

### Password change (optional, first login)

```kotlin
supabase.auth.updateUser {
    password = "new_password"
}
```

---

## Database Schema

### `users`
```sql
create table users (
  id             uuid primary key default gen_random_uuid(),
  auth_id        uuid unique references auth.users(id),  -- links to Supabase Auth
  steam_id       text unique not null,
  display_name   text not null,
  avatar_url     text,
  cache_at       timestamptz,
  fcm_token      text,
  is_admin       boolean default false,
  cooldown_until timestamptz,          -- null = not in cooldown
  created_at     timestamptz default now()
);
```

> `auth_id` is the bridge between Supabase Auth (who is logged in) and your `users` table (their profile). RLS policies use `auth.uid()` which maps to this field.

### `summons`
```sql
create table summons (
  id           uuid primary key default gen_random_uuid(),
  created_by   uuid references users(id),
  game_time    timestamptz not null,  -- the time the summoner is calling for
  expires_at   timestamptz not null,  -- same as game_time; summon auto-closes at this time
  status       text default 'open',   -- 'open' | 'expired' | 'cancelled'
  cancelled_by uuid references users(id),
  created_at   timestamptz default now()
);
```

### `rsvps`
```sql
create table rsvps (
  id            uuid primary key default gen_random_uuid(),
  summon_id     uuid references summons(id),
  user_id       uuid references users(id),
  response      text not null,        -- 'yes' | 'no' | 'yes_at_time'
  response_time timestamptz,          -- only set for 'yes_at_time' responses
  responded_at  timestamptz default now(),
  unique(summon_id, user_id)          -- one response per user per summon; upsert to change
);
```

### `summon_history`
```sql
create table summon_history (
  id           uuid primary key default gen_random_uuid(),
  summon_id    uuid references summons(id),
  summoner_id  uuid references users(id),
  game_time    timestamptz not null,
  created_at   timestamptz not null,
  closed_at    timestamptz not null,
  status       text not null,         -- 'expired' | 'cancelled'
  cancelled_by uuid references users(id),
  snapshot     jsonb not null
);
```

**Snapshot JSON structure:**
```json
{
  "respondents": [
    {
      "user_id": "uuid",
      "display_name": "string",
      "avatar_url": "string",
      "response": "yes | no | yes_at_time",
      "response_time": "timestamptz or null",
      "responded_at": "timestamptz"
    }
  ],
  "non_respondents": [
    {
      "user_id": "uuid",
      "display_name": "string",
      "avatar_url": "string"
    }
  ]
}
```

> Snapshot is immutable. Even if a user leaves the group later, history is preserved exactly as it was.

### `config`
```sql
create table config (
  id                       uuid primary key default gen_random_uuid(),
  summon_cooldown_seconds  integer default 900,   -- 15 minutes
  max_summon_ahead_minutes integer default 120,   -- 2 hour max
  created_at               timestamptz default now(),
  updated_at               timestamptz default now()
);
-- Single row table. Admin editable via Settings tab.
```

### Schema migration to run now
```sql
-- Add auth_id to existing users table
alter table users
add column auth_id uuid unique references auth.users(id);
```

---

## Business Logic Rules

### Summon creation
- Only one `status = 'open'` summon allowed at a time. Reject if one exists
- `game_time` must be between `now()` and `now() + max_summon_ahead_minutes`
- Check `users.cooldown_until > now()` for the requesting user — reject with remaining time if in cooldown
- All checks enforced server-side in the Edge Function, not just client-side

### RSVP validation
- `yes_at_time` responses: `response_time` must be `<= summon.game_time`. Reject if after game time
- Users can update their response (upsert on `unique(summon_id, user_id)`)
- No response changes accepted after summon closes

### Cooldown
- Triggered on cancellation only (self-cancel or admin-cancel)
- `cooldown_until = now() + config.summon_cooldown_seconds` written to the summoner's row
- Natural expiry → no cooldown
- Client shows countdown: "You can summon again in X:XX"

### Cancellation permissions
- Summoner can cancel their own active summon
- Admin can cancel any active summon
- No other user can cancel

---

## Badges (Live, Recalculated)

Badges are recalculated dynamically from stats — they are not permanently awarded. Anyone can lose a badge if someone else overtakes them.

| Badge | Metric | Scope |
|---|---|---|
| ⚡ Fastest Responder | Lowest average response time (from notif to RSVP) | This month / All time |
| 📯 Summoner Supreme | Most summons sent | This month / All time |
| 👻 Biggest Ghost | Most summons ignored (no response) | This month / All time |
| 🛋️ Biggest Bum | Most "no" responses | This month / All time |

Stats are filterable between **This Month** (default) and **All Time**.

Stats shown per user:
- Summons sent
- Summons accepted (yes or yes_at_time within window)
- Summons rejected (no)
- Summons ignored (no response by expiry)

---

## History — Calendar View

- Calendar page: each day shows a number badge if summons occurred that day
- Tap a day → slide to list view of summons that day (there can be multiple if they happened sequentially)
- Tap a summon row → detail view showing: summoner, game time, status, each respondent's response, and non-respondents
- History kept forever
- Data sourced from `summon_history` table (immutable snapshots)

---

## Steam Profile Fetching (No API Key)

Steam profiles are fetched via the public XML endpoint — no API key required:

```
https://steamcommunity.com/profiles/{STEAM_ID_64}/?xml=1
https://steamcommunity.com/id/{VANITY_URL}/?xml=1
```

Returns `steamID64`, `steamID` (display name), and `avatarFull` (avatar URL).

- Admin enters the member's Steam ID or vanity URL when creating their account
- App fetches and caches `display_name` and `avatar_url` in the `users` table
- `cache_at` is set on fetch — refresh if older than 7 days or on next login

### Two Steam ID formats

Steam supports two URL formats and both must be handled:

| Format | Example stored value | URL used |
|---|---|---|
| Vanity URL (custom name) | `swurf` | `steamcommunity.com/id/swurf/?xml=1` |
| Steam ID64 (numeric) | `76561198071372079` | `steamcommunity.com/profiles/76561198071372079/?xml=1` |

Detection is simple — if the value is all digits it's an ID64, otherwise it's a vanity URL:

```kotlin
fun buildSteamUrl(steamId: String): String {
    return if (steamId.all { it.isDigit() }) {
        "https://steamcommunity.com/profiles/$steamId/?xml=1"
    } else {
        "https://steamcommunity.com/id/$steamId/?xml=1"
    }
}
```

### Full fetch and cache flow

```kotlin
suspend fun fetchAndCacheSteamProfile(userId: String, steamId: String) {
    val url = buildSteamUrl(steamId)
    val xml = fetchXml(url)  // plain HTTP GET, parse XML response

    val displayName = xml.getString("steamID")       // display name
    val avatarUrl   = xml.getString("avatarFull")    // full size avatar URL

    // Update the users row with fetched data
    supabase.from("users")
        .update(mapOf(
            "display_name" to displayName,
            "avatar_url"   to avatarUrl,
            "cache_at"     to Clock.System.now().toString()
        ))
        .eq("id", userId)
}
```

### When to fetch

- On first login (when `display_name` is null)
- On subsequent logins if `cache_at` is older than 7 days
- Never block the UI — fetch in background, show placeholder avatar until ready

---

## Firebase FCM V1 — Kotlin Side

### Receiving notifications

FCM messages come in two flavours for this app:

- **Visible notifications** — summon alerts. Shown to the user even when the app is closed
- **Silent data messages** — RSVP updates. Wake the app briefly to refresh lobby state without showing a notification

```kotlin
class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "rsvp_update" -> {
                // Silent refresh — update lobby state in background, no notification shown
                val summonId = message.data["summon_id"] ?: return
                refreshLobbyInBackground(summonId)
            }
            else -> {
                // Visible summon notification
                showNotification(
                    title = message.notification?.title ?: "🎮 Dota summon!",
                    body  = message.notification?.body  ?: "Someone is calling for a game. You in?"
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        // Called on install or token rotation — save to Supabase immediately
        saveFcmTokenToSupabase(token)
    }

    private fun refreshLobbyInBackground(summonId: String) {
        // Trigger a lightweight fetch of current RSVPs for this summon
        serviceScope.launch {
            RsvpRepository.refreshForSummon(summonId)
        }
    }
}
```

Register the service in `AndroidManifest.xml`:

```xml
<service
    android:name=".MyFirebaseService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT"/>
    </intent-filter>
</service>
```

### Lobby websocket — reconnect on resume

The Supabase Realtime websocket can be dropped when the phone locks or the user switches apps. Reconnect on every resume so the lobby is always fresh when the user returns:

```kotlin
// In your Activity
override fun onResume() {
    super.onResume()
    viewModel.resubscribeToLobby()
}

// In LobbyViewModel
fun resubscribeToLobby() {
    activeChannel?.unsubscribe()
    val channel = supabase.channel("lobby-${activeSummonId}")
    channel.postgresChangeFlow<PostgresAction>(schema = "public") {
        table = "rsvps"
        filter = "summon_id=eq.${activeSummonId}"
    }.onEach { updateLobby(it) }
     .launchIn(viewModelScope)
    channel.subscribe()
    activeChannel = channel
}
```

### Battery optimisation prompt

Android's aggressive battery management can delay or block FCM on some manufacturers (Samsung, Xiaomi, OnePlus). Prompt the user to exempt the app on first launch:

```kotlin
// In MainActivity, call once after login
fun requestBatteryOptimisationExemption() {
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
```

Add the required permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

This opens a system dialog asking the user to exempt Osquio from battery optimisation. Only fires once — the `isIgnoringBatteryOptimizations` check prevents it showing again after they have accepted.

> **Tell your friends:** If they dismiss the prompt, they can manually fix it via Settings → Battery → App battery usage → Osquio → Unrestricted. Worth a WhatsApp message when you first distribute the APK.

### Token lifecycle note

FCM tokens can rotate (reinstall, cleared data, etc.). `onNewToken` handles this automatically as long as it always writes the new token back to Supabase. Stale tokens result in silent missed notifications — no crash.

---

## Supabase Edge Function — Notification Trigger (FCM V1)

Stored in `/supabase/functions/notify-summon/index.ts`. Triggered via Supabase Database Webhook on INSERT to `summons`.

FCM V1 requires a short-lived OAuth2 token generated from the service account JSON, not a static server key.

```typescript
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { GoogleAuth } from 'https://esm.sh/google-auth-library@9'

const supabase = createClient(
  Deno.env.get('SUPABASE_URL')!,
  Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
)

const serviceAccount = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT')!)
const projectId = serviceAccount.project_id

async function getFcmAccessToken(): Promise<string> {
  const auth = new GoogleAuth({
    credentials: serviceAccount,
    scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
  })
  const client = await auth.getClient()
  const token = await client.getAccessToken()
  return token.token!
}

Deno.serve(async (req) => {
  const { record } = await req.json()

  const { data: users } = await supabase
    .from('users')
    .select('fcm_token, display_name')
    .not('fcm_token', 'is', null)

  const accessToken = await getFcmAccessToken()

  const sendPromises = users!.map((user) =>
    fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token: user.fcm_token,
          notification: {
            title: '🎮 Dota summon!',
            body: `Game called for ${new Date(record.game_time).toLocaleTimeString()}. You in?`,
          },
          android: {
            priority: 'high',           // wakes device even in doze mode
          },
          data: {
            type: 'summon',             // handled in onMessageReceived
            summon_id: record.id,
            game_time: record.game_time,
          },
        },
      }),
    })
  )

  await Promise.all(sendPromises)
  return new Response(JSON.stringify({ ok: true }), { status: 200 })
})
```

**Environment variables required in Supabase:**
- `FIREBASE_SERVICE_ACCOUNT` — full contents of the service account JSON file
- `SUPABASE_SERVICE_ROLE_KEY` — from Supabase project settings
- `SUPABASE_URL` — your project URL

### Silent RSVP data messages (in `close-summon` / RSVP Edge Functions)

When an RSVP is submitted, send a silent FCM data message to all group members so their lobby screens refresh even if the websocket dropped. No notification is shown — just a background state refresh:

```typescript
// Silent data-only message — no "notification" key, just "data"
body: JSON.stringify({
  message: {
    token: user.fcm_token,
    data: {
      type: 'rsvp_update',
      summon_id: summonId,
    },
    android: {
      priority: 'high',
    },
  },
})
```

The `type: 'rsvp_update'` value is caught in `onMessageReceived` on the Kotlin side and triggers a silent lobby refresh instead of showing a notification.

---

## Supabase Realtime — Lobby Updates (Kotlin)

Subscribe to RSVP changes for the active summon so the lobby view updates live:

```kotlin
val channel = supabase.channel("lobby-${summonId}")

channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
    table = "rsvps"
    filter = "summon_id=eq.${summonId}"
}.onEach { change ->
    updateLobby(change.record)
}.launchIn(viewModelScope)

channel.subscribe()
```

Enable Realtime on: `rsvps` table, `summons` table (so all clients know when a summon opens or closes).

---

## Auto-Update Checker

Since this app is distributed via GitHub releases (not the Play Store), update checking is done manually against the GitHub API.

### How it works

1. On app launch, fetch the latest release from the GitHub API
2. Compare the release tag against the app's own `versionName`
3. If a newer version exists, show a non-blocking dialog with a download link
4. User taps download → browser opens → Android's package installer handles the rest

### GitHub release convention

Tag every release consistently: `v1.0.0`, `v1.1.0`, `v1.2.3`, etc. Each release must have the APK attached as a release asset.

### `UpdateChecker.kt`

```kotlin
data class UpdateInfo(val version: String, val downloadUrl: String)

class UpdateChecker(
    private val repoOwner: String = "YOUR_GITHUB_USERNAME",
    private val repoName: String  = "YOUR_REPO_NAME"
) {
    private val client = OkHttpClient()

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            val latestTag = json.getString("tag_name")

            if (!isUpdateAvailable(currentVersion, latestTag)) return@withContext null

            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    return@withContext UpdateInfo(
                        version     = latestTag,
                        downloadUrl = asset.getString("browser_download_url")
                    )
                }
            }
            null
        } catch (e: Exception) {
            null  // Fail silently
        }
    }

    private fun isUpdateAvailable(current: String, latest: String): Boolean {
        val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
```

---

## Admin Flows

- `is_admin` flag lives on the `users` row
- All add/remove operations validated server-side via Row Level Security
- RLS policy: only allow INSERT/DELETE on `users` where requesting user's `is_admin = true`
- Admin creates new member accounts via Edge Function using service role key (bypasses RLS)
- Admin can cancel any active summon
- Admin can edit `config` table values (cooldown duration, max ahead time) from Settings tab
- First admin (you) is bootstrapped manually — see Setup Checklist

---

## Gradle Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.1"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")

    // Ktor engine (required by Supabase SDK)
    implementation("io.ktor:ktor-client-okhttp:2.3.12")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Coil for loading Steam avatars
    implementation("io.coil-kt:coil-compose:2.6.0")

    // OkHttp for GitHub update check
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

Also apply the Google Services plugin in `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services")
}
```

---

## Project Structure (suggested)

```
app/
└── src/main/
    ├── java/com/kvi/osquio/
    │   ├── MainActivity.kt
    │   ├── MyFirebaseService.kt
    │   ├── data/
    │   │   ├── SupabaseClient.kt         // singleton client setup
    │   │   ├── UserRepository.kt
    │   │   ├── SummonRepository.kt
    │   │   ├── RsvpRepository.kt
    │   │   ├── HistoryRepository.kt
    │   │   └── ConfigRepository.kt
    │   ├── ui/
    │   │   ├── auth/
    │   │   │   ├── LoginScreen.kt
    │   │   │   └── LoginViewModel.kt
    │   │   ├── summon/
    │   │   │   ├── SummonScreen.kt
    │   │   │   └── SummonViewModel.kt
    │   │   ├── stats/
    │   │   │   ├── StatsScreen.kt
    │   │   │   └── StatsViewModel.kt
    │   │   ├── rankings/
    │   │   │   ├── RankingsScreen.kt
    │   │   │   └── RankingsViewModel.kt
    │   │   ├── history/
    │   │   │   ├── HistoryScreen.kt
    │   │   │   ├── HistoryDayScreen.kt
    │   │   │   ├── HistoryDetailScreen.kt
    │   │   │   └── HistoryViewModel.kt
    │   │   └── settings/
    │   │       ├── SettingsScreen.kt
    │   │       └── SettingsViewModel.kt
    │   └── util/
    │       ├── SteamApi.kt
    │       └── UpdateChecker.kt
    └── res/
supabase/
└── functions/
    ├── notify-summon/
    │   └── index.ts
    ├── close-summon/
    │   └── index.ts
    ├── validate-rsvp/
    │   └── index.ts
    └── create-member/
        └── index.ts                      // admin creates auth account + users row
```

---

## Setup Checklist

### Firebase
- [x] Create project at console.firebase.google.com
- [x] Add Android app with package name `com.kvi.osquio`
- [x] Download `google-services.json`
- [x] Download service account JSON from Project Settings → Service Accounts
- [ ] Place `google-services.json` in `app/` folder when Android project is created

### Supabase
- [x] Create project (Singapore region)
- [x] Create tables: `users`, `summons`, `rsvps`, `summon_history`, `config`
- [x] Enable Realtime on `rsvps` and `summons` tables
- [x] RLS enabled on all tables with policies applied
- [ ] Run schema migration: add `auth_id` column to `users` table
- [ ] Disable email confirmations in Supabase Auth settings
- [ ] Disable public sign-ups in Supabase Auth settings
- [ ] Create Edge Functions: `notify-summon`, `close-summon`, `validate-rsvp`, `create-member`
- [ ] Set env vars: `FIREBASE_SERVICE_ACCOUNT` (full JSON), `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_URL`
- [ ] Create Database Webhook: trigger `notify-summon` on INSERT to `summons`
- [ ] Create Database Webhook: trigger `close-summon` on UPDATE to `summons` where status changes
- [ ] Bootstrap your own account: create auth user manually in Supabase Auth dashboard → insert row into `users` with matching `auth_id` and `is_admin = true`

### Android project
- [ ] Create project in Android Studio with package `com.kvi.osquio`
- [ ] Add `google-services.json` to `app/`
- [ ] Add Gradle dependencies above
- [ ] Add `SUPABASE_URL` and `SUPABASE_ANON_KEY` to `local.properties` (never commit)
- [ ] Implement `MyFirebaseService`
- [ ] Register service in `AndroidManifest.xml`
- [ ] Set `repoOwner` and `repoName` in `UpdateChecker.kt`
- [ ] Confirm `versionName` in `build.gradle.kts` matches release tag format (e.g. `1.0.0`)
- [ ] On every release: tag as `v1.x.x` on GitHub and attach the APK as a release asset

---

## Free Tier Limits (Supabase + Firebase)

| Resource | Free limit | Expected usage (15 users) |
|---|---|---|
| Supabase DB | 500 MB | Well under |
| Supabase Realtime | 2 GB / month | Well under |
| Supabase Edge Functions | 500K invocations / month | Well under |
| Firebase FCM | Unlimited | Free forever |
| Steam public XML | No limit | Free forever |

You will not hit any free tier limits with a group of 5–15 people.

---

## Future Ideas (Out of Scope for Now)

- Recurring summons (e.g. every Friday 9pm) — needs a `recurring_summons` table
- Reminder notification partway through summon window — scheduled Edge Function
- Per-summon chat or reactions
- Minimum headcount threshold before a summon is considered valid
- Push notification for "game starts in 5 minutes"
- Password change screen for members on first login