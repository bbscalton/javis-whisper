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

## 0.1.23 – Advisor mode (wake word → one whisper → silence)
* **Default mode "Advisor"**: the existing foreground service listens on the earbud mic only. Frames go to an **on-device** Vosk keyword grammar (`vosk-model-small-en-us-0.15`, bundled in assets; fetch with `tools/fetch-vosk-model.sh` before building – not committed). Wake word `jarvis` by default, or a custom word in Settings (checked against the offline dictionary; falls back to jarvis if unknown).
* **Tight trigger** (`WakeGate`): all words of the phrase recognised consecutively with confidence ≥ 0.95 (Strict 0.97 / Relaxed 0.90 in Settings), spoken length ≥ 0.35 s per word, sound-alike decoys in the grammar (harvest, service, nervous, travis, …), a 4 s cooldown, and detection muted while javis speaks plus 1.5 s afterwards.
* **Audio** (`PcmRing`): ~10 s rolling PCM ring buffer in RAM before the wake word, then up to 4 s after it (or 800 ms of silence). It's sent once to the existing xAI STT and then zero-filled. It is never written to disk, and nothing leaves the phone before the wake word.
* **Advice**: transcript + the last 5 text notes → Grok (same key/model) → `AdviceText.enforce` (max 3 sentences, question sentences and greetings stripped) → existing Ara TTS path. Then silence until the next wake word.
* **Continuity**: after each piece of advice a one-line text note is saved (Room `turns`, kind `advice_note`) and the recent notes feed the next prompt.
* Pairing and xAI key handling are unchanged. Unit tests: `./gradlew testReleaseUnitTest` (sentence trim, question strip, wake gate, ring buffer wipe/no-persistence).

## Server (optional backup sync, v0.1.1 path)
`server/` – `docker compose up -d --build` on PC01. Public API: https://whisper.neuereatec.org (Cloudflare tunnel → localhost:3120; `/`, `/health`, token-protected `/api/*`, and key-protected `/admin` (401 on wrong key, 10 failed tries per IP or 30 in total per 15 min → 429). Admin page: `https://whisper.neuereatec.org/admin?k=<C:\javis-whisper\data\admin_token.txt>` (pairing codes, device list, revoke, export). Backups every 6 h to `data/backups` (14 kept) and `data/exports/javis-whisper-latest.json`.
