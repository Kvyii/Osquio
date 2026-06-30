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
  const { message_id, sender_id, sender_name, mentioned_user_ids } = await req.json()

  if (!message_id || !sender_id || !sender_name || !mentioned_user_ids?.length) {
    return new Response(JSON.stringify({ error: 'Missing required fields' }), { status: 400 })
  }

  let query = supabase.from('users').select('id, fcm_token').not('fcm_token', 'is', null).neq('id', sender_id)

  if (!(mentioned_user_ids.length === 1 && mentioned_user_ids[0] === 'all')) {
    query = query.in('id', mentioned_user_ids)
  }

  const { data: users, error } = await query

  if (error) {
    return new Response(JSON.stringify({ error: error.message }), { status: 500 })
  }

  if (!users || users.length === 0) {
    return new Response(JSON.stringify({ ok: true, sent: 0 }), { status: 200 })
  }

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
            android: { priority: 'normal' },
            data: {
              type: 'mention',
              sender_name,
              message_id,
            },
          },
        }),
      })
    )
  )

  return new Response(JSON.stringify({ ok: true, sent: users.length }), { status: 200 })
})
