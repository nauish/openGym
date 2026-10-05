// Pure projection of the ordered Done actions for an active workout. Android needs to know
// what each tap will do while the WebView is asleep, so these decisions follow the same
// nextOpenSet/superset/rest rules as the in-app completion flow.
import { nextOpenSet } from './workout-keys.js'
import { toggleSide, isWarmupRow, isSideSet } from './workout-model.js'
import { supersetUnits, bestWeightForEntry } from './history.js'
import { nextUnfinishedUnit, supersetFlowStep, restAfterSet, restSecFor, warmupRestSecFor } from './supersetFlow.js'

const cloneSet = set => ({
  ...set,
  ...(set?.sides ? { sides: { ...set.sides, L: { ...set.sides.L }, R: { ...set.sides.R } } } : {}),
  ...(Array.isArray(set?.drops) ? { drops: set.drops.map(row => ({ ...row })) } : {}),
  ...(Array.isArray(set?.clusters) ? { clusters: set.clusters.map(row => ({ ...row })) } : {}),
})

export function cloneActiveWorkout(active) {
  return {
    ...active,
    entries: (active?.entries || []).map(entry => ({
      ...entry,
      ...(entry?.target ? { target: { ...entry.target } } : {}),
      sets: (entry?.sets || []).map(cloneSet),
    })),
  }
}

// An action ID identifies the workout state immediately before the tap. The next planned ID
// therefore becomes the head after the previous action is replayed into the JS store.
function progressSignature(active) {
  const shape = {
    sessionId: String(active?.id || ''),
    cur: Number.isInteger(active?.cur) ? active.cur : 0,
    entries: (active?.entries || []).map(entry => ({
      id: String(entry?.id || ''),
      sg: String(entry?.sg || ''),
      sets: (entry?.sets || []).map(set => ({
        side: isSideSet(set),
        done: set?.done === true,
        sides: isSideSet(set) ? [set.sides.L.done === true, set.sides.R.done === true] : null,
      })),
    })),
  }
  const raw = JSON.stringify(shape)
  let hash = 2166136261
  let hash2 = 0x9e3779b9
  for (let i = 0; i < raw.length; i++) {
    hash ^= raw.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
    hash2 ^= raw.charCodeAt(i) + ((i & 255) << 8)
    hash2 = Math.imul(hash2, 2246822519)
  }
  return `${(hash >>> 0).toString(36)}.${(hash2 >>> 0).toString(36)}`
}

export function appliedWorkoutActionId(active) {
  return `wa:${progressSignature(active)}`
}

function completeProjectedSet(active, { idx, i, side }) {
  const row = active.entries?.[idx]?.sets?.[i]
  if (!row) return false
  if (side) active.entries[idx].sets[i] = toggleSide(row, side)
  else row.done = true
  const completed = active.entries[idx].sets[i]
  if (completed.done && completed.planSec != null) delete completed.planSec
  if (completed.done && active.entries[idx].sets.every(set => set.done)) {
    active.entries[idx].topW = bestWeightForEntry(active.entries[idx]) || null
  }
  return completed.done === true
}

function completionOutcome(active, { idx, i }, defaultRestSec, checked) {
  if (!checked) return { restSec: 0, final: false }

  const units = supersetUnits(active.entries)
  const unit = units.find(candidate => candidate.includes(idx))
  const unitDone = unit?.every(entryIdx => active.entries[entryIdx].sets.every(set => set.done))
  const nextUnit = unitDone ? nextUnfinishedUnit(active.entries, units, idx) : null
  const final = !!unitDone && !nextUnit
  if (final) return { restSec: 0, final: true }

  const restBeforeWarmup = nextUnit?.some(entryIdx =>
    active.entries[entryIdx].sets.some(set => isWarmupRow(set) && !set.done),
  )
  const restSec = restSecFor(active.entries, unit || [idx], defaultRestSec)
  const restAfter = warmupRestSecFor(active.entries[idx], i, restSec)

  if (!unit || unit.length <= 1) {
    return {
      restSec: !restBeforeWarmup && restAfterSet({ unitDone, lastUnit: final }) ? restAfter : 0,
      final,
    }
  }

  const step = supersetFlowStep(active.entries, unit, idx)
  if (!step) return { restSec: 0, final }
  if (step.unitDone) {
    return { restSec: nextUnit?.length && !restBeforeWarmup ? restAfter : 0, final }
  }
  if (step.nextIdx != null) active.cur = step.nextIdx
  return { restSec: step.roundDone ? restAfter : 0, final }
}

/**
 * Return ordered native Done actions from the active state. Each step also carries `afterActive`
 * for replay; callers should send only the public action fields to Android.
 */
export function buildWorkoutActionPlan(active, { defaultRestSec = 0 } = {}) {
  if (!active?.id || !Array.isArray(active.entries)) return []

  let projected = cloneActiveWorkout(active)
  const actions = []
  const maxActions = projected.entries.reduce((n, entry) => n + (entry.sets?.length || 0) * 2, 0) + 1

  for (let count = 0; count < maxActions; count++) {
    const next = nextOpenSet(projected.entries, projected.cur)
    if (!next) {
      const before = cloneActiveWorkout(projected)
      actions.push({
        actionId: `wf:${progressSignature(projected)}`,
        beforeActive: before,
        entryIdx: null,
        setIdx: null,
        side: null,
        restMs: 0,
        final: true,
        afterActive: cloneActiveWorkout(projected),
      })
      break
    }

    const before = cloneActiveWorkout(projected)
    const after = cloneActiveWorkout(projected)
    const checked = completeProjectedSet(after, next)
    const outcome = completionOutcome(after, next, defaultRestSec, checked)
    const restMs = outcome.restSec > 0 ? Math.max(0, Math.round(outcome.restSec * 1000)) : 0
    const actionId = appliedWorkoutActionId(after)
    actions.push({
      actionId,
      beforeActive: before,
      entryIdx: next.idx,
      setIdx: next.i,
      side: next.side || null,
      restMs,
      restForIdx: restMs > 0 ? next.idx : null,
      final: outcome.final,
      afterActive: cloneActiveWorkout(after),
    })
    projected = after
    if (outcome.final) break
  }

  return actions
}
