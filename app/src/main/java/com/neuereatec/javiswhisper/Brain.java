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

  // ---- 0.1.5 room context: the setting changes how often and what javis whispers ----
  static final String[] SETTINGS = {"negotiation", "sale", "meeting", "call", "casual", "family", "street"};
  static final int[] MIN_TURNS = {1, 1, 2, 1, 2, 3, 1}; static final int[] COOLDOWN = {10, 10, 12, 12, 12, 30, 8};
  static final double[] VALUE_CONF = {0.45, 0.45, 0.5, 0.5, 0.5, 0.65, 0.45};
  static final String[] FOCUS = {"numbers, concessions, anchors, deadlines, points they dodged", "objections, the next step, price facts", "facts, decisions, who owns what, open items",
    "like a meeting but shorter lines", "only clear factual errors or a direct question Calton can't answer", "nearly silent – only if Calton is clearly stuck", "answer passers-by quickly"};
  String setting = "casual", modelSetting = ""; int modelAgree; String override = "auto";
  static int si(String s) { for (int i = 0; i < SETTINGS.length; i++) if (SETTINGS[i].equals(s)) return i; return 4; }
  String settingNow() { return "auto".equals(override) || override.isEmpty() ? setting : override; }
  /** Local prior from pre-brief, mode and what's being said; the model can refine it (2 agreeing calls). */
  void priorSetting(String prebrief, String mode, boolean inCall) {
    if ("street".equals(mode)) { setting = "street"; return; }
    if (inCall) { setting = "call"; return; }
    String t = ((prebrief == null ? "" : prebrief) + " " + String.join(" ", window.subList(Math.max(0, window.size() - 12), window.size()))).toLowerCase(Locale.US);
    int neg = count(t, "price|offer|deal|discount|rate|loan|negotiat|counter|lowest|best you can|terms|deposit|\\$|gyd|thousand|million");
    int sale = count(t, "buy|sell|customer|warranty|quote|order|deliver|stock|model");
    int meet = count(t, "meeting|agenda|project|deadline|team|report|action item|minutes|update");
    int fam = count(t, "mom|mum|dad|kids|auntie|uncle|dinner|family|granny|cousin|wife|husband");
    int max = Math.max(Math.max(neg, sale), Math.max(meet, fam)); if (!modelSetting.isEmpty() && modelAgree >= 2) { setting = modelSetting; return; }
    setting = max < 2 ? "casual" : max == neg ? "negotiation" : max == sale ? "sale" : max == meet ? "meeting" : "family";
  }
  static int count(String t, String re) { java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(t); int n = 0; while (m.find()) n++; return n; }

  // ---- 0.1.5 adaptive pause: slow talkers get a longer beat before javis decides ----
  double themGapEma = 900; long lastThemStart;
  long pauseMs() { return Math.max(700, Math.min(1800, Math.round(themGapEma * 1.3))); }
  String personCard = "", goal = ""; boolean hasBrief;
  // ---- 0.1.6 prosody: each speaker's own baseline, so cues are RELATIVE ("louder than he usually is"), never absolute guesses ----
  final Map<String, double[]> base = new HashMap<>(); // tag -> pitch, loudDb, rate(words/s), n
  volatile long overlapAt; long lastMeEnd;
  String prosody(String who, String text, Proximity.Feat f) {
    List<String> r = new ArrayList<>(); int words = text.trim().split("\\s+").length; double rate = f.voicedSec > 0.6 ? words / f.voicedSec : 0;
    double[] b = base.get(who);
    if (b != null && b[3] >= 3) {
      if (f.pitch > 0 && b[0] > 0 && f.pitch > b[0] * 1.15) r.add("higher pitch than usual"); else if (f.pitch > 0 && b[0] > 0 && f.pitch < b[0] * 0.88) r.add("lower, flatter voice");
      if (f.loudDb > b[1] + 4) r.add("louder"); else if (f.loudDb > 0 && f.loudDb < b[1] - 5) r.add("quieter");
      if (rate > 0 && b[2] > 0 && rate > b[2] * 1.3) r.add("fast"); else if (rate > 0 && b[2] > 0 && rate < b[2] * 0.7) r.add("slow");
    }
    if (f.pitchEnd > 1.12 && !text.trim().endsWith("?")) r.add("rising pitch at the end");
    if (b == null) b = new double[]{0, 0, 0, 0}; double a = b[3] < 5 ? 1.0 / (b[3] + 1) : 0.15;
    if (f.pitch > 0) b[0] = b[0] <= 0 ? f.pitch : b[0] + a * (f.pitch - b[0]); if (f.loudDb > 0) b[1] = b[1] <= 0 ? f.loudDb : b[1] + a * (f.loudDb - b[1]); if (rate > 0) b[2] = b[2] <= 0 ? rate : b[2] + a * (rate - b[2]);
    b[3]++; base.put(who, b);
    return String.join(", ", r);
  }
  /** Voice-and-pause stand-in for body language: how quickly someone answered. */
  String timingCue(String spk, long startWall) {
    if ("Them".equals(spk) && lastMeEnd > 0 && "Me".equals(lastSpk)) { long g = startWall - lastMeEnd; if (g > 2500 && g < 12000) return "long pause before answering"; if (g >= 0 && g < 250) return "answered instantly"; }
    return "";
  }
  String lastTone = "", lastMood = "";

  // ---- conversation membership: is Calton actually talking WITH this far voice? ----
  static final Set<String> STOP = new HashSet<>(Arrays.asList("the","a","an","and","or","but","is","are","was","were","be","to","of","in","on","at","for","with","that","this","it","its","i","you","we","they","he","she","me","my","your","our","do","does","did","so","just","like","yeah","yes","no","ok","okay","what","how","why","when","where","who","there","here","have","has","had","not","can","will","would","could","about","then","than","too","very","really","oh","um","uh","well","know","think","get","got"));
  double[] partner;                 // far voice attached as Calton's partner: snrDb, modDb, lowRatio, hfRatio (EMA)
  long engagedAt; String lastThemText = ""; long lastThemEnd; double[] lastThemFeat; boolean lastThemPartner;
  static Set<String> words(String t) { Set<String> r = new HashSet<>(); for (String w : t.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2 && !STOP.contains(w)) r.add(w); return r; }
  static double[] fv(Proximity.Feat f) { return new double[]{f.snrDb, f.modDb, f.lowRatio, f.hfRatio, f.pitch}; }
  /** 0..1 how well a far voice matches the attached partner (coarse – distance cues only). */
  double partnerMatch(double[] v) {
    if (partner == null) return 0.5;
    double d = Math.abs(v[0] - partner[0]) / 6 + Math.abs(v[1] - partner[1]) / 6 + Math.abs(v[2] - partner[2]) / Math.max(partner[2], 0.05) + Math.abs(v[3] - partner[3]) / Math.max(partner[3], 0.05);
    if (v.length > 4 && partner.length > 4 && v[4] > 0 && partner[4] > 0) d += Math.abs(Math.log(v[4] / partner[4])) / 0.15; // 0.1.6: pitch separates two far voices that sit at the same distance
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
    if (lastThemFeat != null) { if (partner == null) partner = lastThemFeat.clone(); else for (int i = 0; i < Math.min(partner.length, lastThemFeat.length); i++) if (i < 4 || (lastThemFeat[i] > 0 && partner[i] > 0)) partner[i] += 0.3 * (lastThemFeat[i] - partner[i]); else if (partner[i] <= 0) partner[i] = lastThemFeat[i]; }
    return hit >= 1 ? "shared words" : question ? "answered a question" : "topic match";
  }

  void heard(String spk, String text, long startWall, long endWall, Proximity.Feat f) { heard(spk, text, startWall, endWall, f, ""); }
  void heard(String spk, String text, long startWall, long endWall, Proximity.Feat f, String cues) {
    String tag = spk;
    if ("Them".equals(spk) && "Them".equals(lastSpk) && lastThemEnd > 0) { long g = startWall - lastThemEnd; if (g > 150 && g < 4000) themGapEma += 0.3 * (g * 1.0 - themGapEma); }
    if ("Them".equals(spk)) { double[] v = fv(f); double pm = partnerMatch(v); lastThemPartner = partner == null || pm >= 0.5;
      tag = partner == null ? "Them" : lastThemPartner ? "Partner" : "Other"; lastThemText = text; lastThemEnd = endWall; lastThemFeat = v; }
    else if ("Me".equals(spk)) { String why = checkMembership(text, startWall, 0); if (why != null) WhisperService.log("🤝 Calton is in this conversation (" + why + ")"); }
    window.add("[" + tag + (cues == null || cues.isEmpty() ? "" : " · " + cues) + "] " + text); while (window.size() > 40) window.remove(0);
    if ("Me".equals(spk)) { lastMeAt = System.currentTimeMillis(); lastMeEnd = endWall; }
    if (!spk.equals(lastSpk)) speakerTurns++; lastSpk = spk; turnsSinceTopic++;
  }
  /** Cheap pre-gate before spending an LLM call. */
  String preGate(long now, boolean calTalking) {
    if (calTalking) return "Calton is talking";
    if (now - overlapAt < 3000) return "people are talking over each other – waiting";
    if (!"Them".equals(lastSpk)) return "Me".equals(lastSpk) ? "last turn was Calton" : "unsure who spoke – waiting it out";
    // 0.1.7: with only one far voice (no partner linked yet), one exchange with Calton is enough – or a pre-brief says he's in a conversation
    boolean oneVoiceExchange = partner == null && lastMeAt > 0 && (now - lastMeAt < 60_000 || (hasBrief && now - lastMeAt < 180_000));
    if (!engaged(now) && !oneVoiceExchange) return "background – Calton hasn't spoken with this voice yet";
    if (!lastThemPartner) return "a different far voice (not his partner) – waiting it out";
    int k = si(settingNow());
    if (now - lastCoachAt < COOLDOWN[k] * 1000L) return "cooldown (" + settingNow() + ")";
    boolean enough = speakerTurns >= MIN_TURNS[k] || window.size() >= MIN_TURNS[k];
    if (!enough) return "still listening (" + window.size() + " turns)";
    return null;
  }
  JSONArray assessMessages(Context c, Db.Session s) throws JSONException {
    // 0.1.7: compact prompt (~40% shorter than 0.1.6) – suggestions only, never questions back to Calton
    String sys = "You are javis – a friend in Calton's ear who helps him make good decisions in the conversation around him (he's in Guyana, speaks English). "
      + "Return ONLY compact JSON: {\"topic\":\"<best guess, 3-8 words – guess even from one line>\",\"confidence\":0..1,\"open_question\":\"<unresolved point or ''>\",\"mid_topic\":true|false,"
      + "\"calton_engaged\":0..1,\"kind\":\"recommend\"|\"risk\"|\"better_option\"|\"next_step\"|\"fact\"|\"answer\"|\"fact_error\"|\"none\",\"say\":\"<≤12 words or SKIP>\",\"setting\":\"negotiation|sale|meeting|call|casual|family|street\",\"setting_conf\":0..1,"
      + "\"tone\":\"literal|joking|sarcastic\",\"their_mood\":\"<1-3 words or ''>\",\"partner_name\":\"<name if clearly said or ''>\",\"partner_name_conf\":0..1}\n"
      + "Think: what decision is Calton facing right now, and what would a sharp friend whisper to help him choose well? Whisper ONE short practical suggestion:\n"
      + "- recommend: what to do (\"Take the 5 kVA – the 3 kVA won't run your AC.\")\n- risk: a red flag (\"No receipt means no warranty – get it written.\")\n"
      + "- better_option: a better deal or choice (\"Offer G$260k cash, delivery included.\")\n- next_step: what to say or ask for next (\"Ask for the warranty in writing.\")\n"
      + "- fact: a key fact he needs; fact_error: correct a wrong fact that matters; answer: they asked him something → the answer or exact words.\n"
      + "Rules: help early – one exchange is enough. Statements or imperatives only; NEVER a question to Calton, never ask what he means or wants. If unsure or nothing helps his decision, SKIP. Never invent facts or numbers. "
      + "Never correct a joke or sarcasm. Cues in [tags] (hesitant, louder, fast, long pause, talked over) are hints from mic sound, not facts; you can't see faces. Plain warm words, contractions, lead with the point.\n"
      + "[Me]=Calton, [Them]/[Partner]=the other person, [Other]=a different far voice, [Unsure]=unclear. Speech recognition may have errors.\n"
      + "SETTING: " + settingNow() + " – focus on " + FOCUS[si(settingNow())] + ". " + GUYANA_SHORT;
    if (s.prebrief != null && !s.prebrief.isEmpty()) sys += "\nPRE-BRIEF (start with this context): " + People.clip(s.prebrief, 300);
    if (!goal.isEmpty()) sys += "\nCALTON'S GOAL: " + goal + " – steer him toward it.";
    if (!personCard.isEmpty()) sys += "\nTALKING WITH: " + People.clip(personCard, 400);
    String learned = Prefs.s(c, "learned", ""); if (!learned.isEmpty()) sys += "\nWhat helps Calton: " + People.clip(learned, 250);
    String u = "Previous state: topic=\"" + topic + "\" confidence=" + String.format(Locale.US, "%.2f", confidence) + " open_question=\"" + openQuestion + "\"\n\nConversation so far (oldest first, live speech recognition, may have errors):\n" + String.join("\n", window.subList(Math.max(0, window.size() - 16), window.size()));
    return new JSONArray().put(Learn.msg("system", sys)).put(Learn.msg("user", u));
  }
  /** Apply the model's state update and run the final coach gate. Returns text to whisper, or null. */
  String gate(JSONObject j, long now) {
    String newTopic = j.optString("topic", topic);
    if (!similar(newTopic, topic)) { topicSince = now; turnsSinceTopic = 0; }
    String ms = j.optString("setting", ""); if (si(ms) != 4 || "casual".equals(ms)) { if (ms.equals(modelSetting)) modelAgree++; else { modelSetting = j.optDouble("setting_conf", 0) >= 0.6 ? ms : ""; modelAgree = modelSetting.isEmpty() ? 0 : 1; } }
    lastTone = j.optString("tone", ""); lastMood = j.optString("their_mood", "");
    topic = newTopic; confidence = j.optDouble("confidence", 0); openQuestion = j.optString("open_question", "");
    if (j.optDouble("calton_engaged", 1) < 0.25) return null;   // model thinks he isn't really in this conversation
    String say = j.optString("say", "SKIP").trim(), kind = j.optString("kind", "none");
    if (say.isEmpty() || say.toUpperCase(Locale.US).startsWith("SKIP") || "none".equals(kind)) return null;
    // 0.1.7: the pause timer already waits for them to finish; mid_topic only holds back weak (low-confidence) suggestions
    if (j.optBoolean("mid_topic", false) && confidence < 0.6 && !"fact_error".equals(kind) && !"risk".equals(kind)) return null;
    if ("fact_error".equals(kind) && ("joking".equals(lastTone) || "sarcastic".equals(lastTone))) return null; // never correct a joke      // never interrupt a point being made
    if (isQuestionToCalton(say)) { WhisperService.log("(dropped a question: " + say + ")"); return null; }
    double need = "fact_error".equals(kind) || "answer".equals(kind) || "risk".equals(kind) ? 0.45 : VALUE_CONF[si(settingNow())];    // value-adds need the most certainty
    if (confidence < need) return null;
    lastCoachAt = now; return say;
  }
  /** 0.1.7 post-filter: javis gives suggestions, never questions back to Calton. A quoted line for him to ask is fine ("Ask: 'Is it in writing?'"). */
  static boolean isQuestionToCalton(String say) {
    String t = say.trim(); if (t.isEmpty()) return false;
    String unq = t.replaceAll("[\"“”'‘’][^\"“”]*[\"“”]", "").trim();
    if (t.matches("(?is)^(ask|say|try|tell)\\b.*") && !unq.endsWith("?")) return false;
    return unq.endsWith("?") || unq.matches("(?is)^(do|did|does|are|is|was|were|can|could|would|should|will|have|has|what|why|how|which|who|when|where|want|need)\\b.*\\byou\\b.*");
  }
  static boolean similar(String a, String b) {
    Set<String> x = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.US).split("\\W+"))), y = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.US).split("\\W+")));
    int hit = 0; for (String w : x) if (w.length() > 3 && y.contains(w)) hit++; return hit >= 2;
  }
  static final String GUYANA_SHORT = "Guyana: money in GYD (~G$209/US$1); understand Creolese (\"wha yuh seh\", \"just now\"=later); greetings and courtesy matter.";
  static final String GUYANA = "LOCAL CONTEXT (Guyana): money is Guyana dollars (GYD, ~G$209 per US$1 – say 'about'); places like Georgetown, Stabroek Market, Berbice, Linden, Essequibo, Bartica, Lethem, Diamond, Providence; "
    + "people may speak Guyanese Creolese (e.g. 'wha yuh seh' = what did you say, 'just now' = later, 'me ah come' = I'm coming, 'lime' = hang out, 'gaff' = chat) – understand it, answer in plain English unless Calton asks. "
    + "Courtesy matters: greetings first, 'Auntie/Uncle' for elders, don't rush small talk at the start.\n";
  static String commandPrompt(Db.Session s, Brain b, String memory) {
    return commandPrompt(s, b) + (memory == null || memory.isEmpty() ? "" : "\nFrom javis's memory (people and past conversations):\n" + memory) + "\n" + GUYANA;
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
