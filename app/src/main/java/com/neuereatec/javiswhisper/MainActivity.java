package com.neuereatec.javiswhisper;
import android.Manifest; import android.app.Activity; import android.content.Intent; import android.content.pm.PackageManager;
import android.os.*; import android.view.*; import android.widget.*; import org.json.JSONObject;
import java.util.*;
import static com.neuereatec.javiswhisper.Ui.*;

public class MainActivity extends Activity {
  final Handler h = new Handler(Looper.getMainLooper());
  TextView statusT, routeT, logT, pairT; ProgressBar meBar, themBar; Button startB; final Map<String, Button> modeBtns = new LinkedHashMap<>();
  LinearLayout settings;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG); sv.setFillViewport(true);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root);
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
    EditText brief = edit(this, "Pre-brief: who are you talking to, what about? (e.g. 'Bank manager, loan for crane truck')", Prefs.s(this, "prebrief", ""));
    brief.setMinLines(2); root.addView(brief, lp(this, -1, -2, 10));
    brief.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "prebrief", brief.getText().toString()); });

    startB = button(this, "START listening", GREEN); root.addView(startB, lp(this, -1, px(this, 64), 12));
    startB.setOnClickListener(v -> { Prefs.put(this, "prebrief", brief.getText().toString()); if (WhisperService.running) stopSvc(); else startSvc(); });

    LinearLayout row = new LinearLayout(this); root.addView(row, lp(this, -1, -2, 10));
    Button rev = button(this, "Review history", BLUE), set = button(this, "Settings", CARD);
    row.addView(rev, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1); l2.leftMargin = px(this, 8); row.addView(set, l2);
    rev.setOnClickListener(v -> startActivity(new Intent(this, ReviewActivity.class)));
    settings = buildSettings(); settings.setVisibility(View.GONE); root.addView(settings, lp(this, -1, -2, 10));
    set.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

    LinearLayout lc = card(this); root.addView(lc, lp(this, -1, -2, 12));
    lc.addView(text(this, "Live transcript & whispers (text only – no audio is stored)", 12, MUTED));
    logT = text(this, "", 14, 0xFFE3ECF7); logT.setTextIsSelectable(true); lc.addView(logT);
    setContentView(sv);
    paintModes();
    if (Prefs.token(this).isEmpty()) { settings.setVisibility(View.VISIBLE); WhisperService.log("Not paired yet: open Settings, enter the server URL and pairing code from PC01."); }
    tick();
  }
  ProgressBar bar() { ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100); return pb; }
  void paintModes() { String m = Prefs.s(this, "mode", "coach"); for (Map.Entry<String, Button> e : modeBtns.entrySet()) e.getValue().setBackground(round(e.getKey().equals(m) ? BLUE : CARD, px(this, 12))); }

  LinearLayout buildSettings() {
    LinearLayout c = card(this);
    c.addView(text(this, "PC01 server", 16, 0xFFFFFFFF));
    EditText url = edit(this, "Server URL, e.g. https://whisper.neuereatec.com", Prefs.s(this, "server", "")); c.addView(url, lp(this, -1, -2, 6));
    EditText code = edit(this, "6-digit pairing code from PC01 admin page", ""); code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); c.addView(code, lp(this, -1, -2, 6));
    Button pair = button(this, "Pair this phone", BLUE); c.addView(pair, lp(this, -1, -2, 6));
    pairT = text(this, pairedText(), 13, MUTED); c.addView(pairT, lp(this, -1, -2, 4));
    pair.setOnClickListener(v -> {
      Prefs.put(this, "server", url.getText().toString().trim()); String cd = code.getText().toString().trim();
      new Thread(() -> { try {
        JSONObject r = new Api(this).call("/api/pair", new JSONObject().put("code", cd).put("name", Build.MANUFACTURER + " " + Build.MODEL));
        Prefs.put(this, "token", r.getString("token")); ui("Paired ✔ (device #" + r.optInt("deviceId") + ")");
      } catch (Exception e) { ui("Pairing failed: " + e.getMessage()); } }).start();
    });
    Button test = button(this, "Test connection", CARD); c.addView(test, lp(this, -1, -2, 6));
    test.setOnClickListener(v -> { Prefs.put(this, "server", url.getText().toString().trim()); new Thread(() -> { try { JSONObject r = new Api(this).call("/api/ping", null); ui("Connected ✔ as " + r.optString("device") + " · model " + r.optString("model")); } catch (Exception e) { ui("Not connected: " + e.getMessage()); } }).start(); });

    c.addView(text(this, "Whisper volume", 14, 0xFFFFFFFF), lp(this, -1, -2, 14));
    SeekBar vol = new SeekBar(this); vol.setMax(100); vol.setProgress((int) (Float.parseFloat(Prefs.s(this, "vol", "0.35")) * 100)); c.addView(vol);
    vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s, int v, boolean u) { Prefs.put(MainActivity.this, "vol", String.valueOf(Math.max(5, v) / 100f)); } public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} });

    Button sensB = button(this, "", CARD); c.addView(sensB, lp(this, -1, -2, 10));
    Runnable ps = () -> sensB.setText("Mic sensitivity: " + WhisperService.SENS_NAME[Integer.parseInt(Prefs.s(this, "sens", "1"))]); ps.run();
    sensB.setOnClickListener(v -> { Prefs.put(this, "sens", String.valueOf((Integer.parseInt(Prefs.s(this, "sens", "1")) + 1) % 3)); ps.run(); });
    Switch dual = new Switch(this); dual.setText("Use phone mic for the other person (labels Me / Them)"); dual.setTextColor(0xFFFFFFFF); dual.setChecked("1".equals(Prefs.s(this, "dual", "1"))); c.addView(dual, lp(this, -1, -2, 10));
    dual.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "dual", on ? "1" : "0"));
    Switch voice = new Switch(this); voice.setText("Speak whispers in earbud (off = text only)"); voice.setTextColor(0xFFFFFFFF); voice.setChecked(!"0".equals(Prefs.s(this, "voice", "1"))); c.addView(voice, lp(this, -1, -2, 10));
    voice.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "voice", on ? "1" : "0"));
    c.addView(text(this, "Translate mode – their possible languages (max 3, BCP-47)", 13, MUTED), lp(this, -1, -2, 10));
    EditText langs = edit(this, "es-ES,pt-BR,fr-FR", Prefs.s(this, "langs", "es-ES,pt-BR,fr-FR")); c.addView(langs);
    langs.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "langs", langs.getText().toString()); });
    c.addView(text(this, "Changes apply on next START (mode and sensitivity apply live).", 12, MUTED), lp(this, -1, -2, 8));
    return c;
  }
  String pairedText() { return Prefs.token(this).isEmpty() ? "Not paired" : "Paired with " + Prefs.server(this); }
  void ui(String s) { h.post(() -> { pairT.setText(s); WhisperService.log(s); }); }

  void startSvc() {
    List<String> need = new ArrayList<>(); need.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
    List<String> miss = new ArrayList<>(); for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
    if (!miss.isEmpty()) { requestPermissions(miss.toArray(new String[0]), 1); return; }
    if (Prefs.token(this).isEmpty()) { settings.setVisibility(View.VISIBLE); WhisperService.log("Pair with PC01 first (Settings)."); return; }
    startForegroundService(new Intent(this, WhisperService.class).setAction(WhisperService.ACTION_START));
  }
  void stopSvc() { startService(new Intent(this, WhisperService.class).setAction(WhisperService.ACTION_STOP)); }
  @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) { for (int x : g) if (x != PackageManager.PERMISSION_GRANTED) { WhisperService.log("Microphone and Nearby devices permissions are required."); return; } startSvc(); }

  void tick() {
    boolean r = WhisperService.running;
    statusT.setText(WhisperService.status + (r ? " · " + mmss(System.currentTimeMillis() - WhisperService.sessionStart) : ""));
    routeT.setText(WhisperService.route + " · queued " + WhisperService.queued(this));
    meBar.setProgress(meter(WhisperService.levelMe)); themBar.setProgress(meter(WhisperService.levelThem));
    startB.setText(r ? "STOP" : "START listening"); startB.setBackground(round(r ? RED : GREEN, px(this, 12)));
    logT.setText(String.join("\n", WhisperService.logSnapshot()));
    h.postDelayed(this::tick, 150);
  }
  static int meter(float rms) { return (int) Math.max(0, Math.min(100, (20 * Math.log10(Math.max(rms, 1)) - 20) * 100 / 60)); }
  static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }
}
