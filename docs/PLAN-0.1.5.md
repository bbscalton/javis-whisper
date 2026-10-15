# Javis Whisper 0.1.5 – people memory + room context (design only, not released)

Builds on 0.1.4 without changing its gates: owner voice profile, topic buffer, wait-when-unsure, wake word, partner linking.
Everything is **text only** and **on the phone** (Room DB; file encrypted with SQLCipher, key in Android Keystore via
EncryptedSharedPreferences). Never audio. Optional PC01 sync stays off by default.

## 1. People memory
### Data (Room DB v2 with migration)
- `people(id, name, aliases, relationship, notes, voiceSig, voiceN, firstSeen, lastSeen, pinned)` – `voiceSig` = the coarse
  partner-link fingerprint (snrDb, modDb, lowRatio, hfRatio EMA). A hint, not an identity; low weight until voiceN ≥ 5.
- `convos(id, sessionId, personId, started, ended, setting, topic, keyFacts, promises, openItems, summary)` – one per partner
  conversation (a session can hold several people).
- `turns.personId` (nullable) for filtering in Review.

### Learning names
- Pre-brief ("Meeting John Persaud, GBTI loans") → one Grok call at START → `{name, relationship, topic}`; fuzzy-match/create person.
- Live: regex ("this is X", "I'm X", "my name is X", "meet X", "thanks, X") + `partner_name`/`partner_name_conf` in the
  existing coach JSON; attach only if conf ≥ 0.7 and not contradicted, else "Unknown person #n".
- Voice: when a partner is linked, compare with `people.voiceSig`; ≥ 0.75 **and** (name heard / pre-brief agrees / ≥ 3 prior
  linked convos) → that person; otherwise stay unknown (wait, like the unsure band).

### Retrieval at live time
Trigger: partner linked, known name heard, or pre-brief names someone. Inject a person card (name, relationship, ≤ 40-word
notes) + last 2–3 convo summaries (topic, promises, open items, ≤ 60 words each) into the coach prompt; ~300-token cap;
cached per session, refreshed when the person changes. Wake-word path uses it only if the command names the person.

### Writing
At conversation end (STOP, partner silent > 3 min, or partner changes): one Grok call → `{topic, key_facts[],
promises[] (who→what→when), open_items[], summary}` → `convos`; additive ≤ 120-word note update on `people`.

### App
People screen: list, edit card, merge, forget voice signature, delete person with their convos. Review shows person chips;
export includes people + convos.

## 2. Room context (setting)
Settings: meeting, negotiation, sale, casual, family, street, call. Local prior from pre-brief keywords, mode, number of far
voices (partner/other tags), window keywords/formality (price, offer, deadline → negotiation/sale; kids, dinner → family),
time of day, in-call SCO → call. Coach JSON adds `setting` + `setting_conf`; use model if conf ≥ 0.6 else prior; switch only
after 2 agreeing calls. Manual override chip on the main screen; shown in the 🧭 log line.

| setting | min turns | cooldown | value-add conf | focus |
|---|---|---|---|---|
| negotiation | 2 | 15 s | 0.65 | numbers, concessions, anchors, deadlines, unanswered points |
| sale | 3 | 20 s | 0.7 | objections, next step, price facts |
| meeting | 3 | 25 s | 0.75 | facts, decisions, owners, open items |
| call | 2 | 20 s | 0.7 | like meeting, shorter lines |
| casual | 5 | 60 s | 0.9 | only clear factual errors or direct questions |
| family | 6 | 90 s | 0.95 | nearly silent |
| street | 1 | 10 s | 0.6 | answer passers-by |
Fact-error threshold stays 0.55; partner-required and wait-when-unsure unchanged.

## Touchpoints
`Db.java` (entities, migration, SQLCipher) · new `People.java` (name extraction, voice match, retrieval, end-of-convo writer) ·
`Brain.java` (setting prior + thresholds, person card in `assessMessages`, new JSON fields) · `WhisperService.java` (convo
boundaries) · new `PeopleActivity.java` · `MainActivity.java` (setting chip) · `ReviewActivity.java` (person filter).

