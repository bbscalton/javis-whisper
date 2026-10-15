# Javis Whisper 0.1.4 – "friend next to you" + owner-voice learning (branch `listen-first`, not released)

## Behaviour
1. **Listen first, coach rarely.** Every transcript goes into a rolling window (last 40 lines, tagged `[Me]`/`[Them]`/`[Unsure]`).
   Coach mode never answers a single snippet. After the *other person* finishes and 900 ms of silence pass (cancelled if anyone
   starts talking), a pre-gate checks: last speaker was Them, Calton has been part of this talk in the last 2 min (else it's
   background → ignored), ≥3 speaker turns (or ≥3 lines and ≥20 s), 25 s cooldown since the last whisper.
   Only then one Grok call returns JSON `{topic, confidence, open_question, mid_topic, kind, say}` – the rolling topic state
   is fed back next time. Final gate: no whisper if `mid_topic`, if `kind`=none/SKIP, or if confidence is below 0.55
   (fact error) / 0.6 (answer to a question Calton got) / 0.75 (value-add). Otherwise one ≤15-word line in Ara's voice.
2. **Direct address = command.** Utterance *starting* with Whisper / Javis / Jarvis (also STT slips "Javi", "Travis",
   "Jervis", optional "hey/ok") **and** voice band = Me → fast path: streamed Grok + streamed Ara TTS straight away, with
   phone local time/date in the prompt (~2 s like 0.1.3). "What's the time?" between two people without the wake word is
   just conversation. Wake word from an unsure/far voice is treated as conversation.
3. **Natural pauses only**: coach output is held back if someone starts talking while Grok thinks.

## Owner voice (the core) – soft scoring, favoured approach
Auto-gain stays ON (AGC-off option removed). Each utterance gets a 0..1 **near-owner score** from relative cues that survive AGC:
level above that mic's running background floor; earbud-vs-phone level gap (dual mic); bass share <300 Hz (proximity effect);
spectral tilt (difference-energy ratio); syllable-gap depth p90−p20 dB (direct-to-reverb/echo proxy); and **consistency with
Calton's learned profile**. The profile is an EMA of his features that keeps learning passively from accepted "Whisper …"
commands (weight 1) and very confident Me turns (≥0.85, weight 0.3). Optional "Teach javis my voice" = one sentence, weight 3.
Bands: ≥0.65 **Me**, ≤0.35 **Them**, in between **Unsure → javis waits it out** (no coaching, no commands; line still logged).

SCO is 16 kHz mono with headset DSP (AGC/NS), so absolute level and tone partly flatten; that's why cues are *relative* (vs
floor, vs other mic, vs his own profile). If that proves insufficient on the S948B: (a) a real speaker embedding
(e.g. small ECAPA/x-vector TFLite, ~2–5 MB, enrolled from his commands) as one more cue in the same score; (b) more
weight on the dual-mic gap. No hard switch either way.

## Conversation membership (who Calton is actually talking with)
Everything heard is kept, background included. When a **Me** turn starts within 6 s after a far (**Them**) turn ends and is
on the same topic (shared content words, or a short reply to a question), javis marks *"Calton is in this conversation"*
and attaches that far voice as his **Partner** (coarse voice fingerprint: level vs floor, echo depth, bass, tilt; EMA).
Later far turns are tagged `[Partner]` if they match, `[Other]` if not. Coaching only runs when Calton is engaged
(on-topic reply within the last 3 min) **and** the last far turn was his Partner; the coach JSON also returns
`calton_engaged` (<0.4 → stay quiet). Far conversations with no topical reply from Calton = background → waited out.
The mode + pre-brief steer which single heads-up is most useful.

## Code touchpoints
- `Brain.java` (new): `wake()`, `checkMembership()`, `partnerMatch()`, `engaged()`, rolling `window`/`topic`/`confidence`, `preGate()`, `assessMessages()`, `gate()`, `commandPrompt()`.
- `Proximity.java` (new): `Acc` per-utterance features, `nearScore()`, `band()`, `learn()` (profile in EncryptedSharedPreferences `prox_prof`).
- `WhisperService.java`: `readLoop` feeds features + cancels pending coach when speech starts; `process()` = proximity band →
  wake path / conversation; `coachDecide()`; `speakText()`; `speakStream()` (was `whisper`). Street answers only `Them`; translate skips `Me`.
- `MainActivity.java`: optional "Teach javis my voice"; log shows `near 0.xx` and features per line, and `🧭 topic · conf` decisions.

## Risks
- Partner fingerprint uses distance cues only (not a true voice embedding); two far people at similar distance may be confused.
- Membership needs Calton to reply on topic first, so the first exchange with a new person never gets coached.
- Cue thresholds are first guesses; needs a real session to tune (log lines show the scores).
- Coach is now deliberately quiet; first minute of a talk = no whispers by design.
- One extra Grok call per qualifying pause (JSON, ~140 tokens).
- Profile could drift if someone else says "Whisper, …" close to the earbud; only Me-band commands train it.
- Untested on device (no emulator); build compiles and is signed with the same key.
