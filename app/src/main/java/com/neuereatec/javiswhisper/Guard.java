package com.neuereatec.javiswhisper;
import java.util.*;
import java.util.regex.*;
/**
 * 0.1.9 guard rails (pure Java – the offline replay uses this exact class):
 * mishear normalisation, unclear-line detection, "Calton echoed a whisper", and the whisper post-filter
 * (no questions, no parroting, no self-repeat, no pushing a point Calton already used) for EVERY mode.
 */
public class Guard {
  // ---- STT hints (xAI keyterm) ----
  static final String[] KEYTERMS = {"Mounjaro", "Ozempic", "Zepbound", "Wegovy", "semaglutide", "tirzepatide", "cortisone", "insulin", "Georgetown", "Stabroek",
    "Berbice", "Linden", "Essequibo", "Bartica", "Lethem", "Demerara", "Guyana", "GYD", "Creolese", "Whisper", "Javis",
    "Calton", "Calton Andrew", "Miss Calton", "Mounjaro pen", "GLP-1", "Neuereatec"}; // 0.1.10: Calton (never Carlton)
  // ---- mishear normalisation (seen in Calton's 15 Oct upload) ----
  static final String[][] FIX = {
    {"(?i)\\b(mon ?jaro|mu ?jaro|man ?jaro|moun ?jaro|munjaro|mountain jaro)\\b", "Mounjaro"}, {"(?i)\\bwhat jara\\b", "Mounjaro"}, {"(?i)\\bis that mountain\\b", "is that Mounjaro"},
    {"(?i)\\b(olympic|ozempik|cinemic|osempic)\\b", "Ozempic"}, {"(?i)\\b(zepp? ?bound|zip ?bound)\\b", "Zepbound"},
    {"(?i)\\bmozambique and zimbabwe\\b", "Mounjaro and Zepbound"}, {"(?i)\\b(corazon|cortizone|cortisol shot)\\b", "cortisone"},
    {"(?i)\\bsema ?glue ?tide\\b", "semaglutide"}, {"(?i)\\btirzepa ?tide\\b", "tirzepatide"},
    // 0.1.10 from the 15 Oct uploads: more drug-name mishears
    {"(?i)\\b(mounjar|monjar|mujar|manjar)\\b", "Mounjaro"}, {"(?i)\\bmuj?aro\\b", "Mounjaro"}, {"(?i)\\b(a z word for truth)\\b", "a Z word (Zepbound)"},
    // 0.1.10 caller name: Calton (never Carlton). Carlton always; carbon only in a clear self-ID line.
    {"(?i)\\bmiss\\s+carlton\\b", "Miss Calton"}, {"\\bCarlton\\b", "Calton"}, {"\\bcarlton\\b", "Calton"}, {"\\bCARLTON\\b", "Calton"},
    {"(?i)\\b(this is|it'?s|i'?m|i am|my name is|name'?s|call me|miss|mister|mr\\.?)\\s+carbon\\b", "$1 Calton"},
    {"(?i)\\bcarbon andrew\\b", "Calton Andrew"}, {"(?i)\\bcarlton andrew\\b", "Calton Andrew"}};
  static String normalize(String t) { String r = t; for (String[] f : FIX) r = r.replaceAll(f[0], f[1]); return r; }

  /** 0.1.10: pure voice/mic-test lines – never coach, never invent people, never put in learned notes. */
  static final Pattern VOICE_TEST = Pattern.compile("(?i)\\b(testing( my)? (earbuds?|mic|microphone)|mic test|doing the mic test|test(ing)?,? (one|1),? (two|2)|one,? two,? three,? four,? five|can you hear me|learn my voice|how (would|do) you learn my voice|what do i have to say|hi whisper,? how are you|just to my ears|this is calton(,| doing| testing)|mode street|mode coach|mode translate)\\b");
  static boolean isVoiceTest(String t) { return t != null && VOICE_TEST.matcher(t).find(); }
  /** session looks like a test if most heard lines are test phrases / fragments */
  static boolean testSession(List<String> lines) {
    if (lines == null || lines.isEmpty()) return false; int test = 0, n = 0;
    for (String l : lines) { if (l == null || l.trim().isEmpty()) continue; n++; if (isVoiceTest(l) || unclear(l) != null) test++; }
    return n > 0 && (test * 2 >= n || n <= 3 && test >= 1);
  }
  // ---- lines that came out as another language or as fragments: wait them out, never repeat them ----
  static final Set<String> FOREIGN = new HashSet<>(Arrays.asList("dia", "menjana", "setakat", "mana", "jai", "buddha", "alam", "bikri", "kya", "hai", "nahi", "aur", "saya", "tidak", "anda", "akan", "untuk", "dengan", "yang", "ini", "itu", "ka", "ke", "ki"));
  static final Set<String> EN = new HashSet<>(Arrays.asList("the","a","and","is","are","you","i","to","it","what","of","in","that","this","for","my","me","we","do","have","yes","no","can","how","where","your","be","was","on","with","not","okay","ok","so","just","like","know","he","she","they","him","her","them","go","get","got","one","said","say","if","but","all","about","there","when","then","dey","deh","wha","nah","mek","dem","ah","gyal","bai","pickney","di","yuh","fuh"));
  /** null = fine, else the reason to wait it out. */
  static String unclear(String t) {
    String[] w = t.toLowerCase(Locale.US).replaceAll("[^a-z' ]", " ").trim().split("\\s+");
    if (w.length == 0 || w[0].isEmpty()) return "no words";
    int f = 0, en = 0; for (String x : w) { if (FOREIGN.contains(x)) f++; if (EN.contains(x)) en++; }
    if (f >= 2 && f * 2 >= w.length - en) return "came out as another language (probably Creolese misheard)";
    if (w.length <= 2 && !t.trim().endsWith("?")) return "fragment";
    return null;
  }
  static Set<String> words(String t) { Set<String> r = new HashSet<>(); for (String w : t.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2 && !Brain.STOP.contains(w)) r.add(w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w); return r; }
  static double overlap(String a, String b) { Set<String> x = words(a), y = words(b); if (x.isEmpty()) return 0; int h = 0; for (String w : x) if (y.contains(w)) h++; return (double) h / x.size(); }
  static int hits(String a, String b) { Set<String> x = words(a), y = words(b); int h = 0; for (String w : x) if (y.contains(w)) h++; return h; }

