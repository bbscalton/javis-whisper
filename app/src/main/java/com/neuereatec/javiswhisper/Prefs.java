package com.neuereatec.javiswhisper;
import android.content.Context; import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences; import androidx.security.crypto.MasterKey;
/** All settings incl. the per-device server token live in EncryptedSharedPreferences. No API keys are ever in the APK. */
public class Prefs {
  public static final String DEFAULT_SERVER = "https://whisper.neuereatec.org";
  private static SharedPreferences sp;
  public static synchronized SharedPreferences get(Context c) {
    if (sp == null) try {
      MasterKey mk = new MasterKey.Builder(c.getApplicationContext()).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
      sp = EncryptedSharedPreferences.create(c.getApplicationContext(), "jw_secure", mk, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    } catch (Exception e) { throw new RuntimeException(e); }
    return sp;
  }
  public static String s(Context c, String k, String d) { return get(c).getString(k, d); }
  public static void put(Context c, String k, String v) { get(c).edit().putString(k, v).apply(); }
  public static String server(Context c) { String u = s(c, "server", DEFAULT_SERVER).trim(); while (u.endsWith("/")) u = u.substring(0, u.length() - 1); return u; }
  public static int i(Context c, String k, int d, int lo, int hi) { try { return Math.max(lo, Math.min(hi, Integer.parseInt(s(c, k, String.valueOf(d)).trim()))); } catch (Exception e) { return d; } }
  public static String token(Context c) { return s(c, "token", ""); }
}
