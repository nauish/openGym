import { useStore } from '../store/useStore.js'
import { useUI } from '../store/useUI.js'
import { nextOpenSet } from './workout-keys.js'
import { toggleSide, isWarmupRow } from './workout-model.js'
import { supersetUnits, bestWeightForEntry, setsDoneActive, setUnitsTotal } from './history.js'
import { nextUnfinishedUnit, supersetFlowStep, restAfterSet, restSecFor, warmupRestSecFor } from './supersetFlow.js'
import { buildWorkoutNotification, syncWorkoutNotification } from './rest-alert.js'
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
          })
          if (notice) syncWorkoutNotification(notice).catch(() => {})
        }
      }
    }
  }
  return true
}

// Load the existing finish flow lazily: sheets imports useUI, which imports this command.
// Re-check both identity and completion after loading, so a queued action cannot finish a new
// session or a workout where the user has since added/unchecked a set.
function finishCompletedWorkout(sessionId) {
  import('../sheets.jsx').then(({ finishWorkout }) => {
    const active = useStore.getState().S?.active
    if (active?.id !== sessionId || nextOpenSet(active.entries, active.cur)) return
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
  }).catch(error => {
    console.error('Could not finish workout from notification', error)
  })
}
