import { useMemo, useState } from 'react'
import { Icon } from '../ui'

/** Multi-select genre picker (suggested genres plus custom ones); value is a comma-separated list. */
const GENRE_SUGGESTIONS = [
  'Action', 'Adventure', 'Animation', 'Comedy', 'Documentary',
  'Drama', 'Fantasy', 'Horror', 'Lifestyle', 'Music',
  'News', 'Romance', 'Sci-Fi', 'Sports', 'Thriller', 'True Crime'
]

export function GenreSelector({
  value,
  onChange,
  disabled = false,
}: {
  value: string
  onChange: (val: string) => void
  disabled?: boolean
}) {
  const [customGenre, setCustomGenre] = useState('')
  const selectedGenres = useMemo(() => {
    return value ? value.split(',').map(s => s.trim()).filter(Boolean) : []
  }, [value])

  const toggleGenre = (gName: string) => {
    if (disabled) return
    const trimmed = gName.trim()
    if (!trimmed) return
    let next: string[]
    if (selectedGenres.includes(trimmed)) {
      next = selectedGenres.filter(g => g !== trimmed)
    } else {
      next = [...selectedGenres, trimmed]
    }
    onChange(next.join(', '))
  }

  const handleAddCustom = (e: React.FormEvent) => {
    e.preventDefault()
    if (disabled) return
    const trimmed = customGenre.trim()
    if (!trimmed) return
    if (!selectedGenres.includes(trimmed)) {
      toggleGenre(trimmed)
    }
    setCustomGenre('')
  }

  return (
    <div className="field library-genre-section">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <span className="row" style={{ gap: 6, alignItems: 'center' }}>
          <Icon name="sliders" size={13} />
          <b>Genre(s)</b>
          <span className="faint small" style={{ fontWeight: 400 }}>— select all that apply</span>
        </span>
        <div className="row" style={{ gap: 8, alignItems: 'center' }}>
          {selectedGenres.length > 0 && (
            <>
              <span className="badge primary sm">{selectedGenres.length} selected</span>
              {!disabled && (
                <button
                  type="button"
                  className="btn ghost sm"
                  style={{ height: 22, padding: '0 6px', fontSize: 11 }}
                  onClick={() => onChange('')}
                >
                  Clear all
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {/* Checkbox grid of genres */}
      <div className="library-genre-checkbox-grid">
        {GENRE_SUGGESTIONS.map(g => {
          const isChecked = selectedGenres.includes(g)
          return (
            <button
              key={g}
              type="button"
              role="checkbox"
              aria-checked={isChecked}
              disabled={disabled}
              className={`library-genre-check-pill ${isChecked ? 'is-checked' : ''}`}
              onClick={() => toggleGenre(g)}
            >
              <span className={`genre-check-box ${isChecked ? 'is-checked' : ''}`}>
                {isChecked && <Icon name="check" size={11} />}
              </span>
              <span className="genre-check-label">{g}</span>
            </button>
          )
        })}

        {/* Custom genres added by user */}
        {selectedGenres
          .filter(g => !GENRE_SUGGESTIONS.includes(g))
          .map(g => (
            <button
              key={g}
              type="button"
              role="checkbox"
              aria-checked={true}
              disabled={disabled}
              className="library-genre-check-pill is-checked"
              onClick={() => toggleGenre(g)}
            >
              <span className="genre-check-box is-checked">
                <Icon name="check" size={11} />
              </span>
              <span className="genre-check-label">{g}</span>
            </button>
          ))}
      </div>

      {/* Custom genre add row */}
      {!disabled && (
        <div className="library-custom-genre-row">
          <input
            type="text"
            placeholder="+ Add custom genre tag (e.g. Cyberpunk, Anime)…"
            value={customGenre}
            onChange={e => setCustomGenre(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault()
                handleAddCustom(e)
              }
            }}
            className="library-custom-genre-input"
          />
          <button
            type="button"
            className="btn sm"
            disabled={!customGenre.trim()}
            onClick={handleAddCustom}
          >
            <Icon name="plus" size={13} /> Add
          </button>
        </div>
      )}
    </div>
  )
}
