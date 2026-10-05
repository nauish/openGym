import { useStore } from '../store/useStore.js'
import { useUI } from '../store/useUI.js'
import { nextOpenSet } from './workout-keys.js'
import { toggleSide, isWarmupRow } from './workout-model.js'
import { supersetUnits, bestWeightForEntry, setsDoneActive, setUnitsTotal } from './history.js'
import { nextUnfinishedUnit, supersetFlowStep, restAfterSet, restSecFor, warmupRestSecFor } from './supersetFlow.js'
import { ackPendingWorkoutAction, buildWorkoutNotification, readPendingWorkoutState, syncWorkoutNotification } from './rest-alert.js'
import { appliedWorkoutActionId, buildWorkoutActionPlan, cloneActiveWorkout } from './workout-action-plan.js'
import { beep, vibrate } from './sound.js'
import { nav } from './nav.js'
import { t } from './i18n.js'

/**
 * Completes the current open set in the active workout and transitions to rest if earned.
 * Invoked by notification / HyperIsland "Done" action button or hardware/native controls.
 */
export function completeCurrentWorkoutSet() {
  const store = useStore.getState()
  const active = store.S?.active
  if (!active || !Array.isArray(active.entries)) return false
  const next = nextOpenSet(active.entries, active.cur)
  if (!next) {
    // Also recover a workout whose last set was checked before this completion flow existed.
    // The notification command also handles empty (0/0) sessions.
    finishCompletedWorkout(active.id)
    return true
  }
  const { idx, i, side } = next

  let checked = false
  store.update(s => {
    const e = s.active?.entries?.[idx]
    if (!e || !e.sets?.[i]) return
    if (side) e.sets[i] = toggleSide(e.sets[i], side)
    else e.sets[i].done = true
    checked = e.sets[i].done
    if (checked && e.sets[i].planSec != null) delete e.sets[i].planSec
    if (e.sets[i].done) {
      if (e.sets.every(x => x.done)) {
        e.topW = bestWeightForEntry(e) || null
      }
    }
  }, true)

  beep(store.S?.sound, 1040, 0.12)
  vibrate(30)

  const fresh = useStore.getState().S?.active
  if (fresh && checked && fresh.entries?.[idx]) {
    const freshUnits = supersetUnits(fresh.entries)
    const freshUnit = freshUnits.find(u => u.includes(idx))
    const freshUnitDone = freshUnit?.every(ui => fresh.entries[ui].sets.every(x => x.done))
    const nextUnit = freshUnitDone ? nextUnfinishedUnit(fresh.entries, freshUnits, idx) : null
    const freshWorkoutDone = freshUnitDone && !nextUnit
    if (freshWorkoutDone) {
      // The final Done commits the workout, rather than starting another rest or leaving an
      // all-checked active session behind. Keep the same history/PR/backup flow as the app.
      useUI.getState().stopRest()
      finishCompletedWorkout(fresh.id)
      return true
    }
    const restBeforeWarmup = nextUnit?.some(ui =>
      fresh.entries[ui].sets.some(set => isWarmupRow(set) && !set.done),
    )
    const restSec = restSecFor(fresh.entries, freshUnit || [idx], store.S.restSec)
    const restAfter = warmupRestSecFor(fresh.entries[idx], i, restSec)

    if (freshUnitDone) useUI.getState().stopRest()
    if (!freshUnit || freshUnit.length <= 1) {
      if (!restBeforeWarmup && restAfterSet({ unitDone: freshUnitDone, lastUnit: freshWorkoutDone })) {
        useUI.getState().startRest(restAfter, idx)
      } else {
        const notice = buildWorkoutNotification(fresh, {
          setsDone: setsDoneActive(fresh),
          setsTotal: setUnitsTotal(fresh.entries),
          accent: store.S?.accent,
          defaultRestSec: store.S?.restSec,
          sound: !!store.S?.sound,
          vibrate: store.S?.vibrate !== false,
        })
        if (notice) syncWorkoutNotification(notice).catch(() => {})
      }
      return true
    }

    const step = supersetFlowStep(fresh.entries, freshUnit, idx)
    if (step) {
      if (step.unitDone) {
        if (nextUnit?.length && !restBeforeWarmup) {
          useUI.getState().startRest(restAfter, idx)
        } else {
          const notice = buildWorkoutNotification(fresh, {
            setsDone: setsDoneActive(fresh),
            setsTotal: setUnitsTotal(fresh.entries),
            accent: store.S?.accent,
            defaultRestSec: store.S?.restSec,
            sound: !!store.S?.sound,
            vibrate: store.S?.vibrate !== false,
          })
          if (notice) syncWorkoutNotification(notice).catch(() => {})
        }
      } else {
        if (step.nextIdx != null) store.update(s => { if (s.active) s.active.cur = step.nextIdx })
        if (step.roundDone) {
          useUI.getState().startRest(restAfter, idx)
        } else {
          const notice = buildWorkoutNotification(fresh, {
            setsDone: setsDoneActive(fresh),
            setsTotal: setUnitsTotal(fresh.entries),
            accent: store.S?.accent,
            defaultRestSec: store.S?.restSec,
            sound: !!store.S?.sound,
            vibrate: store.S?.vibrate !== false,
          })
          if (notice) syncWorkoutNotification(notice).catch(() => {})
        }
      }
    }
  }
  return true
}

