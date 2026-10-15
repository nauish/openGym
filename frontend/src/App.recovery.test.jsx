// @vitest-environment happy-dom
import React, { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRoot } from 'react-dom/client'

const h = vi.hoisted(() => ({ recover: vi.fn(), syncNotification: vi.fn(), listeners: new Set() }))

vi.mock('./lib/mobile.js', async importOriginal => ({
  ...(await importOriginal()),
  MOBILE: true,
  isAndroid: vi.fn(async () => true),
  onAppActive: vi.fn(() => () => {}),
  initReminderSync: vi.fn(),
  nativeSave: vi.fn(async () => true),
  syncReminder: vi.fn(async () => true),
  writeJsonFile: vi.fn(async () => true),
}))
vi.mock('./lib/workout-complete-set.js', () => ({
  reconcilePendingWorkoutActions: (...args) => {
    for (const listener of h.listeners) listener({ state: 'pending' })
    return Promise.resolve(h.recover(...args)).then(result => {
      for (const listener of h.listeners) listener({ state: 'settled', result })
      return result
    })
  },
  subscribeWorkoutActionRecovery: listener => {
    h.listeners.add(listener)
    return () => h.listeners.delete(listener)
  },
}))
vi.mock('./lib/rest-alert.js', () => ({
  armRestAlert: vi.fn(async () => true),
  holdRestAlert: vi.fn(),
  disarmRestAlert: vi.fn(),
  bindNativeRest: vi.fn(),
  buildWorkoutNotification: active => active ? { sessionId: active.id } : null,
  syncWorkoutNotification: (...args) => h.syncNotification(...args),
}))
vi.mock('./lib/sound.js', () => ({ setPlayOnSilent: vi.fn(), setVibrate: vi.fn(), setAlarmBuzzer: vi.fn() }))
vi.mock('./lib/nav.js', () => ({ setNav: vi.fn() }))
vi.mock('./lib/back.js', () => ({ initBackButton: vi.fn(async () => () => {}) }))
vi.mock('./lib/wakelock.js', () => ({ useWakeLock: vi.fn() }))
vi.mock('./lib/viewport-guard.js', () => ({ installViewportGuard: vi.fn(() => () => {}) }))
vi.mock('./lib/hchips.js', () => ({ installChipDrag: vi.fn(() => () => {}) }))
vi.mock('./lib/push.js', () => ({ syncPushSubscription: vi.fn(async () => {}) }))
vi.mock('./lib/i18n.js', () => ({
  setLang: vi.fn(), useLang: () => 'en', baseLang: () => 'en', t: message => message,
}))
vi.mock('./lib/default-lang.js', () => ({ effectiveLang: () => 'en' }))
vi.mock('./lib/history.js', () => ({
  setsDoneActive: active => (active?.entries || []).flatMap(entry => entry.sets || []).filter(set => set.done).length,
  setUnitsTotal: entries => (entries || []).flatMap(entry => entry.sets || []).length,
}))
vi.mock('./components/ui.jsx', () => ({ bindUI: vi.fn() }))
vi.mock('./components/Icon.jsx', () => ({ default: () => null }))
vi.mock('./components/TabBar.jsx', () => ({ default: () => null }))
vi.mock('./components/ErrorBoundary.jsx', () => ({ default: ({ children }) => children }))
vi.mock('./components/Modals.jsx', () => ({ default: () => null }))
vi.mock('./components/Toast.jsx', () => ({ default: () => null }))
vi.mock('./components/SyncBanner.jsx', () => ({ default: () => null }))
vi.mock('./components/RestTimer.jsx', () => ({ default: () => null }))
vi.mock('./components/TimerFlash.jsx', () => ({ default: () => null }))
vi.mock('./components/Passkeys.jsx', () => ({ openDeviceLinkRedeem: vi.fn() }))
vi.mock('./views/Login.jsx', () => ({ default: () => null }))
vi.mock('./views/MobileOnboarding.jsx', () => ({ default: () => null }))
vi.mock('./views/Home.jsx', () => ({ default: () => null }))
vi.mock('./views/CheckIn.jsx', () => ({ default: () => null }))
vi.mock('./views/Plan.jsx', () => ({ default: () => null }))
vi.mock('./views/RoutineEdit.jsx', () => ({ default: () => null }))
vi.mock('./views/Workout.jsx', () => ({ default: () => <div data-testid="workout-controls">Workout controls</div> }))
vi.mock('./views/Stats.jsx', () => ({ default: () => null }))
vi.mock('./views/History.jsx', () => ({ default: () => null }))
vi.mock('./views/Library.jsx', () => ({ default: () => null }))
vi.mock('./views/Muscles.jsx', () => ({ default: () => null }))
vi.mock('./views/StructuralBalance.jsx', () => ({ default: () => null }))
vi.mock('./views/ProgressPhotos.jsx', () => ({ default: () => null }))
vi.mock('./views/Measurements.jsx', () => ({ default: () => null }))
vi.mock('./views/Settings.jsx', () => ({ default: () => null, SettingsRoute: () => null }))
vi.mock('./views/Admin.jsx', () => ({ default: () => null }))
vi.mock('./views/CoachChat.jsx', () => ({ default: () => null }))
vi.mock('./views/CoachIntake.jsx', () => ({ default: () => null }))
vi.mock('./views/CoachSetup.jsx', () => ({ default: () => null }))

