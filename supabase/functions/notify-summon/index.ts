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
  const { record } = await req.json()

  const { data: users, error } = await supabase
    .from('users')
    .select('fcm_token, display_name')
    .not('fcm_token', 'is', null)

  if (error) {
    console.error('Failed to fetch users:', error)
    return new Response(JSON.stringify({ error: error.message }), { status: 500 })
  }

  if (!users || users.length === 0) {
    return new Response(JSON.stringify({ ok: true, sent: 0 }), { status: 200 })
  }

  const gameTime = new Date(record.game_time).toLocaleTimeString('en-AU', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
    timeZone: 'Australia/Sydney',
  })

  const accessToken = await getFcmAccessToken()

  const sendPromises = users.map((user) =>
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
            body: `Game called for ${gameTime}. You in?`,
          },
          android: {
            priority: 'high',
          },
          data: {
            type: 'summon',
            summon_id: record.id,
            game_time: record.game_time,
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

  return new Response(JSON.stringify({ ok: true, sent: users.length }), { status: 200 })
})
