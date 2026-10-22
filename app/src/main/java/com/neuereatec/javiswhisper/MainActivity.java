package com.neuereatec.javiswhisper;
import android.Manifest; import android.app.Activity; import android.content.Intent; import android.content.pm.PackageManager;
import android.os.*; import android.view.*; import android.widget.*; import org.json.JSONObject;
import java.util.*;
import static com.neuereatec.javiswhisper.Ui.*;

public class MainActivity extends Activity {
  final Handler h = new Handler(Looper.getMainLooper());
  LinearLayout voicesBox, spkRow; TextView qualT; String spkSig = ""; TextView readyT; ProgressBar readyBar; TextView meBadge, statusT, routeT, logT, pairT, lastT; ProgressBar meBar, themBar; Button startB; final Map<String, Button> modeBtns = new LinkedHashMap<>();
  LinearLayout settings; Button settingChip, voiceB;
  void paintChip() { String o = Prefs.s(this, "setting", "auto"); settingChip.setText("🧭 Setting: " + ("auto".equals(o) ? "Auto" + (WhisperService.running ? " – " + WhisperService.settingShown.replace(" (auto)", "") : "") : o + " (your choice)") + "  · tap to change"); }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b); WhisperService.initLog(this); Advisor.migrate(this);
    LinearLayout outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(BG);
    ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG); outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root, new ScrollView.LayoutParams(-1, -2));
    LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackgroundColor(0xFF081730); bar.setPadding(p, px(this, 8), p, px(this, 12)); outer.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    lastT = text(this, "", 13, 0xFFE3ECF7); lastT.setMaxLines(3); bar.addView(lastT);
    root.addView(text(this, "Javis Whisper", 28, 0xFFFFFFFF));
    root.addView(text(this, "Your earbud AI companion · by Neuereatec", 14, MUTED));
    { String vn = "?"; try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {} root.addView(text(this, "v" + vn, 13, 0xFF9CC9FF)); } // 0.1.14 visible version

    LinearLayout st = card(this); root.addView(st, lp(this, -1, -2, 14));
    statusT = text(this, "Stopped", 20, 0xFFFFFFFF); st.addView(statusT);
    meBadge = text(this, "● It's you", 14, 0xFF7A8BA3); meBadge.setPadding(px(this, 10), px(this, 4), px(this, 10), px(this, 4)); st.addView(meBadge, lp(this, -2, -2, 4)); // 0.1.12 owner-voice light
    meBadge.setOnClickListener(v -> { if (WhisperService.running && WhisperService.curSpeaker != null) { alert("Recognised", "Last speaker: " + WhisperService.curSpeaker + ". Manage voices in Settings."); return; }
      Prefs.put(this, "cal_pending", "1"); alert("Identify yourself", "With the earbud in, say:\n\n“This is [your name] doing a mic test.”\n\njavis saves that voice under the name you say (encrypted, on this phone only) and shows the name here when it hears that voice again."); });
    // 0.1.16 speakers heard this session (tap an unnamed one to name it) + rolling quality
    spkRow = new LinearLayout(this); HorizontalScrollView hs = new HorizontalScrollView(this); hs.addView(spkRow); st.addView(hs, lp(this, -1, -2, 4));
    qualT = text(this, "", 13, 0xFFFFFFFF); st.addView(qualT, lp(this, -1, -2, 4));
    readyT = text(this, "", 13, 0xFFFFFFFF); st.addView(readyT, lp(this, -1, -2, 6)); readyBar = bar(); readyBar.setMax(100); st.addView(readyBar, lp(this, -1, px(this, 12), 2)); // 0.1.15 context readiness meter
    routeT = text(this, "-", 13, MUTED); st.addView(routeT);
    st.addView(text(this, "Earbud mic (Me)", 12, MUTED), lp(this, -1, -2, 8)); meBar = bar(); st.addView(meBar, lp(this, -1, px(this, 10), 2));
    st.addView(text(this, "Phone mic (Them)", 12, MUTED), lp(this, -1, -2, 6)); themBar = bar(); st.addView(themBar, lp(this, -1, px(this, 10), 2));

    voiceB = button(this, "🎙 Voice not enrolled – press START, then say: “This is [your name] doing a mic test.”", 0xFFEF6C00); voiceB.setTextSize(13); root.addView(voiceB, lp(this, -1, -2, 10));
    voiceB.setOnClickListener(v -> { if (!WhisperService.running) { alert("Teach javis my voice", "Press START first, then tap this again and say one normal sentence."); return; }
      Prefs.put(this, "cal_pending", "1"); alert("Teach javis my voice", "Now say one normal sentence, ‘This is [your name] doing a mic test.’ It’s kept as an encrypted voice sample on this phone only (max 3) and never sent to PC."); });
    LinearLayout modes = new LinearLayout(this); root.addView(modes, lp(this, -1, -2, 12));
    for (String[] m : new String[][]{{"advisory", "Advisor"}, {"coach", "Coach"}, {"translate", "Translate"}, {"street", "Street"}}) {
      Button bt = button(this, m[1], CARD); modeBtns.put(m[0], bt); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); l.setMargins(px(this, 3), 0, px(this, 3), 0); modes.addView(bt, l);
      bt.setOnClickListener(v -> { Prefs.put(this, "mode", m[0]); paintModes(); WhisperService.log("Mode → " + m[1]); });
    }
    // 0.1.5 setting chip: tap to cycle Auto → negotiation → … (override); shows what javis thinks when on Auto
    Button chip = button(this, "", CARD); chip.setTextSize(14); root.addView(chip, lp(this, -1, -2, 8)); settingChip = chip; paintChip();
    chip.setOnClickListener(v -> { String[] o = {"auto", "negotiation", "sale", "meeting", "call", "casual", "family", "street"}; String cur = Prefs.s(this, "setting", "auto"); int i = 0;
      for (int k = 0; k < o.length; k++) if (o[k].equals(cur)) i = k; Prefs.put(this, "setting", o[(i + 1) % o.length]); paintChip(); WhisperService.log("Setting → " + o[(i + 1) % o.length]); });
    EditText brief = edit(this, "Pre-brief (optional): who are you talking to, what about? (e.g. 'Bank manager, loan for crane truck')", Prefs.s(this, "prebrief", ""));
    brief.setMinLines(2); root.addView(brief, lp(this, -1, -2, 10));
    brief.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "prebrief", brief.getText().toString()); });

    startB = button(this, "START listening", GREEN); bar.addView(startB, lp(this, -1, px(this, 60), 6));
    startB.setOnClickListener(v -> { Prefs.put(this, "prebrief", brief.getText().toString()); if (WhisperService.running) stopSvc(); else if ("1".equals(Prefs.s(this, "require_brief", "0")) && brief.getText().toString().trim().isEmpty()) alert("Briefing required", "Add a short pre-brief (who / what about), or turn off ‘Always require a briefing’ in Settings."); else startSvc(); });

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

    // 0.1.23 Advisor mode: wake word + strictness (on-device detector; applies on next START)
    c.addView(text(this, "Advisor wake phrase (default: right cool – said as one phrase; common English words only; applies on next START)", 13, MUTED), lp(this, -1, -2, 10));
    EditText ww = edit(this, WakeGate.DEFAULT, Prefs.s(this, "wake_word", WakeGate.DEFAULT)); c.addView(ww);
    ww.setOnFocusChangeListener((v, f) -> { if (!f) Prefs.put(this, "wake_word", WakeGate.normalize(ww.getText().toString())); });
    Button strictB = button(this, "", CARD); c.addView(strictB, lp(this, -1, -2, 6));
    Runnable pst = () -> strictB.setText("Wake word strictness: " + Advisor.STRICT_NAME[Advisor.strictIdx(this)] + " (confidence ≥ " + Advisor.STRICT[Advisor.strictIdx(this)] + ")"); pst.run();
    strictB.setOnClickListener(v -> { Prefs.put(this, "wake_strict", String.valueOf((Advisor.strictIdx(this) + 1) % 3)); pst.run(); });
    c.addView(text(this, "Latency (applies on next START): coach pause min ms · max ms · end-of-phrase frames (×20 ms)", 13, MUTED), lp(this, -1, -2, 10));
    EditText lat = edit(this, "450 1000 22", Prefs.s(this, "pause_min", "450") + " " + Prefs.s(this, "pause_max", "1000") + " " + Prefs.s(this, "end_sil", "22")); c.addView(lat);
    lat.setOnFocusChangeListener((v, f) -> { if (f) return; String[] p = lat.getText().toString().trim().split("[\\s,]+"); String[] k = {"pause_min", "pause_max", "end_sil"}, dv = {"450", "1000", "22"};
      for (int j = 0; j < 3; j++) Prefs.put(this, k[j], j < p.length && p[j].matches("\\d{2,4}") ? p[j] : dv[j]); });

    // 0.1.18 resume after restart
    c.addView(text(this, "Resume the last conversation if it was active within (minutes, 0 = always start fresh)", 13, MUTED), lp(this, -1, -2, 10));
    EditText res = edit(this, "30", Prefs.s(this, "resume_min", "30")); res.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); c.addView(res);
    res.setOnFocusChangeListener((v, f) -> { if (!f) { String t = res.getText().toString().trim(); Prefs.put(this, "resume_min", t.matches("\\d{1,3}") ? t : "30"); } });
    Button fresh = button(this, "Start fresh next time (don't resume)", CARD); c.addView(fresh, lp(this, -1, -2, 6));
    fresh.setOnClickListener(v -> { Prefs.put(this, "start_fresh_once", "1"); alert("Start fresh", WhisperService.running ? "Stop and START again – the next session begins a new conversation." : "The next START begins a new conversation (history stays saved)."); });
    c.addView(text(this, "Whisper volume", 14, 0xFFFFFFFF), lp(this, -1, -2, 14));
    SeekBar vol = new SeekBar(this); vol.setMax(100); vol.setProgress((int) (Float.parseFloat(Prefs.s(this, "vol", "0.35")) * 100)); c.addView(vol);
    vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s, int v, boolean u) { Prefs.put(MainActivity.this, "vol", String.valueOf(Math.max(5, v) / 100f)); } public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} });
    Switch av = new Switch(this); av.setText("Louder whisper in noisy places (adaptive volume)"); av.setTextColor(0xFFFFFFFF); av.setChecked(!"0".equals(Prefs.s(this, "adapt_vol", "1"))); c.addView(av, lp(this, -1, -2, 8));
    av.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "adapt_vol", on ? "1" : "0"));
    Button sensB = button(this, "", CARD); c.addView(sensB, lp(this, -1, -2, 10));
    Runnable ps = () -> sensB.setText("Mic sensitivity: " + WhisperService.SENS_NAME[Integer.parseInt(Prefs.s(this, "sens", "1"))]); ps.run();
    sensB.setOnClickListener(v -> { Prefs.put(this, "sens", String.valueOf((Integer.parseInt(Prefs.s(this, "sens", "1")) + 1) % 3)); ps.run(); });
    Switch dual = new Switch(this); dual.setText("Use phone mic for the other person (labels Me / Them)"); dual.setTextColor(0xFFFFFFFF); dual.setChecked("1".equals(Prefs.s(this, "dual", "1"))); c.addView(dual, lp(this, -1, -2, 10));
    dual.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "dual", on ? "1" : "0"));
    Button cal = button(this, Prefs.s(this, "prox_prof", "").isEmpty() ? "Enroll my voice" : "Re-enroll my voice (" + VoiceEnroll.count(this) + "/3 samples · long-press to reset)", CARD); c.addView(cal, lp(this, -1, -2, 10));
    cal.setOnLongClickListener(v -> { VoiceEnroll.clear(this); Prefs.put(this, "prox_prof", ""); alert("Voice samples removed", "Press START and say: ‘This is [your name] doing a mic test.’"); return true; });
    cal.setOnClickListener(v -> { if (!WhisperService.running) { alert("Teach javis my voice", "Optional – javis also learns your voice by itself from your ‘Whisper, …’ commands. Press START first, then tap this and say one normal sentence with the earbud in."); return; }
      Prefs.put(this, "cal_pending", "1"); alert("Teach javis my voice", "Now say one normal sentence, ‘This is [your name] doing a mic test.’ It’s kept as an encrypted voice sample on this phone only (max 3) and never sent to PC."); });
    // 0.1.13 manage voices: every enrolled voice persists until removed here; removing one never touches the others
    c.addView(text(this, "Enrolled voices", 14, 0xFFFFFFFF), lp(this, -1, -2, 14)); voicesBox = new LinearLayout(this); voicesBox.setOrientation(LinearLayout.VERTICAL); c.addView(voicesBox); paintVoices();
    Switch lock = new Switch(this); lock.setText("🔒 Lock: only track the voices ticked ‘Lock’ below (one or more); everything else is ignored"); lock.setTextColor(0xFFFFFFFF); lock.setChecked("1".equals(Prefs.s(this, "lock", "0"))); c.addView(lock, lp(this, -1, -2, 6));
    lock.setOnCheckedChangeListener((v, on) -> { Prefs.put(this, "lock", on ? "1" : "0"); WhisperService.log(on ? (WhisperService.lockActive(this) ? "🔒 Lock on" : "🔒 Lock on – inactive until at least one enrolled voice is ticked") : "Lock off – listening to everyone"); });
    Button addV = button(this, "Enroll last speaker as…", CARD); c.addView(addV, lp(this, -1, -2, 6));
    addV.setOnClickListener(v -> { Proximity.Feat f = WhisperService.lastFeat; if (f == null) { alert("Nobody heard yet", "Press START, let the person say a sentence, then tap this. Or have them say ‘This is [name] doing a mic test’."); return; }
      EditText nm = edit(this, "Their name", ""); new android.app.AlertDialog.Builder(this).setTitle("Enroll the last voice heard").setView(nm).setPositiveButton("Save", (dd, w) -> { String n = VoiceEnroll.clean(nm.getText().toString()); if (n.isEmpty()) return;
        if (VoiceEnroll.isOwner(this, n)) { alert("That's you", "Use ‘Re-enroll my voice’ for your own voice."); return; } VoiceEnroll.saveNamed(this, n, f); WhisperService.log("✔ Enrolled " + n + " from the last voice heard"); paintVoices(); }).setNegativeButton("Cancel", null).show(); });
    Switch voice = new Switch(this); voice.setText("Speak whispers in earbud (off = text only)"); voice.setTextColor(0xFFFFFFFF); voice.setChecked(!"0".equals(Prefs.s(this, "voice", "1"))); c.addView(voice, lp(this, -1, -2, 10));
    voice.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "voice", on ? "1" : "0"));

    c.addView(text(this, "PC01 backup sync (optional, off by default)", 16, 0xFFFFFFFF), lp(this, -1, -2, 18));
    Switch reqB = new Switch(this); reqB.setText("Always require a briefing before START (off = optional; javis uses recent context)"); reqB.setTextColor(0xFFFFFFFF); reqB.setChecked("1".equals(Prefs.s(this, "require_brief", "0"))); c.addView(reqB, lp(this, -1, -2, 6));
    reqB.setOnCheckedChangeListener((v, on) -> Prefs.put(this, "require_brief", on ? "1" : "0"));
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
    if (VoiceEnroll.needGuided(this)) alert("Voice enrollment (once)", "With the earbud in, say:\n\n“This is [your name] doing a mic test.”\n\njavis keeps this short clip encrypted on this phone only (never uploaded, conversations are never recorded) so it knows your voice from the first line.");
  }
  CheckBox lockBox(String n) { CheckBox cb = new CheckBox(this); cb.setText("Lock"); cb.setTextColor(0xFFFFFFFF); cb.setChecked(WhisperService.lockAllowed(this).contains(n));
    cb.setOnCheckedChangeListener((v, on) -> { Set<String> s = new LinkedHashSet<>(WhisperService.lockAllowed(this)); if (on) s.add(n); else s.remove(n); Prefs.put(this, "lock_allow", s.isEmpty() ? ";" : String.join(";", s)); }); return cb; }
  void paintSpeakers(boolean r) { Diar dz = WhisperService.diar; List<Diar.Cl> ks = r && dz != null ? dz.recent(10 * 60_000L) : new ArrayList<>();
    StringBuilder sig = new StringBuilder(); Diar.Cl cur = WhisperService.curCl; for (Diar.Cl k : ks) sig.append(k.label()).append(k == cur ? "*" : "").append('|'); if (sig.toString().equals(spkSig)) return; spkSig = sig.toString();
    spkRow.removeAllViews(); for (Diar.Cl k : ks) { Button b = button(this, (k == cur ? "● " : "") + k.label() + (k.name == null ? " ✎" : ""), k.name != null ? 0xFF2E7D4F : 0xFF5A4A1E); b.setTextSize(12);
      LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = px(this, 6); spkRow.addView(b, l); b.setOnClickListener(v -> nameSpeaker(k)); } }
  void nameSpeaker(Diar.Cl k) { EditText nm = edit(this, "Name for " + k.label(), k.name == null ? "" : k.name);
    new android.app.AlertDialog.Builder(this).setTitle("Who is " + k.label() + "?").setMessage("javis will show this name for that voice and remember it next time.").setView(nm).setPositiveButton("Save", (dd, w) -> { String n = VoiceEnroll.clean(nm.getText().toString()); if (n.isEmpty()) return;
      Diar dz = WhisperService.diar; if (dz != null) dz.name(k, n); if (!VoiceEnroll.isOwner(this, n) && k.lastF != null) VoiceEnroll.saveNamed(this, n, k.lastF);
      WhisperService.log("✔ " + n + " – voice named (" + (k.id > 0 ? "was Speaker " + k.id : "") + ")"); spkSig = ""; paintVoices(); }).setNegativeButton("Cancel", null).show(); }
  void paintVoices() { if (voicesBox == null) return; voicesBox.removeAllViews();
    if (VoiceEnroll.has(this)) { LinearLayout or = new LinearLayout(this); or.addView(text(this, "• " + VoiceEnroll.owner(this) + " (you) – re-enroll/reset below", 14, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -2, 1)); or.addView(lockBox(VoiceEnroll.owner(this))); voicesBox.addView(or, lp(this, -1, -2, 4)); }
    for (String n : VoiceEnroll.voices(this).keySet()) { LinearLayout row = new LinearLayout(this); row.addView(text(this, "• " + n, 15, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -2, 1)); row.addView(lockBox(n));
      Button re = button(this, "Re-enroll", CARD), rm = button(this, "Remove", 0xFF8E2C2C); re.setTextSize(13); rm.setTextSize(13); row.addView(re); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.leftMargin = px(this, 6); row.addView(rm, l);
      re.setOnClickListener(v -> { Prefs.put(this, "enroll_as", n); alert("Re-enroll " + n, "Press START (if not running) and have " + n + " say one sentence. The next voice heard is saved as " + n + "."); });
      rm.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Remove " + n + "?").setMessage("Only " + n + "'s voice is deleted; other enrolled voices stay.").setPositiveButton("Remove", (dd, w) -> { VoiceEnroll.removeNamed(this, n); Diar.removeNamed(this, n); WhisperService.log("Removed " + n + "'s voice"); paintVoices(); }).setNegativeButton("Cancel", null).show());
      voicesBox.addView(row, lp(this, -1, -2, 4)); }
    if (voicesBox.getChildCount() == 0) voicesBox.addView(text(this, "None yet – anyone can say ‘This is [name] doing a mic test’ (optional).", 13, MUTED)); }
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
    { String who = r ? WhisperService.curSpeaker : null; boolean lit = who != null; // 0.1.13: shows the LATEST speaker until the next utterance
      boolean live = r && System.currentTimeMillis() - WhisperService.curAt < 4000;
      String dl = r ? WhisperService.curLabel : null; if (!lit && dl != null) { meBadge.setText("● " + dl + (live ? " is talking" : " (last speaker)") + " · tap ✎ below to name"); meBadge.setTextColor(0xFFFFFFFF); meBadge.setBackgroundColor(0xFF8A6D1E); } else
      meBadge.setText(lit ? "● " + who + (live ? " is talking" : " (last speaker)") : r && WhisperService.curAt > 0 ? "○ Voice not recognised · tap for phrase" : "○ No speaker yet · tap for phrase"); if (r && WhisperService.lockActive(this)) meBadge.setText("🔒 " + meBadge.getText());
      if (lit || dl == null) { meBadge.setTextColor(lit ? 0xFFFFFFFF : 0xFF9AA5B4); meBadge.setBackgroundColor(lit ? 0xFF2E9E4F : 0xFF3A4556); }
      if (!lit && dl == null && r && WhisperService.curAt == 0 && VoiceEnroll.has(this)) { meBadge.setText((WhisperService.lockActive(this) ? "🔒 " : "") + "● " + VoiceEnroll.owner(this) + " enrolled · waiting for speech"); meBadge.setTextColor(0xFFFFFFFF); meBadge.setBackgroundColor(0xFF2E9E4F); } } // 0.1.19
    paintSpeakers(r); { int q = WhisperService.quality; qualT.setVisibility(r && q >= 0 ? View.VISIBLE : View.GONE);
      if (q >= 0) { String lvl = q >= 75 ? "good" : q >= 50 ? "fair" : "poor"; qualT.setText("Quality: " + q + "% " + lvl + " · " + WhisperService.qTrend + (WhisperService.qWhy.isEmpty() ? "" : " (" + WhisperService.qWhy + ")"));
        qualT.setTextColor(q >= 75 && !"dropping".equals(WhisperService.qTrend) ? 0xFF7FE09A : q >= 50 ? 0xFFF0C060 : 0xFFFF8080); } }
    { int rd = WhisperService.readiness; int vis = r ? View.VISIBLE : View.GONE; readyT.setVisibility(vis); readyBar.setVisibility(vis); readyBar.setProgress(rd);
      String why = WhisperService.readyWhy; readyT.setText("Context readiness: " + rd + "% · " + (WhisperService.readyNoisy ? "noisy – being talked over" : rd >= 80 && why.isEmpty() ? "ready to whisper" : rd >= 50 ? "building" : "still gathering") + (why.isEmpty() ? "" : " (" + why + ")"));
      int col = WhisperService.readyNoisy ? 0xFFE04848 : rd >= 80 && why.isEmpty() ? 0xFF2E9E4F : 0xFFF0A020; // red noisy · green ready · amber gathering
      readyBar.setProgressTintList(android.content.res.ColorStateList.valueOf(col)); }
    meBar.setProgress(meter(WhisperService.levelMe)); themBar.setProgress(meter(WhisperService.levelThem));
    startB.setText(r ? "STOP" : "START listening"); startB.setBackground(round(r ? RED : GREEN, px(this, 12)));
    paintChip(); voiceB.setVisibility(VoiceEnroll.needGuided(this) ? View.VISIBLE : View.GONE);
    List<String> lg = WhisperService.logSnapshot(); logT.setText(String.join("\n", lg));
    lastT.setText(r ? WhisperService.status + " · " + WhisperService.route + (lg.isEmpty() ? "" : "\n" + lg.get(0)) : (Prefs.s(this, "xai_key", "").isEmpty() ? "Setup: Settings → pair with PC01 (6-digit code)" : "Ready – press START"));
    h.postDelayed(this::tick, 150);
  }
  static int meter(float rms) { return (int) Math.max(0, Math.min(100, (20 * Math.log10(Math.max(rms, 1)) - 20) * 100 / 60)); }
  static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }
}