  // ---- whisper memory ----
  static class W { final String text; final long at; W(String t, long a) { text = t; at = a; } }
  final LinkedList<W> recent = new LinkedList<>(); String usedPoint = ""; long usedAt;
  void said(String w, long now) { recent.add(new W(w, now)); while (recent.size() > 6) recent.removeFirst(); }
  /** Heard line repeats a whisper from the last 20 s → it's Calton, using it. Returns that whisper or null. */
  String echoOf(String heard, long now) {
    for (Iterator<W> it = recent.descendingIterator(); it.hasNext(); ) { W w = it.next(); if (now - w.at > 20_000) break;
      if (hits(heard, w.text) >= 2 && overlap(heard, w.text) >= 0.4) { usedPoint = w.text; usedAt = now; return w.text; } }
    return null;
  }
  static final Pattern PROMPTY = Pattern.compile("(?i)\\b(then what|what'?s next|next\\?|finish (that|it|the|your)|keep going|go on|what do you see|what'?s the (hack|trick|full problem)|what'?s that mean)\\b");
  /** null = OK to whisper, else why it was dropped. heard = the line it answers. */
  String reject(String say, String heard, long now) {
    if (say == null || say.trim().isEmpty() || Brain.isQuestionToCalton(stripSay(say)) || PROMPTY.matcher(say).find() || say.trim().endsWith("?")) return "question / prompt back to Calton";
    String core = stripSay(say);
    if (core.contains("?") && !core.matches("(?is)^ask\\b.*")) return "question / prompt back to Calton";
    if (heard != null && overlap(core, heard) >= 0.6) return "just repeats what was said";
    int k = 0; for (Iterator<W> it = recent.descendingIterator(); it.hasNext() && k < 3; k++) { W w = it.next(); if (overlap(core, stripSay(w.text)) >= 0.6 || overlap(stripSay(w.text), core) >= 0.7) return "same point as a recent whisper"; }
    if (!usedPoint.isEmpty() && now - usedAt < 60_000 && overlap(core, stripSay(usedPoint)) >= 0.5) return "Calton already used that point";
    return null;
  }
  static String stripSay(String s) { return s.replaceAll("(?i)^\\s*say:?\\s*", "").replaceAll("[\"“”]", "").trim(); }

  // ---- video / one-sided talk: one far voice going on with no reply from Calton → background ----
  int farRun, farQ; long farRunStart;
  /** a real back-and-forth has questions in it; a video or speech mostly doesn't */
  void far(long t, String text) { if (farRun == 0) { farRunStart = t; farQ = 0; } farRun++; if (text.trim().endsWith("?")) farQ++; }
  void calton() { farRun = 0; farQ = 0; }
  boolean monologue(long now) { return farRun >= 8 && now - farRunStart > 45_000 && farQ * 8 < farRun; }

  static final String RULES = "Never state a time, date, price, dose or other number unless it is in the conversation or the CLOCK line; prices are 'about' and in GYD unless they said another currency. "
    + "'Cover' usually means insurance cover. Lines may be Guyanese English or Creolese (me=I, he=him, dem=them, deh=there, mek=make, a-visit=visiting, 'double me size'=twice my size) – read them that way; if a line still makes no sense, SKIP. "
    + "Never repeat their words back as a suggestion and never ask Calton a question. If Calton just used your last point, don't repeat it.";
  static String clock() { return "CLOCK (Calton's phone, Guyana): " + new java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(new Date()) + "."; }
}
