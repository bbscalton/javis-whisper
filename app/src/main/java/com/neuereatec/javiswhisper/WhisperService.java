package com.neuereatec.javiswhisper;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Wearable pipeline, runs as a microphone foreground service:
 * earbud mic (Bluetooth SCO) [+ optional phone mic] -> VAD -> PC01 server STT -> Grok whisper (streamed) -> TTS into the earbud.
 * Capture never stops while whispering (full duplex); our own TTS is rejected by echo matching.
 * Audio lives only in RAM for the length of one utterance; it is never written to disk.
 */
public class WhisperService extends Service {
  public static final String ACTION_START = "start", ACTION_STOP = "stop";
  static final int SR = 16000, FRAME = 320; // 20 ms
  // live state read by the UI
  public static volatile boolean running; public static volatile float levelMe, levelThem, thrMe, thrThem;
  public static volatile String status = "Stopped", route = "-"; public static volatile long sessionStart;
  static final LinkedList<String> LOG = new LinkedList<>();
  static final float[] SENS_MIN = {900, 450, 220}, SENS_MARGIN = {3.2f, 2.4f, 1.8f};
  static final String[] SENS_NAME = {"Low", "Normal", "High"};

  public static void log(String s) {
    String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
    synchronized (LOG) { LOG.addFirst(line); while (LOG.size() > 200) LOG.removeLast(); }
    android.util.Log.i("JavisWhisper", s);
  }
  public static List<String> logSnapshot() { synchronized (LOG) { return new ArrayList<>(LOG); } }

  class Chan {
    final String label; final boolean earbud; volatile AudioRecord rec; Thread th;
    volatile long lastRead; volatile String restart; float noise = 150;
    Chan(String l, boolean e) { label = l; earbud = e; }
  }
  static class Utt { Chan ch; byte[] pcm; long start, end; float snr; boolean duringTts; boolean dropped; }

  AudioManager am; TextToSpeech tts; volatile boolean ttsReady; Api api; PowerManager.WakeLock wl;
  Chan me, them; boolean dual; int sens; String mode, prebrief, sessionId;
  final ExecutorService proc = Executors.newSingleThreadExecutor(), net = Executors.newSingleThreadExecutor();
  final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
  final List<Utt> recent = Collections.synchronizedList(new ArrayList<>());
  volatile long speakingUntil; volatile String lastWhisper = ""; volatile long lastWhisperAt;
  AudioDeviceCallback devCb;

  @Override public IBinder onBind(Intent i) { return null; }

