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
  static final double[] SCALE = {0.18, 0.25, 0.15, 0.10, 0.25, 0.04, 1.2, 8, 4}; // per-dim spread used to normalise
  static final double[] W = {4, 1, 0.7, 1, 0.5, 1.5, 0.8, 0.6, 0.5};               // pitch-led weights
  static double[] vec(Proximity.Feat f) {
    return new double[]{ Math.log(Math.max(f.pitch, 1)), f.pitchSpread, f.pitchEnd, f.lowRatio, Math.min(f.hfRatio, 2), f.zcr, f.rate, f.modDb, f.tailDb };
  }
  static double dist(double[] a, double[] b) { double d = 0, w = 0; for (int i = 0; i < a.length; i++) { double x = (a[i] - b[i]) / SCALE[i]; d += W[i] * x * x; w += W[i]; } return Math.sqrt(d / w); }
  static final double NEW_T = 1.4; // farther than this from every cluster = new speaker
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
    if (known != null) { Cl k = byName(known); if (k == null) { k = new Cl(); k.id = next++; k.name = known; k.c = v.clone(); cls.add(k); }
      else if (best != null && best != k && best.name == null && bd < 0.6) { merge(best, k); } best = k; bd = 0; }
    else if (best == null || bd > NEW_T) { if (cls.size() >= 10) { Cl old = null; for (Cl k : cls) if (k.name == null && (old == null || k.last < old.last)) old = k; if (old != null) cls.remove(old); }
      best = new Cl(); best.id = next++; best.c = v.clone(); cls.add(best); bd = 0; }
    double a = Math.max(0.1, 1.0 / (best.n + 1)); for (int i = 0; i < v.length; i++) best.c[i] += a * (v[i] - best.c[i]);
    if (best.id == 0) best.id = next++;
    best.n++; best.last = System.currentTimeMillis(); best.lastF = f;
    if (best.name != null && best.n % 5 == 0) saveNamed(ctx, best.name, best.c); // keep the stored fingerprint fresh
    return best;
  }
  void merge(Cl from, Cl into) { for (int i = 0; i < into.c.length; i++) into.c[i] = (into.c[i] * into.n + from.c[i] * from.n) / (into.n + from.n); into.n += from.n; cls.remove(from); }
  /** Bind a cluster to a name (tap-to-name or "This is <name>…"); persists. */
  synchronized void name(Cl k, String name) { if (k == null || name == null || name.isEmpty()) return; Cl ex = byName(name); if (ex != null && ex != k) merge(ex, k); k.name = name; saveNamed(ctx, name, k.c); }
  synchronized List<Cl> recent(long withinMs) { List<Cl> r = new ArrayList<>(); long now = System.currentTimeMillis(); for (Cl k : cls) if (k.last > 0 && now - k.last < withinMs) r.add(k); r.sort((x, y) -> Long.compare(y.last, x.last)); return r; }
}