## Release split
- 0.1.5a – room context (no schema change, low risk).
- 0.1.5b – people memory core: schema v2 + SQLCipher, pre-brief names, end-of-convo summaries, retrieval.
- 0.1.5c – live name learning, voice match to people, People screen edit/merge/delete.

## Risks
Coarse voice fingerprints → wrong-person cards (names must agree first); ~2 extra Grok calls per session + ~300 tokens per
coach call; SQLCipher +3 MB and a migration that must keep 0.1.4 history; summaries about other people are sensitive –
on-phone, encrypted, deletable, no audio.

## 3. Gap analysis vs the javis standard (docs/HUMAN-STANDARD.md)
"Human" = feels like a person listening; "software" = feels like a program reacting.

| What a human uses | 0.1.4 today | Feels | Gap → fix (target) |
|---|---|---|---|
| Who's speaking / who you're with | owner voice score, unsure band, partner link | mostly human | coarse cues → add small on-device speaker embedding as one more cue (0.1.6) |
| Listening before talking | topic buffer, ≥3 turns, pre-gate | human | — tune thresholds from real logs |
| Topic & intent / goals | rolling topic + open question; pre-brief | partly | goal not explicit → extract `goal` from pre-brief at START, coach toward it ("you wanted the 6% rate – they haven't said") (0.1.5b) |
| Setting | none | software | room context classifier + per-setting style (0.1.5a) |
| Memory of people | none | software | people memory + last convos (0.1.5b/c) |
| Tone / emotion | text only | software | add prosody cues we already compute (pitch-free: energy rise, speech rate, pause length) + ask model for `their_mood` from words; whisper style adapts ("he's getting impatient – get to the number") (0.1.5a light, 0.1.6 prosody) |
| Sarcasm / humour | none explicit | software | add `tone: literal/joking/sarcastic` to coach JSON; never "correct" a joke (0.1.5a) |
| Hesitation / uncertainty | none | software | pause & filler stats per turn ("um", long gaps, restarts) → flag as "they're unsure – push gently" (0.1.5a) |
| Interruptions / overlap | single-utterance pipeline, overlap lost | software | detect both mics active at once → mark overlap; never whisper during overlap; note "they cut you off" (0.1.6) |
| Turn timing | 900 ms pause, cancels on speech | human | adapt pause to speaker's own rhythm (slow talkers get longer) (0.1.5a) |
| Brevity | ≤15 words | human | keep; add 3–6-word "nudge" style for fast settings ("Ask for the date.") |
| Guyanese / cultural context | "lives in Guyana" only | partly | add light local knowledge pack: GYD money, local places, Creolese phrases understood (not spoken unless asked), courtesy norms; STT keyterms for local names (0.1.5a) |
| Body language | blind | blind spot | can't see; compensate with tone/hesitation cues; optional manual "they look unhappy" tap later; state honestly in guide |
| Ara's warmth & pacing | Ara stream, fixed speed, 35% vol | partly | slower speed 0.95, leading breath/pause tag, softer prefix ("Hey – …" only for urgent), volume ducks if Calton is speaking (0.1.5a) |
| Latency – commands | ~2 s | partly | pre-warm STT socket, speculative start on wake word, shorter end-pause (350 ms) for Me-band utterances starting with wake word → target ≈1.2 s (0.1.5a) |
| Latency – coaching | pause + ~2 s | human | fine: a friend also waits a beat |
| Owning limits | SKIP, unsure band | human | add "I'm not sure – ask them" as a valid whisper when Calton is asked something unknown |
| Follow-up after | session summary only | partly | end-of-conversation recap card + optional "Whisper, what did I promise?"; notification with promises/open items (0.1.5b) |
| Wow demo moment | wake word answer | partly | "demo mode" prompt tip in guide; first-name recall ("That's Marcus – last time he wanted delivery by Friday") once memory lands (0.1.5c) |

**Left after 0.1.5:** body language (structural blind spot), true prosody/emotion from audio (0.1.6 with on-device model),
overlap/interruption handling (0.1.6), speaker embedding (0.1.6). Each is listed with its fix above.
