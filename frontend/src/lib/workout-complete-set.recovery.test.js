// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  nativeState: null,
  acknowledged: [],
  ackSucceeds: true,
  events: [],
}))

vi.mock('./mobile.js', async importOriginal => ({
  ...(await importOriginal()),
  MOBILE: true,
  initReminderSync: vi.fn(),
  nativeSave: vi.fn(async () => true),
  syncReminder: vi.fn(async () => true),
  writeJsonFile: vi.fn(async () => true),
}))
vi.mock('./rest-alert.js', () => ({
  armRestAlert: vi.fn(() => Promise.resolve(true)),
  holdRestAlert: vi.fn(),
  disarmRestAlert: vi.fn(),
  bindNativeRest: vi.fn(),
  buildWorkoutNotification: vi.fn(active => active ? { sessionId: active.id } : null),
  syncWorkoutNotification: vi.fn(async notice => {
    if (notice === null && h.nativeState?.actions?.length) h.events.push(['clear-ignored'])
    else if (notice === null) h.events.push(['clear'])
    else h.events.push(['sync', notice])
    return true
  }),
  readPendingWorkoutState: vi.fn(async () => h.nativeState == null ? null : structuredClone(h.nativeState)),
  ackPendingWorkoutAction: vi.fn(async command => {
    h.acknowledged.push({ ...command })
    h.events.push(['ack', { ...command }])
    if (h.ackSucceeds && h.nativeState) {
      h.nativeState.actions = h.nativeState.actions.filter(action =>
        action.sessionId !== command.sessionId || action.actionId !== command.actionId,
      )
    }
    return h.ackSucceeds
  }),
}))
vi.mock('./sound.js', () => ({ beep: vi.fn(), chime: vi.fn(), vibrate: vi.fn() }))
vi.mock('./nav.js', () => ({ nav: vi.fn() }))
vi.mock('../sheets.jsx', () => ({ finishWorkout: vi.fn() }))

import { useStore } from '../store/useStore.js'
import { useUI } from '../store/useUI.js'
import { buildWorkoutActionPlan } from './workout-action-plan.js'
import {
  ackPendingWorkoutAction,
  armRestAlert,
  readPendingWorkoutState,
  syncWorkoutNotification,
} from './rest-alert.js'
import { applyPendingWorkoutAction, reconcilePendingWorkoutActions } from './workout-complete-set.js'
import { finishWorkout } from '../sheets.jsx'

let originalS

const activeWorkout = (id = 'background-session', count = 2) => ({
  id,
  name: 'Workout',
  start: Date.now(),
  cur: 0,
  entries: [{ id: '0025', sets: Array.from({ length: count }, () => ({ w: 50, r: 8, done: false })) }],
})

const nativeCommand = (active, step = 0) => {
  const planned = buildWorkoutActionPlan(active, { defaultRestSec: useStore.getState().S.restSec })[step]
  return { sessionId: active.id, actionId: planned.actionId, final: planned.final }
}

const nativeState = (actions, sessionId, rest = null) => ({
  actions,
  nativeSessionId: sessionId,
  finalizing: false,
  rest,
})

beforeEach(() => {
  originalS = useStore.getState().S
  useUI.getState().stopRest()
  useUI.setState({ timer: null, work: null })
  useStore.setState({
    ready: true,
    user: null,
    S: { ...originalS, accent: 'lime', restSec: 60, sound: false, vibrate: false, active: activeWorkout() },
  })
  h.nativeState = null
  h.acknowledged = []
  h.ackSucceeds = true
  h.events = []
  for (const f of [ackPendingWorkoutAction, armRestAlert, readPendingWorkoutState, syncWorkoutNotification, finishWorkout]) f.mockClear()
})

afterEach(() => {
  useUI.getState().stopRest()
  useStore.setState({ S: originalS, ready: false, user: null })
})

