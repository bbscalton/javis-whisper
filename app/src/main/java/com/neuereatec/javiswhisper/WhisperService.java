package com.neuereatec.javiswhisper;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.io.File;

/**
 * Wearable pipeline (v0.1.2, phone talks to xAI directly):
 * earbud mic (SCO) + phone mic -> on-phone VAD -> xAI streaming STT (audio streamed WHILE the person talks)
 * -> Grok streaming -> xAI streaming TTS (voice Ara) fed token by token -> AudioTrack into the earbud.
 * Capture never pauses (full duplex); our own voice is rejected by echo matching. Conversation audio is never written anywhere (0.1.10 exception: 1–3 encrypted enrollment clips, on-phone only).
 */
public class WhisperService extends Service {
  public static final String ACTION_START = "start", ACTION_STOP = "stop";
  static final int SR = 16000, FRAME = 320; // 20 ms
  public static volatile boolean running; public static volatile float levelMe, levelThem, thrMe, thrThem;
  public static volatile String status = "Stopped", route = "-", lastTiming = ""; public static volatile long sessionStart;
  static final LinkedList<String> LOG = new LinkedList<>();
  static final float[] SENS_MIN = {700, 300, 150}, SENS_MARGIN = {3.2f, 2.4f, 1.8f};
  static final String[] SENS_NAME = {"Low", "Normal", "High"};
  static volatile int END_SILENCE_FRAMES = 22; // 0.1.11: 440 ms pause ends an utterance (was 25 = 500 ms); Settings "end_sil"

