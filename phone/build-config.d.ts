export type Env = Record<string, string | undefined>
export function insecureDevAllowed(env: Env): boolean
export function checkUrls(bridgeUrl: string, pwaUrl: string, env: Env): string[]
export function assertUrls(bridgeUrl: string, pwaUrl: string, env: Env): void