describe('native Done action recovery', () => {
  it('replays a background completion and mirrors Android’s active rest without arming a second alarm', async () => {
    const active = useStore.getState().S.active
    const command = nativeCommand(active)
    h.nativeState = nativeState([command], active.id, {
      sessionId: active.id,
      endsAt: Date.now() + 30_000,
      totalMs: 60_000,
      leftMs: 30_000,
      paused: false,
    })

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 1, staleSessions: 0, available: true })
    expect(useStore.getState().S.active.entries[0].sets.map(set => set.done)).toEqual([true, false])
    expect(useUI.getState().timer).toMatchObject({ left: 30, total: 60 })
    expect(armRestAlert).not.toHaveBeenCalled()
    expect(ackPendingWorkoutAction).toHaveBeenCalledWith(command)
    expect(syncWorkoutNotification).toHaveBeenCalledTimes(1)

    // A subsequent resume after acknowledgement must not complete the next set.
    await reconcilePendingWorkoutActions()
    expect(useStore.getState().S.active.entries[0].sets.map(set => set.done)).toEqual([true, false])
  })

  it('replays multiple queued sets in plan order, with each next action matching the new plan head', async () => {
    const active = activeWorkout('multi-session', 3)
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const commands = [nativeCommand(active, 0), nativeCommand(active, 1)]
    h.nativeState = nativeState(commands, active.id, {
      sessionId: active.id,
      endsAt: Date.now() + 20_000,
      totalMs: 60_000,
      leftMs: 20_000,
      paused: false,
    })

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 2, staleSessions: 0 })
    expect(useStore.getState().S.active.entries[0].sets.map(set => set.done)).toEqual([true, true, false])
    expect(h.acknowledged).toEqual(commands)
    expect(useUI.getState().timer).toMatchObject({ left: 20, total: 60 })
  })

  it('replays the same set after rest settings change and keeps Android’s captured rest duration', async () => {
    const active = activeWorkout('rest-policy-session', 2)
    active.entries[0].target = { restSec: 90 }
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const command = nativeCommand(active)
    h.nativeState = nativeState([command], active.id, {
      sessionId: active.id,
      endsAt: Date.now() + 20_000,
      totalMs: 90_000,
      leftMs: 20_000,
      forIdx: 0,
      paused: false,
    })

    const edited = structuredClone(active)
    edited.entries[0].target.restSec = 15
    useStore.setState({ S: { ...useStore.getState().S, restSec: 30, active: edited } })

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 1, staleSessions: 0 })
    expect(useStore.getState().S.active.entries[0].sets[0].done).toBe(true)
    expect(useUI.getState().timer).toMatchObject({ left: 20, total: 90, forIdx: 0 })
    expect(armRestAlert).not.toHaveBeenCalled()
  })

  it('does not apply an unacknowledged duplicate to the following set', async () => {
    const active = useStore.getState().S.active
    const command = nativeCommand(active)
    h.nativeState = nativeState([command], active.id)
    h.ackSucceeds = false

    expect(await reconcilePendingWorkoutActions()).toMatchObject({ applied: 1, blocked: true })
    expect(useStore.getState().S.active.entries[0].sets.map(set => set.done)).toEqual([true, false])

    // Simulates Android returning the same durable command after JS saved the change but its
    // acknowledgement was interrupted. Its post-action ID proves the first set was saved, so the
    // command can now be acknowledged without running the next set.
    h.ackSucceeds = true
    expect(await reconcilePendingWorkoutActions()).toMatchObject({ applied: 0, alreadyApplied: 1 })
    expect(useStore.getState().S.active.entries[0].sets.map(set => set.done)).toEqual([true, false])
    expect(ackPendingWorkoutAction).toHaveBeenCalledTimes(2)
    expect(h.nativeState.actions).toEqual([])
  })

  it('retains a same-session command that does not match the current plan head', async () => {
    const active = activeWorkout('same-session', 3)
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const laterCommand = nativeCommand(active, 1)
    h.nativeState = nativeState([laterCommand], active.id)

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 0, blocked: true })
    expect(useStore.getState().S.active.entries[0].sets.every(set => !set.done)).toBe(true)
    expect(ackPendingWorkoutAction).not.toHaveBeenCalled()
    expect(h.nativeState.actions).toEqual([laterCommand])
    expect(syncWorkoutNotification).not.toHaveBeenCalled()
  })

  it('discards a command from an old workout without touching the current workout', async () => {
    const current = useStore.getState().S.active
    const command = { sessionId: 'old-session', actionId: 'wa:old', final: false }
    h.nativeState = nativeState([command], current.id, {
      sessionId: 'old-session', endsAt: Date.now() + 30_000, totalMs: 60_000, leftMs: 30_000, paused: false,
    })

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 0, staleSessions: 1 })
    expect(useStore.getState().S.active.id).toBe(current.id)
    expect(useStore.getState().S.active.entries[0].sets.every(set => !set.done)).toBe(true)
    expect(useUI.getState().timer).toBe(null)
    expect(ackPendingWorkoutAction).toHaveBeenCalledWith(command)
  })

  it('passes a final action through the existing workout finish flow', async () => {
    const active = activeWorkout('final-session', 1)
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const command = nativeCommand(active)
    finishWorkout.mockImplementation(() => {
      useStore.getState().update(s => {
        s.workouts = [...(s.workouts || []), s.active]
        s.active = null
      }, true)
    })

    expect(command.final).toBe(true)
    await expect(applyPendingWorkoutAction(command)).resolves.toMatchObject({ applied: true, final: true })
    expect(useStore.getState().S.active).toBe(null)
    expect(useStore.getState().S.workouts.some(workout => workout.id === active.id)).toBe(true)
    expect(finishWorkout).toHaveBeenCalledTimes(1)
  })

  it('recognizes a retried final action after that session has been saved', async () => {
    const active = activeWorkout('saved-final-session', 1)
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const command = nativeCommand(active)
    finishWorkout.mockImplementation(() => {
      useStore.getState().update(s => {
        s.workouts = [...(s.workouts || []), s.active]
        s.active = null
      }, true)
    })

    await expect(applyPendingWorkoutAction(command)).resolves.toMatchObject({ applied: true, final: true })
    expect(finishWorkout).toHaveBeenCalledTimes(1)
    expect(useStore.getState().S.active).toBe(null)

    await expect(applyPendingWorkoutAction(command)).resolves.toMatchObject({ alreadyApplied: true, final: true })
    expect(finishWorkout).toHaveBeenCalledTimes(1)
  })

  it('retries a final notification clear after acknowledging the queued action', async () => {
    const active = activeWorkout('final-clear-session', 1)
    useStore.setState({ S: { ...useStore.getState().S, active } })
    const command = nativeCommand(active)
    h.nativeState = nativeState([command], active.id)
    h.nativeState.finalizing = true
    finishWorkout.mockImplementation(() => {
      useStore.getState().update(s => {
        s.workouts = [...(s.workouts || []), s.active]
        s.active = null
      }, true)
    })

    const result = await reconcilePendingWorkoutActions()

    expect(result).toMatchObject({ applied: 1, blocked: false })
    expect(finishWorkout).toHaveBeenCalledTimes(1)
    expect(h.nativeState.actions).toEqual([])
    // finishCompletedWorkout's initial clear arrives while the durable native command is still
    // queued, so Android keeps the finishing notification. Reconciliation then acknowledges the
    // command and sends the final clear against the now-empty queue.
    expect(h.events).toEqual([
      ['clear-ignored'],
      ['ack', command],
      ['clear'],
    ])
    expect(syncWorkoutNotification).toHaveBeenNthCalledWith(1, null)
    expect(syncWorkoutNotification).toHaveBeenNthCalledWith(2, null)
  })
})
