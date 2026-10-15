# Javis Whisper (prototype)

Earbud AI companion by Neuereatec. Listens through your Bluetooth earbud (SCO) and the phone mic, transcribes, asks Grok for a short hint and whispers it back into your earbud.

**Download:** https://bbscalton.github.io/javis-whisper/

## Architecture
```
Earbud mic (SCO, "Me") ─┐                       ┌─ PC01 Docker: javis-whisper (Node + SQLite)
Phone mic ("Them") ─────┤ VAD → utterance PCM ──► /api/stt   → Google Speech (server-side key, audio never stored)
                        │      transcript ──────► /api/whisper → Grok (streamed) → stored text
Earbud ◄── Android TTS ◄┘  ◄──── whisper text ──┘  sessions/turns/summaries/learned-notes in C:\javis-whisper\data
```
* **No API keys in the APK or this repo.** The server reads them read-only from files on PC01. Each phone pairs with a 6‑digit code and gets its own revocable token (stored in EncryptedSharedPreferences).
* **Text only.** Audio stays in RAM for one utterance, is sent for STT and discarded. The phone keeps only a small offline upload queue.
* **Learning loop:** at session end the server summarises the session and updates a compact "what helps Calton" note; the last 8 summaries + the note go into every system prompt.
* **Modes:** Coach (corrections / background / talking points), Translate (auto-detect up to 3 languages → English whisper + suggested reply in their language), Street (brief answers to passers-by).
* **Multi-speaker (best effort, v1):** turns labelled Me/Them by which mic hears them louder. True diarization is v2.

## Server
`server/` – `docker compose up -d --build` on PC01. Public API: https://whisper.neuereatec.org (Cloudflare tunnel → localhost:3120; only `/health` and token-protected `/api/*` answer — `/admin` returns 404 through the tunnel). Admin page (PC01 only): `http://127.0.0.1:3120/admin?k=<C:\javis-whisper\data\admin_token.txt>` (pairing codes, device list, revoke, export). Backups every 6 h to `data/backups` (14 kept) and `data/exports/javis-whisper-latest.json`.
