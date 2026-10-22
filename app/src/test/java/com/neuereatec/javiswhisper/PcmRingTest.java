package com.neuereatec.javiswhisper;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class PcmRingTest {
  @Test public void keepsOnlyNewestSamples() {
    PcmRing r = new PcmRing(5);
    r.write(new short[]{1, 2, 3}, 3); r.write(new short[]{4, 5, 6, 7}, 4);
    assertArrayEquals(new short[]{3, 4, 5, 6, 7}, r.snapshot()); assertEquals(5, r.size());
  }
  @Test public void tenSecondsAt16k() { assertEquals(160000, PcmRing.seconds(10, 16000).capacity()); }
  @Test public void clearWipesEverySample() {
    PcmRing r = new PcmRing(8); r.write(new short[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9}, 10);
    assertFalse(r.isWiped()); r.clear(); assertTrue(r.isWiped()); assertEquals(0, r.snapshot().length);
  }
  @Test public void wipeHelpersZeroArrays() {
    short[] s = {1, 2}; byte[] b = {1, 2}; PcmRing.wipe(s); PcmRing.wipe(b);
    assertArrayEquals(new short[]{0, 0}, s); assertArrayEquals(new byte[]{0, 0}, b);
  }
  /** the ring buffer cannot be serialised / parcelled / streamed: no persistence surface at all. */
  @Test public void ringHasNoPersistenceSurface() {
    assertFalse(java.io.Serializable.class.isAssignableFrom(PcmRing.class));
    for (Class<?> i : PcmRing.class.getInterfaces()) fail("PcmRing must not implement " + i);
    for (Field f : PcmRing.class.getDeclaredFields()) {
      String t = f.getType().getName();
      assertFalse("IO field " + f, t.startsWith("java.io.") || t.startsWith("java.nio.") || t.startsWith("android."));
    }
  }
  /** no disk-write API anywhere in the advisory audio path (ring buffer, wake gate, advisor). */
  @Test public void advisoryAudioPathNeverWritesFiles() throws Exception {
    String[] banned = {"FileOutputStream", "FileWriter", "RandomAccessFile", "openFileOutput", "Files.write", "Files.newOutputStream", "MediaRecorder", "ObjectOutputStream", "getExternal", "VoiceEnroll.save", "new File("};
    for (String n : new String[]{"PcmRing", "WakeGate", "Advisor", "AdviceText"}) {
      File f = new File("src/main/java/com/neuereatec/javiswhisper/" + n + ".java");
      assertTrue("missing " + f.getAbsolutePath(), f.exists());
      String src = new String(Files.readAllBytes(f.toPath()), "UTF-8");
      for (String b : banned) assertFalse(n + ".java uses " + b, src.contains(b));
    }
  }
}