  @Override public void onCreate() {
    super.onCreate(); am = getSystemService(AudioManager.class); api = new Api(this);
    tts = new TextToSpeech(this, st -> {
      ttsReady = st == TextToSpeech.SUCCESS;
      if (!ttsReady) { log("TTS init failed"); return; }
      tts.setLanguage(Locale.US); tts.setSpeechRate(1.15f);
      tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        public void onStart(String id) { speakingUntil = Long.MAX_VALUE; }
        public void onDone(String id) { speakingUntil = SystemClock.uptimeMillis() + 400; }
        public void onError(String id) { speakingUntil = SystemClock.uptimeMillis(); }
      });
    });
  }

  @Override public int onStartCommand(Intent i, int flags, int id) {
    String a = i == null ? ACTION_START : i.getAction();
    if (ACTION_STOP.equals(a)) { stopAll(); return START_NOT_STICKY; }
    if (running) return START_STICKY;
    goForeground("Listening…");
    running = true; sessionStart = System.currentTimeMillis();
    new Thread(this::startAll, "jw-start").start();
    return START_STICKY;
  }

  void goForeground(String text) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("jw", "Javis Whisper", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WhisperService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
    Notification n = new Notification.Builder(this, "jw").setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("Javis Whisper").setContentText(text).setContentIntent(open).setOngoing(true)
      .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); else startForeground(7, n);
  }

  void startAll() {
    try {
      wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jw:listen"); wl.acquire(6 * 3600_000L);
      mode = Prefs.s(this, "mode", "coach"); prebrief = Prefs.s(this, "prebrief", "");
      dual = "1".equals(Prefs.s(this, "dual", "1")); sens = Integer.parseInt(Prefs.s(this, "sens", "1"));
      sessionId = UUID.randomUUID().toString();
      JSONObject s = new JSONObject().put("type", "session").put("clientId", sessionId).put("mode", mode).put("prebrief", prebrief).put("started", iso(System.currentTimeMillis()));
      enqueue(s); flushQueue();
      log("Session started · mode " + mode + (prebrief.isEmpty() ? "" : " · pre-brief set") + " · " + (dual ? "earbud + phone mic" : "earbud mic only"));
      startBt();
      me = new Chan(dual ? "Me" : "Speaker", true); them = dual ? new Chan("Them", false) : null;
      for (Chan c : chans()) { c.restart = "initial open"; c.th = new Thread(() -> readLoop(c), "jw-" + c.label); c.th.start(); }
      devCb = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { for (AudioDeviceInfo x : d) if (isBt(x.getType()) && x.isSource()) { log("Earbuds connected → reconnecting SCO"); sched.schedule(() -> { restoreSco(); if (me != null) me.restart = "earbuds reconnected"; }, 700, TimeUnit.MILLISECONDS); } }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { for (AudioDeviceInfo x : d) if (isBt(x.getType()) && x.isSource()) log("Earbuds disconnected – waiting for them to come back"); }
      };
      am.registerAudioDeviceCallback(devCb, new Handler(Looper.getMainLooper()));
      sched.scheduleWithFixedDelay(this::watchdog, 2, 2, TimeUnit.SECONDS);
      sched.scheduleWithFixedDelay(() -> net.execute(this::flushQueue), 30, 30, TimeUnit.SECONDS);
      status = "Listening";
    } catch (Throwable t) { log("Start failed: " + t); status = "Error: " + t.getMessage(); }
  }
  Chan[] chans() { return them == null ? new Chan[]{me} : new Chan[]{me, them}; }

  void stopAll() {
    running = false; status = "Stopping…";
    new Thread(() -> {
      try { if (devCb != null) am.unregisterAudioDeviceCallback(devCb); } catch (Throwable ignored) {}
      sched.shutdownNow();
      if (me != null) for (Chan c : chans()) { try { c.rec.stop(); } catch (Throwable ignored) {} try { if (c.th != null) c.th.join(1500); } catch (Throwable ignored) {} try { c.rec.release(); } catch (Throwable ignored) {} }
      stopBt();
      if (sessionId != null) { try { enqueue(new JSONObject().put("type", "end").put("clientId", sessionId)); } catch (Exception ignored) {} net.execute(this::flushQueue); }
      try { net.shutdown(); net.awaitTermination(25, TimeUnit.SECONDS); } catch (Throwable ignored) {}
      try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
      levelMe = levelThem = 0; status = "Stopped"; route = "-"; log("Session stopped");
      stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }).start();
  }
  @Override public void onDestroy() { running = false; if (tts != null) tts.shutdown(); super.onDestroy(); }

  // ---------------- Bluetooth SCO (from Neuereatec Translator, incl. keep-listening fix) ----------------
  static boolean isBt(int t) { return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET; }
  AudioDeviceInfo find(int flag, int type) { for (AudioDeviceInfo d : am.getDevices(flag)) if (d.getType() == type) return d; return null; }
  AudioDeviceInfo btIn() { AudioDeviceInfo d = find(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BLUETOOTH_SCO); return d != null ? d : find(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BLE_HEADSET); }
  void startBt() {
    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if (!restoreSco()) log("No Bluetooth earbuds available for calls – connect earbuds (will keep retrying)");
    for (int i = 0; i < 30 && !scoActive(); i++) SystemClock.sleep(100);
    SystemClock.sleep(500); updateRoute();
  }
  boolean scoActive() {
    if (Build.VERSION.SDK_INT >= 31) { AudioDeviceInfo c = am.getCommunicationDevice(); return c != null && isBt(c.getType()); }
    return am.isBluetoothScoOn();
  }
  boolean restoreSco() {
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) if (isBt(d.getType())) return am.setCommunicationDevice(d);
        return false;
      }
      am.startBluetoothSco(); am.setBluetoothScoOn(true); return true;
    } catch (Throwable t) { log("SCO error: " + t); return false; }
  }
  void stopBt() {
    try { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); } am.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
  }
  void updateRoute() {
    String r = scoActive() ? "Earbuds (SCO)" : "Phone (no earbuds)";
    try { if (me != null && me.rec != null) { AudioDeviceInfo d = me.rec.getRoutedDevice(); if (d != null) r += " · mic " + (isBt(d.getType()) ? "earbud" : "phone"); } } catch (Throwable ignored) {}
    route = r;
  }
  void watchdog() {
    if (!running) return;
    try {
      if (!scoActive() && btIn() != null) { log("SCO dropped → re-asserting earbuds"); restoreSco(); }
      if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
      long now = SystemClock.uptimeMillis();
      for (Chan c : chans()) {
        if (c.restart != null) continue;
        AudioRecord r = c.rec; if (r == null) { c.restart = "no recorder"; continue; }
        if (now - c.lastRead > 2500) { c.restart = "read() stalled"; try { r.stop(); } catch (Throwable ignored) {} continue; }
        boolean silenced = false; if (Build.VERSION.SDK_INT >= 29) try { AudioRecordingConfiguration cf = r.getActiveRecordingConfiguration(); silenced = cf != null && cf.isClientSilenced(); } catch (Throwable ignored) {}
        if (silenced) { c.restart = "silenced by Android"; continue; }
        if (c.earbud && btIn() != null) { AudioDeviceInfo d = r.getRoutedDevice(); if (d != null && !isBt(d.getType())) c.restart = "earbud mic rerouted to phone"; }
      }
      updateRoute();
    } catch (Throwable t) { log("watchdog: " + t); }
  }
  AudioRecord makeRec(Chan c) {
    AudioDeviceInfo pref = c.earbud ? btIn() : find(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    int[] srcs = c.earbud ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC} : new int[]{MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION};
    int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    for (int src : srcs) try {
      AudioRecord r = new AudioRecord.Builder().setAudioSource(src).setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min * 4, SR)).build();
      if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); continue; }
      if (pref != null) r.setPreferredDevice(pref);
      r.startRecording(); return r;
    } catch (SecurityException e) { log("Microphone permission missing"); return null; } catch (Throwable t) { log(c.label + " source " + src + " failed: " + t.getMessage()); }
    return null;
  }

  // ---------------- capture + VAD (never pauses while whispering) ----------------
  void readLoop(Chan c) {
    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
    short[] f = new short[FRAME]; ArrayDeque<short[]> pre = new ArrayDeque<>();
    ByteArrayOutputStream utt = null; int voicedRun = 0, silence = 0, voiced = 0, frames = 0; double sum = 0; long uttStart = 0; boolean tts = false;
    while (running) {
      if (c.restart != null) {
        log(c.label + " mic (re)open: " + c.restart); utt = null; pre.clear();
        try { if (c.rec != null) { c.rec.stop(); c.rec.release(); } } catch (Throwable ignored) {}
        c.rec = makeRec(c); c.lastRead = SystemClock.uptimeMillis();
        if (c.rec == null) { SystemClock.sleep(1500); continue; }
        c.restart = null; SystemClock.sleep(100); updateRoute(); continue;
      }
      AudioRecord r = c.rec; if (r == null) { c.restart = "null"; continue; }
      int got; try { got = r.read(f, 0, FRAME); } catch (Throwable t) { got = -1; }
      c.lastRead = SystemClock.uptimeMillis();
      if (got <= 0) { SystemClock.sleep(20); continue; }
      double s = 0; for (int i = 0; i < got; i++) s += (double) f[i] * f[i]; float rms = (float) Math.sqrt(s / got);
      int sv = sens; float thr = Math.max(SENS_MIN[sv], c.noise * SENS_MARGIN[sv]);
      if (c == me) { levelMe = rms; thrMe = thr; } else { levelThem = rms; thrThem = thr; }
      boolean v = rms > thr;
      if (utt == null) {
        c.noise = c.noise * 0.995f + Math.min(rms, c.noise * 3) * 0.005f; if (c.noise < 40) c.noise = 40;
        pre.addLast(Arrays.copyOf(f, got)); if (pre.size() > 15) pre.removeFirst();
        voicedRun = v ? voicedRun + 1 : 0;
        if (voicedRun >= 3) { utt = new ByteArrayOutputStream(); for (short[] p : pre) write(utt, p); pre.clear(); uttStart = System.currentTimeMillis() - 300; silence = 0; voiced = 3; frames = 0; sum = 0; tts = SystemClock.uptimeMillis() < speakingUntil; }
      } else {
        write(utt, Arrays.copyOf(f, got)); frames++; sum += rms / Math.max(c.noise, 1);
        if (SystemClock.uptimeMillis() < speakingUntil) tts = true;
        if (v) { voiced++; silence = 0; } else silence++;
        if (silence > 35 || frames > 600) { // 700 ms pause or 12 s max
          if (voiced >= 12) { Utt u = new Utt(); u.ch = c; u.pcm = utt.toByteArray(); u.start = uttStart; u.end = System.currentTimeMillis(); u.snr = (float) (sum / Math.max(frames, 1)); u.duringTts = tts; submit(u); }
          utt = null; voicedRun = 0;
        }
      }
    }
  }
  static void write(ByteArrayOutputStream o, short[] p) { for (short x : p) { o.write(x & 0xff); o.write((x >> 8) & 0xff); } }

  void submit(Utt u) {
    recent.add(u); synchronized (recent) { recent.removeIf(x -> x.end < System.currentTimeMillis() - 15000); }
    sched.schedule(() -> proc.execute(() -> process(u)), dual ? 350 : 0, TimeUnit.MILLISECONDS);
  }

  /** Multi-speaker (best effort): with two mics the same speech is heard on both; keep the copy with the better signal-to-noise. */
  boolean duplicateOfLouder(Utt u) {
    synchronized (recent) {
      for (Utt o : recent) {
        if (o == u || o.ch == u.ch || o.dropped) continue;
        long ov = Math.min(o.end, u.end) - Math.max(o.start, u.start);
        if (ov > 0.5 * Math.min(o.end - o.start, u.end - u.start) && o.snr > u.snr) return true;
      }
    }
    return false;
  }

  void process(Utt u) {
    if (!running && sessionId == null) return;
    try {
      if (dual && duplicateOfLouder(u)) { u.dropped = true; u.pcm = null; return; }
      mode = Prefs.s(this, "mode", mode); sens = Integer.parseInt(Prefs.s(this, "sens", "1"));
      boolean tr = "translate".equals(mode);
      JSONObject req = new JSONObject().put("audio", Base64.encodeToString(u.pcm, Base64.NO_WRAP)).put("lang", "en-US");
      if (tr) req.put("alt", new JSONArray(Arrays.asList(Prefs.s(this, "langs", "es-ES,pt-BR,fr-FR").split("\\s*,\\s*"))));
      u.pcm = null; // drop audio from memory as soon as it is sent
      status = "Transcribing…";
      JSONObject r = api.call("/api/stt", req);
      String text = r.optString("text").trim(), lang = r.optString("lang", "en-us");
      status = "Listening";
      if (text.isEmpty()) return;
      if ((u.duringTts || u.start < lastWhisperAt + 4000) && echoOf(text, lastWhisper)) { log("(ignored own whisper echo: " + text + ")"); return; }
      String spk = u.ch.label;
      log(spk + (lang.startsWith("en") ? "" : " [" + lang + "]") + ": " + text);
      JSONObject heard = new JSONObject().put("clientId", UUID.randomUUID().toString()).put("sessionId", sessionId).put("ts", iso(u.start)).put("speaker", spk).put("lang", lang).put("text", text);
      boolean ask;
      if (tr) ask = !lang.toLowerCase(Locale.US).startsWith("en");
      else ask = !"Me".equals(spk);
      if (!ask) { enqueue(heard.put("type", "turn")); net.execute(this::flushQueue); return; }
      whisper(heard);
    } catch (Exception e) { status = "Listening"; log("STT failed: " + e.getMessage()); }
  }

  void whisper(JSONObject heard) {
    flushQueue();
    StringBuilder pending = new StringBuilder();
    try {
      status = "Thinking…";
      String all = api.stream("/api/whisper", new JSONObject().put("sessionId", sessionId).put("mode", mode).put("heard", heard), t -> {
        pending.append(t); int cut = lastBoundary(pending);
        if (cut > 12) { String seg = pending.substring(0, cut); pending.delete(0, cut); speak(seg); }
      });
      if (pending.length() > 0) speak(pending.toString());
      status = "Listening";
      if (!all.isEmpty() && !all.equals("-")) { lastWhisper = all; lastWhisperAt = System.currentTimeMillis(); log("🗣 Javis: " + all); }
    } catch (Exception e) {
      status = "Listening"; log("Whisper failed (" + e.getMessage() + ") – saved for upload");
      try { enqueue(heard.put("type", "turn")); } catch (Exception ignored) {}
    }
  }
  static int lastBoundary(StringBuilder b) { int k = -1; for (int i = 0; i < b.length(); i++) { char ch = b.charAt(i); if (ch == '.' || ch == '!' || ch == '?' || ch == '|' || ch == ';') k = i + 1; } return k; }
  void speak(String seg) {
    seg = seg.replace("||", ". ").replaceAll("[*_#`]", "").trim();
    if (seg.isEmpty() || seg.equals("-") || !ttsReady || "0".equals(Prefs.s(this, "voice", "1"))) return;
    Bundle p = new Bundle(); p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Float.parseFloat(Prefs.s(this, "vol", "0.35")));
    speakingUntil = Long.MAX_VALUE;
    tts.speak(seg, TextToSpeech.QUEUE_ADD, p, UUID.randomUUID().toString());
  }
  /** Echo handling: the earbud mic can pick up our own whisper. Reject transcripts that mostly repeat it. */
  static boolean echoOf(String heard, String whisper) {
    if (whisper == null || whisper.isEmpty()) return false;
    Set<String> w = new HashSet<>(Arrays.asList(whisper.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+")));
    String[] h = heard.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+");
    int hit = 0; for (String x : h) if (w.contains(x)) hit++;
    return h.length > 0 && hit >= Math.max(2, h.length * 0.6);
  }

  // ---------------- small offline queue (text only), flushed to PC01 ----------------
  File qf() { return new File(getFilesDir(), "upload-queue.jsonl"); }
  synchronized void enqueue(JSONObject o) { try (FileWriter w = new FileWriter(qf(), true)) { w.write(o.toString() + "\n"); } catch (IOException e) { log("queue write failed " + e); } }
  synchronized void flushQueue() {
    File f = qf(); if (!f.exists() || Prefs.token(this).isEmpty()) return;
    List<String> lines = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new FileReader(f))) { String l; while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) lines.add(l); } catch (IOException e) { return; }
    int done = 0;
    try {
      for (String l : lines) {
        JSONObject o = new JSONObject(l); String t = o.optString("type");
        if (t.equals("session")) api.call("/api/session", o);
        else if (t.equals("turn")) api.call("/api/turns", new JSONObject().put("turns", new JSONArray().put(o)));
        else if (t.equals("end")) { JSONObject r = api.call("/api/session/" + o.getString("clientId") + "/end", new JSONObject()); log("Session summary saved on PC01: " + r.optString("summary")); }
        done++;
      }
    } catch (Exception e) { if (done == 0) log("Offline – " + lines.size() + " item(s) queued (" + e.getMessage() + ")"); }
    try (FileWriter w = new FileWriter(f, false)) { for (int i = done; i < lines.size(); i++) w.write(lines.get(i) + "\n"); } catch (IOException ignored) {}
  }
  public static int queued(Context c) { File f = new File(c.getFilesDir(), "upload-queue.jsonl"); if (!f.exists()) return 0; int n = 0; try (BufferedReader r = new BufferedReader(new FileReader(f))) { while (r.readLine() != null) n++; } catch (IOException ignored) {} return n; }
  static String iso(long ms) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US); return f.format(new Date(ms)); }
}
