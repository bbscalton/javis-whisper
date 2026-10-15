package com.neuereatec.javiswhisper;
import android.app.Activity; import android.content.Intent; import android.os.*; import android.widget.*;
import org.json.*; import java.io.*;
import static com.neuereatec.javiswhisper.Ui.*;
/** Review screen: reads sessions from PC01 (the phone keeps no history). Export shares the full JSON as text. */
public class ReviewActivity extends Activity {
  LinearLayout list; final Handler h = new Handler(Looper.getMainLooper());
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root);
    root.addView(text(this, "History (stored on PC01)", 24, 0xFFFFFFFF));
    Button exp = button(this, "Export all as JSON", BLUE); root.addView(exp, lp(this, -1, -2, 10));
    exp.setOnClickListener(v -> new Thread(() -> { try {
      JSONObject j = new Api(this).call("/api/export", null);
      File f = new File(getCacheDir(), "javis-whisper-export.json"); try (FileWriter w = new FileWriter(f)) { w.write(j.toString(1)); }
      String s = j.toString(1); h.post(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Javis Whisper export").putExtra(Intent.EXTRA_TEXT, s.length() > 400000 ? s.substring(0, 400000) : s), "Export")));
    } catch (Exception e) { toast(e.getMessage()); } }).start());
    list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
    setContentView(sv); load();
  }
  void toast(String s) { h.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }
  void load() { new Thread(() -> { try {
    JSONArray a = new Api(this).call("/api/sessions", null).getJSONArray("list");
    h.post(() -> { list.removeAllViews(); if (a.length() == 0) list.addView(text(this, "No sessions yet.", 15, MUTED));
      for (int i = 0; i < a.length(); i++) { JSONObject s = a.optJSONObject(i); LinearLayout c = card(this); list.addView(c, lp(this, -1, -2, 10));
        c.addView(text(this, s.optString("started").replace('T', ' ').substring(0, 16) + " · " + s.optString("mode") + " · " + s.optInt("n") + " lines", 15, 0xFFFFFFFF));
        if (!s.optString("prebrief").isEmpty()) c.addView(text(this, "Pre-brief: " + s.optString("prebrief"), 13, MUTED));
        c.addView(text(this, s.isNull("summary") ? "(no summary yet)" : s.optString("summary"), 13, 0xFFE3ECF7));
        TextView body = text(this, "", 13, 0xFFE3ECF7); body.setTextIsSelectable(true); c.addView(body);
        int id = s.optInt("id"); c.setOnClickListener(v -> open(id, body)); }
    });
  } catch (Exception e) { toast("Cannot load history: " + e.getMessage()); } }).start(); }
  void open(int id, TextView body) { new Thread(() -> { try {
    JSONArray t = new Api(this).call("/api/session/" + id, null).getJSONArray("turns"); StringBuilder sb = new StringBuilder();
    for (int i = 0; i < t.length(); i++) { JSONObject x = t.getJSONObject(i); String ts = x.optString("ts"); sb.append(ts.length() > 18 ? ts.substring(11, 19) : ts).append("  ").append("whisper".equals(x.optString("kind")) ? "🗣 Javis" : x.optString("speaker")).append(": ").append(x.optString("text")).append('\n'); }
    h.post(() -> body.setText(sb.length() == 0 ? "(empty)" : sb.toString()));
  } catch (Exception e) { toast(e.getMessage()); } }).start(); }
}
