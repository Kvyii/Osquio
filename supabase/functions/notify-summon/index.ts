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

Deno.serve(async (req) => {
  const { record, old_record, type } = await req.json()

  // Webhook updates (cancel/expire) include old_record — skip them
  // Rebeacon calls from the app pass user_id and never include old_record
  if (old_record != null) {
    return new Response(JSON.stringify({ ok: true, skipped: true }), { status: 200 })
  }

  const summonerId = record.user_id ?? record.created_by

  const { data: summoner } = await supabase
    .from('users')
    .select('display_name')
    .eq('id', summonerId)
    .single()

  const { data: rsvps } = await supabase
    .from('rsvps')
    .select('user_id')
    .eq('summon_id', record.id)

  const respondedUserIds = new Set((rsvps ?? []).map((r: { user_id: string }) => r.user_id))

  const { data: users, error } = await supabase
    .from('users')
    .select('fcm_token, id')
    .not('fcm_token', 'is', null)
    .neq('id', summonerId)

  if (error) {
    console.error('Failed to fetch users:', error)
    return new Response(JSON.stringify({ error: error.message }), { status: 500 })
  }

  const eligibleUsers = (users ?? []).filter((u: { fcm_token: string; id: string }) => !respondedUserIds.has(u.id))

  if (eligibleUsers.length === 0) {
    return new Response(JSON.stringify({ ok: true, sent: 0 }), { status: 200 })
  }

  const gameTime = new Date(record.game_time).toLocaleTimeString('en-AU', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
    timeZone: 'Australia/Sydney',
  })

  const accessToken = await getFcmAccessToken()

  const sendPromises = eligibleUsers.map((user: { fcm_token: string; id: string }) =>
    fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token: user.fcm_token,
          android: {
            priority: 'high',
          },
          data: {
            type: 'summon',
            summon_id: record.id,
            game_time: gameTime,
            summoner_name: summoner?.display_name ?? 'Someone',
          },
        },
      }),
    }).then((res) => {
      if (!res.ok) {
        console.error(`FCM send failed for token ${user.fcm_token}:`, res.status)
      }
    })
  )

  await Promise.allSettled(sendPromises)

  return new Response(JSON.stringify({ ok: true, sent: eligibleUsers.length }), { status: 200 })
})
