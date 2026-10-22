package com.neuereatec.javiswhisper;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class WakeGateTest {
  /** words as "word:conf:start:end" */
  static List<WakeGate.Word> ws(String... w) {
    StringBuilder b = new StringBuilder("{\"result\":[");
    for (int i = 0; i < w.length; i++) { String[] p = w[i].split(":"); if (i > 0) b.append(','); b.append("{\"word\":\"").append(p[0]).append("\",\"conf\":").append(p[1]).append(",\"start\":").append(p[2]).append(",\"end\":").append(p[3]).append('}'); }
    return WakeGate.parse(b.append("]}").toString());
  }
  WakeGate g() { return new WakeGate(WakeGate.DEFAULT, 0.95, 0.5, 4000); }
  static final String[] RC = {"right:1:1.0:1.33", "cool:1:1.35:1.75"};
  @Test public void rightCoolTriggers() { assertTrue(g().accept(ws(RC), 10_000)); }
  @Test public void rightCoolInsideSentenceTriggers() { assertTrue(g().accept(ws("[unk]:1:0.2:0.9", "right:1:1.0:1.33", "cool:0.99:1.36:1.75", "[unk]:1:1.9:2.5"), 10_000)); }
  @Test public void coolAloneNeverTriggers() { assertFalse(g().accept(ws("cool:1:1.0:1.5"), 10_000)); }
  @Test public void rightAloneNeverTriggers() { assertFalse(g().accept(ws("right:1:1.0:1.4"), 10_000)); }
  @Test public void wrongOrderNeverTriggers() { assertFalse(g().accept(ws("cool:1:1.0:1.4", "right:1:1.4:1.8"), 10_000)); }
  @Test public void wordInBetweenNeverTriggers() {
    assertFalse(g().accept(ws("right:1:1.0:1.3", "very:1:1.3:1.6", "cool:1:1.6:2.0"), 10_000));
    assertFalse(g().accept(ws("right:1:1.0:1.3", "[unk]:1:1.3:1.6", "cool:1:1.6:2.0"), 10_000));
  }
  @Test public void pauseBetweenWordsNeverTriggers() { assertFalse(g().accept(ws("right:1:1.0:1.3", "cool:1:1.6:2.0"), 10_000)); } // "that's right. cool."
  @Test public void allRightCoolRejected() {
    assertFalse(g().accept(ws("all:1:0.6:0.85", "right:1:1.0:1.33", "cool:1:1.35:1.75"), 10_000));
    assertFalse(g().accept(ws("alright:1:0.5:1.0", "right:1:1.0:1.33", "cool:1:1.35:1.75"), 10_000));
  }
  @Test public void soundAlikesRejected() {
    assertFalse(g().accept(ws("right school:1:1.0:1.8"), 10_000));
    assertFalse(g().accept(ws("right:1:1.0:1.33", "school:1:1.35:1.8"), 10_000));
    assertFalse(g().accept(ws("white:1:1.0:1.33", "cool:1:1.35:1.75"), 10_000));
    assertFalse(g().accept(ws("bright:1:1.0:1.33", "pool:1:1.35:1.75"), 10_000));
  }
  @Test public void lowConfidenceOnEitherWordIgnored() {
    assertFalse(g().accept(ws("right:0.94:1.0:1.33", "cool:1:1.35:1.75"), 10_000));
    assertFalse(g().accept(ws("right:1:1.0:1.33", "cool:0.5:1.35:1.75"), 10_000));
    assertTrue(g().accept(ws("right:1:1.0:1.33", "cool:0.6:1.35:1.75"), 10_000)); // split with the "school" decoy, still the top word
  }
  @Test public void tooShortBlipIgnored() { assertFalse(g().accept(ws("right:1:1.0:1.15", "cool:1:1.15:1.3"), 10_000)); }
  @Test public void emptyAndGarbageIgnored() { assertFalse(g().accept(WakeGate.parse("{\"text\":\"\"}"), 10_000)); assertFalse(g().accept(WakeGate.parse("not json"), 10_000)); }
  @Test public void cooldownBlocksRetrigger() { WakeGate w = g(); assertTrue(w.accept(ws(RC), 10_000)); assertFalse(w.accept(ws(RC), 12_000)); assertTrue(w.accept(ws(RC), 14_001)); }
  @Test public void mutedWhileSpeaking() { WakeGate w = g(); w.mute(20_000); assertFalse(w.accept(ws(RC), 19_999)); assertTrue(w.accept(ws(RC), 20_000)); }
  @Test public void customPhraseStillWorks() {
    WakeGate w = new WakeGate("Hey Buddy", 0.95, 0.5, 4000);
    assertFalse(w.accept(ws("hey:1:0.1:0.4", "buddy:0.5:0.4:0.9"), 10_000)); assertFalse(w.accept(ws("hey:0.9:0.1:0.4", "buddy:1:0.4:0.9"), 10_000)); assertTrue(w.accept(ws("hey:1:0.1:0.4", "buddy:0.99:0.4:0.9"), 10_000));
  }
  @Test public void grammarHasPhraseDecoysAndUnk() {
    String gr = WakeGate.grammar("Right Cool", null);
    assertTrue(gr.startsWith("[\"right cool\",")); assertTrue(gr.contains("\"right\"")); assertTrue(gr.contains("\"cool\"")); assertTrue(gr.contains("\"all right\"")); assertTrue(gr.contains("\"white cool\"")); assertTrue(gr.endsWith("\"[unk]\"]"));
  }
  @Test public void defaultAndFallbackIsRightCool() {
    assertEquals("right cool", WakeGate.DEFAULT); assertEquals("right cool", WakeGate.normalize("  ")); assertEquals("right cool", WakeGate.normalize(null));
    assertEquals("right cool", WakeGate.normalize("Right, Cool!")); assertEquals("computer", WakeGate.normalize("Computer!"));
  }
}
