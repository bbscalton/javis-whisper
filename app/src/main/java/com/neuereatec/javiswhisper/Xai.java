package com.neuereatec.javiswhisper;
import android.os.SystemClock;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Direct xAI client: streaming STT (wss /v1/stt), Grok streaming chat, streaming TTS (wss /v1/tts, voice Ara). Key comes from Settings only. */
public class Xai {
  static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .pingInterval(15, TimeUnit.SECONDS).connectionPool(new ConnectionPool(4, 5, TimeUnit.MINUTES)).build();
  final String key; Xai(String key) { this.key = key.trim(); }
  Request.Builder rq(String url) { return new Request.Builder().url(url).header("Authorization", "Bearer " + key); }

  // ---------- STT: one websocket per utterance, audio streamed while the person talks ----------
  public class Stt extends WebSocketListener {
    final WebSocket ws; volatile boolean ready, done; final List<byte[]> pending = new ArrayList<>(); final TreeMap<Double, String> segs = new TreeMap<>();
    String finalText; long endAt; volatile long lastFinalAt; final CountDownLatch latch = new CountDownLatch(1); String error;
    Stt(String lang) {
      String u = "wss://api.x.ai/v1/stt?sample_rate=16000&encoding=pcm&interim_results=false&endpointing=300" + (lang == null || lang.isEmpty() ? "" : "&language=" + lang);
      String[] kt = keyterms; if (kt != null) for (String k : kt) try { u += "&keyterm=" + java.net.URLEncoder.encode(k.length() > 50 ? k.substring(0, 50) : k, "UTF-8"); } catch (Exception ignored) {}
      ws = HTTP.newWebSocket(rq(u).build(), this);
    }
    public synchronized void push(byte[] pcm) { if (done) return; if (ready) ws.send(ByteString.of(pcm)); else pending.add(pcm); }
    public synchronized void end() { endAt = SystemClock.uptimeMillis(); done = true; if (ready) ws.send("{\"type\":\"audio.done\"}"); }
    public void cancel() { ws.cancel(); latch.countDown(); }
    /** waits for the final transcript (normally ~250 ms after end()). */
    /** final segments that arrived after `since` mean the STT already closed this phrase: no need to wait for transcript.done. */
    public boolean finalSince(long since) { return lastFinalAt > since && !segs.isEmpty(); }
    public String result(long ms) {
      if (!(segs.isEmpty() || lastFinalAt < endAt - 400)) { ws.close(1000, null); return collapse(String.join(" ", segs.values())); }
      try { latch.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {} ws.close(1000, null);
      return collapse(!segs.isEmpty() ? String.join(" ", segs.values()) : finalText == null ? "" : finalText); }
    /** xAI STT sometimes repeats the start of a phrase at the end ("X ... X"); drop the duplicated tail. */
    static String collapse(String t) { t = t.replaceAll("\\s+", " ").trim(); String[] w = t.split(" ");
      for (int k = w.length / 2; k >= 2; k--) { boolean same = true; for (int i = 0; i < k; i++) if (!w[i].equalsIgnoreCase(w[w.length - k + i])) { same = false; break; } if (same) return String.join(" ", Arrays.copyOf(w, w.length - k)); }
      return t; }
    @Override public void onMessage(WebSocket w, String text) {
      try { JSONObject e = new JSONObject(text); String t = e.optString("type");
        if (t.equals("transcript.created")) { synchronized (this) { ready = true; for (byte[] b : pending) w.send(ByteString.of(b)); pending.clear(); if (done) w.send("{\"type\":\"audio.done\"}"); } }
        else if (t.equals("transcript.partial") && e.optBoolean("is_final") && !e.optString("text").isEmpty()) { segs.put(e.optDouble("start", segs.size()), e.optString("text").trim()); lastFinalAt = SystemClock.uptimeMillis(); }
        else if (t.equals("transcript.done")) { finalText = e.optString("text").trim(); latch.countDown(); }
        else if (t.equals("error")) { error = e.optString("message", text); latch.countDown(); }
      } catch (JSONException ignored) {}
    }
    @Override public void onFailure(WebSocket w, Throwable t, Response r) { error = t + (r != null ? " HTTP " + r.code() : ""); latch.countDown(); }
    @Override public void onClosed(WebSocket w, int c, String r) { latch.countDown(); }
  }
  public static volatile String[] keyterms;
  public Stt stt(String lang) { return new Stt(lang); }