import App from './App.jsx'
import { useStore } from './store/useStore.js'

function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

let root
let host
let originalState

beforeEach(() => {
  localStorage.clear()
  window.location.hash = '#/workout'
  originalState = useStore.getState()
  h.recover.mockReset()
  h.listeners.clear()
  h.syncNotification.mockReset().mockResolvedValue(true)
  const S = structuredClone(originalState.S)
  S.active = {
    id: 'recover-session',
    name: 'Workout',
    start: 1,
    cur: 0,
    entries: [{ id: '0025', sets: [{ w: 50, r: 8, done: false }] }],
  }
  useStore.setState({
    S,
    user: { id: 'user-1', name: 'Test' },
    ready: true,
    needsMobileOnboarding: false,
    boot: vi.fn(async () => {}),
  })
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  vi.stubGlobal('requestAnimationFrame', callback => setTimeout(callback, 0))
  vi.stubGlobal('cancelAnimationFrame', id => clearTimeout(id))
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(async () => {
  if (root) await act(async () => root.unmount())
  host?.remove()
  root = null
  host = null
  useStore.setState({
    S: originalState.S,
    user: originalState.user,
    ready: originalState.ready,
    needsMobileOnboarding: originalState.needsMobileOnboarding,
    boot: originalState.boot,
  })
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('native workout recovery gate', () => {
  it('keeps workout controls hidden while replay is pending or blocked, then restores the route on retry', async () => {
    const first = deferred()
    const retry = deferred()
    h.recover.mockReturnValueOnce(first.promise).mockReturnValueOnce(retry.promise)

    await act(async () => root.render(<App />))
    expect(h.recover).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('Syncing…')
    expect(host.querySelector('[data-testid="workout-controls"]')).toBeNull()

    await act(async () => { first.resolve({ available: true, blocked: true }); await first.promise })
    expect(host.textContent).toContain('Recovery')
    expect(host.querySelector('[data-testid="workout-controls"]')).toBeNull()

    const retryButton = [...host.querySelectorAll('button')].find(button => button.textContent === 'Try again')
    expect(retryButton).toBeTruthy()
    await act(async () => { retryButton.click(); await Promise.resolve() })
    expect(h.recover).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('Syncing…')
    expect(host.querySelector('[data-testid="workout-controls"]')).toBeNull()

    await act(async () => { retry.resolve({ available: true, blocked: false }); await retry.promise })
    expect(host.querySelector('[data-testid="workout-controls"]')?.textContent).toBe('Workout controls')
    expect(host.textContent).not.toContain('Recovery')
    expect(h.syncNotification).toHaveBeenCalled()
  })
})
