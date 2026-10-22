package com.neuereatec.javiswhisper;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdviceTextTest {
  @Test public void trimsToThreeSentences() {
    String r = AdviceText.enforce("Hold your price. He's bluffing about the other buyer. Mention the cash. Walk away if he won't move. Smile.");
    assertEquals("Hold your price. He's bluffing about the other buyer. Mention the cash.", r);
    assertEquals(3, AdviceText.sentences(r).size());
  }
  @Test public void stripsQuestions() {
    String r = AdviceText.enforce("Do you want to close today? Don't tell him your budget. What does he really want? Ask about the warranty.");
    assertEquals("Don't tell him your budget. Ask about the warranty.", r);
    assertFalse(r.contains("?"));
  }
  @Test public void quotedQuestionIsStillStripped() {
    assertEquals("Push for a discount.", AdviceText.enforce("Ask him, \"Is it in writing?\" Push for a discount."));
  }
  @Test public void stripsGreetingsAndFormalities() {
    assertEquals("Don't tell him that, he may be after your money.", AdviceText.enforce("Hey Calton, don't tell him that, he may be after your money."));
    assertEquals("Keep it short.", AdviceText.enforce("Sure! Keep it short. Hope this helps!"));
    assertEquals("Watch the fees.", AdviceText.enforce("Okay so, watch the fees"));
  }
  @Test public void markdownAndListsRemoved() {
    assertEquals("Get it in writing. Don't sign today.", AdviceText.enforce("- **Get it in writing.**\n- Don't sign today."));
  }
  @Test public void skipOrOnlyQuestionsMeansSilence() {
    assertEquals("", AdviceText.enforce("SKIP"));
    assertEquals("", AdviceText.enforce("Are you sure? What's the price?"));
    assertEquals("", AdviceText.enforce(null));
    assertEquals("", AdviceText.enforce("   "));
  }
  @Test public void invertedQuestionWithoutMarkIsStripped() {
    assertEquals("Hold firm.", AdviceText.enforce("Hold firm. Do you trust him"));
  }
}
