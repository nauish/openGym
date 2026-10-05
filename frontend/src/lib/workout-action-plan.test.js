import { describe, expect, it } from 'vitest'
import { buildWorkoutActionPlan } from './workout-action-plan.js'
import { makeSideSet } from './workout-model.js'

const entry = (id, sets, extra = {}) => ({ id, sets, ...extra })
const set = (phase = 'work') => ({ w: 50, r: 8, phase, done: false })
const workout = (entries, { id = 'session-a', cur = 0 } = {}) => ({ id, name: 'Workout', start: 1, cur, entries })

describe('buildWorkoutActionPlan', () => {
  it('plans consecutive Done actions with a rest between sets and no rest after the final set', () => {
    const active = workout([entry('0025', [set(), set()])])
    const actions = buildWorkoutActionPlan(active, { defaultRestSec: 60 })

    expect(actions).toHaveLength(2)
    expect(actions[0]).toMatchObject({ entryIdx: 0, setIdx: 0, restMs: 60_000, final: false })
    expect(actions[0].afterActive.entries[0].sets).toMatchObject([{ done: true }, { done: false }])
    expect(actions[1]).toMatchObject({ entryIdx: 0, setIdx: 1, restMs: 0, final: true })
    expect(actions[1].afterActive.entries[0].sets).toMatchObject([{ done: true }, { done: true }])
    expect(new Set(actions.map(action => action.actionId)).size).toBe(2)
  })

  it('uses warm-up rest between ramp sets and work rest before the first work set', () => {
    const active = workout([
      entry('0025', [set('warmup'), set('warmup'), set('work'), set('work')], {
        target: { restSec: 60, warmupRestSec: 30 },
      }),
    ])
    const actions = buildWorkoutActionPlan(active, { defaultRestSec: 90 })

    expect(actions.map(action => action.restMs)).toEqual([30_000, 60_000, 60_000, 0])
    expect(actions.at(-1).final).toBe(true)
  })

  it('waits for a superset round before resting, then completes its final round without rest', () => {
    const active = workout([
      entry('0025', [set(), set()], { sg: 'pair-1' }),
      entry('0031', [set(), set()], { sg: 'pair-1' }),
    ])
    const actions = buildWorkoutActionPlan(active, { defaultRestSec: 45 })

    expect(actions.map(({ entryIdx, setIdx }) => [entryIdx, setIdx])).toEqual([
      [0, 0], [1, 0], [0, 1], [1, 1],
    ])
    expect(actions.map(action => action.restMs)).toEqual([0, 45_000, 0, 0])
    expect(actions.map(action => action.final)).toEqual([false, false, false, true])
  })

  it('does not create native rest actions when rest is off', () => {
    const actions = buildWorkoutActionPlan(workout([entry('0025', [set(), set()])]), { defaultRestSec: 0 })

    expect(actions.map(action => action.restMs)).toEqual([0, 0])
    expect(actions.at(-1).final).toBe(true)
  })

  it('waits for both sides of each unilateral set before starting rest', () => {
    const active = workout([entry('0025', [makeSideSet({ w: 50, r: 16 }), makeSideSet({ w: 50, r: 16 })])])
    const actions = buildWorkoutActionPlan(active, { defaultRestSec: 45 })

    expect(actions.map(action => [action.setIdx, action.side])).toEqual([
      [0, 'L'], [0, 'R'], [1, 'L'], [1, 'R'],
    ])
    expect(actions.map(action => action.restMs)).toEqual([0, 45_000, 0, 0])
    expect(actions.map(action => action.final)).toEqual([false, false, false, true])
    expect(actions[0].afterActive.entries[0].sets[0]).toMatchObject({
      done: false,
      sides: { L: { done: true }, R: { done: false } },
    })
    expect(actions[1].afterActive.entries[0].sets[0]).toMatchObject({
      done: true,
      sides: { L: { done: true }, R: { done: true } },
    })
  })

  it('uses the next stable action ID after applying the prior projected completion', () => {
    const active = workout([entry('0025', [set(), set(), set()])])
    const original = buildWorkoutActionPlan(active, { defaultRestSec: 60 })
    const replayed = buildWorkoutActionPlan(original[0].afterActive, { defaultRestSec: 60 })

    expect(replayed[0].actionId).toBe(original[1].actionId)
    expect(replayed.some(action => action.actionId === original[0].actionId)).toBe(false)
    expect(buildWorkoutActionPlan(active, { defaultRestSec: 60 }).map(action => action.actionId))
      .toEqual(original.map(action => action.actionId))
  })
})
