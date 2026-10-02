// @vitest-environment happy-dom
import { describe, expect, it, beforeEach, vi } from 'vitest'

vi.mock('./mobile.js', async importOriginal => ({
  ...(await importOriginal()),
  MOBILE: true,
}))
vi.mock('./rest-alert.js', () => ({
  armRestAlert: vi.fn(() => Promise.resolve(true)),
  holdRestAlert: vi.fn(),
  disarmRestAlert: vi.fn(),
  bindNativeRest: vi.fn(),
  buildWorkoutNotification: vi.fn(() => ({})),
  syncWorkoutNotification: vi.fn(() => Promise.resolve(true)),
}))
vi.mock('./sound.js', () => ({ beep: vi.fn(), chime: vi.fn(), vibrate: vi.fn() }))

import { useStore } from '../store/useStore.js'
import { useUI } from '../store/useUI.js'
import { completeCurrentWorkoutSet } from './workout-complete-set.js'

describe('completeCurrentWorkoutSet', () => {
  beforeEach(() => {
    useUI.setState({ timer: null, work: null })
  })

  it('marks current open set as done and starts rest timer', () => {
    useStore.setState({
      S: {
        accent: 'lime',
        restSec: 90,
        active: {
          id: 'test-workout',
          name: 'Chest Day',
          start: Date.now(),
          cur: 0,
          entries: [
            {
              id: '0025',
              sets: [
                { w: 60, r: 10, done: false },
                { w: 60, r: 10, done: false },
              ],
            },
          ],
        },
      },
    })

    const handled = completeCurrentWorkoutSet()
    expect(handled).toBe(true)

    const active = useStore.getState().S.active
    expect(active.entries[0].sets[0].done).toBe(true)
    expect(active.entries[0].sets[1].done).toBe(false)

    // Timer should have started
    const timer = useUI.getState().timer
    expect(timer).toBeTruthy()
    expect(timer.total).toBe(90)
  })

  it('handles last set of workout properly', () => {
    useStore.setState({
      S: {
        accent: 'lime',
        restSec: 90,
        active: {
          id: 'test-workout',
          name: 'Chest Day',
          start: Date.now(),
          cur: 0,
          entries: [
            {
              id: '0025',
              sets: [
                { w: 60, r: 10, done: true },
                { w: 60, r: 10, done: false },
              ],
            },
          ],
        },
      },
    })

    const handled = completeCurrentWorkoutSet()
    expect(handled).toBe(true)

    const active = useStore.getState().S.active
    expect(active.entries[0].sets[1].done).toBe(true)
  })
})
