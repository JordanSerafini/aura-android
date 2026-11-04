import { useEffect, useState } from "react"
import { AccessibilityInfo } from "react-native"

// « Supprimer les animations » d'Android (Accessibilité) : lu à l'ouverture, puis suivi s'il change pendant que l'écran
// est affiché. null tant que la réponse n'est pas arrivée (on n'anime rien d'ici là) ; illisible = animations permises.
// Talk (1.23.0) et l'accueil du hub (1.30.0) : la WebView 3D lit le même réglage (prefers-reduced-motion) et se fige.
export function useReduceMotion(): boolean | null {
  const [reduce, setReduce] = useState<boolean | null>(null)
  useEffect(() => {
    let alive = true
    AccessibilityInfo.isReduceMotionEnabled().then((v) => { if (alive) setReduce(v) }).catch(() => { if (alive) setReduce(false) })
    const sub = AccessibilityInfo.addEventListener("reduceMotionChanged", (v) => setReduce(v))
    return () => { alive = false; sub.remove() }
  }, [])
  return reduce
}
