import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'

export type ThemeMode = 'dark' | 'midnight' | 'nord' | 'light'
export type ThemeAccent = 'indigo' | 'emerald' | 'cyan' | 'violet' | 'amber' | 'rose'
export type ThemeDensity = 'compact' | 'standard' | 'spacious'

export interface ThemeConfig {
  mode: ThemeMode
  accent: ThemeAccent
  density: ThemeDensity
}

export interface ThemeContextValue extends ThemeConfig {
  setMode: (m: ThemeMode) => void
  setAccent: (a: ThemeAccent) => void
  setDensity: (d: ThemeDensity) => void
  toggleDarkLight: () => void
}

const STORAGE_KEYS = {
  mode: 'fs.theme.mode',
  accent: 'fs.theme.accent',
  density: 'fs.theme.density',
} as const

export function getInitialTheme(): ThemeConfig {
  try {
    const savedMode = (localStorage.getItem(STORAGE_KEYS.mode) as ThemeMode) || 'dark'
    const savedAccent = (localStorage.getItem(STORAGE_KEYS.accent) as ThemeAccent) || 'indigo'
    const savedDensity = (localStorage.getItem(STORAGE_KEYS.density) as ThemeDensity) || 'standard'
    return {
      mode: ['dark', 'midnight', 'nord', 'light'].includes(savedMode) ? savedMode : 'dark',
      accent: ['indigo', 'emerald', 'cyan', 'violet', 'amber', 'rose'].includes(savedAccent) ? savedAccent : 'indigo',
      density: ['compact', 'standard', 'spacious'].includes(savedDensity) ? savedDensity : 'standard',
    }
  } catch {
    return { mode: 'dark', accent: 'indigo', density: 'standard' }
  }
}

export function applyThemeToDOM(config: ThemeConfig) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.setAttribute('data-theme', config.mode)
  root.setAttribute('data-accent', config.accent)
  root.setAttribute('data-density', config.density)
}

const ThemeContext = createContext<ThemeContextValue>({
  mode: 'dark',
  accent: 'indigo',
  density: 'standard',
  setMode: () => {},
  setAccent: () => {},
  setDensity: () => {},
  toggleDarkLight: () => {},
})

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<ThemeConfig>(getInitialTheme)

  useEffect(() => {
    applyThemeToDOM(config)
    try {
      localStorage.setItem(STORAGE_KEYS.mode, config.mode)
      localStorage.setItem(STORAGE_KEYS.accent, config.accent)
      localStorage.setItem(STORAGE_KEYS.density, config.density)
    } catch {}
  }, [config])

  const setMode = (mode: ThemeMode) => setConfig(prev => ({ ...prev, mode }))
  const setAccent = (accent: ThemeAccent) => setConfig(prev => ({ ...prev, accent }))
  const setDensity = (density: ThemeDensity) => setConfig(prev => ({ ...prev, density }))

  const toggleDarkLight = () => {
    setConfig(prev => ({
      ...prev,
      mode: prev.mode === 'light' ? 'dark' : 'light',
    }))
  }

  return (
    <ThemeContext.Provider value={{ ...config, setMode, setAccent, setDensity, toggleDarkLight }}>
      {children}
    </ThemeContext.Provider>
  )
}

export const useTheme = () => useContext(ThemeContext)
