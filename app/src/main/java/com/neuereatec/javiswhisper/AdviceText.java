package com.neuereatec.javiswhisper;
import java.util.*;
import java.util.regex.*;
/** 0.1.23 hard limits for the advisory whisper, enforced in code regardless of what the model returns. */
final class AdviceText {
  static final int MAX_SENTENCES = 3;
  private static final Pattern GREET = Pattern.compile("(?i)^\\s*(hey|hi|hello|yo|okay|ok|alright|all right|sure|so|well|right|listen|look|good (morning|afternoon|evening))\\b[\\s,!.:;-]*(calton|carlton|boss|man|bro|friend|there)?[\\s,!.:;-]*");
  private static final Pattern FORMAL = Pattern.compile("(?i)^(certainly|of course|absolutely|great question|i hope this helps|hope this helps|let me know|feel free|as an ai|here('s| is) (my|some|the) (advice|tip|whisper))\\b.*");
  private static final Pattern NAME_LEAD = Pattern.compile("(?i)^(calton|carlton)[\\s,!.:;-]+");
  /** split into sentences (keeps the terminal punctuation). */
  static List<String> sentences(String t) {
    List<String> out = new ArrayList<>(); if (t == null) return out;
    Matcher m = Pattern.compile("[^.!?…]+(?:[.!?…]+[\"”’')]*|$)").matcher(t.replaceAll("\\s+", " ").trim());
    while (m.find()) { String s = m.group().trim(); if (!s.isEmpty() && s.matches(".*[\\p{L}\\p{N}].*")) out.add(s); }
    return out;
  }
  /** a sentence that asks something (any '?' at all, or an inverted question opener without a full stop). */
  static boolean isQuestion(String s) {
    String t = s.trim(); if (t.contains("?")) return true;
    return t.matches("(?is)^(do|did|does|are|is|was|were|can|could|would|should|will|have|has|what|why|how|which|who|when|where|shall|may)\\s+(you|i|we|he|she|they|it)\\b.*") && !t.matches(".*[.!]$");
  }
  /** strip greetings / name / formal filler at the start of a sentence; returns "" if nothing useful is left. */
  static String stripFiller(String s) {
    String t = s.trim(); String prev;
    do { prev = t; t = GREET.matcher(t).replaceFirst(""); t = NAME_LEAD.matcher(t).replaceFirst(""); } while (!t.equals(prev));
    if (FORMAL.matcher(t).matches()) return "";
    if (t.isEmpty() || !t.matches(".*[\\p{L}\\p{N}].*")) return "";
    return Character.toUpperCase(t.charAt(0)) + t.substring(1);
  }
  /** markdown / list markers / SKIP removed; questions dropped; greetings dropped; at most 3 sentences. "" = say nothing. */
  static String enforce(String raw) {
    if (raw == null) return "";
    String t = raw.replace("||", ". ").replaceAll("(?m)^\\s*([-*•]|\\d+[.)])\\s+", "").replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim();
    if (t.matches("(?i)^(SKIP|-)\\W*$")) return "";
    List<String> keep = new ArrayList<>();
    for (String s : sentences(t)) {
      if (isQuestion(s)) continue;
      String c = stripFiller(s); if (c.isEmpty()) continue;
      if (!c.matches(".*[.!…][\"”’')]*$")) c = c + ".";
      keep.add(c); if (keep.size() >= MAX_SENTENCES) break;
    }
    return String.join(" ", keep);
  }
}
