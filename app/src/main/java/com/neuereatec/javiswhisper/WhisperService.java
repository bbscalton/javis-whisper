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

/**
 * Wearable pipeline (v0.1.2, phone talks to xAI directly):
 * earbud mic (SCO) + phone mic -> on-phone VAD -> xAI streaming STT (audio streamed WHILE the person talks)
 * -> Grok streaming -> xAI streaming TTS (voice Ara) fed token by token -> AudioTrack into the earbud.
 * Capture never pauses (full duplex); our own voice is rejected by echo matching. Audio is never written anywhere.
 */
public class WhisperService extends Service {
  public static final String ACTION_START = "start", ACTION_STOP = "stop";
  static final int SR = 16000, FRAME = 320; // 20 ms
  public static volatile boolean running; public static volatile float levelMe, levelThem, thrMe, thrThem;
  public static volatile String status = "Stopped", route = "-", lastTiming = ""; public static volatile long sessionStart;
  static final LinkedList<String> LOG = new LinkedList<>();
  static final float[] SENS_MIN = {700, 300, 150}, SENS_MARGIN = {3.2f, 2.4f, 1.8f};
  static final String[] SENS_NAME = {"Low", "Normal", "High"};
  static final int END_SILENCE_FRAMES = 25; // 500 ms pause ends an utterance

  public static void log(String s) {
    String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
    synchronized (LOG) { LOG.addFirst(line); while (LOG.size() > 250) LOG.removeLast(); }
    android.util.Log.i("JavisWhisper", s);
  }
  public static List<String> logSnapshot() { synchronized (LOG) { return new ArrayList<>(LOG); } }

  class Chan { final String label; final boolean earbud; volatile AudioRecord rec; Thread th; volatile long lastRead; volatile String restart; float noise = 150; volatile float snr; long frames; float peak;
    Chan(String l, boolean e) { label = l; earbud = e; } }
  /** One utterance at a time across both mics: the mic that hears speech first streams it; speaker = mic with higher SNR. */
  class Utt { final Proximity.Acc acc = new Proximity.Acc(); int vadMs = 500; Chan owner; Xai.Stt stt; long startWall, endUp; double snrMe, snrThem; int n, pauses; boolean duringTts; }

  AudioManager am; Xai xai; Xai.Tts tts; PowerManager.WakeLock wl; Db db; Db.Session sess;
  Chan me, them; boolean dual; int sens; String mode, prebrief, model;
  volatile Utt cur; final Object uttLock = new Object(); final Brain brain = new Brain(); People people; public static volatile String settingShown = "auto"; volatile ScheduledFuture<?> pendingCoach;
  final ExecutorService proc = Executors.newSingleThreadExecutor();
  final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
  final LinkedList<JSONObject> ctx = new LinkedList<>();
  volatile long playingUntil; volatile String lastWhisper = ""; volatile long lastWhisperAt;
  AudioTrack track; AudioDeviceCallback devCb;

