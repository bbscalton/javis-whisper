package com.neuereatec.javiswhisper;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import java.util.*;
import java.util.concurrent.TimeUnit;
/**
 * 0.1.23 advisory layer. Always-on, on-device wake word (Vosk keyword grammar) on the earbud mic inside the existing
 * foreground service. Keeps the last ~10 s of audio in a RAM-only ring buffer; on the wake word it captures up to 4 s
 * more (or until silence), transcribes with the app's existing xAI STT, asks Grok for one advisory whisper (2–3
 * sentences, enforced in code), speaks it through the existing Ara TTS path, appends a TEXT-only note to the on-phone
 * log, wipes all audio and goes silent until the next wake word. Nothing is streamed off the phone until the wake word.
 */
final class Advisor {
  static final String MODE = "advisory";
  static final int SR = WhisperService.SR;
  static final double PRE_SECONDS = 10, POST_MAX_SECONDS = 4, POST_MIN_SECONDS = 1;
  static final int SILENCE_END_FRAMES = 40;     // 800 ms of quiet after the wake word ends the capture
  static final long COOLDOWN_MS = 4000;          // no retrigger within 4 s of a trigger or of the end of a whisper
  static final long TTS_TAIL_MS = 1500;          // detection stays muted 1.5 s after javis's voice stops
  static final double[] STRICT = {0.97, 0.95, 0.90}; static final String[] STRICT_NAME = {"Strict", "Normal", "Relaxed"};

  /** one-time switch of existing installs to the new Advisor mode (Coach etc. stay available). */
  static void migrate(Context c) {
    if (!"1".equals(Prefs.s(c, "adv0123", ""))) { Prefs.put(c, "mode", MODE); Prefs.put(c, "adv0123", "1"); }
    // 0.1.24: Calton chose "right cool"; any old/empty wake word setting switches over (a real custom word is kept)
    if (!"1".equals(Prefs.s(c, "wake0124", ""))) { String w = Prefs.s(c, "wake_word", "").trim().toLowerCase(Locale.US); if (w.isEmpty() || w.equals("jarvis") /* legacy 0.1.23 default, migrated away */) Prefs.put(c, "wake_word", WakeGate.DEFAULT); Prefs.put(c, "wake0124", "1"); }
  }
  static String wakeWord(Context c) { return WakeGate.normalize(Prefs.s(c, "wake_word", WakeGate.DEFAULT)); }
  static int strictIdx(Context c) { return Prefs.i(c, "wake_strict", 1, 0, 2); }

  final WhisperService svc; volatile String word; volatile WakeGate gate; final PcmRing ring = PcmRing.seconds(PRE_SECONDS, SR);
  org.vosk.Model model; org.vosk.Recognizer rec;
  private final short[] post = new short[(int) (POST_MAX_SECONDS * SR)]; private int postLen, quiet; private short[] pre;
  private volatile int state; // 0 listen · 1 capturing after wake word · 2 busy (transcribe/think/speak)
  static volatile String lastState = "";

  Advisor(WhisperService s) {
    svc = s; String w = wakeWord(s);
    gate = new WakeGate(w, STRICT[strictIdx(s)], 0.25 * w.split(" ").length, COOLDOWN_MS); word = w;
  }

  /** copies the bundled model out of the APK once (model files only – never audio) and builds the recognizer. */
  boolean load() {
    try {
      org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
      String path = org.vosk.android.StorageService.sync(svc, "model-en-us", "vosk");
      model = new org.vosk.Model(path);
      String phrase = word;
      for (String w : phrase.split(" ")) if (!VoskWords.known(model, w)) {
        WhisperService.log("⚠ Wake word \"" + phrase + "\" isn't in the offline dictionary – using \"right cool\". Pick common English words in Settings.");
        phrase = WakeGate.DEFAULT; break; }
      if (!phrase.equals(word)) { word = phrase; gate = new WakeGate(phrase, STRICT[strictIdx(svc)], 0.25 * phrase.split(" ").length, COOLDOWN_MS); }
      rec = new org.vosk.Recognizer(model, SR, WakeGate.grammar(phrase, null)); rec.setWords(true);
      WhisperService.log("👂 Advisor ready – say \"" + cap(phrase) + "\" for advice (" + STRICT_NAME[strictIdx(svc)] + ", conf ≥ " + gate.threshold + "). Listening on-device only; audio stays in memory and is wiped after each use.");
      lastState = "Waiting for \"" + cap(phrase) + "\"";
      return true;
    } catch (Throwable t) { WhisperService.log("Advisor: wake-word engine failed to load: " + t); return false; }
  }
  static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

