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

Deno.serve(async (req) => {
  // This function is called by the Android app directly (HTTP POST) before upserting the RSVP.
  // It validates the RSVP and upserts the row. Clients receive live updates via Supabase Realtime.
  const body = await req.json()
  const { summon_id, user_id, response, response_time } = body

  if (!summon_id || !user_id || !response) {
    return jsonError('Missing required fields: summon_id, user_id, response')
  }

  if (!['yes', 'no', 'yes_at_time'].includes(response)) {
    return jsonError('Invalid response value. Must be yes, no, or yes_at_time')
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

  // yes_at_time may carry a response_time in [game_time, game_time + 60min], or null (hard maybe).
  if (response === 'yes_at_time' && response_time) {
    const gameTime = new Date(summon.game_time).getTime()
    const rsvpTime = new Date(response_time).getTime()
    if (rsvpTime < gameTime || rsvpTime > gameTime + 60 * 60 * 1000) {
      return jsonError('yes_at_time response_time must be within 60 minutes after game_time')
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

  return new Response(JSON.stringify({ ok: true }), { status: 200 })
})
