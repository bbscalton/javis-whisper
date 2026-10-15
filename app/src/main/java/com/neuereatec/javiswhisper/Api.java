package com.neuereatec.javiswhisper;
import android.content.Context;
import org.json.JSONObject;
import java.io.*; import java.net.HttpURLConnection; import java.net.URL; import java.nio.charset.StandardCharsets;
/** Thin client for the PC01 Javis Whisper server. Only text and transient in-memory audio (for STT) are sent. */
public class Api {
  public interface Delta { void on(String t); }
  final Context c; public Api(Context c) { this.c = c.getApplicationContext(); }
  HttpURLConnection open(String path, String method) throws IOException {
    String base = Prefs.server(c); if (base.isEmpty()) throw new IOException("Server URL not set (Settings)");
    HttpURLConnection h = (HttpURLConnection) new URL(base + path).openConnection();
    h.setRequestMethod(method); h.setConnectTimeout(8000); h.setReadTimeout(30000);
    h.setRequestProperty("Content-Type", "application/json");
    String t = Prefs.token(c); if (!t.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + t);
    return h;
  }
  static String readAll(InputStream in) throws IOException { if (in == null) return ""; ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); return b.toString("UTF-8"); }
  public JSONObject call(String path, JSONObject body) throws Exception {
    HttpURLConnection h = open(path, body == null ? "GET" : "POST");
    if (body != null) { h.setDoOutput(true); try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); } }
    int code = h.getResponseCode(); String s = readAll(code < 400 ? h.getInputStream() : h.getErrorStream());
    if (code >= 400) throw new IOException("HTTP " + code + " " + s);
    return s.trim().startsWith("[") ? new JSONObject().put("list", new org.json.JSONArray(s)) : new JSONObject(s);
  }
  public String stream(String path, JSONObject body, Delta d) throws Exception {
    HttpURLConnection h = open(path, "POST"); h.setDoOutput(true);
    try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
    if (h.getResponseCode() >= 400) throw new IOException("HTTP " + h.getResponseCode() + " " + readAll(h.getErrorStream()));
    StringBuilder all = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
      String line; while ((line = r.readLine()) != null) {
        if (!line.startsWith("data:")) continue; String p = line.substring(5).trim(); if (p.equals("[DONE]")) break;
        JSONObject j = new JSONObject(p); if (j.has("error")) throw new IOException(j.getString("error"));
        String t = j.optString("t"); all.append(t); d.on(t);
      }
    }
    return all.toString().trim();
  }
}
