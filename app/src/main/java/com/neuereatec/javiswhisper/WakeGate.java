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
  static String normalize(String p) { String s = p == null ? "" : p.toLowerCase(Locale.US).replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").trim(); return s.isEmpty() ? "jarvis" : s; }
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
  /** best min-confidence of a consecutive phrase match, or -1. */
  double score(List<Word> ws) {
    double best = -1;
    for (int i = 0; i + phrase.length <= ws.size(); i++) {
      double mn = 1; boolean ok = true;
      for (int k = 0; k < phrase.length; k++) { Word w = ws.get(i + k); if (!phrase[k].equals(w.w)) { ok = false; break; } mn = Math.min(mn, w.conf); }
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
    for (String d : DECOYS) if (!normalize(phrase).contains(d) && (known == null || known.contains(d))) g.put(d);
    g.put("[unk]"); return g.toString();
  }
  // decoys chosen by testing espeak voices against vosk-model-small-en-us-0.15 ("harvest" alone scored 0.94 without them)
  static final List<String> DECOYS = Arrays.asList("harvest", "service", "nervous", "travis", "mavis", "garbage", "marvelous", "starving", "jars", "barber", "doctor");
}