/**
 * Apply one native Done command by replaying the current plan head into the durable workout
 * store. It intentionally does not start, stop, or adjust rest: Android already committed that
 * transition and may still be counting down while the WebView catches up.
 */
export async function applyPendingWorkoutAction(command, { nativeSessionId, nativeFinalizing } = {}) {
  const sessionId = String(command?.sessionId || '')
  const actionId = String(command?.actionId || '')
  const state = useStore.getState()
  const active = state.S?.active
  if (!sessionId || !actionId) return { applied: false, stale: false, blocked: true, reason: 'invalid-command' }

  if (String(active?.id || '') !== sessionId || !Array.isArray(active?.entries)) {
    const completedWorkout = (state.S?.workouts || []).find(workout => String(workout.id) === sessionId)
    const allSavedSetsDone = completedWorkout && setsDoneActive(completedWorkout) === setUnitsTotal(completedWorkout.entries)
    const emptyFinalized = !active && command.final && nativeFinalizing && String(nativeSessionId || '') === sessionId
    if ((allSavedSetsDone || emptyFinalized) && command.final) {
      const durable = await useStore.getState().flushNativePersistence()
      return durable
        ? { applied: false, alreadyApplied: true, stale: false, final: true, durable: true }
        : { applied: false, stale: false, blocked: true, reason: 'mirror-write-failed', durable: false }
    }
    // If Android has already replaced the old workout card, its queued action cannot be applied
    // to the new active session. Retire it only in that explicit stale-session case.
    if (nativeSessionId && String(nativeSessionId) !== sessionId) {
      return { applied: false, stale: true, acknowledge: true, final: false }
    }
    return { applied: false, stale: false, blocked: true, reason: 'session-mismatch' }
  }

  const defaultRestSec = state.S?.restSec
  if (actionId.startsWith('wa:') && appliedWorkoutActionId(active) === actionId) {
    const durable = await useStore.getState().flushNativePersistence()
    if (!durable) return { applied: false, alreadyApplied: true, stale: false, blocked: true, reason: 'mirror-write-failed', durable: false }
    if (command.final) {
      const finished = await finishCompletedWorkout(sessionId)
      if (!finished) return { applied: false, alreadyApplied: true, stale: false, blocked: true, reason: 'finish-incomplete', durable: true }
      const finalDurable = await useStore.getState().flushNativePersistence()
      if (!finalDurable) return { applied: false, alreadyApplied: true, stale: false, blocked: true, reason: 'mirror-write-failed', durable: false }
    }
    return { applied: false, alreadyApplied: true, stale: false, final: !!command.final, durable: true }
  }

  const steps = buildWorkoutActionPlan(active, { defaultRestSec })
  const step = steps[0]
  if (!step || step.actionId !== actionId || !!command.final !== !!step.final) {
    return { applied: false, stale: false, blocked: true, reason: 'action-mismatch' }
  }

  state.update(s => {
    if (String(s.active?.id || '') !== sessionId) return
    s.active = cloneActiveWorkout(step.afterActive)
  }, true)

  const durable = await useStore.getState().flushNativePersistence()
  if (!durable) return { applied: true, stale: false, blocked: true, reason: 'mirror-write-failed', final: step.final, durable: false, entryIdx: step.entryIdx }
  if (step.final) {
    const finished = await finishCompletedWorkout(sessionId)
    if (!finished) return { applied: true, stale: false, blocked: true, reason: 'finish-incomplete', final: true, durable: true, entryIdx: step.entryIdx }
    const finalDurable = await useStore.getState().flushNativePersistence()
    if (!finalDurable) return { applied: true, stale: false, blocked: true, reason: 'mirror-write-failed', final: true, durable: false, entryIdx: step.entryIdx }
  }
  return { applied: true, stale: false, final: step.final, durable: true, entryIdx: step.restForIdx }
}

let reconciliation = Promise.resolve()
const recoveryListeners = new Set()
let recoveryRuns = 0

export function subscribeWorkoutActionRecovery(listener) {
  if (typeof listener !== 'function') return () => {}
  recoveryListeners.add(listener)
  return () => recoveryListeners.delete(listener)
}

function emitWorkoutActionRecovery(event) {
  for (const listener of recoveryListeners) {
    try { listener(event) } catch (error) { console.error('Workout recovery listener failed', error) }
  }
}

function settleWorkoutActionRecovery(result) {
  recoveryRuns = Math.max(0, recoveryRuns - 1)
  if (recoveryRuns === 0) emitWorkoutActionRecovery({ state: 'settled', result })
}

/**
 * Recover native Done actions after boot or app resume. Every queued action must match the
 * regenerated plan head before it can mutate the workout. Already-applied commands are
 * acknowledged idempotently; same-session mismatches stay queued for recovery. The fresh
 * notification is pushed after all acknowledgements.
 */
