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
  /** 0.1.14: true when no Bluetooth earbud mic is routed – the earbud-vs-phone loudness gap (Δ) is then meaningless. */
  public static volatile boolean noEarbud;
  /** 0.1.14 auto cutoff: EMA of Calton's confirmed scores vs clearly-other voices (pitch far from his), persisted. */
  static double[] cal(Context c) { try { String[] a = Prefs.s(c, "me_cal", "").split(","); return new double[]{Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3])}; } catch (Exception e) { return new double[]{0, 0, 0, 0}; } }
  static synchronized void calMe(Context c, double near) { double[] k = cal(c); k[0] = k[1] < 1 ? near : k[0] + 0.15 * (near - k[0]); k[1]++; putCal(c, k); }
  static synchronized void calOther(Context c, double near) { double[] k = cal(c); k[2] = k[3] < 1 ? near : k[2] + 0.15 * (near - k[2]); k[3]++; putCal(c, k); }
  static void putCal(Context c, double[] k) { Prefs.put(c, "me_cal", String.format(Locale.US, "%.3f,%.0f,%.3f,%.0f", k[0], Math.min(k[1], 999), k[2], Math.min(k[3], 999))); }
  public static double meCut(Context c) { double base = noEarbud ? 0.58 : ME; double[] k = cal(c);
    if (k[1] >= 4 && k[3] >= 4 && k[0] > k[2] + 0.04) return Math.max(0.50, Math.min(0.70, (k[0] + k[2]) / 2)); return base; }
  public static String band(Context c, double near) { double me = meCut(c); return near >= me ? "Me" : near <= Math.min(THEM, me - 0.15) ? "Them" : "Unsure"; }
  /** owner's typical pitch (Hz) from the profile, or 0. */
  public static double ownerF0(Context c) { double[] p = profile(c); return p != null && p.length >= 7 && p[6] > 0 ? p[6] : 0; }
  public static class Acc {
    // 0.1.6 prosody: pitch per voiced 20 ms frame (autocorrelation over 40 ms, 70-400 Hz), voiced frame count, overlap frames
    final ArrayList<Float> pitch = new ArrayList<>(); short[] last = new short[0]; int voicedFrames; double dbSum; int overlapFrames; final ArrayList<Float> diffs = new ArrayList<>(); float lastEdb = Float.NaN;
    double sumDbE, sumDbP, snrSum; int nE, nP, nS; double low, all, diff; float lp, prev; final ArrayList<Float> frameDb = new ArrayList<>(); double zcrSum; int clipFrames; final ArrayList<Float> voicedDb = new ArrayList<>();
    public void earbud(short[] f, int n, float rms) { sumDbE += db(rms); nE++; lastEdb = (float) db(rms); }
    /** phone mic frame during an earbud-owned utterance: if the phone suddenly gets much louder RELATIVE to the earbud, a second voice is talking over */
    public void phone(float rms) { sumDbP += db(rms); nP++;
      if (!Float.isNaN(lastEdb)) { float d = lastEdb - (float) db(rms); diffs.add(d);
        if (diffs.size() > 25) { ArrayList<Float> s = new ArrayList<>(diffs.subList(0, Math.min(diffs.size(), 40))); Collections.sort(s); if (Math.abs(d - s.get(s.size() / 2)) > 7) overlapFrames++; } } }
    public void spectral(short[] f, int n, float rms, float noise) {
      for (int i = 0; i < n; i++) { float x = f[i]; lp += 0.11f * (x - lp); low += lp * lp; all += x * x; float d = x - prev; diff += d * d; prev = x; }
      frameDb.add((float) db(rms)); snrSum += db(rms) - db(noise); nS++;
      if (rms > noise * 2.5f) { int zc = 0, cl = 0; for (int i = 1; i < n; i++) { if ((f[i] >= 0) != (f[i - 1] >= 0)) zc++; if (Math.abs(f[i]) > 32000) cl++; } zcrSum += zc / (double) n; if (cl > 2) clipFrames++; voicedDb.add((float) db(rms));
        voicedFrames++; dbSum += db(rms); float p0 = pitchOf(last, f, n); if (p0 > 0) pitch.add(p0); }
      last = Arrays.copyOf(f, n);
    }
    public Feat feat() {
      Feat r = new Feat();
      r.dbDiff = nE > 0 && nP > 0 ? sumDbE / nE - sumDbP / nP : Double.NaN;
      r.lowRatio = all > 0 ? low / all : 0; r.hfRatio = all > 0 ? diff / all : 0; r.snrDb = nS > 0 ? snrSum / nS : 0;
      if (frameDb.size() >= 10) { ArrayList<Float> s = new ArrayList<>(frameDb); Collections.sort(s); r.modDb = s.get((int) (s.size() * 0.9)) - s.get((int) (s.size() * 0.2)); }
      if (pitch.size() >= 6) { ArrayList<Float> s = new ArrayList<>(pitch); Collections.sort(s); r.pitch = s.get(s.size() / 2); r.pitchSpread = (s.get((int) (s.size() * 0.85)) - s.get((int) (s.size() * 0.15))) / r.pitch;
        int k = (int) (pitch.size() * 0.7); ArrayList<Float> a = new ArrayList<>(pitch.subList(0, k)), b = new ArrayList<>(pitch.subList(k, pitch.size())); Collections.sort(a); Collections.sort(b);
        if (b.size() >= 3) r.pitchEnd = b.get(b.size() / 2) / a.get(a.size() / 2); }
      if (voicedFrames > 0) { r.zcr = zcrSum / voicedFrames; r.clipFrac = clipFrames / (double) voicedFrames; }
      if (frameDb.size() >= 10) { ArrayList<Float> s = new ArrayList<>(frameDb); Collections.sort(s); float med = s.get(s.size() / 2); int peaks = 0, lastPk = -9;
        for (int i = 1; i < frameDb.size() - 1; i++) { float x = frameDb.get(i); if (x > med + 3 && x >= frameDb.get(i - 1) && x >= frameDb.get(i + 1) && i - lastPk >= 5) { peaks++; lastPk = i; } }
        r.rate = voicedFrames > 5 ? peaks / Math.max(0.3, voicedFrames * 0.02) : 0; }
      if (voicedDb.size() >= 12) { double t = 0; for (int i = voicedDb.size() - 8; i < voicedDb.size(); i++) t += voicedDb.get(i); r.tailDb = t / 8 - dbSum / voicedFrames; }
      r.voicedSec = voicedFrames * 0.02; r.loudDb = voicedFrames > 0 ? dbSum / voicedFrames : 0; r.overlap = overlapFrames >= 15;
      return r;
    }
  }
  /** Normalised autocorrelation pitch over prev+cur frame (16 kHz). 0 = unvoiced/unsure. */
  static float pitchOf(short[] prev, short[] cur, int n) {
    int L = prev.length + n; if (L < 480) return 0; float[] x = new float[L]; for (int i = 0; i < prev.length; i++) x[i] = prev[i]; for (int i = 0; i < n; i++) x[prev.length + i] = cur[i];
    int minLag = 40, maxLag = 228, W = L - maxLag; double e0 = 0; for (int i = 0; i < W; i++) e0 += x[i] * x[i]; if (e0 <= 0) return 0;
    double[] rr = new double[maxLag + 1]; double best = 0;
    for (int lag = minLag; lag <= maxLag; lag++) { double c = 0, e1 = 0; for (int i = 0; i < W; i++) { c += x[i] * x[i + lag]; e1 += x[i + lag] * x[i + lag]; } rr[lag] = c / Math.sqrt(e0 * e1 + 1e-9); if (rr[lag] > best) best = rr[lag]; }
    if (best <= 0.55) return 0;
    for (int lag = minLag + 1; lag < maxLag; lag++) if (rr[lag] >= 0.9 * best && rr[lag] >= rr[lag - 1] && rr[lag] >= rr[lag + 1]) return 16000f / lag; // first strong peak = avoids octave errors
    return 0;
  }
  public static class Feat { public double zcr, rate, tailDb, clipFrac; // 0.1.16 voice-fingerprint extras
    public double dbDiff = Double.NaN, lowRatio, hfRatio, modDb, snrDb, pitch, pitchSpread, pitchEnd = 1, voicedSec, loudDb; public boolean overlap;
    public String toString() { return String.format(Locale.US, "snr%.0f Δ%.1f low%.2f hf%.2f mod%.0f f0 %.0f", snrDb, dbDiff, lowRatio, hfRatio, modDb, pitch); } }
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
    if (!noEarbud && !Double.isNaN(f.dbDiff)) { double mid = (learned && !Double.isNaN(pr[0]) ? pr[0] : 8) - 6; s += 3 * sig((f.dbDiff - mid) / 2.0); w += 3; }
    double modMid = learned ? pr[3] * 0.75 : 18; s += sig((f.modDb - modMid) / 3.0); w += 1;
    if (learned && pr.length >= 8 && pr[6] > 0 && f.pitch > 0) { // 0.1.6 voice-ID: Calton's typical pitch + its range (survives AGC; distance-independent)
      double d = Math.abs(Math.log(f.pitch / pr[6])) / 0.12 + Math.abs(f.pitchSpread - pr[7]) / Math.max(pr[7], 0.1) * 0.5;
      s += 4 * (1 - sig((d - 1.2) / 0.35)); w += 4; // 0.1.14 pitch-led (2→4)
    }
    if (learned) { // spectral consistency with Calton's profile (bass + tilt)
      double d = Math.abs(f.lowRatio - pr[1]) / Math.max(pr[1], 0.05) + 0.25 * Math.abs(f.hfRatio - pr[2]) / Math.max(pr[2], 0.05); // 0.1.14 hf down-weighted
      s += 1.5 * (1 - sig((d - 0.5) / 0.15)); w += 1.5;
    }
    return s / w;
  }
  /** 0.1.10: blend in the enrolled reference match so Me tagging works from the first line. */
  public static double nearScore(Context c, Feat f, byte[] pcm) {
    double base = nearScore(c, f); int n = VoiceEnroll.count(c); if (n == 0) return base;
    double m = VoiceEnroll.match(c, pcm, f); return 0.75 * base + 0.25 * m; // 0.1.14: 0.4→0.25, feature-only match
  }
  public static String band(double near) { return near >= ME ? "Me" : near <= THEM ? "Them" : "Unsure"; }

  /** Passive learning: called for accepted wake-word commands and very confident Me turns; explicit calibration weighs more. */
  /** 0.1.19: every learning path is pitch-guarded – a line more than 15% off his saved pitch (or with no pitch) never touches the profile. */
  public static void learn(Context c, Feat f, double weight) { if (!Classify.canLearn(f.pitch, ownerF0(c))) { WhisperService.log(String.format(Locale.US, "(not learning from this line – pitch %.0f vs yours %.0f)", f.pitch, ownerF0(c))); return; } learnForce(c, f, weight); }
  /** explicit enrollment (Settings re-enroll / guided phrase) may move the profile. */
  static void learnForce(Context c, Feat f, double weight) {
    double[] pr = profile(c); double a = pr == null ? 1 : Math.min(0.5, weight / (pr[5] + weight));
    double[] cur = {Double.isNaN(f.dbDiff) ? (pr == null ? Double.NaN : pr[0]) : f.dbDiff, f.lowRatio, f.hfRatio, f.modDb, f.snrDb, 0, f.pitch > 0 ? f.pitch : Double.NaN, f.pitch > 0 ? f.pitchSpread : Double.NaN};
    double[] out = new double[8];
    for (int i = 0; i < 8; i++) { if (i == 5) continue; double old = pr == null || pr.length <= i || Double.isNaN(pr[i]) || (i >= 6 && pr[i] <= 0) ? cur[i] : pr[i]; out[i] = Double.isNaN(cur[i]) ? (Double.isNaN(old) ? 0 : old) : old + a * (cur[i] - old); }
    out[5] = (pr == null ? 0 : pr[5]) + weight;
    Prefs.put(c, "prox_prof", String.format(Locale.US, "%.2f,%.3f,%.3f,%.1f,%.1f,%.1f,%.1f,%.3f", out[0], out[1], out[2], out[3], out[4], out[5], out[6], out[7]));
  }
}
