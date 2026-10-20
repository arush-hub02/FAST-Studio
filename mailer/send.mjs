// Sends ONE email with nodemailer. The Java server (mail.MailService) runs this for every queued email.
// stdin:  {"from","to","subject","text","html","outboxFile"}
// env:    SMTP_URL, e.g. smtps://user:password@smtp.example.com:465 or smtp://localhost:2525. The server sets it from
//         FASTSTUDIO_SMTP_URL (.env / data-pilot/pilot.env -> application.yaml faststudio.mail.smtp-url); to test this
//         script on its own: SMTP_URL=smtp://127.0.0.1:2525 node send.mjs < message.json
//         Without SMTP_URL the finished message is written to outboxFile (.eml, opens in any mail app) instead.
// stdout: one JSON line {"ok":true,"delivered":true,"detail":...} or {"ok":false,"error":...}
import nodemailer from 'nodemailer'
import fs from 'node:fs/promises'
import path from 'node:path'

const out = (o, code = 0) => { process.stdout.write(JSON.stringify(o) + '\n'); process.exitCode = code }

try {
  let input = ''
  for await (const chunk of process.stdin) input += chunk
  const m = JSON.parse(input)
  const message = { from: m.from, to: m.to, subject: m.subject, text: m.text, html: m.html }
  if (process.env.SMTP_URL) {
    const info = await nodemailer.createTransport(process.env.SMTP_URL).sendMail(message)
    out({ ok: true, delivered: true, detail: `${info.messageId} ${info.response ?? ''}`.trim() })
  } else {
    const info = await nodemailer.createTransport({ streamTransport: true, buffer: true, newline: 'windows' }).sendMail(message)
    await fs.mkdir(path.dirname(m.outboxFile), { recursive: true })
    await fs.writeFile(m.outboxFile, info.message)
    out({ ok: true, delivered: false, detail: m.outboxFile })
  }
} catch (e) {
  out({ ok: false, error: String(e?.message ?? e).slice(0, 500) }, 1)
}
