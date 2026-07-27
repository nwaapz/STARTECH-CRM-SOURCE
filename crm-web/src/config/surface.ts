/** Dual-surface CRM: lan (phone) vs remote (PHP host). */
export type CrmSurface = 'lan' | 'remote'

const SURFACE_KEY = 'gateway_crm_surface'

export function getSurface(): CrmSurface {
  const fromEnv = (import.meta.env.VITE_CRM_SURFACE as string | undefined)?.toLowerCase()
  if (fromEnv === 'remote') return 'remote'
  if (fromEnv === 'lan') return 'lan'
  const stored = sessionStorage.getItem(SURFACE_KEY)
  if (stored === 'remote' || stored === 'lan') return stored
  return 'lan'
}

export function setSurface(surface: CrmSurface): void {
  sessionStorage.setItem(SURFACE_KEY, surface)
}

export function isRemoteSurface(): boolean {
  return getSurface() === 'remote'
}

export function showWhatsAppNav(): boolean {
  return !isRemoteSurface()
}
