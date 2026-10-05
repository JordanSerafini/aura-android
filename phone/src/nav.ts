// Sections de Réglages jointes depuis l'accueil « Téléphone » : l'écran défile jusqu'à la carte.
export type Section = "pause" | "ui_control" | "journal"
export type SettingsParams = { section?: Section, n?: number } | undefined
