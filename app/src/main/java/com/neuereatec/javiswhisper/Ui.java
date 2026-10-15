package com.neuereatec.javiswhisper;
import android.app.Activity; import android.graphics.Color; import android.graphics.drawable.GradientDrawable;
import android.view.*; import android.widget.*;
final class Ui {
  static final int BG = 0xFF0B1E3A, CARD = 0xFF132F57, BLUE = 0xFF1E88E5, GREEN = 0xFF2E7D32, RED = 0xFFC62828, MUTED = 0xFF9FB3CF;
  static int px(Activity a, int dp) { return Math.round(dp * a.getResources().getDisplayMetrics().density); }
  static GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(r); return g; }
  static TextView text(Activity a, String s, int sp, int color) { TextView t = new TextView(a); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
  static Button button(Activity a, String s, int color) { Button b = new Button(a); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setBackground(round(color, px(a, 12))); return b; }
  static LinearLayout card(Activity a) { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(round(CARD, px(a, 14))); int p = px(a, 14); l.setPadding(p, p, p, p); return l; }
  static LinearLayout.LayoutParams lp(Activity a, int w, int h, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = px(a, top); return p; }
  static EditText edit(Activity a, String hint, String val) { EditText e = new EditText(a); e.setHint(hint); e.setText(val); e.setTextColor(Color.WHITE); e.setHintTextColor(MUTED); e.setBackground(round(0xFF0E2546, px(a, 10))); int p = px(a, 10); e.setPadding(p, p, p, p); return e; }
}
