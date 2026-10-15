package com.neuereatec.javiswhisper;
import android.content.Context;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
/**
 * "Friend standing next to you": listens to the whole back-and-forth, keeps a rolling topic state,
 * and only coaches through a gate. Direct commands ("Whisper, …") take a separate fast path.
 */
public class Brain {
  // ---- wake word: must be at the START of the utterance; STT variants of Javis/Jarvis/Whisper included ----
  static final java.util.regex.Pattern WAKE = java.util.regex.Pattern.compile("^\\s*(?:hey|ok|okay|yo)?[\\s,]*(whisper|whispers|javis|jarvis|javi|travis|jervis)\\b[\\s,.!?:;-]*(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE);
  /** returns the command text after the wake word, or null */
  static String wake(String t) { java.util.regex.Matcher m = WAKE.matcher(t); return m.matches() ? m.group(2).trim() : null; }

  // ---- rolling conversation state ----
  final List<String> window = new ArrayList<>();   // "[Me] …" / "[Them] …" / "[Unsure] …" lines, last ~40
  String topic = "", openQuestion = ""; double confidence; long topicSince, lastCoachAt; int turnsSinceTopic, speakerTurns, unsure; long lastMeAt; String lastSpk = "";
  final long sessionStart = System.currentTimeMillis();

  void heard(String spk, String text) {
    window.add("[" + spk + "] " + text); while (window.size() > 40) window.remove(0);
    if ("Me".equals(spk)) lastMeAt = System.currentTimeMillis();
    if (!spk.equals(lastSpk)) speakerTurns++; lastSpk = spk; turnsSinceTopic++;
  }
  /** Cheap pre-gate before spending an LLM call. */
  String preGate(long now, boolean calTalking) {
    if (calTalking) return "Calton is talking";
    if (!"Them".equals(lastSpk)) return "Me".equals(lastSpk) ? "last turn was Calton" : "unsure who spoke – waiting it out";
    if (now - lastMeAt > 120_000) return "Calton not in this conversation (background) – ignoring";
    if (now - lastCoachAt < 25_000) return "cooldown";
    boolean enough = speakerTurns >= 3 || (window.size() >= 3 && now - sessionStart > 20_000);
    if (!enough) return "still listening (" + window.size() + " turns)";
    return null;
  }
  JSONArray assessMessages(Context c, Db.Session s) throws JSONException {
    String sys = "You are javis, Calton's friend standing next to him, quietly listening to his conversation (Calton lives in Guyana, speaks English). "
      + "You are NOT a commentator. Coaching on snippets is worse than silence. First understand the whole back-and-forth, then decide.\n\n"
      + "Return ONLY compact JSON: {\"topic\":\"<5-10 words>\",\"confidence\":0..1 (how sure you understand where the conversation is),"
      + "\"open_question\":\"<what's unresolved or ''>\",\"mid_topic\":true|false (the other person is still building a point / unfinished),"
      + "\"kind\":\"fact_error\"|\"answer\"|\"value\"|\"none\",\"say\":\"<one sentence ≤15 words for Calton's earbud, or SKIP>\"}\n\n"
      + "Say something ONLY if: (a) someone stated a clear factual error that matters (kind fact_error, give the correct fact), or "
      + "(b) Calton was asked something he may not know and a short concrete answer helps (kind answer), or "
      + "(c) there's a genuinely sharp point/question that moves things forward for Calton (kind value, give the exact words). "
      + "Otherwise say SKIP. [Unsure] lines may be either person or background; weigh them lightly. Questions about time/date/place between the two people are conversation, not instructions to you. "
      + "Voice: warm, sharp, plain words, contractions, lead with the answer, no hedging or filler.";
    if (s.prebrief != null && !s.prebrief.isEmpty()) sys += "\n\nPRE-BRIEF: " + s.prebrief;
    String learned = Prefs.s(c, "learned", ""); if (!learned.isEmpty()) sys += "\n\nWhat helps Calton: " + learned;
    String u = "Previous state: topic=\"" + topic + "\" confidence=" + String.format(Locale.US, "%.2f", confidence) + " open_question=\"" + openQuestion + "\"\n\nConversation so far (oldest first, live speech recognition, may have errors):\n" + String.join("\n", window);
    return new JSONArray().put(Learn.msg("system", sys)).put(Learn.msg("user", u));
  }
  /** Apply the model's state update and run the final coach gate. Returns text to whisper, or null. */
  String gate(JSONObject j, long now) {
    String newTopic = j.optString("topic", topic);
    if (!similar(newTopic, topic)) { topicSince = now; turnsSinceTopic = 0; }
    topic = newTopic; confidence = j.optDouble("confidence", 0); openQuestion = j.optString("open_question", "");
    String say = j.optString("say", "SKIP").trim(), kind = j.optString("kind", "none");
    if (say.isEmpty() || say.toUpperCase(Locale.US).startsWith("SKIP") || "none".equals(kind)) return null;
    if (j.optBoolean("mid_topic", false) && !"fact_error".equals(kind)) return null;      // never interrupt a point being made
    double need = "fact_error".equals(kind) ? 0.55 : "answer".equals(kind) ? 0.6 : 0.75;    // value-adds need the most certainty
    if (confidence < need) return null;
    lastCoachAt = now; return say;
  }
  static boolean similar(String a, String b) {
    Set<String> x = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.US).split("\\W+"))), y = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.US).split("\\W+")));
    int hit = 0; for (String w : x) if (w.length() > 3 && y.contains(w)) hit++; return hit >= 2;
  }
  static String commandPrompt(Db.Session s, Brain b) {
    String now = new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a z", Locale.US).format(new Date());
    return "You are javis, Calton's assistant in his earbud. Calton just spoke to YOU directly; answer his request now. Calton lives in Guyana and speaks English. "
      + "Local time on his phone: " + now + ". Be warm, sharp, plain words; lead with the answer; one or two short sentences. No filler. "
      + (b.topic.isEmpty() ? "" : "Current conversation topic: " + b.topic + ". ")
      + (s.prebrief == null || s.prebrief.isEmpty() ? "" : "Pre-brief: " + s.prebrief + ". ")
      + "Recent conversation for context:\n" + String.join("\n", b.window.subList(Math.max(0, b.window.size() - 10), b.window.size()));
  }
}
