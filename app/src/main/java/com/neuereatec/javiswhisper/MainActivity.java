package com.neuereatec.javiswhisper;
import android.Manifest; import android.app.Activity; import android.content.Intent; import android.content.pm.PackageManager;
import android.os.*; import android.view.*; import android.widget.*; import org.json.JSONObject;
import java.util.*;
import static com.neuereatec.javiswhisper.Ui.*;

public class MainActivity extends Activity {
  final Handler h = new Handler(Looper.getMainLooper());
  TextView statusT, routeT, logT, pairT, lastT; ProgressBar meBar, themBar; Button startB; final Map<String, Button> modeBtns = new LinkedHashMap<>();
  LinearLayout settings; Button settingChip;
  void paintChip() { String o = Prefs.s(this, "setting", "auto"); settingChip.setText("🧭 Setting: " + ("auto".equals(o) ? "Auto" + (WhisperService.running ? " – " + WhisperService.settingShown.replace(" (auto)", "") : "") : o + " (your choice)") + "  · tap to change"); }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b); WhisperService.initLog(this);
    LinearLayout outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(BG);
    ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG); outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root, new ScrollView.LayoutParams(-1, -2));
    LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackgroundColor(0xFF081730); bar.setPadding(p, px(this, 8), p, px(this, 12)); outer.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    lastT = text(this, "", 13, 0xFFE3ECF7); lastT.setMaxLines(3); bar.addView(lastT);
    root.addView(text(this, "Javis Whisper", 28, 0xFFFFFFFF));
    root.addView(text(this, "Your earbud AI companion · by Neuereatec", 14, MUTED));

    LinearLayout st = card(this); root.addView(st, lp(this, -1, -2, 14));
    statusT = text(this, "Stopped", 20, 0xFFFFFFFF); st.addView(statusT);
    routeT = text(this, "-", 13, MUTED); st.addView(routeT);
    st.addView(text(this, "Earbud mic (Me)", 12, MUTED), lp(this, -1, -2, 8)); meBar = bar(); st.addView(meBar, lp(this, -1, px(this, 10), 2));
    st.addView(text(this, "Phone mic (Them)", 12, MUTED), lp(this, -1, -2, 6)); themBar = bar(); st.addView(themBar, lp(this, -1, px(this, 10), 2));

    LinearLayout modes = new LinearLayout(this); root.addView(modes, lp(this, -1, -2, 12));
    for (String[] m : new String[][]{{"coach", "Coach"}, {"translate", "Translate"}, {"street", "Street"}}) {
      Button bt = button(this, m[1], CARD); modeBtns.put(m[0], bt); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); l.setMargins(px(this, 3), 0, px(this, 3), 0); modes.addView(bt, l);
      bt.setOnClickListener(v -> { Prefs.put(this, "mode", m[0]); paintModes(); WhisperService.log("Mode → " + m[1]); });
    }
    // 0.1.5 setting chip: tap to cycle Auto → negotiation → … (override); shows what javis thinks when on Auto
    Button chip = button(this, "", CARD); chip.setTextSize(14); root.addView(chip, lp(this, -1, -2, 8)); settingChip = chip; paintChip();
    chip.setOnClickListener(v -> { String[] o = {"auto", "negotiation", "sale", "meeting", "call", "casual", "family", "street"}; String cur = Prefs.s(this, "setting", "auto"); int i = 0;
      for (int k = 0; k < o.length; k++) if (o[k].equals(cur)) i = k; Prefs.put(this, "setting", o[(i + 1) % o.length]); paintChip(); WhisperService.log("Setting → " + o[(i + 1) % o.length]); });
    EditText brief = edit(this, "Pre-brief: who are you talking to, what about? (e.g. 'Bank manager, loan for crane truck')", Prefs.s(this, "prebrief", ""));
    brief.setMinLines(2); root.addView(brief, lp(this, -1, -2, 10));
    brief.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "prebrief", brief.getText().toString()); });

    startB = button(this, "START listening", GREEN); bar.addView(startB, lp(this, -1, px(this, 60), 6));
    startB.setOnClickListener(v -> { Prefs.put(this, "prebrief", brief.getText().toString()); if (WhisperService.running) stopSvc(); else startSvc(); });

    LinearLayout row = new LinearLayout(this); root.addView(row, lp(this, -1, -2, 10));
    Button rev = button(this, "Review history", BLUE), set = button(this, "Settings", CARD);
    row.addView(rev, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1); l2.leftMargin = px(this, 8); row.addView(set, l2);
    rev.setOnClickListener(v -> startActivity(new Intent(this, ReviewActivity.class)));
    Button ppl = button(this, "People javis remembers", CARD); root.addView(ppl, lp(this, -1, -2, 8));
    ppl.setOnClickListener(v -> startActivity(new Intent(this, PeopleActivity.class)));
    Button pc = button(this, "⇪ Send to PC (text only)", CARD); root.addView(pc, lp(this, -1, -2, 8)); pc.setOnClickListener(v -> SendToPc.ask(this));
    settings = buildSettings(); settings.setVisibility(View.GONE); root.addView(settings, lp(this, -1, -2, 10));
    set.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

    LinearLayout lc = card(this); root.addView(lc, lp(this, -1, -2, 12));
    lc.addView(text(this, "Live transcript & whispers (text only – no audio is stored)", 12, MUTED));
    logT = text(this, "", 14, 0xFFE3ECF7); logT.setTextIsSelectable(true); lc.addView(logT);
    setContentView(outer);
    paintModes();
    if (Prefs.s(this, "xai_key", "").isEmpty()) { settings.setVisibility(View.VISIBLE); WhisperService.log("First time: open Settings and pair with PC01 (6-digit code) – that loads the xAI key."); }
    tick();
  }
  ProgressBar bar() { ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100); return pb; }
  void paintModes() { String m = Prefs.s(this, "mode", "coach"); for (Map.Entry<String, Button> e : modeBtns.entrySet()) e.getValue().setBackground(round(e.getKey().equals(m) ? BLUE : CARD, px(this, 12))); }

  LinearLayout buildSettings() {
    LinearLayout c = card(this);
    c.addView(text(this, "xAI API key (required)", 16, 0xFFFFFFFF));
    c.addView(text(this, "From console.x.ai → API Keys. Stored encrypted on this phone only; used for speech-to-text, Grok and the Ara voice.", 12, MUTED));
    String k0 = Prefs.s(this, "xai_key", "");
    EditText key = edit(this, k0.isEmpty() ? "xai-…" : "saved ••••" + k0.substring(Math.max(0, k0.length() - 4)) + " (type to replace)", "");
    key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); c.addView(key, lp(this, -1, -2, 6));
    Button test = button(this, "Save & test key", BLUE); c.addView(test, lp(this, -1, -2, 6));
    pairT = text(this, k0.isEmpty() ? "No key saved" : "Key saved", 13, MUTED); c.addView(pairT, lp(this, -1, -2, 4));
    test.setOnClickListener(v -> {
      String k = key.getText().toString().trim(); if (!k.isEmpty()) { Prefs.put(this, "xai_key", k); key.setText(""); key.setHint("saved ••••" + k.substring(Math.max(0, k.length() - 4))); }
      new Thread(() -> { try { long t0 = SystemClock.uptimeMillis();
        String r = new Xai(Prefs.s(this, "xai_key", "")).chat(Prefs.s(this, "model", "grok-4.20-non-reasoning"), new org.json.JSONArray().put(new JSONObject().put("role", "user").put("content", "Reply with just: ready")), 5, 0, null);
        ui("Key works ✔ Grok replied \"" + r + "\" in " + (SystemClock.uptimeMillis() - t0) + " ms");
      } catch (Exception e) { ui("Key test failed: " + reason(e)); alert("Key test failed", reason(e)); } }).start();
    });
    c.addView(text(this, "Model", 13, MUTED), lp(this, -1, -2, 10));
    EditText model = edit(this, "grok-4.20-non-reasoning", Prefs.s(this, "model", "grok-4.20-non-reasoning")); c.addView(model);
    model.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "model", model.getText().toString().trim().isEmpty() ? "grok-4.20-non-reasoning" : model.getText().toString().trim()); });
    c.addView(text(this, "Voice (xAI): ara, eve, rex, sal, leo", 13, MUTED), lp(this, -1, -2, 10));
    EditText voiceId = edit(this, "ara", Prefs.s(this, "voice_id", "ara")); c.addView(voiceId);
    voiceId.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "voice_id", voiceId.getText().toString().trim().isEmpty() ? "ara" : voiceId.getText().toString().trim().toLowerCase(Locale.US)); });

    c.addView(text(this, "Whisper volume", 14, 0xFFFFFFFF), lp(this, -1, -2, 14));
    SeekBar vol = new SeekBar(this); vol.setMax(100); vol.setProgress((int) (Float.parseFloat(Prefs.s(this, "vol", "0.35")) * 100)); c.addView(vol);
    vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s, int v, boolean u) { Prefs.put(MainActivity.this, "vol", String.valueOf(Math.max(5, v) / 100f)); } public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} });
    Button sensB = button(this, "", CARD); c.addView(sensB, lp(this, -1, -2, 10));
    Runnable ps = () -> sensB.setText("Mic sensitivity: " + WhisperService.SENS_NAME[Integer.parseInt(Prefs.s(this, "sens", "1"))]); ps.run();
    sensB.setOnClickListener(v -> { Prefs.put(this, "sens", String.valueOf((Integer.parseInt(Prefs.s(this, "sens", "1")) + 1) % 3)); ps.run(); });
    Switch dual = new Switch(this); dual.setText("Use phone mic for the other person (labels Me / Them)"); dual.setTextColor(0xFFFFFFFF); dual.setChecked("1".equals(Prefs.s(this, "dual", "1"))); c.addView(dual, lp(this, -1, -2, 10));
    dual.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "dual", on ? "1" : "0"));
    Button cal = button(this, Prefs.s(this, "prox_prof", "").isEmpty() ? "Teach javis my voice (optional)" : "Teach javis my voice again (optional)", CARD); c.addView(cal, lp(this, -1, -2, 10));
    cal.setOnClickListener(v -> { if (!WhisperService.running) { alert("Teach javis my voice", "Optional – javis also learns your voice by itself from your ‘Whisper, …’ commands. Press START first, then tap this and say one normal sentence with the earbud in."); return; }
      Prefs.put(this, "cal_pending", "1"); alert("Teach javis my voice", "Now say one normal sentence, e.g. ‘This is Calton testing my earbud.’"); });
    Switch voice = new Switch(this); voice.setText("Speak whispers in earbud (off = text only)"); voice.setTextColor(0xFFFFFFFF); voice.setChecked(!"0".equals(Prefs.s(this, "voice", "1"))); c.addView(voice, lp(this, -1, -2, 10));
    voice.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "voice", on ? "1" : "0"));

    c.addView(text(this, "PC01 backup sync (optional, off by default)", 16, 0xFFFFFFFF), lp(this, -1, -2, 18));
    Switch sync = new Switch(this); sync.setText("Upload finished sessions (text) to PC01"); sync.setTextColor(0xFFFFFFFF); sync.setChecked("1".equals(Prefs.s(this, "sync", "0"))); c.addView(sync, lp(this, -1, -2, 6));
    sync.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "sync", on ? "1" : "0"));
    EditText url = edit(this, "Server URL", Prefs.s(this, "server", Prefs.DEFAULT_SERVER)); c.addView(url, lp(this, -1, -2, 6));
    EditText code = edit(this, "6-digit pairing code from PC01 admin page", ""); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); c.addView(code, lp(this, -1, -2, 6));
    Button pair = button(this, Prefs.token(this).isEmpty() ? "Pair with PC01" : "Re-pair with PC01 (paired)", CARD); c.addView(pair, lp(this, -1, -2, 6));
    pair.setOnClickListener(v -> { if (code.getText().toString().trim().length() != 6) { alert("Pairing", "Enter the 6-digit code shown on the PC01 admin page."); return; }
      Prefs.put(this, "server", url.getText().toString().trim()); String cd = code.getText().toString().trim();
      new Thread(() -> { try {
        JSONObject r = new Api(this).call("/api/pair", new JSONObject().put("code", cd).put("name", Build.MANUFACTURER + " " + Build.MODEL));
        Prefs.put(this, "token", r.getString("token"));
        String k = r.optString("xaiKey", ""); if (!k.isEmpty()) Prefs.put(this, "xai_key", k);
        ui("Paired with PC01 ✔ (device #" + r.optInt("deviceId") + ")" + (k.isEmpty() ? " – but PC01 sent no xAI key" : " · xAI key received ✔ – press START"));
      } catch (Exception e) { String why = reason(e); ui("Pairing failed: " + why); alert("Pairing failed", why); } }).start();
    });
    return c;
  }
  void ui(String s) { h.post(() -> { pairT.setText(s); WhisperService.log(s); }); }

  void startSvc() {
    List<String> need = new ArrayList<>(); need.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
    List<String> miss = new ArrayList<>(); for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
    if (!miss.isEmpty()) { requestPermissions(miss.toArray(new String[0]), 1); return; }
    if (Prefs.s(this, "xai_key", "").isEmpty()) {
      if (!Prefs.token(this).isEmpty()) { startB.setEnabled(false); WhisperService.log("Fetching xAI key from PC01…");
        new Thread(() -> { try { String k = new Api(this).call("/api/provision", null).optString("xaiKey"); if (k.isEmpty()) throw new Exception("PC01 has no xAI key");
          Prefs.put(this, "xai_key", k); h.post(() -> { startB.setEnabled(true); startSvc(); }); }
          catch (Exception e) { h.post(() -> { startB.setEnabled(true); alert("Can't get the xAI key", reason(e)); }); } }).start(); return; }
      settings.setVisibility(View.VISIBLE); alert("Setup needed", "Pair with PC01 (Settings → 6-digit code) – that loads the xAI key automatically. Or paste an xAI key in Settings."); return; }
    startForegroundService(new Intent(this, WhisperService.class).setAction(WhisperService.ACTION_START));
  }
  void alert(String title, String msg) { h.post(() -> { if (!isFinishing()) new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show(); }); }
  static String reason(Exception e) {
    String m = String.valueOf(e.getMessage());
    if (m.startsWith("HTTP 403") && m.contains("code")) return "Code wrong, expired (codes last 10 min) or already used. Make a NEW code on the PC01 admin page and tap Pair once.";
    if (m.startsWith("HTTP 401")) return "This phone isn't paired (or was revoked). Pair again with a new code.";
    if (m.startsWith("HTTP 429")) return "Too many tries – wait a few minutes.";
    if (m.startsWith("xAI HTTP 401") || m.startsWith("xAI HTTP 403")) return "xAI rejected the key: " + m;
    if (m.startsWith("xAI HTTP 429")) return "xAI credit/rate limit: " + m;
    if (e instanceof java.net.UnknownHostException) return "No internet or wrong server address (" + m + ").";
    if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) return "Can't reach the server (" + m + "). Check internet and the Server URL https://whisper.neuereatec.org";
    return m;
  }
  void stopSvc() { startService(new Intent(this, WhisperService.class).setAction(WhisperService.ACTION_STOP)); }
  @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) { for (int x : g) if (x != PackageManager.PERMISSION_GRANTED) { WhisperService.log("Microphone and Nearby devices permissions are required."); return; } startSvc(); }

  void tick() {
    boolean r = WhisperService.running;
    statusT.setText(WhisperService.status + (r ? " · " + mmss(System.currentTimeMillis() - WhisperService.sessionStart) : ""));
    routeT.setText(WhisperService.route + (WhisperService.lastTiming.isEmpty() ? "" : "\n" + WhisperService.lastTiming));
    meBar.setProgress(meter(WhisperService.levelMe)); themBar.setProgress(meter(WhisperService.levelThem));
    startB.setText(r ? "STOP" : "START listening"); startB.setBackground(round(r ? RED : GREEN, px(this, 12)));
    paintChip();
    List<String> lg = WhisperService.logSnapshot(); logT.setText(String.join("\n", lg));
    lastT.setText(r ? WhisperService.status + " · " + WhisperService.route + (lg.isEmpty() ? "" : "\n" + lg.get(0)) : (Prefs.s(this, "xai_key", "").isEmpty() ? "Setup: Settings → pair with PC01 (6-digit code)" : "Ready – press START"));
    h.postDelayed(this::tick, 150);
  }
  static int meter(float rms) { return (int) Math.max(0, Math.min(100, (20 * Math.log10(Math.max(rms, 1)) - 20) * 100 / 60)); }
  static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }
}
