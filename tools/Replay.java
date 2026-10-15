package com.neuereatec.javiswhisper; import org.json.*; import java.nio.file.*; import java.util.*; import java.time.*;
public class Replay {
  public static void main(String[] a) throws Exception { boolean learned = a.length > 0;
    JSONObject j = new JSONObject(Files.readString(Path.of("/workspace/uploads/jw-up.json")));
    JSONArray ss = j.getJSONArray("sessions"); int before = 0, after = 0; Map<String,Integer> why = new TreeMap<>(); List<String> fired = new ArrayList<>(), dropped = new ArrayList<>();
    for (int si = ss.length() - 1; si >= 0; si--) { JSONObject s = ss.getJSONObject(si); JSONArray t = s.getJSONArray("turns"); String mode = s.getString("mode");
      boolean street = mode.equals("street"); int cooldown = street ? 10_000 : 12_000, minTurns = street ? 1 : 2;
      Guard g = new Guard(); boolean meSeen = learned; long lastMe = 0, lastThemEnd = 0, lastCoach = -1_000_000; String lastThem = "", lastSpk = ""; int turns = 0;
      for (int i = 0; i < t.length(); i++) { JSONObject x = t.getJSONObject(i); long ts = OffsetDateTime.parse(x.getString("ts")).toInstant().toEpochMilli();
        if (x.getString("kind").equals("whisper")) { before++; continue; }
        String text = Guard.normalize(x.getString("text")), spk = x.getString("speaker");
        if (g.echoOf(text, ts) != null) { spk = "Me"; }
        else if (learned && spk.equals("Unsure")) spk = "Me";
        else if (spk.equals("Unsure") && lastThemEnd > 0 && ts - lastThemEnd < 6000 && Guard.hits(text, lastThem) >= 1) spk = "Me";
        long end = ts + (long) (text.split("\\s+").length * 330);
        if (spk.equals("Me")) { meSeen = true; lastMe = ts; g.calton(); } else if (spk.equals("Them")) g.far(ts, text);
        String r = Guard.unclear(text);
        if (r == null && spk.equals("Them")) { if (!spk.equals(lastSpk)) turns++; else turns++; }
        if (!spk.equals(lastSpk)) {} lastSpk = spk;
        if (spk.equals("Them")) { lastThem = text; lastThemEnd = end; }
        if (!spk.equals("Them")) continue;
        String cand = null; for (int k = i + 1; k < t.length(); k++) { JSONObject y = t.getJSONObject(k); if (y.getString("kind").equals("whisper")) { cand = y.getString("text"); break; } if (!y.getString("kind").equals("whisper")) break; }
        String w;
        if (r != null) w = "unclear line";
        else if (!meSeen) w = "voice not learned / Calton not heard yet";
        else if (g.monologue(ts)) w = "one voice talking on (video/speech)";
        else if (ts - lastMe > 60_000) w = "Calton not in this exchange";
        else if (ts - lastCoach < cooldown) w = "cooldown";
        else if (turns < minTurns) w = "still listening";
        else { // held back if someone starts talking within pause (0.9 s) + Grok (~1.2 s) of this line ending
          long next = Long.MAX_VALUE; for (int k = i + 1; k < t.length(); k++) { JSONObject y = t.getJSONObject(k); if (!y.getString("kind").equals("whisper")) { next = OffsetDateTime.parse(y.getString("ts")).toInstant().toEpochMilli(); break; } }
          if (next - end < 2100) w = "someone started talking – held back";
          else if (cand == null) w = "Grok said SKIP (original)";
          else { String rj = g.reject(cand, text, ts); if (rj != null) { w = "filter: " + rj; dropped.add(x.getString("ts").substring(11, 19) + "  " + cand + "   [" + rj + "]"); }
            else { w = null; after++; lastCoach = ts; g.said(cand, ts + 1500); fired.add(x.getString("ts").substring(11, 19) + " " + mode + "  heard: " + text + "\n            → " + cand); } } }
        if (w != null) why.merge(w, 1, Integer::sum);
      } }
    System.out.println("whispers before: " + before + "   after (replay): " + after); System.out.println("\nWhy lines got no whisper (Them lines):"); why.forEach((k, v) -> System.out.println("  " + v + "  " + k));
    System.out.println("\nWOULD FIRE:"); fired.forEach(System.out::println); System.out.println("\nDROPPED BY FILTER (sample):"); dropped.stream().limit(40).forEach(System.out::println);
  }
}
