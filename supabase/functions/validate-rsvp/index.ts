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

function jsonError(message: string, status = 400) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req) => {
  // This function is called by the Android app directly (HTTP POST) before upserting the RSVP.
  // It validates the RSVP, upserts the row, then sends silent FCM to all members.
  const body = await req.json()
  const { summon_id, user_id, response, response_time } = body

  if (!summon_id || !user_id || !response) {
    return jsonError('Missing required fields: summon_id, user_id, response')
  }

  if (!['yes', 'no', 'yes_at_time'].includes(response)) {
    return jsonError('Invalid response value. Must be yes, no, or yes_at_time')
  }

  if (response === 'yes_at_time' && !response_time) {
    return jsonError('response_time is required when response is yes_at_time')
  }

  // Fetch the summon to validate it is still open
  const { data: summon, error: summonError } = await supabase
    .from('summons')
    .select('id, status, game_time')
    .eq('id', summon_id)
    .single()

  if (summonError || !summon) {
    return jsonError('Summon not found', 404)
  }

  if (summon.status !== 'open') {
    return jsonError('Summon is no longer open')
  }

  // Validate yes_at_time window
  if (response === 'yes_at_time' && response_time) {
    const gameTime = new Date(summon.game_time).getTime()
    const rsvpTime = new Date(response_time).getTime()
    if (rsvpTime > gameTime) {
      return jsonError('yes_at_time response_time cannot be after game_time')
    }
  }

  // Upsert the RSVP
  const { error: upsertError } = await supabase.from('rsvps').upsert(
    {
      summon_id,
      user_id,
      response,
      response_time: response === 'yes_at_time' ? response_time : null,
      responded_at: new Date().toISOString(),
    },
    { onConflict: 'summon_id,user_id' }
  )

  if (upsertError) {
    console.error('RSVP upsert failed:', upsertError)
    return new Response(JSON.stringify({ error: upsertError.message }), { status: 500 })
  }

  // Send silent FCM data message to all members so their lobby screens refresh
  try {
    const { data: users } = await supabase
      .from('users')
      .select('fcm_token')
      .not('fcm_token', 'is', null)

    if (users && users.length > 0) {
      const accessToken = await getFcmAccessToken()
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
                  type: 'rsvp_update',
                  summon_id,
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
  } catch (e) {
    console.error('FCM silent push failed (non-fatal):', e)
  }

  return new Response(JSON.stringify({ ok: true }), { status: 200 })
})
