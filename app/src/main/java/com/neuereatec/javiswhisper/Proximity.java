package com.neuereatec.javiswhisper;
import android.content.Context;
import java.util.*;
/**
 * Soft "near-owner" scoring (favoured approach): every utterance gets a 0..1 confidence that it is Calton's own,
 * close-to-the-mic voice, from RELATIVE cues that survive AGC/noise suppression:
 *  - snrDb:    level above the mic's own running background floor (near voice stands far above the floor)
 *  - dbDiff:   earbud level minus phone-mic level when both mics run (Calton ~5 cm from the earbud, others ~1 m+)
 *  - lowRatio: share of energy below ~300 Hz (proximity effect = more bass up close)
 *  - hfRatio:  first-difference / total energy (spectral tilt proxy)
 *  - modDb:    p90 − p20 frame level, a direct-to-reverberant proxy (close speech has deep gaps; distant speech is smeared by echo)
 * plus consistency with a light voice profile learned PASSIVELY from accepted wake-word commands and confident "Me" turns.
 * Result is banded: ME (≥0.65), THEM (≤0.35), UNSURE in between → javis waits it out (no coaching, no commands).
 * Auto-gain stays ON (never disabled). The owner profile is the core and keeps learning; the explicit "Teach javis my voice" step is an optional head start.
 */
public class Proximity {
  public static final double ME = 0.65, THEM = 0.35;
  public static class Acc {
    double sumDbE, sumDbP, snrSum; int nE, nP, nS; double low, all, diff; float lp, prev; final ArrayList<Float> frameDb = new ArrayList<>();
    public void earbud(short[] f, int n, float rms) { sumDbE += db(rms); nE++; }
    public void phone(float rms) { sumDbP += db(rms); nP++; }
    public void spectral(short[] f, int n, float rms, float noise) {
      for (int i = 0; i < n; i++) { float x = f[i]; lp += 0.11f * (x - lp); low += lp * lp; all += x * x; float d = x - prev; diff += d * d; prev = x; }
      frameDb.add((float) db(rms)); snrSum += db(rms) - db(noise); nS++;
    }
    public Feat feat() {
      Feat r = new Feat();
      r.dbDiff = nE > 0 && nP > 0 ? sumDbE / nE - sumDbP / nP : Double.NaN;
      r.lowRatio = all > 0 ? low / all : 0; r.hfRatio = all > 0 ? diff / all : 0; r.snrDb = nS > 0 ? snrSum / nS : 0;
      if (frameDb.size() >= 10) { ArrayList<Float> s = new ArrayList<>(frameDb); Collections.sort(s); r.modDb = s.get((int) (s.size() * 0.9)) - s.get((int) (s.size() * 0.2)); }
      return r;
    }
  }
  public static class Feat { public double dbDiff = Double.NaN, lowRatio, hfRatio, modDb, snrDb;
    public String toString() { return String.format(Locale.US, "snr%.0f Δ%.1f low%.2f hf%.2f mod%.0f", snrDb, dbDiff, lowRatio, hfRatio, modDb); } }
  static double db(float rms) { return 20 * Math.log10(Math.max(rms, 1)); }
  static double sig(double x) { return 1 / (1 + Math.exp(-x)); }

  /** profile = EMA of Calton's features: dbDiff,low,hf,mod,snr,count */
  static double[] profile(Context c) { String p = Prefs.s(c, "prox_prof", ""); if (p.isEmpty()) return null; String[] a = p.split(","); double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = Double.parseDouble(a[i]); return r; }

  /** 0..1 confidence that this is Calton's own near voice. */
  public static double nearScore(Context c, Feat f) {
    double[] pr = profile(c); boolean learned = pr != null && pr[5] >= 3;
    double s = 0, w = 0;
    // level above own floor: near voice typically 20+ dB, across-the-table voice ~8-14 dB on the earbud mic
    double snrMid = learned ? pr[4] - 8 : 16; s += 2 * sig((f.snrDb - snrMid) / 3.0); w += 2;
    if (!Double.isNaN(f.dbDiff)) { double mid = (learned && !Double.isNaN(pr[0]) ? pr[0] : 8) - 6; s += 3 * sig((f.dbDiff - mid) / 2.0); w += 3; }
    double modMid = learned ? pr[3] * 0.75 : 18; s += sig((f.modDb - modMid) / 3.0); w += 1;
    if (learned) { // spectral consistency with Calton's profile (bass + tilt)
      double d = Math.abs(f.lowRatio - pr[1]) / Math.max(pr[1], 0.05) + Math.abs(f.hfRatio - pr[2]) / Math.max(pr[2], 0.05);
      s += 1.5 * (1 - sig((d - 0.5) / 0.15)); w += 1.5;
    }
    return s / w;
  }
  public static String band(double near) { return near >= ME ? "Me" : near <= THEM ? "Them" : "Unsure"; }

  /** Passive learning: called for accepted wake-word commands and very confident Me turns; explicit calibration weighs more. */
  public static void learn(Context c, Feat f, double weight) {
    double[] pr = profile(c); double a = pr == null ? 1 : Math.min(0.5, weight / (pr[5] + weight));
    double[] cur = {Double.isNaN(f.dbDiff) ? (pr == null ? Double.NaN : pr[0]) : f.dbDiff, f.lowRatio, f.hfRatio, f.modDb, f.snrDb};
    double[] out = new double[6];
    for (int i = 0; i < 5; i++) { double old = pr == null || Double.isNaN(pr[i]) ? cur[i] : pr[i]; out[i] = Double.isNaN(cur[i]) ? old : old + a * (cur[i] - old); }
    out[5] = (pr == null ? 0 : pr[5]) + weight;
    Prefs.put(c, "prox_prof", String.format(Locale.US, "%.2f,%.3f,%.3f,%.1f,%.1f,%.1f", out[0], out[1], out[2], out[3], out[4], out[5]));
  }
}
