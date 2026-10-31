

/** Display names for media kinds; unknown keys from the platform catalogue are title-cased. */
export const KIND_LABEL: Record<string, string> = {
  MOVIE: 'Movie',
  EPISODE: 'Episode',
  SHORT: 'Short',
  AD: 'Commercial Ad',
  SLATE: 'Between Programmes',
}

export function formatMediaKind(k: string): string {
  if (!k) return 'Media'
  if (KIND_LABEL[k]) return KIND_LABEL[k]
  // Format dynamic enum keys from admin portal like "DOCUMENTARY_FILM" -> "Documentary Film"
  return k
    .split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ')
}
