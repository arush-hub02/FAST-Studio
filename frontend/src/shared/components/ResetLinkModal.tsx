import { Icon, Modal, useToast } from '../ui'

/** Shows an admin-issued reset link with a copy button. */
export function ResetLinkModal({ who, path, note, onClose }: { who: string; path: string; note: string; onClose: () => void }) {
  const url = window.location.origin + path
  const toast = useToast()
  return (
    <Modal title={`Reset link for ${who}`} onClose={onClose} footer={<button className="btn primary" onClick={onClose}>Done</button>}>
      <div className="callout warn"><Icon name="shield" /><div className="small">{note}</div></div>
      <div className="row"><input className="mono" readOnly value={url} onFocus={e => e.target.select()} aria-label="Reset link" />
        <button className="btn" onClick={() => navigator.clipboard.writeText(url).then(() => toast('Link copied', 'ok'))}>Copy</button></div>
    </Modal>
  )
}
