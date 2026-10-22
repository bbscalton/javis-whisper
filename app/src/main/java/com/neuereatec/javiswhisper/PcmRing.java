package com.neuereatec.javiswhisper;
import java.util.Arrays;
/**
 * 0.1.23 rolling PCM16 ring buffer (RAM only). Holds the last N samples of microphone audio before the wake word.
 * Deliberately has NO file, stream, Parcelable or Serializable support: audio never reaches disk. clear() zero-fills.
 */
final class PcmRing {
  private final short[] buf; private int pos, len;
  PcmRing(int capacitySamples) { buf = new short[capacitySamples]; }
  static PcmRing seconds(double s, int sampleRate) { return new PcmRing((int) Math.round(s * sampleRate)); }
  synchronized void write(short[] f, int n) {
    n = Math.min(n, f.length);
    for (int i = 0; i < n; i++) { buf[pos] = f[i]; pos = (pos + 1) % buf.length; }
    len = Math.min(buf.length, len + n);
  }
  /** oldest → newest copy (caller must wipe it after use). */
  synchronized short[] snapshot() {
    short[] out = new short[len]; int start = (pos - len + buf.length) % buf.length;
    for (int i = 0; i < len; i++) out[i] = buf[(start + i) % buf.length];
    return out;
  }
  synchronized void clear() { Arrays.fill(buf, (short) 0); pos = 0; len = 0; }
  synchronized int size() { return len; }
  int capacity() { return buf.length; }
  /** test hook: true when every stored sample is zero (after clear()). */
  synchronized boolean isWiped() { for (short s : buf) if (s != 0) return false; return len == 0; }
  static byte[] toBytes(short[] a, int n) { byte[] b = new byte[n * 2]; for (int i = 0; i < n; i++) { b[2 * i] = (byte) a[i]; b[2 * i + 1] = (byte) (a[i] >> 8); } return b; }
  static void wipe(short[] a) { if (a != null) Arrays.fill(a, (short) 0); }
  static void wipe(byte[] a) { if (a != null) Arrays.fill(a, (byte) 0); }
}
