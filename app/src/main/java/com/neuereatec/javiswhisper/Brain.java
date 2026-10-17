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
  static final int[] MIN_TURNS = {1, 1, 2, 1, 2, 3, 1}; static final int[] COOLDOWN = {10, 10, 12, 12, 12, 30, 10};
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
  volatile long pauseMin = 450, pauseMax = 1000; // 0.1.11: Settings-backed (was 700/1800)
  long pauseMs() { return Math.max(pauseMin, Math.min(Math.max(pauseMin, pauseMax), Math.round(themGapEma * 1.3))); }
  String personCard = "", goal = ""; boolean hasBrief; volatile boolean meSeen, monologue;
  // ---- 0.1.6 prosody: each speaker's own baseline, so cues are RELATIVE ("louder than he usually is"), never absolute guesses ----
  final Map<String, double[]> base = new HashMap<>(); // tag -> pitch, loudDb, rate(words/s), n
  volatile long overlapAt; long lastMeEnd;
  volatile boolean singleOwnerLock; // true when voice-lock is on and only the owner is tracked – overlap is meaningless
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
    else if ("Unsure".equals(spk)) { lastThemText = text; lastThemEnd = endWall; if (partner == null) lastThemPartner = true; } // 0.1.19: an unsure voice can be the other person
    else if ("Me".equals(spk)) { String why = checkMembership(text, startWall, 0); if (why != null) WhisperService.log("🤝 Calton is in this conversation (" + why + ")"); }
    if ("Me".equals(spk)) quickLabel(text);
    String wl = "[" + tag + (cues == null || cues.isEmpty() ? "" : " · " + cues) + "] " + text; window.add(wl); while (window.size() > 40) { restored.remove(window.get(0)); window.remove(0); }
    trackTopic(spk, text);
    if (chunkLine(wl, text, startWall)) { sinceShift = 0; WhisperService.log("(✂ new topic chunk – content shifted)"); }
    if ("Me".equals(spk)) { lastMeAt = System.currentTimeMillis(); lastMeEnd = endWall; }
    if (!spk.equals(lastSpk)) speakerTurns++; lastSpk = spk; turnsSinceTopic++;
  }
  /** 0.1.15 readiness meter (0–100): how much real context the coach has – turns, the other person's lines, Calton's lines, words, inferred topic. Heuristic. */
  synchronized int readiness() {
    staleCheck(System.currentTimeMillis());
    double them = 0, me = 0, words = 0, n = 0;
    int from = Math.max(0, window.size() - (sinceShift + 2)); // 0.1.15 decay: after a hard topic shift only the new lines (+2 bridge) count
    for (String l : window.subList(from, window.size())) { double w = restored.contains(l) ? RESTORED_W : 1; n += w; // 0.1.18: lines replayed after a restart count at half weight
      if (l.startsWith("[Them") || l.startsWith("[Partner") || l.startsWith("[Other")) them += w; else if (l.startsWith("[Unsure")) them += 0.5 * w; else if (l.startsWith("[Me")) me += w; int i = l.indexOf("] "); if (i > 0) words += w * l.substring(i + 2).trim().split("\\s+").length; }
    double s = 25 * Math.min(1, n / 5.0) + 25 * Math.min(1, them / 2.5) + 15 * Math.min(1, me / 1.5) + 15 * Math.min(1, words / 90.0) // 0.1.18 faster ramp (was 6 lines / 3 theirs / 2 his / 120 words)
      + 20 * (topic == null || topic.isEmpty() ? 0 : Math.max(0.3, Math.min(1, confidence)) * (chunks.isEmpty() || !chunks.get(chunks.size() - 1).topic.isEmpty() ? 1 : 0.5)); // 0.1.16: half credit until the current chunk has its own topic
    if (hasBrief) s = Math.max(s, 15 + s * 0.85); // a briefing gives a head start
    long idle = lastHeardAt == 0 ? 0 : System.currentTimeMillis() - lastHeardAt; if (idle > 60_000) s *= Math.max(0.4, 1 - 0.6 * (idle - 60_000) / 240_000.0); // stale context eases down
    if (noisy()) s *= 0.7; // talked-over turns = less usable signal
    return (int) Math.round(Math.max(0, Math.min(100, s)));
  }
  int sinceShift = 1000; long lastHeardAt; final ArrayDeque<Boolean> ovl = new ArrayDeque<>();
  synchronized void noteTurn(boolean overlap) { lastHeardAt = System.currentTimeMillis(); sinceShift++; ovl.addLast(overlap); while (ovl.size() > 6) ovl.removeFirst(); }
  synchronized boolean noisy() { int k = 0; for (boolean b : ovl) if (b) k++; return ovl.size() >= 3 && k * 2 >= ovl.size(); }
  // ---- 0.1.16 semantic chunking: the transcript is cut into topic-sized chunks (long pause, hard content shift, or size cap) ----
  static class Chunk { final List<String> lines = new ArrayList<>(); final Set<String> kw = new HashSet<>(); String topic = ""; int words; String why = ""; }
  final List<Chunk> chunks = new ArrayList<>(); long lastLineWall; int lowOverlapRun;
  static final Set<String> CHUNK_STOP = new HashSet<>(Arrays.asList("the","a","an","and","or","but","so","to","of","in","on","at","for","is","are","was","were","be","it","its","that","this","i","you","he","she","we","they","me","my","your","our","what","yeah","yes","no","okay","ok","just","like","do","did","have","has","not","with","about","there","here","then","well","um","uh","can","will","would","going","know","think","get","got"));
  static Set<String> kws(String t) { Set<String> s = new HashSet<>(); for (String w : t.toLowerCase(Locale.US).replaceAll("[^a-z0-9' ]", " ").split("\\s+")) if (w.length() > 2 && !CHUNK_STOP.contains(w)) s.add(w); return s; }
  /** returns true if a NEW chunk was opened because the content shifted hard. */
  synchronized boolean chunkLine(String line, String text, long startWall) {
    Set<String> k = kws(text); Chunk c = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1); String why = null;
    if (c == null) why = "start";
    else if (lastLineWall > 0 && startWall - lastLineWall > 8000) why = "long pause";
    else if (c.lines.size() >= 10 || c.words >= 180) why = "size";
    else if (c.lines.size() >= 3 && k.size() >= 3) { int inter = 0; for (String w : k) if (c.kw.contains(w)) inter++; lowOverlapRun = inter == 0 ? lowOverlapRun + 1 : 0; if (lowOverlapRun >= 2) why = "content shift"; }
    if (why != null) { Chunk n = new Chunk(); n.why = why; if (c != null && "content shift".equals(why) && c.lines.size() > 0) { n.lines.add(c.lines.get(c.lines.size() - 1)); c.lines.remove(c.lines.size() - 1); } chunks.add(n); while (chunks.size() > 8) chunks.remove(0); c = n; lowOverlapRun = 0; }
    c.lines.add(line); c.kw.addAll(k); c.words += text.split("\\s+").length; lastLineWall = startWall;
    return "content shift".equals(why);
  }
  synchronized String chunkedTranscript() { if (chunks.isEmpty()) return String.join("\n", window.subList(Math.max(0, window.size() - 16), window.size()));
    StringBuilder b = new StringBuilder(); int n = chunks.size();
    for (int i = Math.max(0, n - 4); i < n - 1; i++) { Chunk c = chunks.get(i); b.append("— earlier chunk ").append(i - n + 1).append(c.topic.isEmpty() ? "" : " (topic: " + c.topic + ")").append(" —\n").append(String.join("\n", c.lines.subList(Math.max(0, c.lines.size() - 3), c.lines.size()))).append("\n"); }
    Chunk cur = chunks.get(n - 1); b.append("— CURRENT chunk (judge the topic from this; opened by: ").append(cur.why).append(") —\n").append(String.join("\n", cur.lines)); return b.toString(); }
  synchronized void resetCtx() { restored.clear(); topicKw.clear(); label = ""; labelAt = 0; lastOnTopicAt = 0; chunks.clear(); lastLineWall = 0; lowOverlapRun = 0; sinceShift = 1000; lastHeardAt = 0; ovl.clear(); window.clear(); speakerTurns = 0; turnsSinceTopic = 0; topic = ""; confidence = 0; openQuestion = ""; lastSpk = ""; }
  /** Cheap pre-gate before spending an LLM call. */
  String preGate(long now, boolean calTalking) {
    if (calTalking) return "Calton is talking";
    if (!meSeen) return "voice not learned yet – waiting to hear Calton first";
    if (monologue) return "one voice talking on with no reply from Calton (video or speech) – background";
    // 0.1.20: with voice-lock on and only the owner tracked, "talking over" is a false positive (there is no second person)
    if (now - overlapAt < 3000 && !singleOwnerLock) return "people are talking over each other – waiting";
    if ("Me".equals(lastSpk) || lastSpk.isEmpty()) return "last turn was Calton"; // 0.1.19: Them and Unsure lines both open the coach
    // 0.1.7: with only one far voice (no partner linked yet), one exchange with Calton is enough – or a pre-brief says he's in a conversation
    boolean oneVoiceExchange = partner == null && lastMeAt > 0 && (now - lastMeAt < 60_000 || (hasBrief && now - lastMeAt < 180_000));
    if (!engaged(now) && !oneVoiceExchange) return "background – Calton hasn't spoken with this voice yet";
    if (!lastThemPartner) return "a different far voice (not his partner) – waiting it out";
    int k = si(settingNow());
    if (now - lastCoachAt < COOLDOWN[k] * 1000L) return "cooldown (" + settingNow() + ")";
    boolean enough = speakerTurns >= MIN_TURNS[k] || window.size() >= MIN_TURNS[k];
    if (!enough) return "still listening (" + window.size() + " turns)";
    int rd = readiness(); if (rd < PRE_READY) return "building context (" + rd + "%)"; // 0.1.18: don't spend a Grok call on almost no context
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
      + Guard.clock() + " " + Guard.RULES + "\n"
      + "SETTING: " + settingNow() + " – focus on " + FOCUS[si(settingNow())] + ". " + GUYANA_SHORT;
    if (s.prebrief != null && !s.prebrief.isEmpty()) sys += "\nPRE-BRIEF (start with this context): " + People.clip(s.prebrief, 300);
    if (!background.isEmpty()) sys += "\nBACKGROUND (an earlier session – may be unrelated, never assume it's the current topic): " + People.clip(background, 300);
    if (!goal.isEmpty()) sys += "\nCALTON'S GOAL: " + goal + " – steer him toward it.";
    if (!personCard.isEmpty()) sys += "\nTALKING WITH: " + People.clip(personCard, 400);
    String learned = Prefs.s(c, "learned", ""); if (!learned.isEmpty()) sys += "\nWhat helps Calton: " + People.clip(learned, 250);
    String u = "Previous state: topic=\"" + topic + "\" confidence=" + String.format(Locale.US, "%.2f", confidence) + " open_question=\"" + openQuestion + "\"\n\nConversation so far, split into topic chunks (oldest first, live speech recognition, may have errors). If the CURRENT chunk is on a different subject than the previous state, report the NEW topic:\n" + chunkedTranscript();
    return new JSONArray().put(Learn.msg("system", sys)).put(Learn.msg("user", u));
  }
  /** Apply the model's state update and run the final coach gate. Returns text to whisper, or null. */
  String gate(JSONObject j, long now) {
    String newTopic = j.optString("topic", topic);
    if (!label.isEmpty() && now - labelAt < LABEL_MS && !newTopic.toLowerCase(Locale.US).contains(label.split(" ")[0])) newTopic = label + (newTopic.isEmpty() ? "" : " · " + newTopic); // 0.1.18: Calton's own label stays on top
    if (!similar(newTopic, topic)) { topicSince = now; turnsSinceTopic = 0; if (topic != null && !topic.isEmpty()) sinceShift = 0; } // 0.1.15: hard topic shift → meter drops and rebuilds
    String ms = j.optString("setting", ""); if (si(ms) != 4 || "casual".equals(ms)) { if (ms.equals(modelSetting)) modelAgree++; else { modelSetting = j.optDouble("setting_conf", 0) >= 0.6 ? ms : ""; modelAgree = modelSetting.isEmpty() ? 0 : 1; } }
    lastTone = j.optString("tone", ""); lastMood = j.optString("their_mood", "");
    boolean shifted = !similar(newTopic, topic); topic = newTopic; double mc = j.optDouble("confidence", 0); confidence = shifted ? mc : Math.max(mc, confidence * 0.9); // 0.1.18: keep locally earned confidence on the same topic
    synchronized (this) { if (shifted || topicKw.isEmpty()) { topicKw.clear(); topicKw.addAll(stems(kws(newTopic))); if (!chunks.isEmpty()) topicKw.addAll(stems(chunks.get(chunks.size() - 1).kw)); } lastOnTopicAt = now; }
    synchronized (this) { if (!chunks.isEmpty()) chunks.get(chunks.size() - 1).topic = newTopic; } try { WhisperService.readiness = readiness(); } catch (Throwable ignored) {} openQuestion = j.optString("open_question", ""); changed();
    if (j.optDouble("calton_engaged", 1) < 0.25) return null;   // model thinks he isn't really in this conversation
    String say = j.optString("say", "SKIP").trim(), kind = j.optString("kind", "none");
    if (say.isEmpty() || say.toUpperCase(Locale.US).startsWith("SKIP") || "none".equals(kind)) return null;
    // 0.1.7: the pause timer already waits for them to finish; mid_topic only holds back weak (low-confidence) suggestions
    if (j.optBoolean("mid_topic", false) && confidence < 0.6 && !"fact_error".equals(kind) && !"risk".equals(kind)) return null;
    if ("fact_error".equals(kind) && ("joking".equals(lastTone) || "sarcastic".equals(lastTone))) return null; // never correct a joke      // never interrupt a point being made
    if (isQuestionToCalton(say)) { WhisperService.log("(dropped a question: " + say + ")"); return null; }
    double need = "fact_error".equals(kind) || "answer".equals(kind) || "risk".equals(kind) ? 0.45 : VALUE_CONF[si(settingNow())];    // value-adds need the most certainty
    if (confidence < need) return null;
    boolean urgent = "fact_error".equals(kind) || "answer".equals(kind) || "risk".equals(kind);
    int rd = readiness(); if (rd < (urgent ? URGENT_READY : READY)) { WhisperService.log("(held: context " + rd + "% – needs " + (urgent ? URGENT_READY : READY) + "%)"); return null; } // 0.1.18 readiness gate
    if (!"answer".equals(kind) && !aligned(lastThemText)) { WhisperService.log("(held: what they said doesn't match the topic – " + topic + ")"); return null; } // 0.1.18 alignment gate
    lastCoachAt = now; return say;
  }
  // ================= 0.1.18 =================
  static final double RESTORED_W = 0.5; static final int READY = 55, URGENT_READY = 35, PRE_READY = 30; static final long LABEL_MS = 15 * 60_000L, DRIFT_DECAY_MS = 5 * 60_000L, DRIFT_DROP_MS = 10 * 60_000L;
  final Set<String> restored = Collections.newSetFromMap(new IdentityHashMap<>()); final Set<String> topicKw = new HashSet<>();
  volatile String background = ""; // 0.1.19 earlier-session summary, background only
  String label = ""; long labelAt, lastOnTopicAt; double driftBase = -1; volatile Runnable onState;
  void changed() { Runnable r = onState; if (r != null) try { r.run(); } catch (Throwable ignored) {} }
  static String stem(String w) { w = w.toLowerCase(Locale.US); if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y"; if (w.length() > 4 && (w.endsWith("es") && (w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")))) return w.substring(0, w.length() - 2); if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1); return w; }
  static Set<String> stems(Collection<String> ws) { Set<String> r = new HashSet<>(); for (String w : ws) r.add(stem(w)); return r; }
  /** (b) quick labels: Calton saying "family talk", "network talk", "business talk"… sets topic + setting locally, instantly (no Grok round trip). */
  static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("(?i)\\b(network(?:ing)?|family|business|work|sales?|money|price|deal|negotiation|meeting|project|school|church|health|doctor|medical|car|solar|cctv|camera|tech|computer|phone|casual|personal|friend|legal|bank|loan|travel|food|sports?|cricket|politics|street)\\s+(?:talk|chat|conversation|matter|mode|stuff|time|discussion)\\b");
  static final Map<String, String[]> LABEL_MAP = new HashMap<>();
  static { String[][] m = {{"network","meeting","network wifi internet router signal fibre speed"},{"networking","meeting","network contact intro connection business"},{"family","family","family mom dad kids wife husband home dinner"},
      {"business","meeting","business client deal plan revenue customer"},{"work","meeting","work job project team deadline boss"},{"sale","sale","sale buy sell price customer order"},{"sales","sale","sale buy sell price customer order"},
      {"money","negotiation","money price cost pay cash loan"},{"price","negotiation","price cost offer discount pay"},{"deal","negotiation","deal offer price terms agree"},{"negotiation","negotiation","offer price terms counter deal"},
      {"meeting","meeting","meeting agenda decision action plan"},{"project","meeting","project plan deadline task team"},{"school","casual","school class teacher exam student"},{"church","casual","church service pastor prayer sunday"},
      {"health","casual","health doctor pain medicine clinic"},{"doctor","casual","doctor clinic medicine pain test"},{"medical","casual","medical doctor clinic medicine test"},{"car","sale","car vehicle engine part mechanic price"},
      {"solar","sale","solar panel inverter battery install power"},{"cctv","sale","cctv camera install dvr security"},{"camera","sale","camera cctv install security"},{"tech","meeting","tech computer software phone system"},
      {"computer","meeting","computer laptop software repair system"},{"phone","casual","phone call number line"},{"casual","casual",""},{"personal","casual",""},{"friend","casual","friend"},{"legal","meeting","legal lawyer contract court document"},
      {"bank","negotiation","bank loan account rate interest"},{"loan","negotiation","loan bank rate interest payment"},{"travel","casual","travel flight trip hotel ticket"},{"food","casual","food eat restaurant cook"},
      {"sport","casual","sport game match team"},{"sports","casual","sport game match team"},{"cricket","casual","cricket match team score"},{"politics","casual","politics government election minister"},{"street","street",""}};
    for (String[] e : m) LABEL_MAP.put(e[0], new String[]{e[1], e[2]}); }
  synchronized boolean quickLabel(String text) {
    java.util.regex.Matcher mt = LABEL.matcher(text == null ? "" : text); if (!mt.find()) return false;
    String key = mt.group(1).toLowerCase(Locale.US); String[] v = LABEL_MAP.get(key); if (v == null) return false;
    String nl = key + " talk"; long now = System.currentTimeMillis(); boolean shift = !nl.equals(label) && !topic.toLowerCase(Locale.US).contains(key);
    label = nl; labelAt = now; topic = nl; confidence = Math.max(shift ? 0 : confidence, 0.75); topicSince = now; turnsSinceTopic = 0; lastOnTopicAt = now; driftBase = -1;
    if (shift && !window.isEmpty()) sinceShift = 0;
    topicKw.clear(); topicKw.add(stem(key)); for (String w : v[1].split(" ")) if (!w.isEmpty()) topicKw.add(stem(w));
    setting = v[0]; modelSetting = v[0]; modelAgree = 2; // the local prior keeps it until the model agrees on something else twice
    if (!chunks.isEmpty()) chunks.get(chunks.size() - 1).topic = nl;
    WhisperService.log("🏷 Topic set by you: " + nl + " (" + v[0] + ")"); changed(); return true;
  }
  boolean onTopic(String text) { if (topicKw.isEmpty()) return false; for (String w : stems(kws(text))) if (topicKw.contains(w)) return true; return false; }
  /** (e)+(d): on-topic lines raise confidence (Calton's own lines ONLY when on topic); drift is tracked for stale-topic decay. */
  synchronized void trackTopic(String spk, String text) {
    if (topic == null || topic.isEmpty() || topicKw.isEmpty()) return; long now = System.currentTimeMillis();
    if (onTopic(text)) { lastOnTopicAt = now; driftBase = -1; double inc = "Me".equals(spk) ? 0.06 : "Unsure".equals(spk) ? 0.03 : 0.05; confidence = Math.min(0.95, confidence + inc);
      for (String w : stems(kws(text))) if (topicKw.size() < 60) topicKw.add(w); }
    staleCheck(now);
  }
  /** (d) stale topic: after 5 min with nothing on topic, confidence eases down; after 10 min the topic is dropped. */
  synchronized void staleCheck(long now) {
    if (topic == null || topic.isEmpty() || lastOnTopicAt == 0) return; long d = now - lastOnTopicAt; if (d < DRIFT_DECAY_MS) return;
    if (d >= DRIFT_DROP_MS) { WhisperService.log("(topic went stale – dropped \"" + topic + "\")"); topic = ""; confidence = 0; openQuestion = ""; label = ""; topicKw.clear(); lastOnTopicAt = 0; driftBase = -1; changed(); return; }
    if (driftBase < 0) driftBase = confidence; confidence = Math.min(confidence, driftBase * (1 - 0.7 * (d - DRIFT_DECAY_MS) / (double) (DRIFT_DROP_MS - DRIFT_DECAY_MS)));
  }
  /** (g) alignment: their words must touch the topic (or Calton's goal) before a value-add whisper. Short/empty lines pass. */
  synchronized boolean aligned(String themText) {
    Set<String> k = stems(kws(themText == null ? "" : themText)); if (k.size() < 3 || topicKw.isEmpty()) return true;
    Set<String> ref = new HashSet<>(topicKw); ref.addAll(stems(kws(goal))); for (String w : k) if (ref.contains(w)) return true; return false;
  }
  /** (f) silent context repair: a likely-misheard word is swapped for a close topic/conversation word (same first letter, edit distance 1–2, the heard word never seen before). */
  static final Set<String> COMMON = new HashSet<>(Arrays.asList("about","after","again","before","being","could","every","first","found","great","house","little","never","other","place","right","small","still","their","there","these","thing","think","three","under","water","where","which","while","world","would","years","price","prices","money","today","tomorrow","really","maybe","going","doing","saying","morning","evening","people","should","inverted","invested"));
  synchronized String repair(String text, Collection<String> extraVocab) {
    if (text == null || text.isEmpty()) return text;
    Map<String, Integer> vocab = new HashMap<>(); for (String w : topicKw) vocab.merge(w, 2, Integer::sum);
    for (String l : window) { int i = l.indexOf("] "); for (String w : kws(i > 0 ? l.substring(i + 2) : l)) vocab.merge(w, 1, Integer::sum); }
    if (extraVocab != null) for (String w : extraVocab) if (w != null && w.length() >= 4) vocab.merge(w.toLowerCase(Locale.US), 2, Integer::sum);
    StringBuilder out = new StringBuilder(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z']+|[^A-Za-z']+").matcher(text); boolean changed = false;
    while (m.find()) { String tok = m.group(), lw = tok.toLowerCase(Locale.US);
      if (lw.length() >= 5 && Character.isLetter(lw.charAt(0)) && !vocab.containsKey(lw) && !vocab.containsKey(stem(lw)) && !COMMON.contains(lw) && !STOP.contains(lw)) {
        String best = null; int bd = 99, bc = 0; int lim = lw.length() >= 8 ? 2 : 1;
        for (Map.Entry<String, Integer> e : vocab.entrySet()) { String v = e.getKey(); if (e.getValue() < 2 || v.length() < 5 || v.charAt(0) != lw.charAt(0) || Math.abs(v.length() - lw.length()) > lim) continue; int d = lev(lw, v); if (d <= lim && (d < bd || d == bd && e.getValue() > bc)) { best = v; bd = d; bc = e.getValue(); } }
        if (best != null) { tok = Character.isUpperCase(tok.charAt(0)) ? Character.toUpperCase(best.charAt(0)) + best.substring(1) : best; changed = true; } }
      out.append(tok); }
    return changed ? out.toString() : text;
  }
  static int lev(String a, String b) { int[] p = new int[b.length() + 1], c = new int[b.length() + 1]; for (int j = 0; j <= b.length(); j++) p[j] = j;
    for (int i = 1; i <= a.length(); i++) { c[0] = i; for (int j = 1; j <= b.length(); j++) c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)); int[] t = p; p = c; c = t; } return p[b.length()]; }
  /** (a) after a restart: replay recent saved turns into the window/chunks (marked restored → half weight in readiness). */
  synchronized int restore(List<Db.Turn> turns) {
    int n = 0; long last = 0;
    for (Db.Turn t : turns) { if (t == null || t.text == null || t.text.isEmpty()) continue; String k = t.kind == null ? "heard" : t.kind; if (!"heard".equals(k) && !"command".equals(k)) continue;
      String tag = "Me".equals(t.speaker) ? "Me" : "Unsure".equals(t.speaker) ? "Unsure" : "Them"; String wl = "[" + tag + "] " + t.text;
      window.add(wl); restored.add(wl); while (window.size() > 40) { restored.remove(window.get(0)); window.remove(0); } chunkLine(wl, t.text, t.ts); n++; last = Math.max(last, t.ts); }
    if (n > 0) { lastHeardAt = last; sinceShift = 1000; } return n;
  }
  /** (a) continuous snapshot of the topic/domain state (saved in sessions.ctx, throttled by the service). */
  synchronized JSONObject snapshot() throws JSONException {
    JSONArray ch = new JSONArray(); for (Chunk c : chunks) ch.put(c.topic);
    return new JSONObject().put("topic", topic).put("confidence", confidence).put("openQuestion", openQuestion).put("setting", setting).put("modelSetting", modelSetting).put("modelAgree", modelAgree)
      .put("topicSince", topicSince).put("lastOnTopicAt", lastOnTopicAt).put("label", label).put("labelAt", labelAt).put("topicKw", new JSONArray(topicKw)).put("chunkTopics", ch).put("goal", goal).put("saved", System.currentTimeMillis());
  }
  synchronized void applySnapshot(JSONObject j) {
    if (j == null) return; topic = j.optString("topic", topic); confidence = j.optDouble("confidence", confidence); openQuestion = j.optString("openQuestion", ""); setting = j.optString("setting", setting);
    modelSetting = j.optString("modelSetting", ""); modelAgree = j.optInt("modelAgree", 0); topicSince = j.optLong("topicSince", 0); lastOnTopicAt = j.optLong("lastOnTopicAt", 0); label = j.optString("label", ""); labelAt = j.optLong("labelAt", 0);
    if (goal.isEmpty()) goal = j.optString("goal", ""); topicKw.clear(); JSONArray k = j.optJSONArray("topicKw"); if (k != null) for (int i = 0; i < k.length(); i++) topicKw.add(k.optString(i));
    JSONArray ch = j.optJSONArray("chunkTopics"); if (ch != null) for (int i = 0; i < Math.min(ch.length(), chunks.size()); i++) { int ci = chunks.size() - Math.min(ch.length(), chunks.size()) + i, si = ch.length() - Math.min(ch.length(), chunks.size()) + i; if (chunks.get(ci).topic.isEmpty()) chunks.get(ci).topic = ch.optString(si); }
    if (!topic.isEmpty() && !chunks.isEmpty() && chunks.get(chunks.size() - 1).topic.isEmpty()) chunks.get(chunks.size() - 1).topic = topic;
    staleCheck(System.currentTimeMillis());
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
