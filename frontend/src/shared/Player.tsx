// Video.js 10 player used everywhere a video plays: title on the video, quality menu, speed, captions,
// picture-in-picture, fullscreen and keyboard shortcuts come from the packaged skin.
// Loaded lazily from ui.tsx (VideoPlayer), so pages without a player don't download it.
import '@videojs/react/video/skin.css'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { VideoPlayer as VjsPlayer, VideoSkin } from '@videojs/react/video'
import { HlsJsVideo } from '@videojs/react/media/hlsjs-video'
import { Hls, type HlsJsAdapter } from '@videojs/hlsjs-video'
import type { PlayerEvent } from './ui'

export type PlayerProps = {
  src?: string | null
  title?: string
  live?: boolean
  poster?: string
  onEvent?: (e: PlayerEvent) => void
  /** the <video> element, for currentTime and seeking */
  videoRef?: RefObject<HTMLVideoElement | null>
  onTimeUpdate?: (sec: number) => void
}

const ENGINE = { hlsJs: { liveSyncDurationCount: 3, enableInterstitialPlayback: true } }

export default function Player({ src, title, live, poster, onEvent, videoRef, onTimeUpdate }: PlayerProps) {
  const media = useRef<HlsJsAdapter>(null)
  const cb = useRef(onEvent)
  cb.current = onEvent
  const [err, setErr] = useState<string | null>(null)

  // The adapter creates hls.js when the source loads (and again if it changes); hook its events onto each one.
  useEffect(() => {
    setErr(null)
    let hooked: Hls | null = null
    let manual = -1
    const hook = () => {
      const h = media.current?.engine
      // A quality picked in the menu: Video.js sets hls.js nextLevel, which waits until everything already buffered has
      // played (a short video never switches). Switch at once instead, as viewers expect.
      if (h && h === hooked && h.manualLevel !== manual) {
        manual = h.manualLevel
        if (manual >= 0 && h.currentLevel !== manual) h.currentLevel = manual
      }
      if (!h || h === hooked) return
      hooked = h
      manual = h.manualLevel
      h.on(Hls.Events.INTERSTITIAL_ASSET_STARTED, (_e, d) => cb.current?.({ type: 'interstitial-start', uri: d.asset?.uri }))
      h.on(Hls.Events.INTERSTITIAL_ASSET_ENDED, (_e, d) => cb.current?.({ type: 'interstitial-end', uri: d.asset?.uri }))
      h.on(Hls.Events.LEVEL_SWITCHED, (_e, d) => {
        const l = h.levels[d.level]
        if (l) cb.current?.({ type: 'level', height: l.height, width: l.width, bitrate: l.bitrate })
      })
      h.on(Hls.Events.FRAG_BUFFERED, () => setErr(null))
      h.on(Hls.Events.ERROR, (_e, d) => {
        if (!d.fatal) return
        setErr(d.details)
        cb.current?.({ type: 'error', message: d.details })
        if (d.type === Hls.ErrorTypes.NETWORK_ERROR) setTimeout(() => h.startLoad(), 3000)
        else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) h.recoverMediaError()
      })
    }
    // ponytail: polls for the engine every 250 ms; switch to an adapter event if Video.js adds one
    hook()
    const t = setInterval(hook, 250)
    return () => clearInterval(t)
  }, [src])

  return (
    <div className="player vjs-player">
      <VjsPlayer title={title ?? null} poster={poster}>
        <VideoSkin style={{ width: '100%', height: '100%' }}>
          <HlsJsVideo
            ref={videoRef}
            mediaRef={media}
            source={src ? { src, engine: ENGINE } : null}
            streamType={live ? 'live' : 'on-demand'}
            autoPlay
            muted={live}
            playsInline
            onTimeUpdate={e => {
              onTimeUpdate?.(e.currentTarget.currentTime)
              // live: which instant of the channel's timeline is on screen, so overlays (ad banners) match the picture
              const at = live ? media.current?.engine?.playingDate?.getTime() : undefined
              if (at) cb.current?.({ type: 'clock', at })
            }}
            onPlaying={() => cb.current?.({ type: 'playing' })}
          />
        </VideoSkin>
      </VjsPlayer>
      {err && <div className="player-top"><span className="badge bad">Playback problem: {err}. Retrying…</span></div>}
    </div>
  )
}