  /** called from the mic read loop for every 20 ms frame. */
  void frame(short[] f, int n, float rms, float noise) {
    if (rec == null) return;
    long now = SystemClock.uptimeMillis();
    if (state == 2) return;                                   // busy: nothing is buffered or recognised
    if (svc.playingUntil > now) { gate.mute(svc.playingUntil + TTS_TAIL_MS); return; } // never listen to our own voice
    if (state == 1) {
      int k = Math.min(n, post.length - postLen); System.arraycopy(f, 0, post, postLen, k); postLen += k;
      quiet = rms < Math.max(noise * 2.5f, 200) ? quiet + 1 : 0;
      if (postLen >= post.length || (postLen >= POST_MIN_SECONDS * SR && quiet >= SILENCE_END_FRAMES)) {
        state = 2; final short[] a = pre; final short[] b = Arrays.copyOf(post, postLen); PcmRing.wipe(post); postLen = 0; pre = null;
        lastState = "Thinking…";
        try { svc.proc.execute(() -> advise(a, b)); } catch (Throwable t) { PcmRing.wipe(a); PcmRing.wipe(b); finish(); }
      }
      return;
    }
    ring.write(f, n);
    if (gate.muted(now)) { rec.reset(); return; }
    boolean fin; try { fin = rec.acceptWaveForm(f, n); } catch (Throwable t) { return; }
    if (!fin) return;
    List<WakeGate.Word> ws = WakeGate.parse(rec.getResult());
    double sc = gate.score(ws);
    if (sc < 0) return;
    if (!gate.accept(ws, now)) { WhisperService.log(String.format(Locale.US, "(heard something like \"%s\" – conf %.2f below %.2f or cooling down – ignored)", word, sc, gate.threshold)); return; }
    pre = ring.snapshot(); ring.clear(); postLen = 0; quiet = 0; state = 1; lastState = "Listening for 4 s…";
    WhisperService.log(String.format(Locale.US, "👂 Wake word \"%s\" (conf %.2f) – capturing up to 4 s more", word, sc));
  }

