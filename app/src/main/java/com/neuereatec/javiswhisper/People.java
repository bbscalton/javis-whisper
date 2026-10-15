package com.neuereatec.javiswhisper;
import android.app.*; import android.content.Context;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
/**
 * 0.1.5 people memory (text only, on the phone): learns names (pre-brief, "this is X", model), keeps a short card per person,
 * writes a conversation note (topic, promises, open items) when a conversation ends, and feeds the card + last notes into coaching.
 * A person is only attached when a NAME agrees; a voice match alone never decides (it's a hint).
 */
public class People {
  static final Pattern NAME = Pattern.compile("\\b(?i:this is|i'm|i am|my name is|meet|call me|thanks,?|thank you,?)\\s+([A-Z][a-z]{2,15})(?:\\s+([A-Z][a-z]{2,15}))?");
  static final Set<String> NOT_NAMES = new HashSet<>(Arrays.asList("Sure","Fine","Good","Okay","Here","Sorry","Just","Going","Really","Not","The","Very","Glad","Happy","Calton","Carlton","Carbon","Andrew","Miss","Mister","Whisper","Javis","Jarvis","Much","You","Everyone","All","Testing","Test","Mode","Street","Coach","Green","Truck","Manager","Earbuds","Mic"));
  final Context c; Db.Person cur; String card = ""; long convoStart; String goal = ""; String preName = "";
  People(Context c) { this.c = c.getApplicationContext(); }

