package com.neuereatec.javiswhisper;
import android.app.Activity; import android.content.Intent; import android.os.*; import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*; import java.text.SimpleDateFormat; import java.util.*;
import static com.neuereatec.javiswhisper.Ui.*;
/** Review screen: local history (Room on this phone). Export shares a JSON file. */
public class ReviewActivity extends Activity {
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root);
    root.addView(text(this, "History (on this phone)", 24, 0xFFFFFFFF));
    String learned = Prefs.s(this, "learned", ""); if (!learned.isEmpty()) { LinearLayout c = card(this); c.addView(text(this, "What javis has learned", 15, 0xFFFFFFFF)); c.addView(text(this, learned, 13, 0xFFE3ECF7)); root.addView(c, lp(this, -1, -2, 10)); }
    Button exp = button(this, "Export all (JSON)", BLUE); root.addView(exp, lp(this, -1, -2, 10));
    exp.setOnClickListener(v -> { try {
      File dir = new File(getCacheDir(), "export"); dir.mkdirs(); File f = new File(dir, "javis-whisper-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json");
      try (FileWriter w = new FileWriter(f)) { w.write(Learn.exportAll(this).toString(1)); }
      android.net.Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
      startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export"));
    } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); } });
    List<Db.Session> ss = Db.get(this).d().sessions(); SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US), tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
    if (ss.isEmpty()) root.addView(text(this, "No sessions yet.", 15, MUTED), lp(this, -1, -2, 10));
    for (Db.Session s : ss) {
      LinearLayout c = card(this); root.addView(c, lp(this, -1, -2, 10));
      c.addView(text(this, df.format(new Date(s.started)) + " · " + s.mode + " · " + Db.get(this).d().count(s.id) + " lines", 15, 0xFFFFFFFF));
      if (s.prebrief != null && !s.prebrief.isEmpty()) c.addView(text(this, "Pre-brief: " + s.prebrief, 13, MUTED));
      c.addView(text(this, s.summary == null ? "(no summary)" : s.summary, 13, 0xFFE3ECF7));
      TextView body = text(this, "", 13, 0xFFE3ECF7); body.setTextIsSelectable(true); c.addView(body);
      c.setOnClickListener(v -> { StringBuilder sb = new StringBuilder(); for (Db.Turn t : Db.get(this).d().turns(s.id)) sb.append(tf.format(new Date(t.ts))).append("  ").append("whisper".equals(t.kind) ? "🗣 javis" : t.speaker).append(": ").append(t.text).append('\n'); body.setText(sb.length() == 0 ? "(empty)" : sb); });
    }
    setContentView(sv);
  }
}
