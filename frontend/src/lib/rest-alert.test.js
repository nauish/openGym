import { describe, expect, it } from 'vitest'
import { armRestAlert, buildRestAlert, buildWorkoutNotification, disarmRestAlert, REST_ALERT_ID, REST_CHANNEL_ID, REST_QUIET_CHANNEL_ID } from './rest-alert.js'

describe('buildRestAlert', () => {
  const now = 1_700_000_000_000

  it('schedules a public notification', () => {
    const alert = buildRestAlert({ at: now + 90_000, title: 'Rest over — next set!', sound: true, now })
    expect(alert).toMatchObject({
      id: REST_ALERT_ID,
      channelId: REST_CHANNEL_ID,
      title: 'Rest over — next set!',
      at: now + 90_000,
      allowWhileIdle: true,
      countdownTitle: 'Rest',
      totalMs: 90_000,
      sound: true,
      localOnly: false,
      visibility: 'public',
      importance: 'high',
    })
  })

  it('paints the notification with the chosen accent, not the default green', () => {
    const alert = buildRestAlert({ at: now + 1000, accent: 'red', now })
    expect(alert.accent).toBe((0xff000000 | 0xff453a) >>> 0)
    expect(alert.ink).toBe((0xff000000 | 0xffffff) >>> 0)
  })

  it('still schedules when sound is off', () => {
    expect(buildRestAlert({ at: now + 1000, sound: false, now }).sound).toBe(false)
  })

  // A channel keeps the vibration it was created with, so Vibrate off cannot switch 'rest-over'
  // off: that end goes out on a channel that never buzzes.
  it('buzzes on the rest channel by default, and uses the quiet one when Vibrate is off', () => {
    expect(buildRestAlert({ at: now + 1000, now })).toMatchObject({ vibrate: true, channelId: REST_CHANNEL_ID })
    expect(buildRestAlert({ at: now + 1000, vibrate: false, now })).toMatchObject({ vibrate: false, channelId: REST_QUIET_CHANNEL_ID })
    expect(REST_QUIET_CHANNEL_ID).not.toBe(REST_CHANNEL_ID)
  })

  it('refuses a deadline that has already passed', () => {
    expect(buildRestAlert({ at: now, now })).toBe(null)
    expect(buildRestAlert({ at: now - 1, now })).toBe(null)
  })
})

describe('rest alert outside the mobile build', () => {
  it('does not schedule an alarm, so the caller keeps the server push', async () => {
    await expect(armRestAlert(Date.now() + 90_000, { title: 'Rest over', sound: true })).resolves.toBe(false)
    disarmRestAlert()
  })
})

describe('buildWorkoutNotification', () => {
  it('extracts current exercise name and set progress', () => {
    const active = {
      id: 'active-123',
      name: 'Leg Day',
      start: 1_700_000_000_000,
      cur: 0,
      entries: [
        {
          id: '0025',
          sets: [
            { w: 100, r: 5, done: true },
            { w: 100, r: 5, done: false },
            { w: 100, r: 5, done: false },
          ],
        },
      ],
    }
    const notif = buildWorkoutNotification(active, { setsDone: 1, setsTotal: 3, accent: 'lime' })
    expect(notif).toMatchObject({
      sessionId: 'active-123',
      title: 'Leg Day',
      setsDone: 1,
      setsTotal: 3,
      completeLabel: 'Done',
    })
    expect(notif.exerciseName).toBeTruthy()
    expect(notif.setProgress).toContain('2/3')
  })
})
