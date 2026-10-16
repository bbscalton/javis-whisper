package com.neuereatec.javiswhisper;
import android.content.Context;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
/**
 * 0.1.10 guided voice enrollment. Keeps 1–3 short encrypted PCM reference clips on the phone
 * (enrollment only — never conversation audio, never uploaded). Features ride along for Me matching.
 */
public class VoiceEnroll {
  static final int MAX = 3, SR = 16000;
  static final String PHRASE = "This is [your name] doing a mic test";
  // 0.1.12 name-driven enrollment: "This is <name> doing a/the mic test" → voice saved under that name (persisted, encrypted prefs)
  static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile("(?i)\\b(?:this is|it's|it is)\\s+([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*)?)\\s+doing\\s+(?:a|the)\\s+mic\\s+test");
  static String spokenName(String text) { if (text == null) return null; java.util.regex.Matcher m = NAMED.matcher(text); if (!m.find()) return null; String n = m.group(1).trim(); if (n.length() < 2 || n.length() > 30) return null; StringBuilder b = new StringBuilder(); for (String w : n.split("\\s+")) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); return b.toString(); }
  static String owner(Context c) { return Prefs.s(c, "owner_name", "Calton"); }
  static boolean isOwner(Context c, String name) { String o = owner(c).toLowerCase(Locale.US), n = name.toLowerCase(Locale.US); return n.equals(o) || n.startsWith(o + " ") || ("calton".equals(o) && (n.equals("carlton") || n.equals("carbon"))); }
  /** other people's named voices: name -> feature line, kept in encrypted prefs ("voices" = name=feat;name=feat). */
  static Map<String, String> voices(Context c) { Map<String, String> m = new LinkedHashMap<>(); for (String e : Prefs.s(c, "voices", "").split(";")) { int i = e.indexOf('='); if (i > 0) m.put(e.substring(0, i), e.substring(i + 1)); } return m; }
  static synchronized void saveNamed(Context c, String name, Proximity.Feat f) { Map<String, String> m = voices(c); m.remove(name); m.put(name, featLine(f)); while (m.size() > 12) m.remove(m.keySet().iterator().next());
    StringBuilder b = new StringBuilder(); for (Map.Entry<String, String> e : m.entrySet()) b.append(b.length() > 0 ? ";" : "").append(e.getKey().replace(";", "").replace("=", "")).append('=').append(e.getValue()); Prefs.put(c, "voices", b.toString()); }
  static synchronized void removeNamed(Context c, String name) { Map<String, String> m = voices(c); m.remove(name); StringBuilder b = new StringBuilder(); for (Map.Entry<String, String> e : m.entrySet()) b.append(b.length() > 0 ? ";" : "").append(e.getKey()).append('=').append(e.getValue()); Prefs.put(c, "voices", b.toString()); }
  static String clean(String n) { n = n == null ? "" : n.replaceAll("[;=]", "").trim(); return n.length() > 30 ? n.substring(0, 30) : n; }
  /** 0.1.13 speaker model entry point: name of the enrolled speaker for this utterance, or null = not recognised.
   *  Owner comes from the proximity band ("Me"); others from the named voiceprints. Foundation for "only track enrolled voices". */
  static String identify(Context c, String band, Proximity.Feat f) { if ("Me".equals(band) && has(c)) return owner(c); if ("Me".equals(band)) return null; return matchNamed(c, f); }
  /** best-matching named voice (≥0.62 similarity) or null. */
  static String matchNamed(Context c, Proximity.Feat f) { String best = null; double bs = 0.62; for (Map.Entry<String, String> e : voices(c).entrySet()) { double s = featSim(e.getValue(), f); if (s > bs) { bs = s; best = e.getKey(); } } return best; }
  /** Successful Me bands from Calton's 11:32 voice test (snr / Δ / low / hf / mod / f0) – tolerance hints. */
  static final double[] SEED = {15, -5.0, 0.27, 0.11, 40, 110};

  static File dir(Context c) { File d = new File(c.getFilesDir(), "enroll_ref"); if (!d.exists()) d.mkdirs(); return d; }
  static int count(Context c) {
    int n = 0; for (int i = 0; i < MAX; i++) if (new File(dir(c), "s" + i + ".pcm.enc").exists() && Prefs.s(c, "enroll_f" + i, "").length() > 0) n++;
    return n;
  }
  static boolean has(Context c) { return count(c) > 0 || !Prefs.s(c, "prox_prof", "").isEmpty(); }
  static boolean needGuided(Context c) { return !has(c); } // 0.1.12: a saved voice profile also counts – no mic test every launch
  static void clear(Context c) {
    for (int i = 0; i < MAX; i++) { new File(dir(c), "s" + i + ".pcm.enc").delete(); Prefs.put(c, "enroll_f" + i, ""); }
    Prefs.put(c, "enroll_n", "0");
  }
  static String featLine(Proximity.Feat f) {
    return String.format(Locale.US, "%.2f,%.3f,%.3f,%.1f,%.1f,%.1f,%.3f",
      Double.isNaN(f.dbDiff) ? 0 : f.dbDiff, f.lowRatio, f.hfRatio, f.modDb, f.snrDb, f.pitch, f.pitchSpread);
  }
  /** Save one enrollment sample (PCM 16 kHz mono + features). Caps at MAX; oldest kept, newest slot rotates. */
  static synchronized void save(Context c, byte[] pcm, Proximity.Feat f) throws Exception {
    if (pcm == null || pcm.length < SR) throw new Exception("enrollment clip too short");
    // trim to ~2.5 s max
    int max = (int) (SR * 2.5) * 2; if (pcm.length > max) pcm = Arrays.copyOfRange(pcm, pcm.length - max, pcm.length);
    int n = Integer.parseInt(Prefs.s(c, "enroll_n", "0"));
    int slot = n < MAX ? n : (n % MAX);
    File out = new File(dir(c), "s" + slot + ".pcm.enc");
    MasterKey mk = new MasterKey.Builder(c.getApplicationContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
    if (out.exists()) out.delete();
    EncryptedFile ef = new EncryptedFile.Builder(c.getApplicationContext(), out, mk, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
    try (OutputStream os = ef.openFileOutput()) { os.write(pcm); }
    Prefs.put(c, "enroll_f" + slot, featLine(f));
    Prefs.put(c, "enroll_n", String.valueOf(n + 1));
    Proximity.learn(c, f, 3); // seed / reinforce the live profile from enrollment
  }
  static byte[] readPcm(Context c, int slot) {
    try {
      File f = new File(dir(c), "s" + slot + ".pcm.enc"); if (!f.exists()) return null;
      MasterKey mk = new MasterKey.Builder(c.getApplicationContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
      EncryptedFile ef = new EncryptedFile.Builder(c.getApplicationContext(), f, mk, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
      try (InputStream in = ef.openFileInput(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096]; int r; while ((r = in.read(buf)) > 0) bos.write(buf, 0, r); return bos.toByteArray();
      }
    } catch (Throwable t) { return null; }
  }
  /** 0..1 similarity vs enrolled refs (feature distance + optional short waveform energy match). */
  static double match(Context c, byte[] pcm, Proximity.Feat f) {
    int n = count(c); if (n == 0) return 0;
    double best = 0;
    for (int i = 0; i < MAX; i++) {
      String fl = Prefs.s(c, "enroll_f" + i, ""); if (fl.isEmpty()) continue;
      double fs = featSim(fl, f);
      double ws = 0; byte[] ref = readPcm(c, i); if (ref != null && pcm != null && pcm.length > 800 && ref.length > 800) ws = waveSim(ref, pcm);
      best = Math.max(best, 0.75 * fs + 0.25 * ws);
    }
    return best;
  }
  static double featSim(String line, Proximity.Feat f) {
    String[] a = line.split(","); if (a.length < 6) return 0;
    double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) try { r[i] = Double.parseDouble(a[i]); } catch (Exception e) { r[i] = 0; }
    // line: dbDiff,low,hf,mod,snr,pitch,pitchSpread
    double d = 0, w = 0;
    if (!Double.isNaN(f.dbDiff)) { d += Math.abs(f.dbDiff - r[0]) / 6.0; w += 1.5; }
    d += Math.abs(f.lowRatio - r[1]) / 0.12; w += 1;
    d += Math.abs(f.hfRatio - r[2]) / 0.12; w += 1;
    d += Math.abs(f.modDb - r[3]) / 15.0; w += 1;
    d += Math.abs(f.snrDb - r[4]) / 10.0; w += 1.5;
    if (f.pitch > 0 && r[5] > 0) { d += Math.abs(Math.log(f.pitch / r[5])) / 0.18; w += 2; }
    double avg = d / Math.max(w, 1);
    return 1 / (1 + avg);
  }
  /** Cheap normalised energy-envelope correlation on downsampled abs samples. */
  static double waveSim(byte[] a, byte[] b) {
    float[] ea = env(a), eb = env(b); if (ea.length < 8 || eb.length < 8) return 0;
    int n = Math.min(ea.length, eb.length);
    double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += ea[i]; mb += eb[i]; } ma /= n; mb /= n;
    double num = 0, da = 0, db = 0;
    for (int i = 0; i < n; i++) { double xa = ea[i] - ma, xb = eb[i] - mb; num += xa * xb; da += xa * xa; db += xb * xb; }
    if (da < 1e-9 || db < 1e-9) return 0;
    return Math.max(0, num / Math.sqrt(da * db));
  }
  static float[] env(byte[] pcm) {
    short[] s = new short[pcm.length / 2];
    ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
    int hop = 320; // 20 ms
    float[] e = new float[Math.max(1, s.length / hop)];
    for (int i = 0; i < e.length; i++) { double sum = 0; int from = i * hop, to = Math.min(s.length, from + hop);
      for (int j = from; j < to; j++) sum += Math.abs(s[j]); e[i] = (float) (sum / (to - from + 1)); }
    return e;
  }
  /** Text-only status for Send to PC / logs – never includes audio bytes. */
  static String statusLine(Context c) {
    int n = count(c);
    if (n == 0) return "none";
    StringBuilder b = new StringBuilder(n + " sample(s)");
    for (int i = 0; i < MAX; i++) { String fl = Prefs.s(c, "enroll_f" + i, ""); if (!fl.isEmpty()) b.append(" | ").append(fl); }
    return b.toString();
  }
}
