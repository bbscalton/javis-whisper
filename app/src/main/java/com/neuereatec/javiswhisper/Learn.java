package com.neuereatec.javiswhisper;
import android.content.Context;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
/** Persona + learning loop, all on the phone. Optional PC01 sync (off by default). */
public class Learn {
  static final Map<String, String> MODES = new HashMap<>();
  static {
    MODES.put("coach", "COACH: help Calton make good decisions in the conversation around him. Whisper one short practical suggestion: a recommendation, a risk or red flag, a better option, what to say or ask for next, or a key fact. If someone states something wrong, give the correct fact. Statements only, never a question to Calton. Otherwise SKIP.");
    MODES.put("translate", "TRANSLATE: if the line is not English, give the natural English translation of what they said, then \" || Say: \" and a short natural reply Calton can say in THEIR language. No brackets. Example: Where is Stabroek Market and how much is a taxi? || Say: Está en Water Street, el taxi cuesta unos mil dólares. If the line is English, SKIP.");
    MODES.put("street", "STREET: people may stop Calton and ask things (directions, prices, opinions, small talk). If someone asks him something, give a quick answer he can say out loud; if something around him is a risk or a bad deal, warn him in a few words. Otherwise SKIP.");
  }
  static String systemPrompt(Context c, Db.Session s, String mode) {
    StringBuilder b = new StringBuilder();
    b.append("You are javis, a friend in Calton's ear who helps him make good decisions in the conversation around him, whispering live into his earbud. Calton lives in Guyana and speaks English.\n\n");
    b.append("Voice: warm, sharp, concise. Plain words, contractions, friendly but direct, like a smart friend at his shoulder with street smarts. Lead with the answer. One short sentence, max 15 words, ideally under 10. Be concrete: the actual fact, number or name, or the exact words to say. No hedging, filler, greetings, 'As an AI', 'it seems', 'you might want to', no markdown.\n\n");
    b.append(Guard.clock()).append(" ").append(Guard.RULES).append("\n\n");
    b.append("Only speak when it genuinely helps. Give suggestions, answers or facts – never ask Calton a question back. If nothing useful to add, reply with exactly: SKIP\n\n");
    b.append(MODES.containsKey(mode) ? MODES.get(mode) : MODES.get("coach")).append("\n\n");
    b.append("Lines are live speech recognition and may contain errors; infer what was meant. [Me] = Calton, [Them] = the other person, [Speaker] = unknown. Never repeat what was just said.\n\n");
    if (s.prebrief != null && !s.prebrief.isEmpty()) b.append("PRE-BRIEF (who/topic): ").append(s.prebrief).append("\n\n");
    String learned = Prefs.s(c, "learned", ""); if (!learned.isEmpty()) b.append("What helps Calton (learned from past sessions): ").append(learned).append("\n\n");
    List<Db.Session> past = Db.get(c).d().summaries(s.id, Integer.parseInt(Prefs.s(c, "learn_n", "8")));
    if (!past.isEmpty()) { b.append("Recent sessions:\n"); SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); for (Db.Session p : past) b.append("- ").append(f.format(new Date(p.started))).append(' ').append(p.mode).append(": ").append(p.summary).append('\n'); }
    return b.toString().trim();
  }
  static void endSession(Context c, Xai x, String model, Db.Session s) throws Exception {
    List<Db.Turn> turns = Db.get(c).d().turns(s.id); if (turns.isEmpty() || x == null) return;
    StringBuilder tx = new StringBuilder(); for (Db.Turn t : turns) tx.append("whisper".equals(t.kind) ? "JAVIS" : t.speaker).append(": ").append(t.text).append('\n');
    String t = tx.length() > 12000 ? tx.substring(tx.length() - 12000) : tx.toString();
    s.summary = x.chat(model, new JSONArray().put(msg("system", "Summarise this earbud-companion session in at most 3 sentences: who, topic, outcome, which whispers helped or were ignored. Plain text only – no markdown, no headings, no asterisks. Plain text.")).put(msg("user", "Mode " + s.mode + ". Pre-brief: " + s.prebrief + "\n" + t)), 160, 0.3, null);
    Db.get(c).d().update(s); WhisperService.log("Session summary: " + s.summary);
    String note = x.chat(model, new JSONArray().put(msg("system", "Maintain a compact note (max 120 words) of what helps Calton in conversations: preferred whisper style, recurring people/topics, corrections. Merge the new session into the existing note. Output only the note.")).put(msg("user", "Existing note: " + Prefs.s(c, "learned", "(none)") + "\nNew session: " + s.summary)), 220, 0.3, null);
    if (!note.isEmpty()) Prefs.put(c, "learned", note);
    if ("1".equals(Prefs.s(c, "sync", "0"))) try { syncToPc01(c, s, turns); } catch (Exception e) { WhisperService.log("PC01 sync failed: " + e.getMessage()); }
  }
  static JSONObject msg(String r, String c) throws JSONException { return new JSONObject().put("role", r).put("content", c); }
  static String iso(long ms) { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(ms)); }
  static void syncToPc01(Context c, Db.Session s, List<Db.Turn> turns) throws Exception {
    if (Prefs.token(c).isEmpty()) return; Api api = new Api(c); String cid = "phone-" + s.started;
    api.call("/api/session", new JSONObject().put("clientId", cid).put("mode", s.mode).put("prebrief", s.prebrief).put("started", iso(s.started)));
    JSONArray a = new JSONArray(); for (Db.Turn t : turns) a.put(new JSONObject().put("sessionId", cid).put("clientId", cid + "-" + t.id).put("ts", iso(t.ts)).put("kind", t.kind).put("speaker", t.speaker).put("lang", t.lang).put("text", t.text));
    api.call("/api/turns", new JSONObject().put("turns", a)); s.synced = true; Db.get(c).d().update(s); WhisperService.log("Synced to PC01");
  }
  static JSONObject exportAll(Context c) throws JSONException {
    JSONArray ss = new JSONArray();
    for (Db.Session s : Db.get(c).d().sessions()) { JSONArray ts = new JSONArray();
      for (Db.Turn t : Db.get(c).d().turns(s.id)) ts.put(new JSONObject().put("ts", iso(t.ts)).put("kind", t.kind).put("speaker", t.speaker).put("text", t.text));
      ss.put(new JSONObject().put("started", iso(s.started)).put("mode", s.mode).put("version", s.version).put("prebrief", s.prebrief).put("summary", s.summary).put("turns", ts)); }
    JSONArray pp = new JSONArray(); for (Db.Person p : Db.get(c).d().people()) { JSONArray cs = new JSONArray();
      for (Db.Convo cv : Db.get(c).d().convos(p.id, 1000)) cs.put(new JSONObject().put("started", iso(cv.started)).put("setting", cv.setting).put("topic", cv.topic).put("keyFacts", cv.keyFacts).put("promises", cv.promises).put("openItems", cv.openItems).put("summary", cv.summary));
      pp.put(new JSONObject().put("name", p.name).put("relationship", p.relationship).put("aliases", p.aliases).put("notes", p.notes).put("conversations", cs)); }
    return new JSONObject().put("exported", iso(System.currentTimeMillis())).put("learned", Prefs.s(c, "learned", "")).put("sessions", ss).put("people", pp);
  }
}
