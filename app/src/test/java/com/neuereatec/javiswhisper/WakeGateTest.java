package com.neuereatec.javiswhisper;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class WakeGateTest {
  static String res(String word, double conf, double s, double e) { return "{\"result\":[{\"word\":\"" + word + "\",\"conf\":" + conf + ",\"start\":" + s + ",\"end\":" + e + "}],\"text\":\"" + word + "\"}"; }
  WakeGate g() { return new WakeGate("Jarvis", 0.95, 0.35, 4000); }
  @Test public void confidentWakeWordTriggers() { assertTrue(g().accept(WakeGate.parse(res("jarvis", 1.0, 1.0, 1.55)), 10_000)); }
  @Test public void lowConfidenceIgnored() { assertFalse(g().accept(WakeGate.parse(res("jarvis", 0.94, 1.0, 1.55)), 10_000)); }
  @Test public void tooShortBlipIgnored() { assertFalse(g().accept(WakeGate.parse(res("jarvis", 1.0, 1.0, 1.2)), 10_000)); }
  @Test public void decoyAndUnkIgnored() {
    assertFalse(g().accept(WakeGate.parse(res("harvest", 1.0, 1.0, 1.6)), 10_000));
    assertFalse(g().accept(WakeGate.parse("{\"text\":\"\"}"), 10_000));
    assertFalse(g().accept(WakeGate.parse("not json"), 10_000));
  }
  @Test public void cooldownBlocksRetrigger() {
    WakeGate w = g(); List<WakeGate.Word> ws = WakeGate.parse(res("jarvis", 1.0, 1.0, 1.6));
    assertTrue(w.accept(ws, 10_000)); assertFalse(w.accept(ws, 12_000)); assertTrue(w.accept(ws, 14_001));
  }
  @Test public void mutedWhileSpeaking() {
    WakeGate w = g(); w.mute(20_000); List<WakeGate.Word> ws = WakeGate.parse(res("jarvis", 1.0, 1.0, 1.6));
    assertFalse(w.accept(ws, 19_999)); assertTrue(w.accept(ws, 20_000));
  }
  @Test public void customPhraseNeedsAllWordsInOrder() {
    WakeGate w = new WakeGate("Hey Buddy", 0.95, 0.7, 4000);
    String ok = "{\"result\":[{\"word\":\"hey\",\"conf\":1,\"start\":0.1,\"end\":0.4},{\"word\":\"buddy\",\"conf\":0.99,\"start\":0.4,\"end\":0.9}]}";
    String weak = "{\"result\":[{\"word\":\"hey\",\"conf\":1,\"start\":0.1,\"end\":0.4},{\"word\":\"buddy\",\"conf\":0.6,\"start\":0.4,\"end\":0.9}]}";
    assertFalse(w.accept(WakeGate.parse(weak), 10_000)); assertTrue(w.accept(WakeGate.parse(ok), 10_000));
  }
  @Test public void grammarHasPhraseDecoysAndUnk() {
    String g = WakeGate.grammar("Jarvis", null);
    assertTrue(g.startsWith("[\"jarvis\"")); assertTrue(g.contains("harvest")); assertTrue(g.endsWith("\"[unk]\"]"));
    assertEquals("jarvis", WakeGate.normalize("  ")); assertEquals("computer", WakeGate.normalize("Computer!"));
  }
}
