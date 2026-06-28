import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { GoogleAuth } from 'https://esm.sh/google-auth-library@9'

const supabase = createClient(
  Deno.env.get('SUPABASE_URL')!,
  Deno.env.get('SERVICE_ROLE_KEY')!,
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

async function sendRsvpUpdateToAll(summonId: string, accessToken: string) {
  const { data: users } = await supabase
    .from('users')
    .select('fcm_token')
    .not('fcm_token', 'is', null)

  if (!users || users.length === 0) return

  await Promise.allSettled(
    users.map((user) =>
      fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
        body: JSON.stringify({
          message: {
            token: user.fcm_token,
            data: {
              type: 'summon_closed',
              summon_id: summonId,
            },
            android: {
              priority: 'high',
            },
          },
        }),
      })
    )
  )
}

Deno.serve(async (req) => {
  const { record, old_record } = await req.json()

  // Only act when status transitions to a closed state
  if (record.status === old_record?.status) {
    return new Response(JSON.stringify({ ok: true, skipped: true }), { status: 200 })
  }
  if (record.status !== 'expired' && record.status !== 'cancelled') {
    return new Response(JSON.stringify({ ok: true, skipped: true }), { status: 200 })
  }

  const summonId = record.id

  // Fetch all RSVPs for this summon
  const { data: rsvps, error: rsvpError } = await supabase
    .from('rsvps')
    .select('user_id, response, response_time, responded_at')
    .eq('summon_id', summonId)

  if (rsvpError) {
    console.error('Failed to fetch rsvps:', rsvpError)
    return new Response(JSON.stringify({ error: rsvpError.message }), { status: 500 })
  }

  // Fetch all users for non-respondent list + display names
  const { data: allUsers, error: usersError } = await supabase
    .from('users')
    .select('id, display_name, avatar_url')

  if (usersError) {
    console.error('Failed to fetch users:', usersError)
    return new Response(JSON.stringify({ error: usersError.message }), { status: 500 })
  }

  const respondedUserIds = new Set((rsvps ?? []).map((r) => r.user_id))
  const userMap = new Map((allUsers ?? []).map((u) => [u.id, u]))

  const respondents = (rsvps ?? []).map((r) => {
    const user = userMap.get(r.user_id)
    return {
      user_id: r.user_id,
      display_name: user?.display_name ?? 'Unknown',
      avatar_url: user?.avatar_url ?? null,
      response: r.response,
      response_time: r.response_time ?? null,
      responded_at: r.responded_at,
    }
  })

  const nonRespondents = (allUsers ?? [])
    .filter((u) => !respondedUserIds.has(u.id))
    .map((u) => ({
      user_id: u.id,
      display_name: u.display_name,
      avatar_url: u.avatar_url ?? null,
    }))

  const snapshot = { respondents, non_respondents: nonRespondents }

  // Write immutable history record
  const { error: historyError } = await supabase.from('summon_history').insert({
    summon_id: summonId,
    summoner_id: record.created_by,
    game_time: record.game_time,
    created_at: record.created_at,
    closed_at: new Date().toISOString(),
    status: record.status,
    cancelled_by: record.cancelled_by ?? null,
    snapshot,
  })

  if (historyError) {
    console.error('Failed to write summon_history:', historyError)
    return new Response(JSON.stringify({ error: historyError.message }), { status: 500 })
  }

  // Apply cooldown only on cancellation, not natural expiry
  if (record.status === 'cancelled') {
    const { data: config } = await supabase
      .from('config')
      .select('summon_cooldown_seconds')
      .single()

    const cooldownSeconds = config?.summon_cooldown_seconds ?? 900
    const cooldownUntil = new Date(Date.now() + cooldownSeconds * 1000).toISOString()

    await supabase
      .from('users')
      .update({ cooldown_until: cooldownUntil })
      .eq('id', record.created_by)
  }

  // Notify all clients via silent FCM so lobby screens refresh
  try {
    const accessToken = await getFcmAccessToken()
    await sendRsvpUpdateToAll(summonId, accessToken)
  } catch (e) {
    console.error('FCM silent push failed (non-fatal):', e)
  }

  return new Response(JSON.stringify({ ok: true }), { status: 200 })
})
