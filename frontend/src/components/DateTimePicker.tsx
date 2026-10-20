import React, { useState, useEffect, useRef, useMemo } from 'react'
import { Icon } from '../ui'

export interface TimezoneOption {
  code: string
  label: string
  offset: string
}

export const COMMON_TIMEZONES: TimezoneOption[] = [
  { code: 'UTC', label: 'UTC (Coordinated Universal Time)', offset: '+00:00' },
  { code: 'America/New_York', label: 'Eastern Time (US / Canada)', offset: '-04:00' },
  { code: 'America/Chicago', label: 'Central Time (US / Canada)', offset: '-05:00' },
  { code: 'America/Denver', label: 'Mountain Time (US / Canada)', offset: '-06:00' },
  { code: 'America/Los_Angeles', label: 'Pacific Time (US / Canada)', offset: '-07:00' },
  { code: 'Europe/London', label: 'London (GMT / BST)', offset: '+01:00' },
  { code: 'Europe/Paris', label: 'Central European (CET / CEST)', offset: '+02:00' },
  { code: 'Asia/Dubai', label: 'Gulf Standard Time (GST)', offset: '+04:00' },
  { code: 'Asia/Kolkata', label: 'India Standard Time (IST)', offset: '+05:30' },
  { code: 'Asia/Singapore', label: 'Singapore / Hong Kong (SGT / HKT)', offset: '+08:00' },
  { code: 'Asia/Tokyo', label: 'Japan Standard Time (JST)', offset: '+09:00' },
  { code: 'Australia/Sydney', label: 'Sydney / Melbourne (AEST / AEDT)', offset: '+11:00' },
]

/**
 * Reusable Timezone pipe helper
 * Formats a given date/time string with timezone, locale, and optional time component
 */
export function formatDateTimeTz(
  value: string | Date | null | undefined,
  opts?: {
    timezone?: string
    showTime?: boolean
    format?: 'short' | 'medium' | 'long' | 'dateOnly' | 'timeOnly'
  }
): string {
  if (!value) return ''
  try {
    const d = typeof value === 'string' ? new Date(value) : value
    if (isNaN(d.getTime())) return String(value)

    const tz = opts?.timezone && opts.timezone !== 'local' ? opts.timezone : undefined
    const showTime = opts?.showTime !== false && opts?.format !== 'dateOnly'

    if (opts?.format === 'timeOnly') {
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: tz,
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(d)
    }

    if (!showTime) {
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: tz,
        day: 'numeric',
        month: opts?.format === 'short' ? 'short' : 'long',
        year: 'numeric',
      }).format(d)
    }

    if (opts?.format === 'short') {
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: tz,
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(d)
    }

    return new Intl.DateTimeFormat('en-GB', {
      timeZone: tz,
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(d)
  } catch {
    return String(value)
  }
}

/**
 * Returns formatted timezone badge (e.g. "IST" or "UTC" or "EDT")
 */
export function getTimezoneBadge(timezone?: string): string {
  if (!timezone || timezone === 'local') {
    try {
      const match = new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' })
        .formatToParts(new Date())
        .find(p => p.type === 'timeZoneName')
      return match?.value || 'Local'
    } catch {
      return 'Local'
    }
  }
  if (timezone === 'UTC') return 'UTC'
  const found = COMMON_TIMEZONES.find(t => t.code === timezone)
  if (found) {
    const abbrev = timezone.split('/')[1]?.replace(/_/g, ' ') || timezone
    return abbrev
  }
  return timezone
}