  // ---------- Grok streaming ----------
  public interface Delta { void on(String t); }
  public String chat(String model, JSONArray messages, int max, double temp, Delta d) throws Exception {
    JSONObject b = new JSONObject().put("model", model).put("stream", d != null).put("max_tokens", max).put("temperature", temp).put("messages", messages);
    Request r = rq("https://api.x.ai/v1/chat/completions").post(RequestBody.create(b.toString(), MediaType.get("application/json"))).build();
    try (Response res = HTTP.newCall(r).execute()) {
      if (!res.isSuccessful()) throw new IOException("xAI HTTP " + res.code() + " " + (res.body() != null ? res.body().string() : ""));
      if (d == null) return new JSONObject(res.body().string()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim();
      StringBuilder all = new StringBuilder(); BufferedReader br = new BufferedReader(new InputStreamReader(res.body().byteStream(), "UTF-8")); String line;
      while ((line = br.readLine()) != null) {
        if (!line.startsWith("data:")) continue; String p = line.substring(5).trim(); if (p.equals("[DONE]")) break;
        try { JSONArray ch = new JSONObject(p).optJSONArray("choices"); if (ch == null || ch.length() == 0) continue;
          String t = ch.getJSONObject(0).optJSONObject("delta") == null ? "" : ch.getJSONObject(0).getJSONObject("delta").optString("content"); if (!t.isEmpty()) { all.append(t); d.on(t); } } catch (JSONException ignored) {}
      }
      return all.toString().trim();
    }
  }

  // ---------- TTS: persistent websocket (multi-utterance), PCM16 16 kHz streamed back ----------
  public interface Audio { void on(byte[] pcm); void done(); }
  public class Tts extends WebSocketListener {
    volatile WebSocket ws; volatile boolean open; volatile Audio sink; final String voice;
    Tts(String voice) { this.voice = voice; connect(); }
    synchronized void connect() { open = false; ws = HTTP.newWebSocket(rq("wss://api.x.ai/v1/tts?language=en&voice=" + voice + "&codec=pcm&sample_rate=16000&optimize_streaming_latency=2").build(), this); }
    public void ensure() { if (ws == null || !open) connect(); }
    public void begin(Audio a) { sink = a; ensure(); }
    public void text(String t) { try { ws.send(new JSONObject().put("type", "text.delta").put("delta", t).toString()); } catch (JSONException ignored) {} }
    public void finish() { ws.send("{\"type\":\"text.done\"}"); }
    public void close() { try { ws.close(1000, null); } catch (Throwable ignored) {} }
    @Override public void onOpen(WebSocket w, Response r) { open = true; }
    @Override public void onMessage(WebSocket w, String text) {
      try { JSONObject e = new JSONObject(text); String t = e.optString("type"); Audio a = sink;
        if (t.equals("audio.delta") && a != null) a.on(android.util.Base64.decode(e.getString("delta"), android.util.Base64.DEFAULT));
        else if (t.equals("audio.done") && a != null) { sink = null; a.done(); }
        else if (t.equals("error")) { WhisperService.log("TTS error: " + e.optString("message")); if (a != null) { sink = null; a.done(); } }
      } catch (JSONException ignored) {}
    }
    @Override public void onFailure(WebSocket w, Throwable t, Response r) { open = false; Audio a = sink; sink = null; if (a != null) a.done(); if (w == ws) WhisperService.log("TTS link dropped (" + t.getMessage() + "), will reconnect"); }
    @Override public void onClosed(WebSocket w, int c, String r) { open = false; }
  }
  public Tts tts(String voice) { return new Tts(voice); }
}
