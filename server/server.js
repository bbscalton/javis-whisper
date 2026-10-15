// Javis Whisper server: text-only store + STT/Grok proxy. No audio is ever written anywhere.
'use strict';
const http = require('node:http'), crypto = require('node:crypto'), fs = require('node:fs'), path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const DATA = process.env.DATA_DIR || '/data', PORT = +process.env.PORT || 3110;
const MODEL = process.env.XAI_MODEL || 'grok-4-fast-non-reasoning';
fs.mkdirSync(path.join(DATA, 'backups'), { recursive: true }); fs.mkdirSync(path.join(DATA, 'exports'), { recursive: true });
function readSecret(file, name) { // file may be raw key or KEY=VALUE env file
  try { const t = fs.readFileSync(file, 'utf8'); const m = t.match(new RegExp('^' + name + '\\s*=\\s*"?([^"\\r\\n]+)', 'm')); return (m ? m[1] : t.split(/\r?\n/)[0]).trim(); } catch { return process.env[name] || ''; }
}
const XAI = readSecret(process.env.XAI_KEY_FILE || '/secrets/xai.env', 'XAI_API_KEY');
const GKEY = readSecret(process.env.GOOGLE_KEY_FILE || '/secrets/google_key.txt', 'GOOGLE_API_KEY');
const G_PKG = process.env.GOOGLE_ANDROID_PACKAGE || '', G_CERT = process.env.GOOGLE_ANDROID_CERT || '';
const INDEX = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const adminFile = path.join(DATA, 'admin_token.txt');
if (!fs.existsSync(adminFile)) fs.writeFileSync(adminFile, crypto.randomBytes(18).toString('base64url'));
const ADMIN = fs.readFileSync(adminFile, 'utf8').trim();
const db = new DatabaseSync(path.join(DATA, 'whisper.db'));
db.exec(`PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS devices(id INTEGER PRIMARY KEY, name TEXT, token_hash TEXT UNIQUE, created TEXT, last_seen TEXT, revoked INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS codes(code TEXT PRIMARY KEY, expires INTEGER);
CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, client_id TEXT UNIQUE, device_id INTEGER, mode TEXT, prebrief TEXT, started TEXT, ended TEXT, summary TEXT);
CREATE TABLE IF NOT EXISTS turns(id INTEGER PRIMARY KEY, client_id TEXT UNIQUE, session_id INTEGER, ts TEXT, kind TEXT, speaker TEXT, lang TEXT, text TEXT);
CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);`);
const log = (...a) => console.log(new Date().toISOString(), ...a);
const now = () => new Date().toISOString();
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const kvGet = k => db.prepare('SELECT v FROM kv WHERE k=?').get(k)?.v || '';
const kvSet = (k, v) => db.prepare('INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v').run(k, v);

