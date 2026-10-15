package com.neuereatec.javiswhisper;
import android.app.Activity; import android.app.AlertDialog; import android.content.ContentValues; import android.os.*; import android.provider.MediaStore;
import org.json.*;
import java.io.*; import java.text.SimpleDateFormat; import java.util.*;
/** 0.1.8 "Send to PC": on demand, uploads recent conversations (text only: transcripts, whispers, skip reasons, voice scores, timings) + the app log to PC01. Never audio. */
public class SendToPc {
  static void ask(Activity a) {
    String[] opts = {"Last session", "Today", "Last 24 hours"};
    new AlertDialog.Builder(a).setTitle("Send to PC01 (text only, no audio)").setItems(opts, (d, i) -> run(a, i, opts[i])).setNegativeButton("Cancel", null).show();
  }
  static void run(Activity a, int which, String label) {
    if (Prefs.token(a).isEmpty()) { info(a, "Not paired", "Pair with PC01 first (Settings → 6-digit code from the admin page)."); return; }
    AlertDialog wait = new AlertDialog.Builder(a).setMessage("Sending " + label.toLowerCase(Locale.US) + "…").setCancelable(false).show();
    new Thread(() -> { String title, msg;
      try {
        JSONObject x = build(a, which, label);
        JSONObject r = new Api(a).call("/api/upload", x);
        String file = r.optString("file"); String local = saveDownloads(a, x);
        title = "Sent ✓"; msg = "Saved on PC01 as:\n" + file + "\n\n" + x.getJSONArray("sessions").length() + " session(s), " + x.getJSONArray("log").length() + " log lines." + (local == null ? "" : "\n\nCopy on this phone: " + local);
        WhisperService.log("⇪ Sent to PC: " + file);
      } catch (Exception e) {
        String m = String.valueOf(e.getMessage()); title = "Not sent";
        msg = m.contains("401") ? "PC01 didn't accept this phone's pairing – pair again in Settings." : m.contains("Unable to resolve") || m.contains("timeout") || m.contains("failed to connect") ? "Can't reach PC01 (whisper.neuereatec.org). Is PC01 on?" : m.contains("HTTP 5") || m.contains("1033") ? "PC01's server is down or the tunnel is off. Try again later." : m;
        try { String local = saveDownloads(a, build(a, which, label)); if (local != null) msg += "\n\nSaved a copy on this phone instead: " + local; } catch (Exception ignored) {}
      }
      String t = title, mm = msg; a.runOnUiThread(() -> { wait.dismiss(); info(a, t, mm); });
    }).start();
  }
  static void info(Activity a, String t, String m) { new AlertDialog.Builder(a).setTitle(t).setMessage(m).setPositiveButton("OK", null).show(); }
  static JSONObject build(Activity a, int which, String label) throws Exception {
    long now = System.currentTimeMillis(), from;
    Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
    List<Db.Session> all = Db.get(a).d().sessions(), pick = new ArrayList<>();
    if (which == 0) { if (!all.isEmpty()) pick.add(all.get(0)); from = all.isEmpty() ? now : all.get(0).started - 5000; }
    else { from = which == 1 ? c.getTimeInMillis() : now - 86_400_000L; for (Db.Session s : all) if (s.started >= from || s.ended >= from) pick.add(s); }
    JSONArray ss = new JSONArray();
    for (Db.Session s : pick) { JSONArray ts = new JSONArray();
      for (Db.Turn t : Db.get(a).d().turns(s.id)) ts.put(new JSONObject().put("ts", Learn.iso(t.ts)).put("kind", t.kind).put("speaker", t.speaker).put("lang", t.lang).put("text", t.text));
      ss.put(new JSONObject().put("started", Learn.iso(s.started)).put("ended", s.ended > 0 ? Learn.iso(s.ended) : "").put("mode", s.mode).put("prebrief", s.prebrief).put("summary", s.summary).put("turns", ts)); }
    JSONArray log = new JSONArray(); long to = which == 0 && !pick.isEmpty() && pick.get(0).ended > 0 ? pick.get(0).ended + 60_000 : now + 1;
    WhisperService.initLog(a); File lf = WhisperService.logFile;
    for (File f : new File[]{new File(lf.getPath() + ".1"), lf}) if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) { String l;
      while ((l = r.readLine()) != null) { int tab = l.indexOf('\t'); if (tab < 0) continue; long t; try { t = Long.parseLong(l.substring(0, tab)); } catch (NumberFormatException e) { continue; }
        if (t >= from && t <= to) log.put(Learn.iso(t) + "  " + l.substring(tab + 1)); } }
    String ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
    JSONObject settings = new JSONObject(); for (String k : new String[]{"mode", "model", "voice_id", "sens", "dual", "setting", "vol", "pace", "prox_prof"}) settings.put(k, Prefs.s(a, k, ""));
    return new JSONObject().put("app", "Javis Whisper").put("version", ver).put("range", label).put("from", Learn.iso(from)).put("to", Learn.iso(now))
      .put("phone", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE).put("settings", settings).put("learned", Prefs.s(a, "learned", "")).put("sessions", ss).put("log", log);
  }
  /** Also drop a copy in Downloads/JavisWhisper so it can be pulled over USB (adb pull /sdcard/Download/JavisWhisper). */
  static String saveDownloads(Activity a, JSONObject x) {
    try { String name = "javis-whisper-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
      ContentValues v = new ContentValues(); v.put(MediaStore.Downloads.DISPLAY_NAME, name); v.put(MediaStore.Downloads.MIME_TYPE, "application/json"); v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JavisWhisper");
      android.net.Uri u = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v); if (u == null) return null;
      try (OutputStream o = a.getContentResolver().openOutputStream(u)) { o.write(x.toString(1).getBytes("UTF-8")); }
      return "Download/JavisWhisper/" + name;
    } catch (Throwable t) { return null; }
  }
}