  @Override public IBinder onBind(Intent i) { return null; }
  @Override public void onCreate() { super.onCreate(); am = getSystemService(AudioManager.class); db = Db.get(this); }

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
      sess = new Db.Session(); sess.started = System.currentTimeMillis(); sess.mode = mode; sess.prebrief = prebrief; sess.id = db.d().add(sess);
      people = new People(this); brain.override = Prefs.s(this, "setting", "auto");
      final String pb = prebrief; proc.execute(() -> { people.fromPrebrief(xai, model, pb); brain.goal = people.goal; brain.personCard = people.card; });
      log("Session started · " + mode + (prebrief.isEmpty() ? "" : " · pre-brief set") + " · " + (dual ? "earbud + phone mic" : "earbud only") + " · " + model + " · voice " + Prefs.s(this, "voice_id", "ara"));
      startBt();
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
      sched.scheduleWithFixedDelay(() -> { if (tts != null) tts.ensure(); }, 5, 20, TimeUnit.SECONDS); // keep the TTS socket warm
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
      try { proc.shutdown(); proc.awaitTermination(8, TimeUnit.SECONDS); } catch (Throwable ignored) {}
      try { if (track != null) { track.stop(); track.release(); } } catch (Throwable ignored) {}
      if (tts != null) tts.close();
      stopBt();
      if (sess != null && people != null) try { people.endConvo(xai, model, sess, brain.settingNow()); } catch (Throwable t) { log("Recap failed: " + t.getMessage()); }
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
      if (++c.frames % 250 == 0 && c.peak < 30) log(c.label + " mic is silent (no audio for 5 s) – check permissions/earbuds"); if (c.frames % 250 == 0) c.peak = 0;
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
            n.stt = xai.stt("translate".equals(mode) ? "" : "en"); for (byte[] p : pre) n.stt.push(p); pre.clear(); cur = n; silence = 0; voiced = 3; frames = 0;
          } }
        }
      } else { // this mic owns the open utterance: stream its audio to xAI STT live
        u.stt.push(pcm); frames++;
        if (v) { if (silence >= 18) u.pauses++; voiced++; silence = 0; } else if (silence++ == 0) silenceStart = SystemClock.uptimeMillis();
        // end on 500 ms pause, or after 300 ms if xAI STT already finalised the phrase (saves ~200-400 ms)
        if (silence >= END_SILENCE_FRAMES || (silence >= 15 && u.stt.finalSince(silenceStart)) || frames > 750) {
          u.vadMs = silence * 20;
          synchronized (uttLock) { cur = null; }
          u.endUp = SystemClock.uptimeMillis(); u.stt.end(); voicedRun = 0;
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
      if ((u.duringTts || System.currentTimeMillis() < lastWhisperAt + 4000) && echoOf(text, lastWhisper)) { log("(ignored own whisper echo)"); return; }
      // ---- voice proximity: soft near-owner score with an UNSURE middle band ----
      Proximity.Feat pf = u.acc.feat();
      if ("1".equals(Prefs.s(this, "cal_pending", "0"))) { Prefs.put(this, "cal_pending", "0"); Proximity.learn(this, pf, 3); log("✔ Learned your voice: " + pf); return; }
      double near = Proximity.nearScore(this, pf);
      String spk = Proximity.band(near);
      if ("Me".equals(spk) && near >= 0.85) Proximity.learn(this, pf, 0.3); // passive profile from very confident own turns
      boolean english = isEnglishish(text);
      String cues = cues(text, u, spk);
      // 0.1.6: tone from the voice (relative to each speaker's own baseline), overlap, and pause timing
      String who = "Me".equals(spk) ? "Me" : "Them".equals(spk) ? (brain.partner != null && brain.partnerMatch(Brain.fv(pf)) >= 0.5 ? "Partner" : "Them") : "";
      List<String> extra = new ArrayList<>(); if (!cues.isEmpty()) extra.add(cues);
      if (!who.isEmpty()) { String pr = brain.prosody(who, text, pf); if (!pr.isEmpty()) extra.add(pr); }
      String tc = brain.timingCue(spk, u.startWall); if (!tc.isEmpty()) extra.add(tc);
      if (pf.overlap) { brain.overlapAt = System.currentTimeMillis(); extra.add("Me".equals(spk) ? "talked over – Calton may have been cut off" : "talked over"); }
      cues = String.join(", ", extra);
      log(spk + ": " + text + (cues.isEmpty() ? "" : "  [" + cues + "]") + String.format(Locale.US, "   (near %.2f · %s)", near, pf));
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
      // ---- 0.1.5 people memory: learn names, attach the person, learn their voice once linked ----
      String nm = People.nameIn(text, spk);
      if (nm != null && people.attach(nm, "", "heard \"" + nm + "\"")) brain.personCard = people.card;
      if ("Them".equals(spk) && brain.partner != null && brain.lastThemPartner) { double[] fv = Brain.fv(pf);
        if (people.cur != null) people.learnVoice(fv); else { Db.Person h = people.voiceHint(fv); if (h != null) log("(voice sounds like " + h.name + " – waiting for the name to confirm)"); } }
      if ("Unsure".equals(spk)) brain.unsure++;
      if ("translate".equals(mode)) { if (!english && !"Me".equals(spk)) whisper(u, tText); return; }
      if ("street".equals(mode)) { if ("Them".equals(spk)) whisper(u, tText); return; }
      // ---- coach: listen first; decide only at a natural pause after the other person finishes ----
      if ("Them".equals(spk)) { ScheduledFuture<?> pc = pendingCoach; if (pc != null) pc.cancel(false);
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
      String pn = j.optString("partner_name", ""); if (!pn.isEmpty() && j.optDouble("partner_name_conf", 0) >= 0.7 && people.cur == null && people.attach(pn, "", "named in conversation")) brain.personCard = people.card;
      log(String.format(Locale.US, "🧭 [%s%s] %s · conf %.2f%s · %s", brain.settingNow(), brain.lastMood.isEmpty() ? "" : " · they seem " + brain.lastMood, brain.topic, brain.confidence, brain.openQuestion.isEmpty() ? "" : " · open: " + brain.openQuestion, say == null ? "stay quiet" : j.optString("kind")));
      if (say == null) return;
      if (cur != null) { log("(someone started talking – held back)"); brain.lastCoachAt = 0; return; }
      speakText(say);
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
    log("🗣 javis: " + say); saveTurn("whisper", "javis", "", say, System.currentTimeMillis()); addCtx("assistant", say);
  }

  void whisper(Utt u, long tText) throws Exception {
    JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", Learn.systemPrompt(this, sess, mode)));
    synchronized (ctx) { for (JSONObject o : ctx) msgs.put(o); }
    speakStream(u, tText, msgs, false);
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
    lastWhisper = all; lastWhisperAt = System.currentTimeMillis();
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
  void play(byte[] pcm, float vol) {
    Utt c = cur; if (c != null && c.owner == me && me != null) vol *= 0.6f; // duck under Calton's own voice
    try { track.setVolume(vol); track.write(pcm, 0, pcm.length);
      long now = SystemClock.uptimeMillis(); playingUntil = Math.max(playingUntil, now) + pcm.length / 32 + 300; } catch (Throwable ignored) {}
  }
  void addCtx(String role, String content) { try { synchronized (ctx) { ctx.add(new JSONObject().put("role", role).put("content", content)); while (ctx.size() > 30) ctx.removeFirst(); } } catch (Exception ignored) {} }
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
