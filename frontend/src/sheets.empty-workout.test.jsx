// @vitest-environment happy-dom
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { DEF, useStore } from './store/useStore.js'
import { useUI } from './store/useUI.js'
import { finishWorkout } from './sheets.jsx'
import { setNav } from './lib/nav.js'
import { syncWorkoutNotification } from './lib/rest-alert.js'
import { completeCurrentWorkoutSet } from './lib/workout-complete-set.js'

vi.mock('./lib/rest-alert.js', async importOriginal => ({
  ...await importOriginal(),
  syncWorkoutNotification: vi.fn().mockResolvedValue(true),
  disarmRestAlert: vi.fn(),
}))

let navigate, toast
beforeEach(() => {
  vi.clearAllMocks()
  navigate = vi.fn()
  toast = vi.fn()
  setNav(navigate)
  useUI.setState({ sheets: [], timer: { left: 30, total: 60 }, work: { left: 10 }, toast })
  useStore.setState({ S: { ...structuredClone(DEF), workouts: [], active: { id: 'empty', start: 1000, entries: [] } }, user: null })
})
afterEach(() => setNav(() => {}))

it('notification Done clears an empty workout and timers without creating a record', async () => {
  completeCurrentWorkoutSet()
  await vi.waitFor(() => expect(useStore.getState().S.active).toBeNull())
  expect(useStore.getState().S.active).toBeNull()
  expect(useStore.getState().S.workouts).toEqual([])
  expect(useUI.getState().timer).toBeNull()
  expect(useUI.getState().work).toBeNull()
  expect(useUI.getState().sheets).toEqual([])
  expect(syncWorkoutNotification).toHaveBeenCalledWith(null)
  expect(navigate).toHaveBeenCalledWith('/home')
  expect(toast).toHaveBeenCalledWith('Workout ended. No exercises to save.')
})

it('keeps confirmation for a workout with exercises but no completed sets', () => {
  useStore.setState(({ S }) => ({ S: { ...S, active: { ...S.active, entries: [{ id: '0025', sets: [{ w: 20, r: 8, done: false }] }] } } }))
  finishWorkout()
  expect(useStore.getState().S.active).not.toBeNull()
  expect(useUI.getState().sheets).toHaveLength(1)
  expect(navigate).not.toHaveBeenCalled()
  expect(syncWorkoutNotification).not.toHaveBeenCalled()
})

it('notification cleanup leaves existing history untouched', async () => {
  const record = { id: 'old', entries: [{ id: '0025', sets: [{ done: true, w: 20, r: 8 }] }] }
  useStore.setState(({ S }) => ({ S: { ...S, workouts: [record], active: { ...S.active, backfill: { replaceId: 'old' } } } }))
  completeCurrentWorkoutSet()
  await vi.waitFor(() => expect(useStore.getState().S.active).toBeNull())
  expect(useStore.getState().S.workouts).toEqual([record])
  expect(useStore.getState().S.active).toBeNull()
})

it.each([{ entries: [] }, { entries: [{ id: '0025', sets: [] }] }])('ends a 0/0 session from notification Done: $entries', async ({ entries }) => {
  useStore.setState(({ S }) => ({ S: { ...S, active: { ...S.active, entries } } }))
  expect(completeCurrentWorkoutSet()).toBe(true)
  await vi.waitFor(() => expect(useStore.getState().S.active).toBeNull())
  expect(useStore.getState().S.workouts).toEqual([])
  expect(useUI.getState().timer).toBeNull()
  expect(useUI.getState().work).toBeNull()
  expect(syncWorkoutNotification).toHaveBeenCalledWith(null)
  expect(navigate).toHaveBeenCalledWith('/home')
})

it('does not end a new session when an empty-session Done is queued', async () => {
  expect(completeCurrentWorkoutSet()).toBe(true)
  const replacement = { id: 'new-session', start: 2000, entries: [] }
  useStore.setState(({ S }) => ({ S: { ...S, active: replacement } }))
  await new Promise(resolve => setTimeout(resolve, 50))
  expect(useStore.getState().S.active).toEqual(replacement)
  expect(syncWorkoutNotification).not.toHaveBeenCalled()
  expect(navigate).not.toHaveBeenCalled()
})
