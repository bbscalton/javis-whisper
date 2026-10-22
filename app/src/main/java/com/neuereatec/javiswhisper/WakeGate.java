package com.neuereatec.javiswhisper;
import org.json.*;
import java.util.*;
/**
 * 0.1.23 wake-word decision on top of Vosk's keyword-grammar result: every word of the phrase must be recognised
 * consecutively with confidence ≥ threshold and a plausible spoken length, outside the cooldown and outside any mute
 * window (javis's own voice). Pure Java so it is unit-tested.
 */
final class WakeGate {
  static final class Word { final String w; final double conf, start, end; Word(String w, double c, double s, double e) { this.w = w; conf = c; start = s; end = e; } }
  final String[] phrase; final double threshold, minDur; final long cooldownMs;
  private long lastTrigger = Long.MIN_VALUE / 2, muteUntil;
  WakeGate(String phrase, double threshold, double minDurSec, long cooldownMs) {
    this.phrase = normalize(phrase).split(" "); this.threshold = threshold; this.minDur = minDurSec; this.cooldownMs = cooldownMs;
  }
  static final String DEFAULT = "right cool";
  static String normalize(String p) { String s = p == null ? "" : p.toLowerCase(Locale.US).replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").trim(); return s.isEmpty() ? DEFAULT : s; }
  static final double MAX_GAP = 0.12; // s between phrase words: "right" must be IMMEDIATELY followed by "cool"
  /** a phrase directly preceded by one of these is a different expression ("all right, cool", "alright cool") */
  static final Set<String> PREFIX_REJECT = new HashSet<>(Arrays.asList("all", "alright", "al"));
  static List<Word> parse(String json) {
    List<Word> out = new ArrayList<>();
    try { JSONArray r = new JSONObject(json).optJSONArray("result"); if (r != null) for (int i = 0; i < r.length(); i++) { JSONObject o = r.getJSONObject(i);
        out.add(new Word(o.optString("word"), o.optDouble("conf", 0), o.optDouble("start", 0), o.optDouble("end", 0))); } } catch (JSONException ignored) {}
    return out;
  }
  synchronized void mute(long until) { muteUntil = Math.max(muteUntil, until); }
  synchronized boolean muted(long now) { return now < muteUntil; }
  synchronized boolean coolingDown(long now) { return now - lastTrigger < cooldownMs; }
  /** restart the cooldown (called when a trigger starts AND when the advisory finishes speaking). */
  synchronized void markTrigger(long now) { lastTrigger = now; }
  /** Later phrase words only need this confidence: the "right school"/"white cool" decoys compete for "cool" in the lattice, so its
   *  posterior is split even when "cool" is the top hypothesis (espeak test: real "right cool" gave cool 0.59–0.77). The decoded word
   *  itself must still be "cool", which is what rejects "right school". */
  static final double TAIL_FLOOR = 0.55;
  /** confidence of the first phrase word for a valid consecutive match (later words ≥ TAIL_FLOOR, tight gaps), or -1. */
  double score(List<Word> ws) {
    double best = -1;
    for (int i = 0; i + phrase.length <= ws.size(); i++) {
      double mn = 1; boolean ok = true;
      for (int k = 0; k < phrase.length; k++) { Word w = ws.get(i + k); if (!phrase[k].equals(w.w)) { ok = false; break; } if (k == 0) mn = w.conf; else if (w.conf < TAIL_FLOOR) ok = false; }
      if (!ok) continue;
      for (int k = 1; k < phrase.length && ok; k++) if (ws.get(i + k).start - ws.get(i + k - 1).end > MAX_GAP) ok = false;
      if (ok && i > 0 && PREFIX_REJECT.contains(ws.get(i - 1).w) && ws.get(i).start - ws.get(i - 1).end <= 0.4) ok = false;
      if (!ok) continue;
      double dur = ws.get(i + phrase.length - 1).end - ws.get(i).start;
      if (dur < minDur || dur > 2.5 * phrase.length) continue;
      best = Math.max(best, mn);
    }
    return best;
  }
  /** true = trigger now (and the cooldown starts). */
  synchronized boolean accept(List<Word> ws, long now) {
    if (now < muteUntil || now - lastTrigger < cooldownMs) return false;
    if (score(ws) < threshold) return false;
    lastTrigger = now; return true;
  }
  /** Vosk grammar: the phrase, sound-alike decoys that soak up near misses, and [unk] for everything else. */
  static String grammar(String phrase, Collection<String> known) {
    JSONArray g = new JSONArray(); g.put(normalize(phrase));
    for (String d : DECOYS) if (!normalize(phrase).equals(d) && (known == null || known.contains(d))) g.put(d);
    g.put("[unk]"); return g.toString();
  }
  // sound-alikes / partial matches that soak up near misses (tested with espeak voices against vosk-model-small-en-us-0.15)
  static final List<String> DECOYS = Arrays.asList("right", "cool", "all right", "alright", "right school", "white cool", "school", "white", "bright", "write", "tool", "pool", "cold");
}