export interface DateTimePickerProps {
  /** Current ISO value, e.g. "2026-10-20T18:00" or "2026-10-20" */
  value?: string | null
  /** Called whenever the date or time changes */
  onChange?: (val: string) => void
  /** If false, time section is hidden and picker acts as date-only */
  showTime?: boolean
  /** If true, user gets an interactive toggle in the UI to enable/disable time (key-based optionality) */
  allowTimeToggle?: boolean
  /** Timezone code (e.g. 'UTC', 'Asia/Kolkata', or 'local') */
  timezone?: string
  /** Timezone change handler when timezone switcher is enabled */
  onTimezoneChange?: (tz: string) => void
  /** Show timezone pill/indicator */
  showTimezone?: boolean
  /** Placeholder when empty */
  placeholder?: string
  /** Field label */
  label?: string
  /** Field hint text */
  hint?: string
  /** Disabled state */
  disabled?: boolean
  /** Clearable button */
  clearable?: boolean
  /** Quick presets */
  presets?: Array<{ label: string; getValue: () => Date | string }>
  /** Custom root CSS class */
  className?: string
  /** Custom inline style */
  style?: React.CSSProperties
  /** Popover placement preference ('auto' will flip upward if near the bottom) */
  placement?: 'bottom' | 'top' | 'auto'
  /** If true, renders a fixed inline component instead of a floating popover (Default: true) */
  fixed?: boolean
}

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
]
const DAY_NAMES = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']