  void advise(short[] preAudio, short[] postAudio) {
    byte[] pcm = null;
    try {
      int n = preAudio.length + postAudio.length; short[] all = new short[n];
      System.arraycopy(preAudio, 0, all, 0, preAudio.length); System.arraycopy(postAudio, 0, all, preAudio.length, postAudio.length);
      PcmRing.wipe(preAudio); PcmRing.wipe(postAudio); pcm = PcmRing.toBytes(all, n); PcmRing.wipe(all);
      WhisperService.status = "Transcribing…";
      Xai.Stt stt = svc.xai.stt("en");
      for (int i = 0; i < pcm.length; i += 3200) stt.push(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 3200)));
      stt.end(); String heard = stt.result(6000); PcmRing.wipe(pcm); pcm = null;
      if (heard == null || heard.trim().isEmpty()) { WhisperService.log(stt.error != null ? "Advisor STT error: " + stt.error : "(advisor: no words in the last few seconds – staying quiet)"); return; }
      WhisperService.log("👂 Heard (" + String.format(Locale.US, "%.1f", n / (double) SR) + " s): " + heard);
      List<String> notes = recentNotes(svc, 5);
      WhisperService.status = "Thinking…";
      JSONArray msgs = new JSONArray().put(Learn.msg("system", prompt(word))).put(Learn.msg("user", userMsg(heard, notes)));
      String raw = svc.xai.chat(svc.model, msgs, 120, 0.6, null);
      String say = AdviceText.enforce(raw);
      if (!say.equals(raw.trim())) WhisperService.log("(advisor trimmed reply: \"" + raw.replace('\n', ' ') + "\")");
      if (say.isEmpty()) { WhisperService.log("(advisor: nothing useful to say – staying quiet)"); return; }
      svc.saveTurn("heard", "Advisor", "en", heard, System.currentTimeMillis());
      svc.speakText(say);
      waitForSpeech();
      String note = summarize(heard, say);
      svc.saveTurn("advice_note", "javis", "en", note, System.currentTimeMillis());
      WhisperService.log("📝 Note saved (text only): " + note);
    } catch (Throwable t) { WhisperService.log("Advisor error: " + t.getMessage()); }
    finally { PcmRing.wipe(pcm); PcmRing.wipe(preAudio); PcmRing.wipe(postAudio); finish(); }
  }
  /** wait until javis has finished speaking, then keep detection muted for the tail. */
  private void waitForSpeech() {
    long w = SystemClock.uptimeMillis() + 6000; while (svc.playingUntil <= SystemClock.uptimeMillis() && SystemClock.uptimeMillis() < w) SystemClock.sleep(30);
    long cap = SystemClock.uptimeMillis() + 30_000; while (svc.playingUntil > SystemClock.uptimeMillis() && SystemClock.uptimeMillis() < cap) SystemClock.sleep(50);
  }
  private void finish() {
    long now = SystemClock.uptimeMillis();
    gate.mute(Math.max(svc.playingUntil, now) + TTS_TAIL_MS); gate.markTrigger(now);
    ring.clear(); try { if (rec != null) rec.reset(); } catch (Throwable ignored) {}
    state = 0; WhisperService.status = "Listening"; lastState = "Waiting for \"" + cap(word) + "\"";
  }
  void close() { state = 2; ring.clear(); PcmRing.wipe(post); PcmRing.wipe(pre); try { if (rec != null) rec.close(); } catch (Throwable ignored) {} try { if (model != null) model.close(); } catch (Throwable ignored) {} rec = null; model = null; }

  String summarize(String heard, String say) {
    try {
      String s = svc.xai.chat(svc.model, new JSONArray().put(Learn.msg("system", "Write ONE short plain-text note (max 25 words) for Calton's private log: the situation in the conversation and the advice javis gave. No markdown, no quotes, no names you weren't given."))
        .put(Learn.msg("user", "Conversation snippet: " + heard + "\nAdvice given: " + say)), 60, 0.3, null);
      s = s.replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim(); if (!s.isEmpty()) return People.clip(s, 220);
    } catch (Throwable ignored) {}
    return People.clip("Heard: " + heard, 140) + " · Advised: " + People.clip(say, 120);
  }
  static List<String> recentNotes(Context c, int n) {
    List<String> out = new ArrayList<>();
    try { List<Db.Turn> t = Db.get(c).d().adviceNotes(n); for (int i = t.size() - 1; i >= 0; i--) out.add(new java.text.SimpleDateFormat("d MMM h:mm a", Locale.US).format(new Date(t.get(i).ts)) + " – " + t.get(i).text); } catch (Throwable ignored) {}
    return out;
  }
  static String prompt(String word) {
    return "You are javis, Calton's sharp, street-smart friend whispering into his earbud. Calton lives in Guyana and speaks English. "
      + "He just said your wake word \"" + word + "\" to get a quick read on the conversation happening around him right now. "
      + "Reply with ONE advisory whisper: 2 to 3 short sentences at most, casual and human, like a friend leaning in. "
      + "Pick what matters most right now: a sales or negotiation tactic, a privacy or safety warning (e.g. \"Don't tell him that, he may be after your money.\"), what to ask next (say it as \"Ask about …\" or \"Ask him to …\", never as a question), or what to avoid. "
      + "Never ask Calton a question. No greetings, no names at the start, no 'sure' or 'okay', no formalities, no lists, no markdown. "
      + "If the snippet is unclear, give the most useful practical tip you can from what is there. Earlier notes are background only – use them if they clearly relate. "
      + Brain.GUYANA_SHORT;
  }
  static String userMsg(String heard, List<String> notes) {
    StringBuilder b = new StringBuilder();
    if (!notes.isEmpty()) { b.append("Earlier advisory notes (oldest first):\n"); for (String s : notes) b.append("- ").append(s).append('\n'); b.append('\n'); }
    b.append("What was just said around Calton (last ~10 s plus a few seconds after he called you; speakers not labelled, the wake word near the end is Calton calling you):\n").append(heard);
    return b.toString();
  }
}