  /** Name heard in a line of speech, or null. spk tells us whose name it is ("this is X" from Them = their name). */
  static String nameIn(String text, String spk) {
    if (text == null || Guard.isVoiceTest(text) || Guard.unclear(text) != null) return null; // 0.1.10: never from test noise / fragments
    Matcher m = NAME.matcher(text); if (!m.find()) return null;
    String n = m.group(1); if (NOT_NAMES.contains(n)) return null;
    String lead = m.group(0).toLowerCase(Locale.US);
    boolean self = lead.startsWith("this is") || lead.startsWith("i") || lead.startsWith("my name") || lead.startsWith("call me");
    if ("Me".equals(spk) && self) return null;             // Calton introducing himself
    if (!"Me".equals(spk) && !self) return null;           // they're naming someone else ("thanks, Calton")
    return m.group(2) != null && !NOT_NAMES.contains(m.group(2)) ? n + " " + m.group(2) : n;
  }
  /** At START: pull name / relationship / goal out of the pre-brief with one cheap call. */
  void fromPrebrief(Xai x, String model, String prebrief) {
    if (prebrief == null || prebrief.trim().length() < 3 || x == null) return;
    try {
      String raw = x.chat(model, new JSONArray().put(Learn.msg("system", "From Calton's pre-brief extract JSON {\"name\":\"<person's name or ''>\",\"relationship\":\"<e.g. bank manager, client, ''>\",\"goal\":\"<what Calton wants out of it, ≤12 words, or ''>\"}. Only facts in the text.")).put(Learn.msg("user", prebrief)), 80, 0, null);
      JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
      goal = j.optString("goal", ""); preName = j.optString("name", "");
      if (!preName.isEmpty()) attach(preName, j.optString("relationship", ""), "pre-brief");
      if (!goal.isEmpty()) WhisperService.log("🎯 Goal: " + goal);
    } catch (Exception e) { WhisperService.log("(pre-brief read failed: " + e.getMessage() + ")"); }
  }
  static String norm(String n) { return n == null ? "" : n.toLowerCase(Locale.US).replaceAll("[^a-z ]", "").trim(); }
  Db.Person find(String name) {
    String n = norm(name), first = n.split(" ")[0];
    Db.Person best = null;
    for (Db.Person p : Db.get(c).d().people()) {
      String pn = norm(p.name); if (pn.equals(n)) return p;
      if (!first.isEmpty() && (pn.split(" ")[0].equals(first) || (p.aliases != null && norm(p.aliases).contains(first)))) best = best == null ? p : best;
    }
    return best;
  }
  /** Attach (or create) the person Calton is talking with. Returns true if this changed who's attached. */
  synchronized boolean attach(String name, String rel, String why) {
    Db.Person p = find(name); long now = System.currentTimeMillis();
    if (p == null) { p = new Db.Person(); p.name = name; p.relationship = rel; p.notes = ""; p.firstSeen = now; p.lastSeen = now; p.id = Db.get(c).d().add(p); WhisperService.log("👤 New person: " + name + " (" + why + ")"); }
    else { if ((p.relationship == null || p.relationship.isEmpty()) && rel != null && !rel.isEmpty()) p.relationship = rel; p.lastSeen = now; Db.get(c).d().update(p); }
    if (cur != null && cur.id == p.id) return false;
    cur = p; convoStart = now; card = buildCard(p);
    WhisperService.log("👤 Talking with " + p.name + (card.contains("Last time") ? " – remembered past conversations" : "") + " (" + why + ")");
    return true;
  }
  /** Voice hint: does the linked partner's voice look like a known person? Only used to SUGGEST (name must agree). */
  Db.Person voiceHint(double[] v) {
    Db.Person best = null; double bs = 0;
    for (Db.Person p : Db.get(c).d().people()) { if (p.voiceSig == null || p.voiceN < 5) continue; double s = sim(v, sig(p.voiceSig)); if (s > bs) { bs = s; best = p; } }
    return bs >= 0.75 ? best : null;
  }
  static double[] sig(String s) { String[] a = s.split(","); double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = Double.parseDouble(a[i]); return r; }
  static double sim(double[] a, double[] b) { int n = Math.min(a.length, b.length); double d = 0; for (int i = 0; i < n; i++) d += Math.abs(a[i] - b[i]) / Math.max(Math.abs(b[i]), i < 2 ? 3 : 0.05); return 1 / (1 + d / n * 2); }
  synchronized void learnVoice(double[] v) {
    if (cur == null || v == null) return; double[] o = cur.voiceSig == null ? v.clone() : sig(cur.voiceSig); double a = Math.max(0.1, 1.0 / (cur.voiceN + 1));
    StringBuilder b = new StringBuilder(); int n = Math.min(o.length, v.length); for (int i = 0; i < n; i++) { if (i > 0) b.append(','); b.append(String.format(Locale.US, "%.3f", o[i] + a * (v[i] - o[i]))); }
    cur.voiceSig = b.toString(); cur.voiceN++; Db.get(c).d().update(cur);
  }
  String buildCard(Db.Person p) {
    StringBuilder b = new StringBuilder("PERSON: ").append(p.name);
    if (p.relationship != null && !p.relationship.isEmpty()) b.append(" (").append(p.relationship).append(")");
    if (p.notes != null && !p.notes.isEmpty()) b.append(". Notes: ").append(clip(p.notes, 240));
    SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.US);
    List<Db.Convo> past = Db.get(c).d().convos(p.id, 3);
    for (Db.Convo cv : past) b.append("\nLast time (").append(f.format(new Date(cv.started))).append("): ").append(clip(cv.summary, 200))
      .append(cv.promises == null || cv.promises.isEmpty() ? "" : " Promises: " + clip(cv.promises, 120)).append(cv.openItems == null || cv.openItems.isEmpty() ? "" : " Open: " + clip(cv.openItems, 100));
    return b.toString();
  }
  static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }

  /** Conversation ended (STOP, partner silent > 3 min, or partner changed): write the note + update the person card + recap. */
  void endConvo(Xai x, String model, Db.Session s, String setting) {
    Db.Person p; long from; synchronized (this) { p = cur; from = convoStart; cur = null; card = ""; }
    if (x == null || s == null) return;
    List<Db.Turn> turns = Db.get(c).d().turnsSince(s.id, from == 0 ? s.started : from); if (turns.size() < 3) return;
    StringBuilder tx = new StringBuilder(); for (Db.Turn t : turns) tx.append("whisper".equals(t.kind) ? "JAVIS" : t.speaker).append(": ").append(t.text).append('\n');
    String t = tx.length() > 10000 ? tx.substring(tx.length() - 10000) : tx.toString();
    try {
      String raw = x.chat(model, new JSONArray().put(Learn.msg("system", "You write the after-conversation note for Calton (Me) about a conversation with " + (p == null ? "someone" : p.name) + ". Return ONLY JSON: "
        + "{\"topic\":\"<≤8 words>\",\"key_facts\":[\"…\"],\"promises\":[\"<who> → <what> → <when>\"],\"open_items\":[\"…\"],\"summary\":\"<≤40 words>\",\"person_note\":\"<≤25 words of durable facts about this person, or ''>\",\"name\":\"<their name if said, or ''>\"}. "
        + "Plain facts only from the transcript (live speech recognition, may have errors). Empty arrays when none.")).put(Learn.msg("user", t)), 400, 0.2, null);
      JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
      // 0.1.10: only create a person when the name is clearly introduced in the transcript (not a garbled test word), never in a test session
      String jn = j.optString("name", "").trim(); List<String> heard = new ArrayList<>(); for (Db.Turn tt : turns) if ("heard".equals(tt.kind)) heard.add(tt.text);
      boolean test = Guard.testSession(heard); if (test) { WhisperService.log("(recap skipped: voice/mic test, no person or note saved)"); return; }
      if (p == null && !jn.isEmpty() && clearlyIntroduced(jn, turns)) { attach(jn, "", "named in conversation"); synchronized (this) { p = cur; cur = null; card = ""; } }
      else if (p == null && !jn.isEmpty()) WhisperService.log("(not adding \"" + jn + "\" – name wasn't clearly introduced)");
      Db.Convo cv = new Db.Convo(); cv.sessionId = s.id; cv.personId = p == null ? 0 : p.id; cv.started = from == 0 ? s.started : from; cv.ended = System.currentTimeMillis(); cv.setting = setting;
      cv.topic = j.optString("topic"); cv.keyFacts = join(j.optJSONArray("key_facts")); cv.promises = join(j.optJSONArray("promises")); cv.openItems = join(j.optJSONArray("open_items")); cv.summary = j.optString("summary");
      Db.get(c).d().add(cv);
      String pn = j.optString("person_note", "");
      if (p != null && !pn.isEmpty()) { p.notes = clip((p.notes == null || p.notes.isEmpty() ? "" : p.notes + " ") + pn, 600); Db.get(c).d().update(p); }
      String recap = (p == null ? "" : p.name + ": ") + cv.topic + (cv.promises.isEmpty() ? "" : " · Promised: " + cv.promises) + (cv.openItems.isEmpty() ? "" : " · Open: " + cv.openItems);
      WhisperService.log("📝 Recap – " + recap); notifyRecap(recap);
    } catch (Exception e) { WhisperService.log("(recap failed: " + e.getMessage() + ")"); }
  }
  /** 0.1.10: the name must come from a real introduction line ("this is X", "my name is X") by Them, or a pre-brief. */
  static boolean clearlyIntroduced(String name, List<Db.Turn> turns) {
    String first = norm(name).split(" ")[0]; if (first.length() < 3 || NOT_NAMES.contains(cap(first))) return false;
    for (Db.Turn t : turns) { if (!"heard".equals(t.kind) || "Me".equals(t.speaker)) continue; String n = nameIn(t.text, t.speaker); if (n != null && norm(n).startsWith(first)) return true; }
    return false;
  }
  static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
  static String join(JSONArray a) { if (a == null) return ""; List<String> r = new ArrayList<>(); for (int i = 0; i < a.length(); i++) { String s = a.optString(i).trim(); if (!s.isEmpty()) r.add(s); } return String.join("; ", r); }
  void notifyRecap(String recap) {
    try { NotificationManager nm = c.getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("jw-recap", "Conversation recaps", NotificationManager.IMPORTANCE_DEFAULT));
      nm.notify((int) (System.currentTimeMillis() & 0xFFFF) + 100, new Notification.Builder(c, "jw-recap").setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle("javis recap").setContentText(recap).setStyle(new Notification.BigTextStyle().bigText(recap)).build());
    } catch (Throwable ignored) {}
  }
  /** "Whisper, what did I promise?" – recent promises/open items as context for the command path. */
  String memoryForCommand(String cmd) {
    StringBuilder b = new StringBuilder(); String lc = cmd.toLowerCase(Locale.US);
    for (Db.Person p : Db.get(c).d().people()) if (lc.contains(norm(p.name).split(" ")[0]) && norm(p.name).length() > 2) b.append(buildCard(p)).append('\n');
    if (lc.matches(".*\\b(promis|owe|open|follow|todo|to do|last time|remember|recap|agreed)\\w*.*")) {
      SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.US);
      for (Db.Convo cv : Db.get(c).d().recentConvos(5)) { Db.Person p = Db.get(c).d().person(cv.personId);
        b.append("- ").append(f.format(new Date(cv.started))).append(p == null ? "" : " with " + p.name).append(": ").append(cv.topic).append(cv.promises.isEmpty() ? "" : " · promises: " + cv.promises).append(cv.openItems.isEmpty() ? "" : " · open: " + cv.openItems).append('\n'); }
    }
    return b.toString().trim();
  }
}
