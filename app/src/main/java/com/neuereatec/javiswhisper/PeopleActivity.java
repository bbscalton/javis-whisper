package com.neuereatec.javiswhisper;
import android.app.Activity; import android.app.AlertDialog; import android.os.Bundle; import android.widget.*;
import java.text.SimpleDateFormat; import java.util.*;
import static com.neuereatec.javiswhisper.Ui.*;
/** 0.1.5 People screen: who javis remembers (text only, on this phone). Edit, merge, forget voice, delete. */
public class PeopleActivity extends Activity {
  LinearLayout root;
  @Override protected void onCreate(Bundle b) { super.onCreate(b); ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
    root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p = px(this, 16); root.setPadding(p, px(this, 28), p, p); sv.addView(root); setContentView(sv); load(); }
  void load() {
    root.removeAllViews(); Db.D d = Db.get(this).d();
    root.addView(text(this, "People javis remembers", 24, 0xFFFFFFFF));
    root.addView(text(this, "Learned from your pre-brief and names said in conversation. Text only, on this phone. A voice match alone never decides who someone is.", 13, MUTED));
    List<Db.Person> ps = d.people(); if (ps.isEmpty()) root.addView(text(this, "Nobody yet. Put a name in the pre-brief (\"Meeting John Persaud about the loan\") or let them introduce themselves.", 15, MUTED), lp(this, -1, -2, 12));
    SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy", Locale.US);
    for (Db.Person pr : ps) {
      LinearLayout c = card(this); root.addView(c, lp(this, -1, -2, 10));
      c.addView(text(this, pr.name + (pr.relationship == null || pr.relationship.isEmpty() ? "" : " · " + pr.relationship), 17, 0xFFFFFFFF));
      c.addView(text(this, "Last seen " + f.format(new Date(pr.lastSeen)) + " · voice " + (pr.voiceN >= 5 ? "known (" + pr.voiceN + " turns)" : pr.voiceN > 0 ? "learning (" + pr.voiceN + ")" : "not learned"), 12, MUTED));
      if (pr.notes != null && !pr.notes.isEmpty()) c.addView(text(this, pr.notes, 13, 0xFFE3ECF7));
      for (Db.Convo cv : d.convos(pr.id, 5)) c.addView(text(this, "• " + f.format(new Date(cv.started)) + " – " + cv.topic + (cv.promises == null || cv.promises.isEmpty() ? "" : "\n   Promised: " + cv.promises) + (cv.openItems == null || cv.openItems.isEmpty() ? "" : "\n   Open: " + cv.openItems), 13, 0xFFE3ECF7), lp(this, -1, -2, 4));
      LinearLayout row = new LinearLayout(this); c.addView(row, lp(this, -1, -2, 8));
      Button ed = button(this, "Edit", BLUE), mg = button(this, "Merge", CARD), fv = button(this, "Forget voice", CARD), del = button(this, "Delete", RED);
      for (Button x : new Button[]{ed, mg, fv, del}) { x.setTextSize(13); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1); l.setMargins(px(this, 2), 0, px(this, 2), 0); row.addView(x, l); }
      ed.setOnClickListener(v -> { LinearLayout fm = new LinearLayout(this); fm.setOrientation(LinearLayout.VERTICAL);
        EditText n = edit(this, "Name", pr.name), r = edit(this, "Relationship (client, bank manager…)", pr.relationship), a = edit(this, "Other names / nicknames", pr.aliases), no = edit(this, "Notes", pr.notes);
        for (EditText e : new EditText[]{n, r, a, no}) fm.addView(e, lp(this, -1, -2, 6));
        new AlertDialog.Builder(this).setTitle("Edit person").setView(fm).setPositiveButton("Save", (x, y) -> { pr.name = n.getText().toString().trim(); pr.relationship = r.getText().toString().trim(); pr.aliases = a.getText().toString().trim(); pr.notes = no.getText().toString().trim(); d.update(pr); load(); }).setNegativeButton("Cancel", null).show(); });
      mg.setOnClickListener(v -> { List<Db.Person> others = new ArrayList<>(); for (Db.Person o : d.people()) if (o.id != pr.id) others.add(o); if (others.isEmpty()) return;
        String[] names = new String[others.size()]; for (int i = 0; i < names.length; i++) names[i] = others.get(i).name;
        new AlertDialog.Builder(this).setTitle("Merge " + pr.name + " into…").setItems(names, (x, i) -> { Db.Person t = others.get(i); d.moveConvos(pr.id, t.id);
          t.notes = ((t.notes == null ? "" : t.notes) + " " + (pr.notes == null ? "" : pr.notes)).trim(); t.aliases = ((t.aliases == null ? "" : t.aliases) + " " + pr.name).trim(); d.update(t); d.delete(pr); load(); }).show(); });
      fv.setOnClickListener(v -> { pr.voiceSig = null; pr.voiceN = 0; d.update(pr); load(); });
      del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete " + pr.name + " and their conversation notes?").setPositiveButton("Delete", (x, y) -> { d.deleteConvos(pr.id); d.delete(pr); load(); }).setNegativeButton("Cancel", null).show());
    }
  }
}