function send(res, code, obj, type = 'application/json') { res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' }); res.end(type.startsWith('application/json') ? JSON.stringify(obj) : obj); }
function body(req) { return new Promise((ok, no) => { let n = 0; const b = []; req.on('data', c => { n += c.length; if (n > 4e6) { no(new Error('too big')); req.destroy(); } else b.push(c); }); req.on('end', () => { try { ok(b.length ? JSON.parse(Buffer.concat(b)) : {}); } catch (e) { no(e); } }); }); }
function auth(req) {
  const t = (req.headers.authorization || '').replace(/^Bearer /, ''); if (!t) return null;
  const d = db.prepare('SELECT * FROM devices WHERE token_hash=? AND revoked=0').get(sha(t));
  if (d) db.prepare('UPDATE devices SET last_seen=? WHERE id=?').run(now(), d.id); return d;
}
const isAdmin = (req, u) => { const k = u.searchParams.get('k') || req.headers['x-admin-token'] || ''; return k.length === ADMIN.length && crypto.timingSafeEqual(Buffer.from(k), Buffer.from(ADMIN)); };

// ---------- Grok ----------
const MODES = {
  coach: 'COACH mode. Listen to the conversation. If someone says something factually wrong, whisper the correction. If useful background or a smart point Calton could make exists, whisper it. If nothing is worth saying, reply with exactly "-".',
  translate: 'TRANSLATE mode. If the heard text is not English, reply as: "<English translation> || Say: <short suggested reply in THEIR language>". If it is English and from Calton, reply "-".',
  street: 'STREET mode. Passers-by may ask Calton questions. If a question is asked, whisper a brief, correct, friendly answer Calton can repeat. Otherwise reply "-".',
};
function systemPrompt(sess) {
  const sums = db.prepare("SELECT started, mode, summary FROM sessions WHERE summary IS NOT NULL AND summary<>'' AND id<>? ORDER BY id DESC LIMIT 8").all(sess.id);
  return [
    'You are Javis Whisper, a discreet AI companion whispering into Calton Andrew\'s earbud during live conversations (Guyana, Caribbean context).',
    MODES[sess.mode] || MODES.coach,
    'Rules: at most 20 words, plain spoken text, no markdown, no preamble, never repeat what was just said. Transcripts are from speech recognition and may contain errors. Speaker labels: "Me" = Calton (earbud mic), "Them" = others (phone mic), "Speaker" = unknown.',
    sess.prebrief ? 'PRE-BRIEF from Calton (who/topic): ' + sess.prebrief : '',
    kvGet('learned') ? 'What you have learned about Calton\'s preferences from past sessions: ' + kvGet('learned') : '',
    sums.length ? 'Recent past session summaries:\n' + sums.map(s => `- ${s.started.slice(0, 10)} ${s.mode}: ${s.summary}`).join('\n') : '',
  ].filter(Boolean).join('\n\n');
}
function contextMsgs(sessId) {
  const rows = db.prepare('SELECT kind,speaker,lang,text FROM turns WHERE session_id=? ORDER BY id DESC LIMIT 30').all(sessId).reverse();
  return rows.map(r => r.kind === 'whisper' ? { role: 'assistant', content: r.text } : { role: 'user', content: `[${r.speaker || 'Speaker'}${r.lang ? ' ' + r.lang : ''}] ${r.text}` });
}
async function grok(messages, { stream = false, max = 80 } = {}) {
  if (!XAI) throw new Error('xAI key missing on server');
  const r = await fetch('https://api.x.ai/v1/chat/completions', { method: 'POST', headers: { Authorization: 'Bearer ' + XAI, 'Content-Type': 'application/json' }, body: JSON.stringify({ model: MODEL, stream, max_tokens: max, temperature: 0.4, messages }) });
  if (!r.ok) throw new Error('xAI ' + r.status + ' ' + (await r.text()).slice(0, 200));
  return r;
}
async function* sse(r) { const dec = new TextDecoder(); let buf = ''; for await (const c of r.body) { buf += dec.decode(c, { stream: true }); let i; while ((i = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1); if (!line.startsWith('data:')) continue; const d = line.slice(5).trim(); if (d === '[DONE]') return; try { const t = JSON.parse(d).choices?.[0]?.delta?.content; if (t) yield t; } catch {} } } }

// ---------- STT (audio only passes through memory) ----------
async function stt({ audio, lang = 'en-US', alt = [] }) {
  if (!GKEY) throw new Error('Google key missing on server');
  const h = { 'Content-Type': 'application/json' }; if (G_PKG) { h['X-Android-Package'] = G_PKG; h['X-Android-Cert'] = G_CERT; }
  const r = await fetch('https://speech.googleapis.com/v1/speech:recognize?key=' + GKEY, { method: 'POST', headers: h, body: JSON.stringify({ config: { encoding: 'LINEAR16', sampleRateHertz: 16000, languageCode: lang, alternativeLanguageCodes: alt.slice(0, 3), enableAutomaticPunctuation: true, model: 'latest_short' }, audio: { content: audio } }) });
  const j = await r.json(); if (!r.ok) throw new Error('STT ' + r.status + ' ' + JSON.stringify(j.error?.message || j).slice(0, 200));
  const res = (j.results || []).filter(x => x.alternatives?.[0]?.transcript);
  return { text: res.map(x => x.alternatives[0].transcript).join(' ').trim(), lang: res[0]?.languageCode || lang };
}

function addTurn(sid, t, kind) {
  db.prepare('INSERT OR IGNORE INTO turns(client_id,session_id,ts,kind,speaker,lang,text) VALUES(?,?,?,?,?,?,?)').run(t.clientId || crypto.randomUUID(), sid, t.ts || now(), kind || t.kind || 'heard', t.speaker || '', t.lang || '', String(t.text || '').slice(0, 4000));
}
function sessFor(dev, id) { return db.prepare('SELECT * FROM sessions WHERE (id=? OR client_id=?)').get(Number(id) || -1, String(id)); }

async function endSession(s) {
  const turns = db.prepare('SELECT kind,speaker,text FROM turns WHERE session_id=? ORDER BY id').all(s.id);
  if (!turns.length) { db.prepare('UPDATE sessions SET ended=? WHERE id=?').run(now(), s.id); return ''; }
  const tx = turns.map(t => `${t.kind === 'whisper' ? 'WHISPER' : t.speaker || 'Speaker'}: ${t.text}`).join('\n').slice(-12000);
  const r = await (await grok([{ role: 'system', content: 'Summarise this earbud-companion session in at most 3 sentences: who, topic, outcomes, and which whispers were useful or ignored.' }, { role: 'user', content: `Mode ${s.mode}. Pre-brief: ${s.prebrief || '-'}\n${tx}` }], { max: 160 })).json();
  const summary = r.choices?.[0]?.message?.content?.trim() || '';
  db.prepare('UPDATE sessions SET ended=?, summary=? WHERE id=?').run(now(), summary, s.id);
  try { // learning loop over full history: maintain a compact preference note
    const r2 = await (await grok([{ role: 'system', content: 'Maintain a compact note (max 120 words) of what helps Calton: preferred whisper style, recurring people/topics, corrections. Merge the new session into the existing note. Output only the note.' }, { role: 'user', content: `Existing note: ${kvGet('learned') || '(none)'}\nNew session: ${summary}` }], { max: 220 })).json();
    const n = r2.choices?.[0]?.message?.content?.trim(); if (n) kvSet('learned', n);
  } catch (e) { log('learn fail', e.message); }
  return summary;
}
function exportAll() {
  return { exported: now(), learned: kvGet('learned'), sessions: db.prepare('SELECT * FROM sessions ORDER BY id').all().map(s => ({ ...s, turns: db.prepare('SELECT ts,kind,speaker,lang,text FROM turns WHERE session_id=? ORDER BY id').all(s.id) })) };
}
function backup() {
  try {
    const d = now().slice(0, 10), f = path.join(DATA, 'backups', `whisper-${d}.db`);
    if (fs.existsSync(f)) fs.unlinkSync(f); db.exec(`VACUUM INTO '${f}'`);
    fs.writeFileSync(path.join(DATA, 'exports', 'javis-whisper-latest.json'), JSON.stringify(exportAll(), null, 1));
    const old = fs.readdirSync(path.join(DATA, 'backups')).sort().slice(0, -14); old.forEach(x => fs.unlinkSync(path.join(DATA, 'backups', x)));
    log('backup ok', f);
  } catch (e) { log('backup fail', e.message); }
}
setTimeout(backup, 10e3); setInterval(backup, 6 * 3600e3);

const adminHtml = (k, msg = '') => `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Javis Whisper admin</title><style>body{font-family:system-ui;background:#0b1e3a;color:#fff;max-width:720px;margin:auto;padding:20px}button{padding:10px 18px;border-radius:10px;border:0;background:#1e88e5;color:#fff;font-size:16px}td{padding:6px;border-bottom:1px solid #234}.code{font-size:48px;letter-spacing:8px;background:#132f57;padding:16px;border-radius:12px;text-align:center}</style>
<h1>Javis Whisper · devices</h1>${msg}
<form method=post action="/admin/code?k=${k}"><button>New pairing code (10 min)</button></form>
<table><tr><th>Device</th><th>Paired</th><th>Last seen</th><th></th></tr>${db.prepare('SELECT * FROM devices ORDER BY id DESC').all().map(d => `<tr><td>${esc(d.name)}</td><td>${d.created.slice(0, 16)}</td><td>${(d.last_seen || '').slice(0, 16)}</td><td>${d.revoked ? 'revoked' : `<form method=post action="/admin/revoke?k=${k}&id=${d.id}"><button style="background:#c62828">Revoke</button></form>`}</td></tr>`).join('')}</table>
<p>Sessions: ${db.prepare('SELECT count(*) n FROM sessions').get().n} · Turns: ${db.prepare('SELECT count(*) n FROM turns').get().n} · <a style=color:#9cf href="/admin/export?k=${k}">Export JSON</a></p>`;
const esc = s => String(s || '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x'), p = u.pathname;
  try {
    if (p === '/' || p === '/index.html') return send(res, 200, INDEX, 'text/html; charset=utf-8');
    if (p === '/health') return send(res, 200, { ok: true, xai: !!XAI, google: !!GKEY, model: MODEL });
    if (p.startsWith('/admin')) {
      if (req.headers['cf-connecting-ip'] || req.headers['cf-ray']) return send(res, 404, { error: 'not found' }); // admin is LAN/localhost only, never via the tunnel
      if (!isAdmin(req, u)) return send(res, 403, { error: 'admin token required' });
      const k = encodeURIComponent(u.searchParams.get('k') || '');
      if (p === '/admin/code' && req.method === 'POST') { const code = String(crypto.randomInt(100000, 999999)); db.prepare('INSERT OR REPLACE INTO codes VALUES(?,?)').run(code, Date.now() + 600e3); return send(res, 200, adminHtml(k, `<p>Enter this code in the app (Settings → Pair):</p><div class=code>${code}</div>`), 'text/html'); }
      if (p === '/admin/revoke' && req.method === 'POST') { db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(+u.searchParams.get('id')); return send(res, 200, adminHtml(k, '<p>Device revoked.</p>'), 'text/html'); }
      if (p === '/admin/export') return send(res, 200, exportAll());
      return send(res, 200, adminHtml(k), 'text/html');
    }
    if (p === '/api/pair' && req.method === 'POST') {
      const b = await body(req); const c = db.prepare('SELECT * FROM codes WHERE code=?').get(String(b.code || '').trim());
      if (!c || c.expires < Date.now()) return send(res, 403, { error: 'invalid or expired code' });
      db.prepare('DELETE FROM codes WHERE code=?').run(c.code);
      const token = crypto.randomBytes(32).toString('base64url');
      const r = db.prepare('INSERT INTO devices(name,token_hash,created) VALUES(?,?,?)').run(String(b.name || 'phone').slice(0, 60), sha(token), now());
      log('paired device', r.lastInsertRowid); return send(res, 200, { token, deviceId: Number(r.lastInsertRowid) });
    }
    if (!p.startsWith('/api/')) return send(res, 404, { error: 'not found' });
    const dev = auth(req); if (!dev) return send(res, 401, { error: 'device not paired or revoked' });
    if (p === '/api/ping') return send(res, 200, { ok: true, device: dev.name, model: MODEL });
    if (p === '/api/stt' && req.method === 'POST') { const b = await body(req); const r = await stt(b); b.audio = null; return send(res, 200, r); }
    if (p === '/api/session' && req.method === 'POST') {
      const b = await body(req); let s = b.clientId && sessFor(dev, b.clientId);
      if (!s) { db.prepare('INSERT INTO sessions(client_id,device_id,mode,prebrief,started) VALUES(?,?,?,?,?)').run(b.clientId || crypto.randomUUID(), dev.id, b.mode || 'coach', String(b.prebrief || '').slice(0, 2000), b.started || now()); s = sessFor(dev, b.clientId); }
      return send(res, 200, { id: s.id });
    }
    let m;
    if ((m = p.match(/^\/api\/session\/([\w-]+)\/end$/)) && req.method === 'POST') { const s = sessFor(dev, m[1]); if (!s) return send(res, 404, {}); return send(res, 200, { summary: await endSession(s) }); }
    if ((m = p.match(/^\/api\/session\/([\w-]+)$/))) { const s = sessFor(dev, m[1]); if (!s) return send(res, 404, {}); return send(res, 200, { ...s, turns: db.prepare('SELECT ts,kind,speaker,lang,text FROM turns WHERE session_id=? ORDER BY id').all(s.id) }); }
    if (p === '/api/sessions') return send(res, 200, db.prepare('SELECT s.*, (SELECT count(*) FROM turns t WHERE t.session_id=s.id) n FROM sessions s ORDER BY id DESC LIMIT 100').all());
    if (p === '/api/export') return send(res, 200, exportAll());
    if (p === '/api/turns' && req.method === 'POST') { // offline queue upload
      const b = await body(req); for (const t of b.turns || []) { const s = sessFor(dev, t.sessionId); if (s) addTurn(s.id, t); } return send(res, 200, { ok: true });
    }
    if (p === '/api/whisper' && req.method === 'POST') {
      const b = await body(req); const s = sessFor(dev, b.sessionId); if (!s) return send(res, 404, { error: 'session' });
      if (b.mode && b.mode !== s.mode) { db.prepare('UPDATE sessions SET mode=? WHERE id=?').run(b.mode, s.id); s.mode = b.mode; }
      addTurn(s.id, b.heard, 'heard');
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
      let out = '';
      try { const r = await grok([{ role: 'system', content: systemPrompt(s) }, ...contextMsgs(s.id)], { stream: true, max: 70 }); for await (const t of sse(r)) { out += t; res.write('data: ' + JSON.stringify({ t }) + '\n\n'); } }
      catch (e) { res.write('data: ' + JSON.stringify({ error: e.message }) + '\n\n'); }
      out = out.trim(); if (out && out !== '-') addTurn(s.id, { text: out, speaker: 'Javis' }, 'whisper');
      res.end('data: [DONE]\n\n'); return;
    }
    send(res, 404, { error: 'not found' });
  } catch (e) { log('ERR', p, e.message); if (!res.headersSent) send(res, 500, { error: e.message }); else res.end(); }
}).listen(PORT, () => log('Javis Whisper server on', PORT, 'xai', !!XAI, 'google', !!GKEY));
