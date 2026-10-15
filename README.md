# Javis Whisper (prototype)

Earbud AI companion by Neuereatec. Listens through your Bluetooth earbud (SCO) and the phone mic, transcribes, asks Grok for a short hint and whispers it back into your earbud.

**Download:** https://bbscalton.github.io/javis-whisper/

## Architecture (v0.1.2 – phone calls xAI directly)
```
Earbud mic (SCO, "Me") ─┐  on-phone VAD (500 ms)     wss://api.x.ai/v1/stt   (audio streamed while they talk)
Phone mic ("Them") ─────┘ ───────────────────────────► transcript ─► Grok grok-4.20-non-reasoning (stream)
Earbud ◄─ AudioTrack ◄── PCM 16 kHz ◄── wss://api.x.ai/v1/tts voice=ara (fed token by token, socket kept warm)
Room DB on phone: sessions, turns, summaries, learned note  ──(optional, off)──► PC01 server sync
```
* **No keys in the APK or repo.** The user enters the xAI key in Settings (EncryptedSharedPreferences).
* **Text only** stored (Room, on the phone). Audio exists only in RAM / in flight to xAI STT.
* **Persona:** javis – warm, sharp, concise, plain words, leads with the answer, one sentence ≤15 words, says `SKIP` (silence) when nothing useful.
* **Learning loop:** at STOP the phone asks Grok for a 3‑sentence summary and updates a short "what helps Calton" note; the note + last 8 summaries go into the system prompt.
* **Modes:** Coach, Translate (xAI STT auto-language → English meaning + reply in their language), Street.
* **Multi-speaker (best effort):** Me/Them by which mic has the higher SNR during the utterance. Diarization is v2.

Measured latency, end of speech → first Ara audio (pipeline replayed from PC01 against xAI, same endpoints/params as the app): ~2.0–2.3 s (v0.1.1: ~6 s).

## Server (optional backup sync, v0.1.1 path)
`server/` – `docker compose up -d --build` on PC01. Public API: https://whisper.neuereatec.org (Cloudflare tunnel → localhost:3120; `/`, `/health`, token-protected `/api/*`, and key-protected `/admin` (401 on wrong key, 10 failed tries per IP or 30 in total per 15 min → 429). Admin page: `https://whisper.neuereatec.org/admin?k=<C:\javis-whisper\data\admin_token.txt>` (pairing codes, device list, revoke, export). Backups every 6 h to `data/backups` (14 kept) and `data/exports/javis-whisper-latest.json`.
