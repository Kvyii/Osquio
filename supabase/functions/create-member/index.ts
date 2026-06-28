import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const supabase = createClient(
  Deno.env.get('SUPABASE_URL')!,
  Deno.env.get('SERVICE_ROLE_KEY')!,
)

function jsonError(message: string, status = 400) {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function buildSteamUrl(steamId: string): string {
  return /^\d+$/.test(steamId)
    ? `https://steamcommunity.com/profiles/${steamId}/?xml=1`
    : `https://steamcommunity.com/id/${steamId}/?xml=1`
}

async function fetchSteamProfile(
  steamId: string
): Promise<{ displayName: string; avatarUrl: string } | null> {
  try {
    const url = buildSteamUrl(steamId)
    const res = await fetch(url)
    if (!res.ok) return null
    const xml = await res.text()

    const displayName = xml.match(/<steamID><!\[CDATA\[(.+?)\]\]><\/steamID>/)?.[1] ?? null
    const avatarUrl = xml.match(/<avatarFull><!\[CDATA\[(.+?)\]\]><\/avatarFull>/)?.[1] ?? null

    if (!displayName || !avatarUrl) return null
    return { displayName, avatarUrl }
  } catch {
    return null
  }
}

Deno.serve(async (req) => {
  // Verify the calling user is an admin
  const authHeader = req.headers.get('Authorization')
  if (!authHeader) return jsonError('Missing Authorization header', 401)

  const callerClient = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('ANON_KEY')!,
    { global: { headers: { Authorization: authHeader } } }
  )

  const { data: callerAuth, error: authError } = await callerClient.auth.getUser()
  if (authError || !callerAuth.user) return jsonError('Unauthorized', 401)

  const { data: callerProfile, error: profileError } = await supabase
    .from('users')
    .select('is_admin')
    .eq('auth_id', callerAuth.user.id)
    .single()

  if (profileError || !callerProfile?.is_admin) {
    return jsonError('Forbidden — admin only', 403)
  }

  const body = await req.json()
  const { display_name, steam_id, password } = body

  if (!display_name || !steam_id || !password) {
    return jsonError('Missing required fields: display_name, steam_id, password')
  }

  if (password.length < 6) {
    return jsonError('Password must be at least 6 characters')
  }

  // Derive fake email from display name
  const email = `${display_name.toLowerCase().replace(/\s+/g, '')}@osquio.kvi`

  // Create Supabase Auth account using service role (bypasses sign-up restrictions)
  const { data: authData, error: createError } =
    await supabase.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
    })

  if (createError) {
    console.error('Auth createUser failed:', createError)
    return new Response(JSON.stringify({ error: createError.message }), { status: 500 })
  }

  // Fetch Steam profile for initial cache
  const steam = await fetchSteamProfile(steam_id)

  // Insert users row
  const { error: insertError } = await supabase.from('users').insert({
    auth_id: authData.user.id,
    steam_id,
    display_name: steam?.displayName ?? display_name,
    avatar_url: steam?.avatarUrl ?? null,
    cache_at: steam ? new Date().toISOString() : null,
    is_admin: false,
  })

  if (insertError) {
    console.error('users insert failed:', insertError)
    // Clean up the orphaned auth user so the admin can retry
    await supabase.auth.admin.deleteUser(authData.user.id)
    return new Response(JSON.stringify({ error: insertError.message }), { status: 500 })
  }

  return new Response(
    JSON.stringify({ ok: true, email }),
    { status: 201, headers: { 'Content-Type': 'application/json' } }
  )
})