export function reconcilePendingWorkoutActions() {
  recoveryRuns++
  emitWorkoutActionRecovery({ state: 'pending' })
  const run = reconciliation.then(reconcileWorkoutActions, reconcileWorkoutActions)
  const settled = run.then(result => {
    settleWorkoutActionRecovery(result)
    return result
  }, error => {
    settleWorkoutActionRecovery({ available: false, blocked: true })
    throw error
  })
  reconciliation = settled.then(() => {}, () => {})
  return settled
}

async function reconcileWorkoutActions() {
  if (!useStore.getState().ready) return { applied: 0, alreadyApplied: 0, staleSessions: 0, blocked: false, available: false }
  const native = await readPendingWorkoutState()
  if (!native) return { applied: 0, alreadyApplied: 0, staleSessions: 0, blocked: false, available: false }
  const actions = Array.isArray(native.actions) ? native.actions : []
  let applied = 0
  let alreadyApplied = 0
  let staleSessions = 0
  let blocked = false
  let restForIdx = null

  for (const command of actions) {
    const outcome = await applyPendingWorkoutAction(command, {
      nativeSessionId: native.nativeSessionId,
      nativeFinalizing: native.finalizing,
    })
    if (outcome.blocked) {
      blocked = true
      console.warn('Native workout action remains pending', { reason: outcome.reason, sessionId: command?.sessionId, actionId: command?.actionId })
      break
    }
    if (outcome.applied) applied++
    if (outcome.alreadyApplied) alreadyApplied++
    if (outcome.stale) staleSessions++
    if (outcome.entryIdx != null) restForIdx = outcome.entryIdx
    // Never drop a valid native command until the local store update above has completed.
    const acknowledged = await ackPendingWorkoutAction(command)
    if (!acknowledged) {
      blocked = true
      console.warn('Native workout action acknowledgement failed; it will be retried', { sessionId: command?.sessionId, actionId: command?.actionId })
      break
    }
  }

  // Restore the visible timer from Android's persisted deadline. This only mirrors native state;
  // it must not issue another alarm or replace the countdown that is already running there.
  const nativeRest = native.rest
  if (nativeRest?.sessionId && String(useStore.getState().S?.active?.id || '') === String(nativeRest.sessionId)) {
    const left = Math.max(0, Math.ceil((nativeRest.leftMs || 0) / 1000))
    if (left > 0) {
      const ownerIdx = Number.isInteger(nativeRest.forIdx) && nativeRest.forIdx >= 0 ? nativeRest.forIdx : restForIdx
      useUI.getState().followNativeRest({
        endsAt: nativeRest.endsAt,
        left,
        total: Math.max(left, Math.ceil((nativeRest.totalMs || 0) / 1000)),
        paused: !!nativeRest.paused,
        forIdx: Number.isInteger(nativeRest.forIdx) && nativeRest.forIdx >= 0 ? nativeRest.forIdx : ownerIdx,
      })
    }
  }

  if (actions.length && !blocked) await syncCurrentWorkoutNotification()
  return { applied, alreadyApplied, staleSessions, blocked, available: true, finalizing: !!native.finalizing }
}

async function syncCurrentWorkoutNotification() {
  const { S } = useStore.getState()
  const tm = useUI.getState().timer
  const notice = buildWorkoutNotification(S?.active, {
    setsDone: setsDoneActive(S?.active),
    setsTotal: setUnitsTotal(S?.active?.entries),
    accent: S?.accent,
    restForIdx: tm && !tm.ready ? tm.forIdx : null,
    defaultRestSec: S?.restSec,
    sound: !!S?.sound,
    vibrate: S?.vibrate !== false,
  })
  await syncWorkoutNotification(notice)
}

// Load the existing finish flow lazily: sheets imports useUI, which imports this command.
// Re-check both identity and completion after loading, so a queued action cannot finish a new
// session or a workout where the user has since added/unchecked a set.
async function finishCompletedWorkout(sessionId) {
  try {
    const { finishWorkout } = await import('../sheets.jsx')
    const active = useStore.getState().S?.active
    if (active?.id !== sessionId || nextOpenSet(active.entries, active.cur)) return false
    if (setUnitsTotal(active.entries) === 0) {
      useStore.getState().update(s => { s.active = null })
      useUI.getState().stopRest()
      useUI.getState().stopWork()
      nav('/home')
      useUI.getState().toast(t('Workout ended. No exercises to save.'))
    } else {
      finishWorkout()
    }
    // Clear the native timer immediately, including when the WebView is in the background and
    // the App effect that normally mirrors S.active cannot repaint yet.
    if (!useStore.getState().S?.active) syncWorkoutNotification(null).catch(() => {})
    const finishedState = useStore.getState().S
    const saved = finishedState?.workouts?.some(workout => String(workout.id) === String(sessionId))
    return !finishedState?.active && (!!saved || setUnitsTotal(active.entries) === 0)
  } catch (error) {
    console.error('Could not finish workout from notification', error)
    return false
  }
}
