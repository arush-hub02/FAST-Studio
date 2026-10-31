import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, type Alert, type AuditRow } from '../../shared/api'
import { useOrg } from '../../app/App'
import { ago, Empty, fmtDayTime, Icon, Loading, Modal, PageHead, Status, useAction, useApi } from '../../shared/ui'
import { describe } from '../home/Home'

export default function Monitoring() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'alerts'
  const [filter, setFilter] = useState('open')
  const alerts = useApi<Alert[]>(`/api/orgs/${org.slug}/alerts?filter=${filter}`, [], 20000)
  const audit = useApi<AuditRow[]>(tab === 'audit' ? `/api/orgs/${org.slug}/audit?limit=200` : null)
  const [resolving, setResolving] = useState<Alert | null>(null)
  const { busy, run } = useAction()
  return (
    <div className="page">
      <PageHead title="Monitoring" subtitle="Problems the system found by itself, and a permanent record of who changed what."
        actions={tab === 'alerts' && <button className="btn" disabled={busy} onClick={() => run(() => api(`/api/orgs/${org.slug}/alerts/scan`, { method: 'POST' }), 'Checked everything again').then(alerts.reload)}><Icon name="refresh" size={16} />Re-check now</button>} />
      <div className="tabs">
        <button className={'tab' + (tab === 'alerts' ? ' active' : '')} onClick={() => setParams({ tab: 'alerts' })}>Alerts</button>
        <button className={'tab' + (tab === 'audit' ? ' active' : '')} onClick={() => setParams({ tab: 'audit' })}>Activity log (audit)</button>
      </div>
      {tab === 'alerts' ? <>
        <div className="between">
          <div className="seg"><button className={filter === 'open' ? 'active' : ''} onClick={() => setFilter('open')}>Needs attention</button><button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>All, incl. resolved</button></div>
          <span className="faint">Acknowledge = "I'm on it". Resolved = fixed, with a note. The scanner re-opens anything that's still broken.</span>
        </div>
        {!alerts.data ? <Loading /> : alerts.data.length === 0 ? <div className="card"><Empty icon="check" title="All clear" text="No open alerts. The system re-checks schedules, licences and videos every minute." /></div> :
          alerts.data.map(a => (
            <section key={a.id} className="card card-pad row" style={{ alignItems: 'flex-start', gap: 14 }}>
              <div className={'empty-icon'} style={{ width: 40, height: 40, background: a.severity === 'CRITICAL' ? 'var(--bad-soft)' : 'var(--warn-soft)', color: a.severity === 'CRITICAL' ? 'var(--bad)' : 'var(--warn)' }}><Icon name="alert" /></div>
              <div className="grow stack" style={{ gap: 4 }}>
                <div className="row-wrap"><h3>{a.title}</h3><Status value={a.severity} /><Status value={a.status} /></div>
                <p className="muted">{a.detail}</p>
                <p className="small"><b>Suggested fix:</b> {a.suggestion}</p>
                {a.resolution && <p className="small faint">Resolution: {a.resolution}</p>}
                <span className="faint">Raised {ago(a.createdAt)} · updated {ago(a.updatedAt)}</span>
              </div>
              <div className="stack" style={{ gap: 6 }}>
                {a.link && <Link className="btn sm" to={org.base + a.link}>Go fix</Link>}
                {org.can('ALERTS_EDIT') && a.status === 'OPEN' && <button className="btn sm" disabled={busy} onClick={() => run(() => api(`/api/orgs/${org.slug}/alerts/${a.id}/acknowledge`, { method: 'POST' }), 'Acknowledged').then(alerts.reload)}>Acknowledge</button>}
                {org.can('ALERTS_EDIT') && a.status !== 'RESOLVED' && <button className="btn sm" onClick={() => setResolving(a)}>Resolve…</button>}
              </div>
            </section>
          ))}
      </> : (
        <section className="card">
          {!audit.data ? <div className="card-pad"><Loading /></div> : (
            <table className="table"><thead><tr><th>When</th><th>Who</th><th>What</th><th>Details</th></tr></thead>
              <tbody>{audit.data.map(r => (
                <tr key={r.id}><td className="small" title={r.createdAt}>{fmtDayTime(r.createdAt)}</td><td><b>{r.actor}</b></td><td>{describe(r.action)}<div className="faint mono">{r.action}</div></td><td className="small muted">{r.detail}</td></tr>
              ))}</tbody></table>
          )}
        </section>
      )}
      {resolving && <Resolve alert={resolving} onClose={() => setResolving(null)} onDone={() => { setResolving(null); alerts.reload() }} />}
    </div>
  )
}

function Resolve({ alert, onClose, onDone }: { alert: Alert; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const [note, setNote] = useState('')
  const { busy, run } = useAction()
  return (
    <Modal title="Resolve alert" subtitle={alert.title} onClose={onClose} footer={<><button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy || note.trim().length < 3} onClick={() => run(() => api(`/api/orgs/${org.slug}/alerts/${alert.id}/resolve`, { body: { resolution: note } }), 'Resolved').then(r => r !== undefined && onDone())}>Mark resolved</button></>}>
      <label className="field">What fixed it?<span className="hint">Kept in the record. If the problem is still there, the next automatic check raises it again.</span>
        <textarea value={note} onChange={e => setNote(e.target.value)} autoFocus /></label>
    </Modal>
  )
}
