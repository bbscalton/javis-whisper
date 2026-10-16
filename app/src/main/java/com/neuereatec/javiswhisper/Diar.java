package com.neuereatec.javiswhisper;
import android.content.Context;
import java.util.*;
/**
 * 0.1.16 on-device speaker diarization (non-neural). Each utterance becomes a small voice "fingerprint"
 * (pitch, pitch range/contour, bass & brightness, zero-crossing (vowel/formant brightness), syllable rate,
 * loudness swing, trailing-off). Voices are clustered live into Speaker 1, 2, …; a named cluster shows its name
 * and is saved (encrypted prefs "diar_named") so it is recognised in later sessions.
 */
public class Diar {
  public static class Cl { public int id; public String name; public double[] c; public int n; public long last; public Proximity.Feat lastF; public String label() { return name != null ? name : "Speaker " + id; } }
  static final double[] SCALE = {0.22, 0.30, 0.20, 0.12, 0.60, 0.05, 1.5, 10, 5}; // 0.1.17: wider spreads – one voice varied this much in the 10:19 log (hf 0.09–1.36, f0 82–116)
  static final double[] W = {3, 1, 0.7, 1, 0.25, 1.5, 0.8, 0.6, 0.5};              // pitch-led, a bit less dominant; hf nearly ignored
  static double[] vec(Proximity.Feat f) {
    return new double[]{ Math.log(Math.max(f.pitch, 1)), f.pitchSpread, f.pitchEnd, f.lowRatio, Math.min(f.hfRatio, 2), f.zcr, f.rate, f.modDb, f.tailDb };
  }
  static double dist(double[] a, double[] b) { double d = 0, w = 0; for (int i = 0; i < a.length; i++) { double x = (a[i] - b[i]) / SCALE[i]; d += W[i] * x * x; w += W[i]; } return Math.sqrt(d / w); }
  static final double NEW_T = 1.4; // farther than this from every cluster = new speaker
  static final double STICKY_T = NEW_T * 1.6, OWNER_T = NEW_T * 1.4; static final long STICKY_MS = 3000;
  // 0.1.17 anchoring: hysteresis (a new tag needs 2 consecutive clearly-non-matching turns), short-gap stickiness, owner collapse
  double[] pend; Cl lastCl; long lastAt; public volatile String owner;
  final List<Cl> cls = new ArrayList<>(); int next = 1; public volatile double lastMargin = 0.5, lastDist = 0;
  final Context ctx;
  Diar(Context c) { ctx = c; for (Map.Entry<String, double[]> e : named(c).entrySet()) { Cl k = new Cl(); k.id = 0; k.name = e.getKey(); k.c = e.getValue(); k.n = 5; cls.add(k); } }
  static Map<String, double[]> named(Context c) { Map<String, double[]> m = new LinkedHashMap<>();
    for (String e : Prefs.s(c, "diar_named", "").split(";")) { int i = e.indexOf('='); if (i <= 0) continue; String[] a = e.substring(i + 1).split(","); if (a.length != 9) continue;
      double[] v = new double[9]; try { for (int k = 0; k < 9; k++) v[k] = Double.parseDouble(a[k]); m.put(e.substring(0, i), v); } catch (Exception ignored) {} } return m; }
  static synchronized void saveNamed(Context c, String name, double[] v) { Map<String, double[]> m = named(c); m.remove(name); m.put(name, v); while (m.size() > 16) m.remove(m.keySet().iterator().next());
    StringBuilder b = new StringBuilder(); for (Map.Entry<String, double[]> e : m.entrySet()) { b.append(b.length() > 0 ? ";" : "").append(e.getKey()).append('='); for (int k = 0; k < 9; k++) b.append(k > 0 ? "," : "").append(String.format(Locale.US, "%.4f", e.getValue()[k])); }
    Prefs.put(c, "diar_named", b.toString()); }
  static synchronized void removeNamed(Context c, String name) { Map<String, double[]> m = named(c); if (m.remove(name) == null) return; StringBuilder b = new StringBuilder();
    for (Map.Entry<String, double[]> e : m.entrySet()) { b.append(b.length() > 0 ? ";" : "").append(e.getKey()).append('='); for (int k = 0; k < 9; k++) b.append(k > 0 ? "," : "").append(String.format(Locale.US, "%.4f", e.getValue()[k])); } Prefs.put(c, "diar_named", b.toString()); }
  Cl byName(String n) { for (Cl k : cls) if (n.equals(k.name)) return k; return null; }
  /** Assign this utterance. known = name from the owner/enrolled recogniser (wins), or null. Returns the cluster (null if too little voice). */
  synchronized Cl assign(Proximity.Feat f, String known) {
    if (f.pitch <= 0 || f.voicedSec < 0.4) return null;
    double[] v = vec(f); Cl best = null; double bd = 1e9, sd = 1e9;
    for (Cl k : cls) { double d = dist(v, k.c); if (d < bd) { sd = bd; bd = d; best = k; } else if (d < sd) sd = d; }
    lastDist = bd; lastMargin = sd >= 1e8 ? 1 : Math.max(0, Math.min(1, (sd - bd) / Math.max(sd, 0.01)));
    long now = System.currentTimeMillis(); boolean held = false;
    if (known == null) {
      Cl own = owner == null ? null : byName(owner);
      if (own != null && dist(v, own.c) < OWNER_T) { known = owner; }                         // enrolled owner: his lines collapse to him
      else if (lastCl != null && now - lastAt < STICKY_MS && dist(v, lastCl.c) < STICKY_T) { best = lastCl; bd = 0; pend = null; } // a short pause never relabels
    }
    if (known != null) { pend = null; Cl k = byName(known); if (k == null) { k = new Cl(); k.id = next++; k.name = known; k.c = v.clone(); cls.add(k); }
      else if (best != null && best != k && best.name == null && bd < 0.6) { merge(best, k); } best = k; bd = 0; }
    else if (bd > 0 && best != null && bd > NEW_T && (pend == null || dist(v, pend) > NEW_T)) { pend = v.clone(); held = true; } // 1st clearly-different turn: keep nearest tag (unchanged), remember it
    else if (best == null || bd > NEW_T) { if (pend != null && best != null) for (int i = 0; i < v.length; i++) v[i] = (v[i] + pend[i]) / 2; pend = null; if (cls.size() >= 10) { Cl old = null; for (Cl k : cls) if (k.name == null && (old == null || k.last < old.last)) old = k; if (old != null) cls.remove(old); }
      best = new Cl(); best.id = next++; best.c = v.clone(); cls.add(best); bd = 0; }
    if (held) { best.last = now; best.lastF = f; lastCl = best; lastAt = now; return best; } // outlier never moves the anchor
    double a = Math.max(0.05, 1.0 / (best.n + 1)); for (int i = 0; i < v.length; i++) best.c[i] += a * (v[i] - best.c[i]); // running centroid (anchor)
    if (best.id == 0) best.id = next++;
    best.n++; best.last = now; best.lastF = f; lastCl = best; lastAt = now; pend = null;
    if (best.name != null && best.n % 5 == 0) saveNamed(ctx, best.name, best.c); // keep the stored fingerprint fresh
    return best;
  }
  void merge(Cl from, Cl into) { for (int i = 0; i < into.c.length; i++) into.c[i] = (into.c[i] * into.n + from.c[i] * from.n) / (into.n + from.n); into.n += from.n; cls.remove(from); }
  /** Bind a cluster to a name (tap-to-name or "This is <name>…"); persists. */
  synchronized void name(Cl k, String name) { if (k == null || name == null || name.isEmpty()) return; Cl ex = byName(name); if (ex != null && ex != k) merge(ex, k); k.name = name; saveNamed(ctx, name, k.c); }
  synchronized List<Cl> recent(long withinMs) { List<Cl> r = new ArrayList<>(); long now = System.currentTimeMillis(); for (Cl k : cls) if (k.last > 0 && now - k.last < withinMs) r.add(k); r.sort((x, y) -> Long.compare(y.last, x.last)); return r; }
}
