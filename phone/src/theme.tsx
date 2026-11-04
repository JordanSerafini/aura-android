import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react"
import { StyleSheet, useColorScheme } from "react-native"
import { themes, type Scheme, type Theme, type ThemePref } from "./config"
import { AuraDevice } from "./native"

// Thème vivant : celui du système (useColorScheme), sauf si la PWA a forcé « Clair » ou « Sombre » dans Réglages → Apparence.
// La WebView relaie ce choix (AuraScreen, message `theme`) ; il est gardé dans les prefs natives pour le démarrage suivant.
// Un seul réglage, côté PWA : l'app native n'a pas d'interrupteur à elle.
const PREF = "theme_pref"

type Ctx = { theme: Theme, pref: ThemePref, setPref: (p: ThemePref) => void }
const ThemeContext = createContext<Ctx>({ theme: themes.dark, pref: "auto", setPref: () => {} })

function readPref(): ThemePref {
  try {
    const v = AuraDevice.getPref(PREF)
    return v === "light" || v === "dark" ? v : "auto"
  } catch {
    return "auto"
  }
}

export function asThemePref(v: unknown): ThemePref | null {
  return v === "auto" || v === "light" || v === "dark" ? v : null
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const system = useColorScheme()
  const [pref, setPrefState] = useState<ThemePref>(readPref)
  const setPref = useCallback((p: ThemePref) => {
    setPrefState((prev) => {
      if (prev !== p) AuraDevice.setPref(PREF, p)
      return p
    })
  }, [])
  // système inconnu (null) : sombre, l'apparence historique de l'app
  const scheme: Scheme = pref === "auto" ? (system === "light" ? "light" : "dark") : pref
  const value = useMemo(() => ({ theme: themes[scheme], pref, setPref }), [scheme, pref, setPref])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): Theme {
  return useContext(ThemeContext).theme
}

export function useThemePref(): [ThemePref, (p: ThemePref) => void] {
  const c = useContext(ThemeContext)
  return [c.pref, c.setPref]
}

/** Feuille de styles par thème, créée une fois par thème (deux au plus) : `const useStyles = makeStyles((t) => ({ ... }))`. */
export function makeStyles<T extends StyleSheet.NamedStyles<T>>(build: (t: Theme) => T): () => T {
  const cache = new Map<Theme, T>()
  return function useStyles() {
    const t = useTheme()
    let sheet = cache.get(t)
    if (!sheet) {
      sheet = StyleSheet.create(build(t))
      cache.set(t, sheet)
    }
    return sheet
  }
}