  public static void log(String s) {
    String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
    synchronized (LOG) { LOG.addFirst(line); while (LOG.size() > 250) LOG.removeLast(); }
    android.util.Log.i("JavisWhisper", s);
    // 0.1.8: keep a text-only log on the phone (skip reasons, voice scores, timings) for "Send to PC"; ~3 MB, one rotation
    File f = logFile; if (f != null) synchronized (LOG) { try {
      if (f.length() > 3_000_000) { File o = new File(f.getPath() + ".1"); o.delete(); f.renameTo(o); }
      try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(System.currentTimeMillis() + "\t" + s.replace('\n', ' ') + "\n"); }
    } catch (Throwable ignored) {} }
  }
  static volatile File logFile;
  static void initLog(Context c) { if (logFile == null) logFile = new File(c.getFilesDir(), "jw-log.txt"); }
  public static List<String> logSnapshot() { synchronized (LOG) { return new ArrayList<>(LOG); } }

  class Chan { final String label; final boolean earbud; volatile AudioRecord rec; Thread th; volatile long lastRead; volatile String restart; float noise = 150; volatile float snr; long frames; float peak; int quietFrames; boolean silentWarned;
    Chan(String l, boolean e) { label = l; earbud = e; } }
  /** One utterance at a time across both mics: the mic that hears speech first streams it; speaker = mic with higher SNR. */
  class Utt { final Proximity.Acc acc = new Proximity.Acc(); int vadMs = 500; Chan owner; Xai.Stt stt; long startWall, endUp; double snrMe, snrThem; int n, pauses; boolean duringTts; final java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream(); }

  AudioManager am; Xai xai; Xai.Tts tts; PowerManager.WakeLock wl; Db db; Db.Session sess;
  volatile boolean heardSpeech; Chan me, them; boolean dual; int sens; String mode, prebrief, model;
  // 0.1.11: keep one STT websocket pre-opened so the next phrase streams with no connection setup
  volatile Xai.Stt spareStt; volatile String sttLang = "en";
  synchronized void refillStt() { if (xai == null || !running) return; Xai.Stt s = spareStt; if (s != null && s.fresh()) return; if (s != null) s.cancel(); try { spareStt = xai.stt(sttLang); } catch (Throwable t) { spareStt = null; } }
  synchronized Xai.Stt takeStt() { Xai.Stt s = spareStt; spareStt = null; if (s == null || !s.fresh()) { if (s != null) s.cancel(); s = xai.stt(sttLang); } try { sched.execute(this::refillStt); } catch (Throwable ignored) {} return s; }
  public static volatile long meMatchAt; public static volatile boolean ownerKnown; public static volatile String matchName = ""; public static volatile String curSpeaker = null; public static volatile long curAt; public static volatile Proximity.Feat lastFeat; // 0.1.13 live per-utterance speaker // 0.1.12 owner-voice badge
  /** 0.1.13 Lock: enrolled voices allowed while locked (Prefs "lock_allow", ';'-list; empty = all enrolled). */
  static Set<String> lockAllowed(Context c) { Set<String> all = new LinkedHashSet<>(); if (VoiceEnroll.has(c)) all.add(VoiceEnroll.owner(c)); all.addAll(VoiceEnroll.voices(c).keySet());
    String p = Prefs.s(c, "lock_allow", ""); if (p.isEmpty()) return all; Set<String> s = new LinkedHashSet<>(); for (String n : p.split(";")) if (all.contains(n)) s.add(n); return s; }
  static boolean lockActive(Context c) { return "1".equals(Prefs.s(c, "lock", "0")) && !lockAllowed(c).isEmpty(); }
  // 0.1.14 natural-speech enrollment + continuity
  volatile int natLeft; volatile double natF0; volatile long natUntil, lastMeWall; volatile double lastMeF0;
  public static volatile boolean readyNoisy; public static volatile int readiness; public static volatile String readyWhy = ""; // 0.1.15 meter
  // 0.1.16 diarization + rolling quality
  public static volatile Diar diar; public static volatile String curLabel; public static volatile Diar.Cl curCl;
  public static volatile int quality = -1; public static volatile String qTrend = "", qWhy = ""; static double qShort = -1, qLong = -1;
  static synchronized void qNote(boolean overlap, double conf, double sep, double stab) {
    double s = 100 * (0.30 * (overlap ? 0 : 1) + 0.25 * conf + 0.20 * sep + 0.25 * stab);
    qShort = qShort < 0 ? s : qShort + 0.35 * (s - qShort); qLong = qLong < 0 ? s : qLong + 0.08 * (s - qLong); quality = (int) Math.round(qShort);
    qTrend = qShort < qLong - 8 ? "dropping" : qShort > qLong + 8 ? "improving" : "steady";
    qWhy = overlap ? "talked over" : conf < 0.4 ? "unsure who's speaking" : sep < 0.3 ? "voices hard to tell apart" : stab < 0.5 ? "unclear transcript" : ""; }
  volatile Utt cur; final Object uttLock = new Object(); final Brain brain = new Brain(); final Guard guard = new Guard(); People people; public static volatile String settingShown = "auto"; volatile ScheduledFuture<?> pendingCoach;
  final ExecutorService proc = Executors.newSingleThreadExecutor();
  final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
  final LinkedList<JSONObject> ctx = new LinkedList<>();
  volatile long playingUntil; volatile String lastWhisper = ""; volatile long lastWhisperAt;
  AudioTrack track; AudioDeviceCallback devCb;

  @Override public IBinder onBind(Intent i) { return null; }
  @Override public void onCreate() { super.onCreate(); am = getSystemService(AudioManager.class); db = Db.get(this); initLog(this); }

  @Override public int onStartCommand(Intent i, int flags, int id) {
    String a = i == null ? ACTION_START : i.getAction();
    if (ACTION_STOP.equals(a)) { stopAll(); return START_NOT_STICKY; }
    if (running) return START_STICKY;
    goForeground("Listening…"); running = true; sessionStart = System.currentTimeMillis();
    new Thread(this::startAll, "jw-start").start();
    return START_STICKY;
  }
  void goForeground(String text) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("jw", "Javis Whisper", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WhisperService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
    Notification n = new Notification.Builder(this, "jw").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Javis Whisper").setContentText(text)
      .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); else startForeground(7, n);
  }

  void startAll() {
    try {
      String key = Prefs.s(this, "xai_key", "");
      if (key.isEmpty()) { log("Enter your xAI API key in Settings first."); status = "No xAI key"; stopAll(); return; }
      xai = new Xai(key); model = Prefs.s(this, "model", "grok-4.20-non-reasoning");
      tts = xai.tts(Prefs.s(this, "voice_id", "ara"));
      wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jw:listen"); wl.acquire(6 * 3600_000L);
      mode = Prefs.s(this, "mode", "coach"); prebrief = Prefs.s(this, "prebrief", "");
      dual = "1".equals(Prefs.s(this, "dual", "1")); sens = Integer.parseInt(Prefs.s(this, "sens", "1"));
      // 0.1.9 housekeeping (once): reset the learned note polluted by test chatter; volume 1.0 is too loud in an earbud
      if (!"1".equals(Prefs.s(this, "reset019", ""))) { Prefs.put(this, "learned", ""); if (Float.parseFloat(Prefs.s(this, "vol", "0.35")) > 0.8f) Prefs.put(this, "vol", "0.6"); Prefs.put(this, "reset019", "1"); log("0.1.9: learned note reset; whisper volume capped at 60% (change in Settings)"); }
      heardSpeech = false;
      // 0.1.10 one-time cleanup of the 15 Oct voice-test leftovers (approved by Calton): invented "Justina Evans", echo-cycle learned note
      if (!"1".equals(Prefs.s(this, "clean0110", ""))) { try {
          for (Db.Person p : db.d().people()) if (p.name != null && p.name.toLowerCase(Locale.US).contains("justina")) { db.d().deleteConvos(p.id); db.d().delete(p); log("🧹 Removed test person: " + p.name); }
          for (Db.Session s : db.d().sessions()) if (s.summary != null && (s.summary.contains("Justina") || s.summary.contains("Miss Carlton"))) { s.summary = ""; db.d().update(s); log("🧹 Cleared voice-test session summary"); }
          String ln = Prefs.s(this, "learned", ""); if (ln.contains("echo") || ln.contains("Carlton") || ln.contains("Justina")) { Prefs.put(this, "learned", ""); log("🧹 Cleared test learned note"); }
        } catch (Throwable t) { log("cleanup: " + t.getMessage()); } Prefs.put(this, "clean0110", "1"); }
      if (VoiceEnroll.needGuided(this)) { Prefs.put(this, "cal_pending", "1"); log("🎙 Voice enrollment: say “" + VoiceEnroll.PHRASE + "” now (earbud in). Kept encrypted on this phone only."); }
      if (Prefs.s(this, "prox_prof", "").isEmpty()) log("🎙 Voice not learned yet – javis won't coach until it has heard you. Tap 'Teach javis my voice' and say one sentence, or just talk – it learns as you go.");
      try { List<String> kt = new ArrayList<>(Arrays.asList(Guard.KEYTERMS)); for (Db.Person p : db.d().people()) if (p.name != null && kt.size() < 90) kt.add(p.name); Xai.keyterms = kt.toArray(new String[0]); } catch (Throwable ignored) {}
      // 0.1.18 resume: the most recent session continues if it was active within resume_min (default 30 min); "Start fresh" skips it once
      resumed = false; String vName = ""; try { vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
      boolean fresh = "1".equals(Prefs.s(this, "start_fresh_once", "0")); Prefs.put(this, "start_fresh_once", "0"); long resumeMs = Prefs.i(this, "resume_min", 30, 0, 720) * 60_000L;
      List<Db.Session> recent = new ArrayList<>(); try { recent = db.d().sessions(); } catch (Throwable ignored) {}
      if (!fresh && resumeMs > 0 && !recent.isEmpty()) { Db.Session ls = recent.get(0); long act = Math.max(ls.started, Math.max(ls.ended, db.d().lastTs(ls.id)));
        if (System.currentTimeMillis() - act < resumeMs) { sess = ls; resumed = true; resumeAct = act; sess.ended = 0; sess.version = vName; sess.mode = mode; if (!prebrief.isEmpty()) sess.prebrief = prebrief; db.d().update(sess); } }
      if (fresh) log("Starting fresh – previous conversation not resumed");
      if (!resumed) { sess = new Db.Session(); sess.version = vName; sess.started = System.currentTimeMillis(); sess.mode = mode; sess.prebrief = prebrief; sess.id = db.d().add(sess); }
      final List<Db.Session> backfill = new ArrayList<>(); for (int i = 0; i < Math.min(3, recent.size()); i++) { Db.Session ps = recent.get(i); if (ps.id != sess.id && (ps.summary == null || ps.summary.isEmpty()) && ps.ended == 0) backfill.add(ps); }
      brain.pauseMin = Prefs.i(this, "pause_min", 450, 200, 3000); brain.pauseMax = Prefs.i(this, "pause_max", 1000, 200, 5000); END_SILENCE_FRAMES = Prefs.i(this, "end_sil", 22, 10, 60);
      sttLang = "translate".equals(mode) ? "" : "en"; refillStt();
      proc.execute(() -> { long t0 = SystemClock.uptimeMillis(); try { xai.chat(model, new JSONArray().put(Learn.msg("user", "ok")), 1, 0, null); log("(warm-up: Grok link ready in " + (SystemClock.uptimeMillis() - t0) + " ms)"); } catch (Throwable t) { log("(warm-up failed: " + t.getMessage() + ")"); } });
      // 0.1.12: restore Calton's saved voice identity – no need to re-identify each launch
      ownerKnown = VoiceEnroll.has(this); meMatchAt = 0; curSpeaker = null; curAt = 0; diar = new Diar(this); if (VoiceEnroll.has(this)) diar.owner = VoiceEnroll.owner(this); curLabel = null; curCl = null; quality = -1; qShort = qLong = -1; qTrend = ""; qWhy = "";
      if ("1".equals(Prefs.s(this, "lock", "0"))) log(lockActive(this) ? "🔒 Lock on – only tracking: " + String.join(", ", lockAllowed(this)) : "🔒 Lock is on but no enrolled voice is selected – listening to everyone for now"); if (ownerKnown) { brain.meSeen = true; log("✔ Voice remembered – no mic test needed. Re-enroll in Settings anytime."); }
      final String pb = prebrief; // the real brief only (inferred context below never creates people)
      // 0.1.12 optional briefing: no pre-brief → carry context from a recent session (last 6 h) if there is one
      if (resumed && prebrief.isEmpty() && sess.prebrief != null) prebrief = sess.prebrief;
      brain.background = ""; // 0.1.19: an earlier session's summary is background only – no head start, not a pre-brief
      if (!resumed && prebrief.isEmpty()) try { for (Db.Session ps : db.d().sessions()) { if (ps.id == sess.id || ps.summary == null || ps.summary.isEmpty()) continue; if (System.currentTimeMillis() - ps.started < 6 * 3600_000L) { brain.background = People.clip(ps.summary, 300); log("Earlier session kept as background only (may be unrelated)"); } break; } } catch (Throwable ignored) {}
      if (prebrief.isEmpty() && !resumed) log("No briefing – javis will work out the topic as it listens");
      brain.resetCtx(); readiness = 0; readyWhy = "";
      brain.hasBrief = !prebrief.isEmpty(); if (brain.hasBrief) brain.topic = People.clip(prebrief, 60); people = new People(this); brain.override = Prefs.s(this, "setting", "auto");
      if (resumed) try { // 0.1.18: replay recent lines + restore topic/domain + current person
        List<Db.Turn> tl = db.d().tail(sess.id, Math.max(sess.started, resumeAct - Math.max(resumeMs, 20 * 60_000L)), 40); int n = brain.restore(tl);
        for (Db.Turn t : tl) if ("heard".equals(t.kind) || "command".equals(t.kind)) addCtx("user", "[" + t.speaker + "] " + t.text); else if ("whisper".equals(t.kind)) addCtx("assistant", t.text);
        JSONObject sj = sess.ctx == null || sess.ctx.isEmpty() ? null : new JSONObject(sess.ctx); brain.applySnapshot(sj);
        long pid = sj == null ? 0 : sj.optLong("personId", 0); if (pid > 0) { Db.Person pp = db.d().person(pid); if (pp != null) { people.cur = pp; people.convoStart = sj.optLong("convoStart", System.currentTimeMillis()); people.card = people.buildCard(pp); brain.personCard = people.card; } }
        log("↩ Resumed your conversation from " + new java.text.SimpleDateFormat("h:mm a", Locale.US).format(new Date(resumeAct)) + " · " + n + " recent lines" + (brain.topic.isEmpty() ? "" : " · topic: " + brain.topic) + (people.cur == null ? "" : " · with " + people.cur.name) + " (Start fresh in Settings to begin a new one)");
        readiness = brain.readiness();
      } catch (Throwable t) { log("(resume: " + t.getMessage() + ")"); }
      brain.onState = () -> ctxDirty = true;
      if (!backfill.isEmpty()) proc.execute(() -> { for (Db.Session ps : backfill) try { long lt = db.d().lastTs(ps.id); if (lt == 0) continue; ps.ended = lt; db.d().update(ps); Learn.endSession(this, xai, model, ps); log("(wrote the missing summary of an interrupted session)"); } catch (Throwable t) { log("(summary backfill: " + t.getMessage() + ")"); } });
      proc.execute(() -> { if (!pb.isEmpty()) people.fromPrebrief(xai, model, pb); brain.goal = people.goal; brain.personCard = people.card; });
      startBt(); Proximity.noEarbud = !scoActive(); // 0.1.19: label from the real route
      log("Session started · " + mode + (prebrief.isEmpty() ? "" : " · pre-brief set") + " · " + (Proximity.noEarbud ? "phone only (no earbuds) – speakers told apart mainly by pitch" : dual ? "earbud + phone mic" : "earbud only") + " · " + model + " · voice " + Prefs.s(this, "voice_id", "ara"));
      int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(Math.max(min * 2, SR)).setTransferMode(AudioTrack.MODE_STREAM).build();
      // 0.1.5 warmer pacing: Ara a touch slower (time-stretch, same pitch); setting "pace" 0.85-1.1
      try { track.setPlaybackParams(new PlaybackParams().setSpeed(Float.parseFloat(Prefs.s(this, "pace", "0.94"))).setPitch(1f)); } catch (Throwable t) { log("(pacing not supported on this phone – normal speed)"); }
      track.play();
      me = new Chan(dual ? "Me" : "Speaker", true); them = dual ? new Chan("Them", false) : null;
      for (Chan c : chans()) { c.restart = "initial open"; c.th = new Thread(() -> readLoop(c), "jw-" + c.label); c.th.start(); }
      devCb = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { for (AudioDeviceInfo x : d) if (isBt(x.getType()) && x.isSource()) { log("Earbuds connected → reconnecting SCO"); sched.schedule(() -> { restoreSco(); if (me != null) me.restart = "earbuds reconnected"; }, 700, TimeUnit.MILLISECONDS); } }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { for (AudioDeviceInfo x : d) if (isBt(x.getType()) && x.isSource()) log("Earbuds disconnected – waiting for them"); }
      };
      am.registerAudioDeviceCallback(devCb, new Handler(Looper.getMainLooper()));
      sched.scheduleWithFixedDelay(this::watchdog, 2, 2, TimeUnit.SECONDS);
      sched.scheduleWithFixedDelay(() -> { if (tts != null) tts.ensure(); refillStt(); }, 5, 20, TimeUnit.SECONDS); // keep the TTS socket warm
      sched.scheduleWithFixedDelay(() -> { try { readiness = brain.readiness(); readyNoisy = brain.noisy(); saveCtx(false); } catch (Throwable ignored) {} }, 5, 5, TimeUnit.SECONDS); // 0.1.15 meter staleness decay
      status = "Listening";
    } catch (Throwable t) { log("Start failed: " + t); status = "Error: " + t.getMessage(); }
  }
  Chan[] chans() { return them == null ? new Chan[]{me} : new Chan[]{me, them}; }

  void stopAll() {
    running = false; status = "Stopping…";
    try { Xai.Stt s = spareStt; spareStt = null; if (s != null) s.cancel(); } catch (Throwable ignored) {}
    new Thread(() -> {
      try { if (devCb != null) am.unregisterAudioDeviceCallback(devCb); } catch (Throwable ignored) {}
      sched.shutdownNow();
      if (me != null) for (Chan c : chans()) { try { c.rec.stop(); } catch (Throwable ignored) {} try { if (c.th != null) c.th.join(1500); } catch (Throwable ignored) {} try { c.rec.release(); } catch (Throwable ignored) {} }
      try { proc.shutdown(); proc.awaitTermination(8, TimeUnit.SECONDS); } catch (Throwable ignored) {}
      try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
      if (tts != null) tts.close();
      stopBt();
      if (sess != null && people != null) try { people.endConvo(xai, model, sess, brain.settingNow()); } catch (Throwable t) { log("Recap failed: " + t.getMessage()); }
      try { ctxDirty = true; saveCtx(true); } catch (Throwable ignored) {}
      if (sess != null) { sess.ended = System.currentTimeMillis(); db.d().update(sess); try { Learn.endSession(this, xai, model, sess); } catch (Throwable t) { log("Summary failed: " + t.getMessage()); } }
      try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
      levelMe = levelThem = 0; status = "Stopped"; route = "-"; log("Session stopped");
      stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }).start();
  }

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
      if (people != null && people.cur != null && brain.lastThemEnd > 0 && System.currentTimeMillis() - brain.lastThemEnd > 180_000) {
        brain.lastThemEnd = 0; log("(conversation seems over – writing recap)"); proc.execute(() -> { people.endConvo(xai, model, sess, brain.settingNow()); brain.personCard = ""; brain.partner = null; brain.engagedAt = 0; }); }
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
    short[] f = new short[FRAME]; ArrayDeque<byte[]> pre = new ArrayDeque<>();
    int voicedRun = 0, silence = 0, voiced = 0, frames = 0; long silenceStart = 0;
    while (running) {
      if (c.restart != null) {
        log(c.label + " mic (re)open: " + c.restart); pre.clear();
        try { if (c.rec != null) { c.rec.stop(); c.rec.release(); } } catch (Throwable ignored) {}
        c.rec = makeRec(c); c.lastRead = SystemClock.uptimeMillis();
        if (c.rec == null) { SystemClock.sleep(1500); continue; }
        c.restart = null; SystemClock.sleep(100); updateRoute(); continue;
      }
      AudioRecord r = c.rec; if (r == null) { c.restart = "null"; continue; }
      int got; try { got = r.read(f, 0, FRAME); } catch (Throwable t) { got = -1; }
      c.lastRead = SystemClock.uptimeMillis();
      if (got <= 0) { SystemClock.sleep(20); continue; }
      // 0.1.10: "mic is silent" only for a truly dead mic – 5 s before any speech this session, 25 s after speech was heard. Once per quiet stretch.
      c.frames++;
      { double ss = 0; for (int i = 0; i < got; i++) ss += (double) f[i] * f[i]; float rr = (float) Math.sqrt(ss / got);
        if (rr >= 30) { c.quietFrames = 0; c.silentWarned = false; } else c.quietFrames++;
        int limit = heardSpeech ? 1250 : 250; // 20 ms frames
        if (!c.silentWarned && c.quietFrames >= limit) { c.silentWarned = true; log(c.label + " mic is silent (no audio for " + (limit / 50) + " s) – check permissions/earbuds"); } }
      double s = 0; for (int i = 0; i < got; i++) s += (double) f[i] * f[i]; float rms = (float) Math.sqrt(s / got); if (rms > c.peak) c.peak = rms;
      float thr = Math.max(SENS_MIN[sens], c.noise * SENS_MARGIN[sens]); c.snr = rms / Math.max(c.noise, 1);
      if (c == me) { levelMe = rms; thrMe = thr; } else { levelThem = rms; thrThem = thr; }
      boolean v = rms > thr; byte[] pcm = bytes(f, got);
      Utt u = cur;
      if (u != null) { // an utterance is open: track SNR of both mics for the speaker label
        if (c == me) { u.snrMe += c.snr; u.n++; u.acc.earbud(f, got, rms); } else { u.snrThem += c.snr; u.acc.phone(rms); }
        if (c == u.owner) u.acc.spectral(f, got, rms, c.noise);
        if (playingUntil > SystemClock.uptimeMillis()) u.duringTts = true;
      }
      if (u == null || u.owner != c) {
        c.noise = c.noise * 0.995f + Math.min(rms, c.noise * 3) * 0.005f; if (c.noise < 40) c.noise = 40;
        pre.addLast(pcm); if (pre.size() > 15) pre.removeFirst();
        voicedRun = v ? voicedRun + 1 : 0;
        if (voicedRun >= 3 && u == null && xai != null) {
          synchronized (uttLock) { if (cur == null) {
            Utt n = new Utt(); n.owner = c; n.startWall = System.currentTimeMillis() - 300; n.duringTts = playingUntil > SystemClock.uptimeMillis();
            ScheduledFuture<?> pc = pendingCoach; if (pc != null) pc.cancel(false); // someone started talking: not a natural pause
            n.stt = takeStt(); for (byte[] p : pre) { n.stt.push(p); n.pcm.write(p, 0, p.length); } pre.clear(); cur = n; silence = 0; voiced = 3; frames = 0;
          } }
        }
      } else { // this mic owns the open utterance: stream its audio to xAI STT live
        u.stt.push(pcm); frames++; if (u.pcm.size() < 16000 * 2 * 4) u.pcm.write(pcm, 0, pcm.length);
        if (v) { if (silence >= 18) u.pauses++; voiced++; silence = 0; } else if (silence++ == 0) silenceStart = SystemClock.uptimeMillis();
        // end on 500 ms pause, or after 300 ms if xAI STT already finalised the phrase (saves ~200-400 ms)
        if (silence >= END_SILENCE_FRAMES || (silence >= 15 && u.stt.finalSince(silenceStart)) || frames > 750) {
          u.vadMs = silence * 20;
          synchronized (uttLock) { cur = null; }
          u.endUp = SystemClock.uptimeMillis(); lastUttEndUp = u.endUp; u.stt.end(); voicedRun = 0;
          if (voiced >= 12) proc.execute(() -> process(u)); else u.stt.cancel();
        }
      }
    }
  }
  static byte[] bytes(short[] p, int n) { byte[] b = new byte[n * 2]; for (int i = 0; i < n; i++) { b[2 * i] = (byte) p[i]; b[2 * i + 1] = (byte) (p[i] >> 8); } return b; }

  void process(Utt u) {
    try {
      mode = Prefs.s(this, "mode", mode); sens = Integer.parseInt(Prefs.s(this, "sens", "1"));
      status = "Transcribing…";
      String text = u.stt.result(2500); long tText = SystemClock.uptimeMillis();
      status = "Listening";
      if (text == null || text.isEmpty()) { log(u.stt.error != null ? "Speech-to-text error: " + u.stt.error : "(heard sound, no words)"); return; }
      heardSpeech = true;
      String raw = text; text = Guard.normalize(text); if (!text.equals(raw)) log("(fixed mishear: \"" + raw + "\" → \"" + text + "\")");
      try { String rp = brain.repair(text, Arrays.asList(Xai.keyterms)); if (!rp.equals(text)) { log("(context repair: \"" + text + "\" → \"" + rp + "\")"); text = rp; } } catch (Throwable ignored) {} // 0.1.18 silent: log only, never spoken
      if ((u.duringTts || System.currentTimeMillis() < lastWhisperAt + 4000) && echoOf(text, lastWhisper)) { log("(ignored own whisper echo)"); return; }
      // ---- voice proximity: soft near-owner score with an UNSURE middle band ----
      Proximity.Feat pf = u.acc.feat();
      byte[] clip = u.pcm.toByteArray();
      lastFeat = pf;
      String asName = VoiceEnroll.clean(Prefs.s(this, "enroll_as", "")); // 0.1.13 re-enroll a named person from Settings: next utterance
      if (!asName.isEmpty() && !VoiceEnroll.isOwner(this, asName)) { Prefs.put(this, "enroll_as", ""); VoiceEnroll.saveNamed(this, asName, pf); curSpeaker = asName; curAt = System.currentTimeMillis(); log("✔ Re-enrolled " + asName + "'s voice"); saveTurn("heard", "Them", "en", text, u.startWall); return; }
      String said = VoiceEnroll.spokenName(text);
      if (said != null && !VoiceEnroll.isOwner(this, said)) { Prefs.put(this, "cal_pending", "0"); VoiceEnroll.saveNamed(this, said, pf); matchName = said; meMatchAt = System.currentTimeMillis(); curSpeaker = said; curAt = meMatchAt; try { Diar.Cl k = diar.assign(pf, null); if (k != null) { diar.name(k, said); curCl = k; curLabel = said; } } catch (Throwable ignored) {}
        log("✔ Learned " + said + "'s voice (kept encrypted on this phone only)"); saveTurn("heard", "Them", "en", text, u.startWall); return; }
      // 0.1.14: the phrase only says WHO it is – the voiceprint is built from the next 3 natural sentences, the hissy phrase clip is never saved
      if (said != null && VoiceEnroll.has(this) && !"1".equals(Prefs.s(this, "cal_pending", "0"))) { // 0.1.19: already enrolled → no new learning round (re-enroll stays in Settings)
        matchName = VoiceEnroll.owner(this); log("✔ Already know you – no new voice learning (re-enroll in Settings if needed)"); speakText("Already know you.");
        saveTurn("heard", "Me", "en", text, u.startWall); brain.meSeen = true; ownerKnown = true; meMatchAt = System.currentTimeMillis(); curSpeaker = matchName; curAt = meMatchAt; return; }
      if (said != null || "1".equals(Prefs.s(this, "cal_pending", "0"))) { Prefs.put(this, "cal_pending", "0"); matchName = VoiceEnroll.owner(this);
        try { Diar.Cl k = diar.assign(pf, VoiceEnroll.owner(this)); curCl = k; curLabel = VoiceEnroll.owner(this); } catch (Throwable ignored) {}
        natLeft = 3; natF0 = pf.pitch; natUntil = System.currentTimeMillis() + 90_000; lastMeWall = System.currentTimeMillis(); lastMeF0 = pf.pitch;
        if (!VoiceEnroll.has(this)) Proximity.learn(this, pf, 0.5);
        log("✔ Hi " + matchName + " – keep talking normally; javis learns your voice from your next 3 sentences");
        saveTurn("heard", "Me", "en", text, u.startWall); brain.meSeen = true; ownerKnown = true; meMatchAt = System.currentTimeMillis(); curSpeaker = matchName; curAt = meMatchAt; return; }
      boolean forceMe = false;
      if (natLeft > 0 && System.currentTimeMillis() < natUntil && pf.pitch > 0 && (natF0 <= 0 || Math.abs(pf.pitch / natF0 - 1) <= 0.25)) { forceMe = true;
        natLeft--; try { VoiceEnroll.save(this, clip, pf); log("✔ Voice sample from natural speech (" + (3 - natLeft) + "/3): " + pf); } catch (Exception e) { Proximity.learn(this, pf, 1); }
      }
      double near = Proximity.nearScore(this, pf, clip); clip = null;
      Proximity.noEarbud = !scoActive(); final double of0s = Proximity.ownerF0(this);
      if (pf.overlap && !Classify.overlapBelievable(Proximity.noEarbud, them != null)) pf.overlap = false; // 0.1.19: phone-only "talked over" is not believable
      String band0 = Proximity.band(this, near); String spk = forceMe ? "Me" : Classify.refine(band0, pf.pitch, of0s, Proximity.noEarbud); // 0.1.19 pitch check (leads with no earbuds)
      if (!forceMe && !spk.equals(band0)) log(String.format(Locale.US, "(voice by pitch: %s → %s · f0 %.0f vs yours %.0f)", band0, spk, pf.pitch, of0s));
      // 0.1.14 continuity: an unsure turn within 5 s of a confident Me turn with pitch within ±15% is still Calton
      if ("Unsure".equals(spk) && lastMeF0 > 0 && pf.pitch > 0 && System.currentTimeMillis() - lastMeWall < 5000 && Math.abs(pf.pitch / lastMeF0 - 1) <= 0.15 && Classify.pitchWithin(pf.pitch, of0s, Classify.LEARN_TOL)) { spk = "Me"; Proximity.learn(this, pf, 0.3); log(String.format(Locale.US, "(same voice as Calton a moment ago – f0 %.0f, near %.2f)", pf.pitch, near)); }
      { double of0 = Proximity.ownerF0(this); if ("Me".equals(spk)) Proximity.calMe(this, near); else if (of0 > 0 && pf.pitch > 0 && Math.abs(Math.log(pf.pitch / of0)) > 0.25) Proximity.calOther(this, near); }
      long nowW = System.currentTimeMillis();
      // 0.1.9: Calton repeating a whisper = it landed, and it's HIS voice → tag Me, learn his voice, don't push that point again
      String used = "Me".equals(spk) ? null : guard.echoOf(text, nowW);
      if (used != null && !Classify.pitchWithin(pf.pitch, of0s, Classify.CLASS_TOL)) used = null;
      if (used != null) { log("✔ Calton used the whisper (\"" + used + "\") – learning his voice"); spk = "Me"; Proximity.learn(this, pf, 0.6); }
      // 0.1.9: an unsure voice that answers the other person on topic, right after them, is most likely Calton
      else if ("Unsure".equals(spk) && brain.lastThemEnd > 0 && u.startWall - brain.lastThemEnd < 6000 && Guard.hits(text, brain.lastThemText) >= 1 && Classify.pitchWithin(pf.pitch, of0s, Classify.CLASS_TOL)) { log("(probably Calton – replied on topic)"); spk = "Me"; Proximity.learn(this, pf, 0.3); }
      String vname = null; if (!"Me".equals(spk)) { vname = VoiceEnroll.matchNamed(this, pf); if (vname != null) { matchName = vname; meMatchAt = System.currentTimeMillis(); } }
      curSpeaker = VoiceEnroll.identify(this, spk, pf); curAt = System.currentTimeMillis(); // every utterance updates the badge
      // 0.1.16 diarization: every utterance gets a Speaker N / name tag from its voice fingerprint
      String dlabel = null; try { diar.allowOwner = Classify.allowOwner(spk, pf.pitch, of0s); Diar.Cl k = diar.assign(pf, curSpeaker); curCl = k; if (k != null) { dlabel = k.label(); if (curSpeaker == null && k.name != null) curSpeaker = k.name; if (k.name != null && k.name.equals(diar.owner) && !"Me".equals(spk) && Classify.allowOwner(spk, pf.pitch, of0s)) { spk = "Me"; log("(voice anchored to " + k.name + ")"); } } } catch (Throwable t) { curCl = null; }
      curLabel = dlabel != null ? dlabel : curSpeaker;
      try { double cut = Proximity.meCut(this); double conf = curSpeaker != null ? 1 : "Unsure".equals(spk) ? Math.min(1, Math.abs(near - cut) / 0.15) * 0.6 : 0.6;
        String uc = Guard.unclear(text); double stab = text.trim().split("\\s+").length >= 3 && uc == null ? 1 : uc == null ? 0.6 : 0.2;
        qNote(pf.overlap, conf, diar == null ? 0.5 : diar.lastMargin, stab); } catch (Throwable ignored) {}
      // 0.1.13 Lock: with ≥2 enrolled voices (owner + 1 other), unrecognised speech is discarded – not saved, not shown, not sent to Grok
      if (lockActive(this) && (curSpeaker == null || !lockAllowed(this).contains(curSpeaker))) { log("(🔒 Lock: ignored " + (curSpeaker == null ? "an unrecognised voice" : curSpeaker + " – not in the lock list") + ")"); curSpeaker = null; return; }
      if ("Me".equals(spk)) { brain.meSeen = true; ownerKnown = true; matchName = VoiceEnroll.owner(this); meMatchAt = System.currentTimeMillis(); lastMeWall = meMatchAt; if (pf.pitch > 0 && Classify.pitchWithin(pf.pitch, of0s, Classify.CLASS_TOL)) lastMeF0 = pf.pitch; guard.calton(); } else if ("Them".equals(spk)) guard.far(nowW, text);
      brain.monologue = guard.monologue(nowW);
      // 0.1.10: pure voice/mic-test phrases: log + save, never coach, never names
      if (Guard.isVoiceTest(text)) { log(spk + ": " + text + String.format(Locale.US, "   (voice test – no coaching · near %.2f · %s)", near, pf)); saveTurn("heard", spk, "en", text, u.startWall); return; }
      String why0 = Guard.unclear(text);
      boolean shortLine = why0 != null && Classify.keepShort(why0); // 0.1.19: short replies count (marked short) instead of being dropped
      if (why0 != null && !shortLine) { log("(waiting out: " + why0 + " – \"" + text + "\")"); saveTurn("heard", spk, "", text, u.startWall); return; }
      if ("Me".equals(spk) && near >= 0.6 && (!pf.overlap || (Proximity.ownerF0(this) > 0 && Math.abs(pf.pitch / Proximity.ownerF0(this) - 1) <= 0.15))) Proximity.learn(this, pf, 0.3); // 0.1.14: passive learning bar 0.85→0.6
      boolean english = isEnglishish(text);
      String cues = cues(text, u, spk);
      // 0.1.6: tone from the voice (relative to each speaker's own baseline), overlap, and pause timing
      String who = "Me".equals(spk) ? "Me" : "Them".equals(spk) ? (brain.partner != null && brain.partnerMatch(Brain.fv(pf)) >= 0.5 ? "Partner" : "Them") : "";
      List<String> extra = new ArrayList<>(); if (!cues.isEmpty()) extra.add(cues);
      if (!who.isEmpty()) { String pr = brain.prosody(who, text, pf); if (!pr.isEmpty()) extra.add(pr); }
      if (vname != null) extra.add("voice sounds like " + vname); else if (curLabel != null && !"Me".equals(spk)) extra.add("voice: " + curLabel);
      String tc = brain.timingCue(spk, u.startWall); if (!tc.isEmpty()) extra.add(tc);
      if (pf.overlap) { brain.overlapAt = System.currentTimeMillis(); extra.add("Me".equals(spk) ? "talked over – Calton may have been cut off" : "talked over"); }
      if (shortLine) extra.add(0, "short reply");
      cues = String.join(", ", extra);
      log((curLabel != null ? curLabel : spk) + ": " + text + (cues.isEmpty() ? "" : "  [" + cues + "]") + String.format(Locale.US, "   (near %.2f · %s)", near, pf));
      // ---- direct address: wake word AND near voice → fast path. Unsure/far = conversation, keep listening ----
      String cmd = Brain.wake(text);
      if (cmd != null) {
        if ("Me".equals(spk)) {
          Proximity.learn(this, pf, 1); // light passive voice profile from Calton's own commands
          saveTurn("command", spk, english ? "en" : "", text, u.startWall);
          JSONArray msgs = new JSONArray().put(Learn.msg("system", Brain.commandPrompt(sess, brain, people.memoryForCommand(cmd)))).put(Learn.msg("user", cmd.isEmpty() ? text : cmd));
          speakStream(u, tText, msgs, true); return;
        }
        log("(wake word but voice " + ("Unsure".equals(spk) ? "unsure" : "not near") + " – treated as conversation)");
      }
      saveTurn("heard", spk, english ? "en" : "", text, u.startWall);
      addCtx("user", "[" + spk + "] " + text); brain.heard(spk, text, u.startWall, System.currentTimeMillis() - u.vadMs - (SystemClock.uptimeMillis() - u.endUp), pf, cues);
      try { brain.noteTurn(pf.overlap); readiness = brain.readiness(); readyNoisy = brain.noisy(); String g = brain.preGate(System.currentTimeMillis(), false); readyWhy = g == null ? "" : g; log("(ready " + readiness + "% · " + (g == null ? "open – coach may decide" : "blocked: " + g) + ")"); } catch (Throwable ignored) {} // 0.1.15 meter · 0.1.19 logged per line
      // ---- 0.1.5 people memory: learn names, attach the person, learn their voice once linked ----
      String nm = People.nameIn(text, spk);
      if (nm != null && people.attach(nm, "", "heard \"" + nm + "\"")) brain.personCard = people.card;
      if ("Them".equals(spk) && brain.partner != null && brain.lastThemPartner) { double[] fv = Brain.fv(pf);
        if (people.cur != null) people.learnVoice(fv); else { Db.Person h = people.voiceHint(fv); if (h != null) log("(voice sounds like " + h.name + " – waiting for the name to confirm)"); } }
      if ("Unsure".equals(spk)) brain.unsure++;
      if ("translate".equals(mode)) { if (!english && !"Me".equals(spk)) whisper(u, tText, text); return; }
      // 0.1.9: Street goes through the same listen-first gate as Coach (street setting: quick, but one whisper per real question)
      // ---- coach: listen first; decide only at a natural pause after the other person finishes ----
      if (!"Me".equals(spk)) { ScheduledFuture<?> pc = pendingCoach; // 0.1.19: any non-Me line (Them / Unsure / short replies) if (pc != null) pc.cancel(false);
        pendingCoach = sched.schedule(() -> proc.execute(this::coachDecide), brain.pauseMs(), TimeUnit.MILLISECONDS); }
    } catch (Throwable e) { status = "Listening"; log("Pipeline error: " + e.getMessage()); }
  }

  void coachDecide() {
    try {
      long now = System.currentTimeMillis();
      brain.override = Prefs.s(this, "setting", "auto"); brain.priorSetting(prebrief, mode, false); settingShown = brain.settingNow() + ("auto".equals(brain.override) ? " (auto)" : "");
      String why = brain.preGate(now, cur != null);
      if (why != null) { log("(listening: " + why + ")"); return; }
      status = "Thinking…";
      String raw = xai.chat(model, brain.assessMessages(this, sess), 140, 0.5, null);
      status = "Listening";
      int a = raw.indexOf('{'), b = raw.lastIndexOf('}'); if (a < 0 || b < a) { log("(coach: unreadable reply)"); return; }
      JSONObject j = new JSONObject(raw.substring(a, b + 1));
      String say = brain.gate(j, now);
      if (say != null) { String rj = guard.reject(say, brain.lastThemText, now); if (rj != null) { log("(dropped: " + rj + " – " + say + ")"); brain.lastCoachAt = 0; say = null; } }
      String pn = j.optString("partner_name", ""); if (!pn.isEmpty() && j.optDouble("partner_name_conf", 0) >= 0.7 && people.cur == null && people.attach(pn, "", "named in conversation")) brain.personCard = people.card;
      log(String.format(Locale.US, "🧭 [%s%s] %s · conf %.2f%s · %s", brain.settingNow(), brain.lastMood.isEmpty() ? "" : " · they seem " + brain.lastMood, brain.topic, brain.confidence, brain.openQuestion.isEmpty() ? "" : " · open: " + brain.openQuestion, say == null ? "stay quiet" : j.optString("kind")));
      if (say == null) return;
      armed(say, SystemClock.uptimeMillis() + 6000); // 0.1.18: stay armed, whisper at the next natural beat
    } catch (Throwable e) { status = "Listening"; log("Coach error: " + e.getMessage()); }
  }
  /** 0.1.5 hesitation cues (from words + pauses inside the turn). Honest: only what we can measure. */
  static final java.util.regex.Pattern FILLER = java.util.regex.Pattern.compile("(?i)\\b(um+|uh+|er+|erm|hmm+|i guess|i mean|sort of|kind of|maybe|i don'?t know|not sure)\\b");
  static final java.util.regex.Pattern RESTART = java.util.regex.Pattern.compile("(?i)\\b(\\w+)[-,]?\\s+\\1\\b");
  String cues(String text, Utt u, String spk) {
    List<String> r = new ArrayList<>(); java.util.regex.Matcher m = FILLER.matcher(text); int f = 0; while (m.find()) f++;
    int words = text.trim().split("\\s+").length;
    if (f >= 2 || (f >= 1 && words < 8)) r.add("hesitant"); if (u.pauses >= 2) r.add("long pauses"); if (RESTART.matcher(text).find()) r.add("restarts");
    return String.join(", ", r);
  }
  void speakText(String say) {
    if (!"0".equals(Prefs.s(this, "voice", "1"))) { final float vol = Float.parseFloat(Prefs.s(this, "vol", "0.35"));
      tts.begin(new Xai.Audio() { public void on(byte[] pcm) { play(pcm, vol); } public void done() {} }); tts.text(clean(say)); tts.finish(); }
    lastWhisper = say; lastWhisperAt = System.currentTimeMillis();
    guard.said(say, System.currentTimeMillis());
    log("🗣 javis: " + say); saveTurn("whisper", "javis", "", say, System.currentTimeMillis()); addCtx("assistant", say);
  }

  /** Translate path. 0.1.9: full reply first, then the same post-filter as coaching (no questions, no parroting, no repeats). */
  void whisper(Utt u, long tText, String heard) throws Exception {
    JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", Learn.systemPrompt(this, sess, mode)));
    synchronized (ctx) { for (JSONObject o : ctx) msgs.put(o); }
    status = "Thinking…"; String all = xai.chat(model, msgs, 70, 0.5, null); status = "Listening";
    if (isSkip(all) || all.isEmpty()) { log("(javis stayed quiet)"); return; }
    String why = guard.reject(all.contains("||") ? all.substring(all.indexOf("||") + 2) : all, null, System.currentTimeMillis());
    if (why != null && !why.startsWith("just repeats")) { log("(dropped: " + why + " – " + all + ")"); return; }
    speakText(all); timing(u, tText, SystemClock.uptimeMillis(), 0);
  }
  /** Streams Grok → Ara TTS token by token (low latency). force = command path (never SKIP). */
  void speakStream(Utt u, long tText, JSONArray msgs, boolean force) throws Exception {
    status = "Thinking…";
    final long[] tFirstTok = {0}, tFirstAudio = {0}; final StringBuilder held = new StringBuilder(); final boolean[] live = {false};
    boolean voice = !"0".equals(Prefs.s(this, "voice", "1"));
    final float vol = Float.parseFloat(Prefs.s(this, "vol", "0.35"));
    if (voice) tts.begin(new Xai.Audio() {
      public void on(byte[] pcm) { if (tFirstAudio[0] == 0) tFirstAudio[0] = SystemClock.uptimeMillis(); play(pcm, vol); }
      public void done() {}
    });
    String all = xai.chat(model, msgs, force ? 90 : 60, 0.6, t -> {
      if (tFirstTok[0] == 0) tFirstTok[0] = SystemClock.uptimeMillis();
      if (!voice) return;
      if (!live[0]) { held.append(t); String h = held.toString().trim();
        if (h.length() >= 5 && !"SKIP".startsWith(h.toUpperCase(Locale.US).substring(0, Math.min(4, h.length())))) { live[0] = true; tts.text(clean(held.toString())); } }
      else tts.text(clean(t));
    });
    if (voice && live[0]) tts.finish();
    else if (voice && !isSkip(all) && !all.isEmpty()) { live[0] = true; tts.text(clean(all)); tts.finish(); }
    status = "Listening";
    if (isSkip(all) || all.isEmpty()) { log("(javis stayed quiet)"); timing(u, tText, tFirstTok[0], 0); return; }
    lastWhisper = all; lastWhisperAt = System.currentTimeMillis(); guard.said(all, lastWhisperAt);
    log("🗣 javis: " + all); saveTurn("whisper", "javis", "", all, System.currentTimeMillis()); addCtx("assistant", all);
    if (voice) { long w = SystemClock.uptimeMillis() + 3000; while (tFirstAudio[0] == 0 && SystemClock.uptimeMillis() < w) SystemClock.sleep(20); }
    timing(u, tText, tFirstTok[0], tFirstAudio[0]);
  }
  void timing(Utt u, long tText, long tTok, long tAudio) {
    int v = u.vadMs;
    String s = String.format(Locale.US, "⏱ after they stopped: VAD %d · text %d · 1st word %d · voice %s ms", v,
      tText - u.endUp + v, tTok == 0 ? -1 : tTok - u.endUp + v, tAudio == 0 ? "-" : String.valueOf(tAudio - u.endUp + v));
    lastTiming = s; log(s);
  }
  static String clean(String t) { return t.replace("||", ". ").replace("|", ",").replaceAll("[*_#`\"]", ""); }
  static boolean isSkip(String t) { return t == null || t.trim().matches("(?i)^(SKIP|-)\\W*$"); }
  static boolean isEnglishish(String t) { // xAI STT doesn't return a language; cheap heuristic for translate mode
    String[] w = t.toLowerCase(Locale.US).replaceAll("[^a-z' ]", " ").trim().split("\\s+"); if (w.length == 0) return true;
    Set<String> en = new HashSet<>(Arrays.asList("the","a","and","is","are","you","i","to","it","what","of","in","that","this","for","my","me","we","do","have","yes","no","can","how","where","your","be","was","on","with","not","okay","ok","so","just","like","know"));
    int hit = 0; for (String x : w) if (en.contains(x)) hit++; return hit * 4 >= w.length || t.matches(".*[a-zA-Z].*") && w.length <= 2 && hit > 0;
  }
  /** 0.1.18 barge-in: the other person's voice (phone mic) opened an utterance while javis is whispering. */
  boolean bargeIn() { Utt c = cur; return c != null && them != null && c.owner == them; }
  /** waits until nobody is talking, then a short natural beat (350 ms of quiet). false = still talking after maxMs. */
  volatile long lastUttEndUp;
  boolean beat() { return cur == null && SystemClock.uptimeMillis() - lastUttEndUp >= 350; }
  boolean waitBeat(long maxMs) { long end = SystemClock.uptimeMillis() + maxMs; while (SystemClock.uptimeMillis() < end) { if (beat()) return true; SystemClock.sleep(25); } return false; }
  /** non-blocking: re-checks every 100 ms on the scheduler so transcription (proc thread) never waits on a held whisper. */
  void armed(String say, long deadline) {
    if (!running) return;
    if (beat()) { if (!say.equals(lastWhisper) || System.currentTimeMillis() - lastWhisperAt > 20_000) proc.execute(() -> speakText(say)); return; }
    if (SystemClock.uptimeMillis() > deadline) { log("(they kept talking – held back: " + say + ")"); brain.lastCoachAt = 0; return; }
    try { sched.schedule(() -> armed(say, deadline), 100, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {} }
  /** 0.1.18 adaptive volume: louder in a noisy room (earbud noise floor), never above 1.0; Settings "adapt_vol". */
  float adaptVol(float vol) { if ("0".equals(Prefs.s(this, "adapt_vol", "1")) || me == null) return vol; double nDb = 20 * Math.log10(Math.max(me.noise, 40)); double boost = Math.max(0, Math.min(1, (nDb - 40) / 25)); return (float) Math.min(1.0, vol * (1 + 1.2 * boost)); }
  volatile boolean bargeLogged;
  void play(byte[] pcm, float vol) {
    if (bargeIn()) { long w = SystemClock.uptimeMillis() + 4000; try { track.pause(); } catch (Throwable ignored) {} if (!bargeLogged) { bargeLogged = true; log("(they started talking – whisper paused)"); }
      while (bargeIn() && SystemClock.uptimeMillis() < w) SystemClock.sleep(40); waitBeat(1500); try { track.play(); } catch (Throwable ignored) {} bargeLogged = false; }
    vol = adaptVol(vol);
    Utt c = cur; if (c != null && c.owner == me && me != null) vol *= 0.6f; // duck under Calton's own voice
    else if (c != null && c.owner != me) vol *= 0.4f; // 0.1.18: duck under the other person
    try { track.setVolume(vol); track.write(pcm, 0, pcm.length);
      long now = SystemClock.uptimeMillis(); playingUntil = Math.max(playingUntil, now) + pcm.length / 32 + 300; } catch (Throwable ignored) {}
  }
  void addCtx(String role, String content) { try { synchronized (ctx) { ctx.add(new JSONObject().put("role", role).put("content", content)); while (ctx.size() > 30) ctx.removeFirst(); } } catch (Exception ignored) {} }
  // 0.1.18: throttled topic/domain snapshot into sessions.ctx (survives swipe-kill)
  volatile boolean ctxDirty; long ctxSavedAt; boolean resumed; long resumeAct;
  synchronized void saveCtx(boolean force) { if (sess == null || !ctxDirty || (!force && System.currentTimeMillis() - ctxSavedAt < 5000)) return;
    try { JSONObject j = brain.snapshot(); if (people != null && people.cur != null) j.put("personId", people.cur.id).put("convoStart", people.convoStart); sess.ctx = j.toString(); db.d().update(sess); ctxSavedAt = System.currentTimeMillis(); ctxDirty = false; } catch (Throwable t) { log("(ctx save: " + t.getMessage() + ")"); } }
  void saveTurn(String kind, String spk, String lang, String text, long ts) { Db.Turn t = new Db.Turn(); t.sessionId = sess.id; t.kind = kind; t.speaker = spk; t.lang = lang; t.text = text; t.ts = ts; db.d().add(t); }
  /** Echo handling: the earbud mic can pick up our own whisper. Reject transcripts that mostly repeat it. */
  static boolean echoOf(String heard, String whisper) {
    if (whisper == null || whisper.isEmpty()) return false;
    Set<String> w = new HashSet<>(Arrays.asList(whisper.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+")));
    String[] h = heard.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+");
    int hit = 0; for (String x : h) if (w.contains(x)) hit++;
    return h.length > 0 && hit >= Math.max(2, h.length * 0.6);
  }
}