export function DateTimePicker({
  value = '',
  onChange,
  showTime: initialShowTime = true,
  allowTimeToggle = true,
  timezone = 'local',
  onTimezoneChange,
  showTimezone = true,
  placeholder = 'Select date & time...',
  label,
  hint,
  disabled = false,
  clearable = true,
  presets,
  className = '',
  style,
  placement = 'auto',
  fixed = true,
}: DateTimePickerProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [activePlacement, setActivePlacement] = useState<'bottom' | 'top'>('bottom')
  const containerRef = useRef<HTMLDivElement>(null)

  // Auto-detect available viewport space and flip upward if needed
  useEffect(() => {
    if (isOpen && containerRef.current) {
      if (placement === 'bottom' || placement === 'top') {
        setActivePlacement(placement)
        return
      }
      const rect = containerRef.current.getBoundingClientRect()
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight
      const spaceBelow = viewportHeight - rect.bottom
      const spaceAbove = rect.top
      // Popover requires ~390px. If not enough room below and more space above, open upwards!
      if (spaceBelow < 410 && spaceAbove > spaceBelow) {
        setActivePlacement('top')
      } else {
        setActivePlacement('bottom')
      }
    }
  }, [isOpen, placement])

  // Internal state for optional time toggle
  const [hasTime, setHasTime] = useState<boolean>(() => {
    if (!initialShowTime) return false
    if (!value) return initialShowTime
    return value.includes('T') || value.includes(':')
  })

  useEffect(() => {
    if (!initialShowTime) {
      setHasTime(false)
    }
  }, [initialShowTime])

  // Parse current value into a working date object or null
  const parsedDate = useMemo(() => {
    if (!value) return null
    const d = new Date(value)
    return isNaN(d.getTime()) ? null : d
  }, [value])

  // View state for month & year navigation
  const [viewYear, setViewYear] = useState<number>(() => {
    return parsedDate ? parsedDate.getFullYear() : new Date().getFullYear()
  })
  const [viewMonth, setViewMonth] = useState<number>(() => {
    return parsedDate ? parsedDate.getMonth() : new Date().getMonth()
  })

  // Selected date components
  const [selectedYear, setSelectedYear] = useState<number | null>(() => parsedDate?.getFullYear() ?? (fixed ? new Date().getFullYear() : null))
  const [selectedMonth, setSelectedMonth] = useState<number | null>(() => parsedDate ? parsedDate.getMonth() : (fixed ? new Date().getMonth() : null))
  const [selectedDay, setSelectedDay] = useState<number | null>(() => parsedDate ? parsedDate.getDate() : (fixed ? new Date().getDate() : null))

  // Time components (24h)
  const [hours, setHours] = useState<number>(() => parsedDate ? parsedDate.getHours() : 12)
  const [minutes, setMinutes] = useState<number>(() => parsedDate ? parsedDate.getMinutes() : 0)

  // Sync internal view when external value changes
  useEffect(() => {
    if (parsedDate) {
      setSelectedYear(parsedDate.getFullYear())
      setSelectedMonth(parsedDate.getMonth())
      setSelectedDay(parsedDate.getDate())
      setHours(parsedDate.getHours())
      setMinutes(parsedDate.getMinutes())
      setViewYear(parsedDate.getFullYear())
      setViewMonth(parsedDate.getMonth())
      if (initialShowTime && value?.includes('T')) {
        setHasTime(true)
      }
    } else if (!value) {
      setSelectedYear(null)
      setSelectedMonth(null)
      setSelectedDay(null)
    }
  }, [value, parsedDate, initialShowTime])

  // Close on outside click
  useEffect(() => {
    function handleOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false)
      }
    }
    if (isOpen) {
      document.addEventListener('mousedown', handleOutside)
      return () => document.removeEventListener('mousedown', handleOutside)
    }
  }, [isOpen])

  // Close on Escape
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && isOpen) {
        setIsOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen])

  // Month navigation
  const prevMonth = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (viewMonth === 0) {
      setViewMonth(11)
      setViewYear(y => y - 1)
    } else {
      setViewMonth(m => m - 1)
    }
  }

  const nextMonth = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (viewMonth === 11) {
      setViewMonth(0)
      setViewYear(y => y + 1)
    } else {
      setViewMonth(m => m + 1)
    }
  }

  const jumpToToday = (e: React.MouseEvent) => {
    e.stopPropagation()
    const now = new Date()
    setViewYear(now.getFullYear())
    setViewMonth(now.getMonth())
    handleSelectDay(now.getFullYear(), now.getMonth(), now.getDate())
  }

  // Format and emit value
  const emitValue = (y: number, m: number, d: number, h: number, min: number, includeTime: boolean) => {
    const pad = (n: number) => String(n).padStart(2, '0')
    let isoStr = `${y}-${pad(m + 1)}-${pad(d)}`
    if (includeTime && initialShowTime) {
      isoStr += `T${pad(h)}:${pad(min)}`
    }
    onChange?.(isoStr)
  }

  const handleSelectDay = (y: number, m: number, d: number) => {
    setSelectedYear(y)
    setSelectedMonth(m)
    setSelectedDay(d)
    emitValue(y, m, d, hours, minutes, hasTime)
  }

  const handleTimeChange = (newHours: number, newMinutes: number) => {
    setHours(newHours)
    setMinutes(newMinutes)
    if (selectedYear !== null && selectedMonth !== null && selectedDay !== null) {
      emitValue(selectedYear, selectedMonth, selectedDay, newHours, newMinutes, true)
    } else {
      const today = new Date()
      setSelectedYear(today.getFullYear())
      setSelectedMonth(today.getMonth())
      setSelectedDay(today.getDate())
      emitValue(today.getFullYear(), today.getMonth(), today.getDate(), newHours, newMinutes, true)
    }
  }

  const handleToggleTime = (e: React.ChangeEvent<HTMLInputElement>) => {
    const checked = e.target.checked
    setHasTime(checked)
    if (selectedYear !== null && selectedMonth !== null && selectedDay !== null) {
      emitValue(selectedYear, selectedMonth, selectedDay, hours, minutes, checked)
    }
  }

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation()
    setSelectedYear(null)
    setSelectedMonth(null)
    setSelectedDay(null)
    onChange?.('')
  }

  // Generate calendar days for the view
  const calendarCells = useMemo(() => {
    const firstDayIndex = new Date(viewYear, viewMonth, 1).getDay()
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate()
    const daysInPrevMonth = new Date(viewYear, viewMonth, 0).getDate()

    const cells: Array<{
      day: number
      month: number
      year: number
      isCurrentMonth: boolean
      isToday: boolean
      isSelected: boolean
    }> = []

    const today = new Date()
    const isThisYear = today.getFullYear() === viewYear
    const isThisMonth = today.getMonth() === viewMonth
    const todayDate = today.getDate()

    // Leading days from previous month
    for (let i = firstDayIndex - 1; i >= 0; i--) {
      const prevMonthNum = viewMonth === 0 ? 11 : viewMonth - 1
      const prevYearNum = viewMonth === 0 ? viewYear - 1 : viewYear
      cells.push({
        day: daysInPrevMonth - i,
        month: prevMonthNum,
        year: prevYearNum,
        isCurrentMonth: false,
        isToday: false,
        isSelected: false,
      })
    }

    // Days in current month
    for (let d = 1; d <= daysInMonth; d++) {
      const isSelected =
        selectedYear === viewYear &&
        selectedMonth === viewMonth &&
        selectedDay === d

      cells.push({
        day: d,
        month: viewMonth,
        year: viewYear,
        isCurrentMonth: true,
        isToday: isThisYear && isThisMonth && d === todayDate,
        isSelected,
      })
    }

    // Trailing days to fill 42 cells (6 rows)
    const remaining = 42 - cells.length
    for (let d = 1; d <= remaining; d++) {
      const nextMonthNum = viewMonth === 11 ? 0 : viewMonth + 1
      const nextYearNum = viewMonth === 11 ? viewYear + 1 : viewYear
      cells.push({
        day: d,
        month: nextMonthNum,
        year: nextYearNum,
        isCurrentMonth: false,
        isToday: false,
        isSelected: false,
      })
    }

    return cells
  }, [viewYear, viewMonth, selectedYear, selectedMonth, selectedDay])

  // Default quick presets
  const defaultPresets = useMemo(() => {
    if (presets) return presets
    return [
      {
        label: 'Now',
        getValue: () => {
          const d = new Date()
          return d
        },
      },
      {
        label: 'Tomorrow 18:00',
        getValue: () => {
          const d = new Date()
          d.setDate(d.getDate() + 1)
          d.setHours(18, 0, 0, 0)
          return d
        },
      },
      {
        label: 'In 1 Week',
        getValue: () => {
          const d = new Date()
          d.setDate(d.getDate() + 7)
          d.setHours(18, 0, 0, 0)
          return d
        },
      },
    ]
  }, [presets])

  const applyPreset = (presetFn: () => Date | string, e: React.MouseEvent) => {
    e.stopPropagation()
    const result = presetFn()
    const d = typeof result === 'string' ? new Date(result) : result
    if (isNaN(d.getTime())) return
    setSelectedYear(d.getFullYear())
    setSelectedMonth(d.getMonth())
    setSelectedDay(d.getDate())
    setHours(d.getHours())
    setMinutes(d.getMinutes())
    setViewYear(d.getFullYear())
    setViewMonth(d.getMonth())
    emitValue(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), hasTime)
  }

  // Display text in input
  const displayValue = useMemo(() => {
    if (!value || selectedDay === null) return ''
    return formatDateTimeTz(value, {
      timezone,
      showTime: hasTime && initialShowTime,
      format: hasTime && initialShowTime ? 'short' : 'dateOnly',
    })
  }, [value, selectedDay, hasTime, initialShowTime, timezone])

  const tzBadgeText = useMemo(() => getTimezoneBadge(timezone), [timezone])

  // Fixed Inline Layout (Zero Popovers, Non-Scrollable, Embedded in Modals & Forms)
  if (fixed) {
    return (
      <div
        ref={containerRef}
        className={`dtpicker-fixed-wrap ${className} ${disabled ? 'is-disabled' : ''}`}
        style={style}
      >
        {label && (
          <div className="dtpicker-label-row">
            <label className="dtpicker-label">{label}</label>
            {hint && <span className="dtpicker-hint">{hint}</span>}
          </div>
        )}

        {/* Fixed Header */}
        <div className="dtpicker-fixed-header">
          <div className="dtpicker-month-nav">
            <button
              type="button"
              className="dtpicker-nav-btn"
              onClick={prevMonth}
              title="Previous month"
            >
              <Icon name="chevronLeft" size={14} />
            </button>
            <div className="dtpicker-month-label">
              <b>{MONTH_NAMES[viewMonth]}</b> <span>{viewYear}</span>
            </div>
            <button
              type="button"
              className="dtpicker-nav-btn"
              onClick={nextMonth}
              title="Next month"
            >
              <Icon name="chevronRight" size={14} />
            </button>
          </div>

          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
            <button type="button" className="dtpicker-today-btn" onClick={jumpToToday}>
              Today
            </button>
            {showTimezone && (
              <span className="dtpicker-tz-pill" title={`Broadcast Timezone: ${timezone}`}>
                {tzBadgeText}
              </span>
            )}
          </div>
        </div>

        {/* Fixed Body: 2 Columns */}
        <div className="dtpicker-fixed-body">
          {/* Calendar Column */}
          <div className="dtpicker-calendar-col">
            <div className="dtpicker-weekdays">
              {DAY_NAMES.map(d => (
                <span key={d} className="dtpicker-weekday-label">{d}</span>
              ))}
            </div>
            <div className="dtpicker-days-grid">
              {calendarCells.map((cell, idx) => (
                <button
                  key={idx}
                  type="button"
                  className={`dtpicker-day-cell ${!cell.isCurrentMonth ? 'is-outside' : ''} ${
                    cell.isSelected ? 'is-selected' : ''
                  } ${cell.isToday ? 'is-today' : ''}`}
                  onClick={() => handleSelectDay(cell.year, cell.month, cell.day)}
                >
                  {cell.day}
                </button>
              ))}
            </div>
          </div>

          {/* Time & Shortcuts Column */}
          {initialShowTime && (
            <div className="dtpicker-time-col">
              <div className="dtpicker-time-summary">
                <span className="faint small" style={{ fontWeight: 600 }}>Selected Broadcast Airtime</span>
                <div className="dtpicker-selected-time-display">
                  <Icon name="clock" size={14} />
                  <b>{displayValue || 'Select day and time'}</b>
                </div>
              </div>

              <div className="dtpicker-time-inputs-wrap">
                <label className="dtpicker-unit-lbl">Broadcast Airtime (24h)</label>
                <div className="dtpicker-time-inputs">
                  <div className="dtpicker-unit-col">
                    <input
                      type="number"
                      min={0}
                      max={23}
                      value={String(hours).padStart(2, '0')}
                      onChange={e =>
                        handleTimeChange(Math.min(23, Math.max(0, parseInt(e.target.value) || 0)), minutes)
                      }
                      className="dtpicker-number-input"
                    />
                    <span className="faint small">Hour</span>
                  </div>
                  <span className="dtpicker-time-sep">:</span>
                  <div className="dtpicker-unit-col">
                    <input
                      type="number"
                      min={0}
                      max={59}
                      step={5}
                      value={String(minutes).padStart(2, '0')}
                      onChange={e =>
                        handleTimeChange(hours, Math.min(59, Math.max(0, parseInt(e.target.value) || 0)))
                      }
                      className="dtpicker-number-input"
                    />
                    <span className="faint small">Min</span>
                  </div>
                </div>
              </div>

              {/* Quick Minute Shortcuts */}
              <div className="dtpicker-quick-minutes-row">
                <span className="faint small" style={{ fontSize: 11, minWidth: 42 }}>Minute:</span>
                {[0, 15, 30, 45].map(m => (
                  <button
                    key={m}
                    type="button"
                    className={`dtpicker-quick-min-btn ${minutes === m ? 'is-active' : ''}`}
                    onClick={() => handleTimeChange(hours, m)}
                  >
                    :{String(m).padStart(2, '0')}
                  </button>
                ))}
              </div>

              {/* Quick Broadcast Daypart Presets */}
              <div className="dtpicker-quick-presets-col">
                <span className="faint small" style={{ fontSize: 11 }}>Daypart Presets:</span>
                <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                  <button type="button" className="dtpicker-preset-chip" onClick={() => handleTimeChange(6, 0)}>
                    Morning (06:00)
                  </button>
                  <button type="button" className="dtpicker-preset-chip" onClick={() => handleTimeChange(12, 0)}>
                    Noon (12:00)
                  </button>
                  <button type="button" className="dtpicker-preset-chip" onClick={() => handleTimeChange(20, 0)}>
                    Prime (20:00)
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className={`dtpicker-root ${className} ${disabled ? 'is-disabled' : ''}`}
      style={style}
    >
      {label && (
        <div className="dtpicker-label-row">
          <label className="dtpicker-label">{label}</label>
          {hint && <span className="dtpicker-hint">{hint}</span>}
        </div>
      )}

      {/* Main Trigger Input */}
      <div
        className={`dtpicker-trigger ${isOpen ? 'is-focused' : ''} ${value ? 'has-value' : ''}`}
        onClick={() => !disabled && setIsOpen(prev => !prev)}
        role="button"
        tabIndex={disabled ? -1 : 0}
        onKeyDown={e => {
          if ((e.key === 'Enter' || e.key === ' ') && !disabled) {
            e.preventDefault()
            setIsOpen(prev => !prev)
          }
        }}
      >
        <div className="dtpicker-trigger-left">
          <span className="dtpicker-icon">
            <Icon name={hasTime && initialShowTime ? 'clock' : 'calendar'} size={15} />
          </span>
          {displayValue ? (
            <span className="dtpicker-value-text">{displayValue}</span>
          ) : (
            <span className="dtpicker-placeholder-text">{placeholder}</span>
          )}
        </div>

        <div className="dtpicker-trigger-right">
          {showTimezone && (
            <span className="dtpicker-tz-pill" title={`Timezone: ${timezone}`}>
              {tzBadgeText}
            </span>
          )}
          {clearable && value && !disabled && (
            <button
              type="button"
              className="dtpicker-clear-btn"
              onClick={handleClear}
              title="Clear date and time"
              aria-label="Clear date and time"
            >
              <Icon name="x" size={13} />
            </button>
          )}
          <span className="dtpicker-chevron">
            <Icon name={isOpen ? 'chevronDown' : 'chevronDown'} size={14} />
          </span>
        </div>
      </div>

      {/* Dropdown Popover */}
      {isOpen && (
        <div className={`dtpicker-popover placement-${activePlacement}`}>
          {/* Header Bar */}
          <div className="dtpicker-popover-header">
            <div className="dtpicker-month-nav">
              <button
                type="button"
                className="dtpicker-nav-btn"
                onClick={prevMonth}
                title="Previous month"
              >
                <Icon name="chevronLeft" size={15} />
              </button>
              <div className="dtpicker-month-label">
                <b>{MONTH_NAMES[viewMonth]}</b> <span>{viewYear}</span>
              </div>
              <button
                type="button"
                className="dtpicker-nav-btn"
                onClick={nextMonth}
                title="Next month"
              >
                <Icon name="chevronRight" size={15} />
              </button>
            </div>

            <button
              type="button"
              className="dtpicker-today-btn"
              onClick={jumpToToday}
              title="Jump to current date"
            >
              Today
            </button>
          </div>

          {/* Calendar Grid */}
          <div className="dtpicker-calendar">
            <div className="dtpicker-weekdays">
              {DAY_NAMES.map(d => (
                <span key={d} className="dtpicker-weekday-label">
                  {d}
                </span>
              ))}
            </div>

            <div className="dtpicker-days-grid">
              {calendarCells.map((cell, idx) => {
                const isCurrent = cell.isCurrentMonth
                const isSelected = cell.isSelected
                const isToday = cell.isToday

                return (
                  <button
                    key={idx}
                    type="button"
                    className={`dtpicker-day-cell ${
                      !isCurrent ? 'is-outside' : ''
                    } ${isSelected ? 'is-selected' : ''} ${isToday ? 'is-today' : ''}`}
                    onClick={e => {
                      e.stopPropagation()
                      handleSelectDay(cell.year, cell.month, cell.day)
                    }}
                  >
                    {cell.day}
                  </button>
                )
              })}
            </div>
          </div>

          {/* Time Picker Section (Key-Based / Optional) */}
          {initialShowTime && (
            <div className="dtpicker-time-section">
              <div className="dtpicker-time-header">
                <span className="dtpicker-time-title">
                  <Icon name="clock" size={13} />
                  <b>Time</b>
                </span>

                {allowTimeToggle && (
                  <label className="dtpicker-toggle-wrap" title="Toggle time requirement">
                    <input
                      type="checkbox"
                      checked={hasTime}
                      onChange={handleToggleTime}
                    />
                    <span>Include time</span>
                  </label>
                )}
              </div>

              {hasTime && (
                <div className="dtpicker-time-controls">
                  <div className="dtpicker-time-inputs">
                    {/* Hours */}
                    <div className="dtpicker-unit-col">
                      <label className="dtpicker-unit-lbl">HH</label>
                      <input
                        type="number"
                        min={0}
                        max={23}
                        value={String(hours).padStart(2, '0')}
                        onChange={e => {
                          const v = Math.min(23, Math.max(0, parseInt(e.target.value) || 0))
                          handleTimeChange(v, minutes)
                        }}
                        className="dtpicker-number-input"
                      />
                    </div>
                    <span className="dtpicker-time-sep">:</span>
                    {/* Minutes */}
                    <div className="dtpicker-unit-col">
                      <label className="dtpicker-unit-lbl">MM</label>
                      <input
                        type="number"
                        min={0}
                        max={59}
                        step={5}
                        value={String(minutes).padStart(2, '0')}
                        onChange={e => {
                          const v = Math.min(59, Math.max(0, parseInt(e.target.value) || 0))
                          handleTimeChange(hours, v)
                        }}
                        className="dtpicker-number-input"
                      />
                    </div>
                  </div>

                  {/* Quick minute pills */}
                  <div className="dtpicker-quick-minutes">
                    {[0, 15, 30, 45].map(m => (
                      <button
                        key={m}
                        type="button"
                        className={`dtpicker-quick-min-btn ${minutes === m ? 'is-active' : ''}`}
                        onClick={e => {
                          e.stopPropagation()
                          handleTimeChange(hours, m)
                        }}
                      >
                        :{String(m).padStart(2, '0')}
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Timezone Switcher / Region Pipe (Optional) */}
          {showTimezone && (
            <div className="dtpicker-tz-row">
              <span className="dtpicker-tz-info">
                <Icon name="globe" size={12} />
                <span>Region / Zone:</span>
              </span>
              {onTimezoneChange ? (
                <select
                  className="dtpicker-tz-select"
                  value={timezone}
                  onChange={e => onTimezoneChange(e.target.value)}
                  onClick={e => e.stopPropagation()}
                >
                  <option value="local">Local System Time ({tzBadgeText})</option>
                  {COMMON_TIMEZONES.map(t => (
                    <option key={t.code} value={t.code}>
                      {t.label} ({t.offset})
                    </option>
                  ))}
                </select>
              ) : (
                <span className="dtpicker-tz-current" title={timezone}>
                  {timezone === 'local' ? `System (${tzBadgeText})` : timezone}
                </span>
              )}
            </div>
          )}

          {/* Quick Presets Footer */}
          {defaultPresets.length > 0 && (
            <div className="dtpicker-presets-row">
              <span className="dtpicker-presets-lbl">Presets:</span>
              {defaultPresets.map((p, i) => (
                <button
                  key={i}
                  type="button"
                  className="dtpicker-preset-chip"
                  onClick={e => applyPreset(p.getValue, e)}
                >
                  {p.label}
                </button>
              ))}
            </div>
          )}

          {/* Actions Footer */}
          <div className="dtpicker-footer-actions">
            <button
              type="button"
              className="dtpicker-btn-secondary"
              onClick={handleClear}
            >
              Clear
            </button>
            <button
              type="button"
              className="dtpicker-btn-primary"
              onClick={e => {
                e.stopPropagation()
                setIsOpen(false)
              }}
            >
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
