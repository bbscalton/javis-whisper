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

  // ---- conversation membership: is Calton actually talking WITH this far voice? ----
  static final Set<String> STOP = new HashSet<>(Arrays.asList("the","a","an","and","or","but","is","are","was","were","be","to","of","in","on","at","for","with","that","this","it","its","i","you","we","they","he","she","me","my","your","our","do","does","did","so","just","like","yeah","yes","no","ok","okay","what","how","why","when","where","who","there","here","have","has","had","not","can","will","would","could","about","then","than","too","very","really","oh","um","uh","well","know","think","get","got"));
  double[] partner;                 // far voice attached as Calton's partner: snrDb, modDb, lowRatio, hfRatio (EMA)
  long engagedAt; String lastThemText = ""; long lastThemEnd; double[] lastThemFeat; boolean lastThemPartner;
  static Set<String> words(String t) { Set<String> r = new HashSet<>(); for (String w : t.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2 && !STOP.contains(w)) r.add(w); return r; }
  static double[] fv(Proximity.Feat f) { return new double[]{f.snrDb, f.modDb, f.lowRatio, f.hfRatio}; }
  /** 0..1 how well a far voice matches the attached partner (coarse – distance cues only). */
  double partnerMatch(double[] v) {
    if (partner == null) return 0.5;
    double d = Math.abs(v[0] - partner[0]) / 6 + Math.abs(v[1] - partner[1]) / 6 + Math.abs(v[2] - partner[2]) / Math.max(partner[2], 0.05) + Math.abs(v[3] - partner[3]) / Math.max(partner[3], 0.05);
    return 1 / (1 + Math.exp((d - 1.6) / 0.4));
  }
  boolean engaged(long now) { return engagedAt > 0 && now - engagedAt < 180_000; }
  /** Calton's reply right after a far turn, on the same topic → he's in that conversation; attach that voice as partner. */
  String checkMembership(String text, long startWall, double llmTopical) {
    if (lastThemEnd == 0 || startWall - lastThemEnd > 6000 || startWall < lastThemEnd - 500) return null; // turn-taking timing
    Set<String> a = words(lastThemText), b = words(text); int hit = 0; for (String w : b) if (a.contains(w)) hit++;
    boolean question = lastThemText.trim().endsWith("?"), shortReply = b.size() <= 3;
    boolean topical = hit >= 1 || (question && shortReply) || llmTopical >= 0.6;
    if (!topical) return null;
    engagedAt = System.currentTimeMillis();
    if (lastThemFeat != null) { if (partner == null) partner = lastThemFeat.clone(); else for (int i = 0; i < 4; i++) partner[i] += 0.3 * (lastThemFeat[i] - partner[i]); }
    return hit >= 1 ? "shared words" : question ? "answered a question" : "topic match";
  }

  void heard(String spk, String text, long startWall, long endWall, Proximity.Feat f) {
    String tag = spk;
    if ("Them".equals(spk)) { double[] v = fv(f); double pm = partnerMatch(v); lastThemPartner = partner == null || pm >= 0.5;
      tag = partner == null ? "Them" : lastThemPartner ? "Partner" : "Other"; lastThemText = text; lastThemEnd = endWall; lastThemFeat = v; }
    else if ("Me".equals(spk)) { String why = checkMembership(text, startWall, 0); if (why != null) WhisperService.log("🤝 Calton is in this conversation (" + why + ")"); }
    window.add("[" + tag + "] " + text); while (window.size() > 40) window.remove(0);
    if ("Me".equals(spk)) lastMeAt = System.currentTimeMillis();
    if (!spk.equals(lastSpk)) speakerTurns++; lastSpk = spk; turnsSinceTopic++;
  }
  /** Cheap pre-gate before spending an LLM call. */
  String preGate(long now, boolean calTalking) {
    if (calTalking) return "Calton is talking";
    if (!"Them".equals(lastSpk)) return "Me".equals(lastSpk) ? "last turn was Calton" : "unsure who spoke – waiting it out";
    if (!engaged(now)) return "background – Calton hasn't replied to this voice on-topic yet";
    if (!lastThemPartner) return "a different far voice (not his partner) – waiting it out";
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
      + "\"calton_engaged\":0..1 (is Calton actually talking with the [Partner]/[Them] voice, replying on topic?),\"kind\":\"fact_error\"|\"answer\"|\"value\"|\"none\",\"say\":\"<one sentence ≤15 words for Calton's earbud, or SKIP>\"}\n\n"
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
    if (j.optDouble("calton_engaged", 1) < 0.4) return null;   // model thinks he isn't really in this conversation
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
