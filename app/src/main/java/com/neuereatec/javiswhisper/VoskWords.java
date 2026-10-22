package com.neuereatec.javiswhisper;
import com.sun.jna.Native; import com.sun.jna.Pointer;
/** 0.1.23 vocabulary check for a custom wake word (libvosk exports vosk_model_find_word; the Java wrapper doesn't). */
final class VoskWords {
  private static boolean ok;
  static { try { Native.register(VoskWords.class, "vosk"); ok = true; } catch (Throwable t) { ok = false; } }
  private static native int vosk_model_find_word(Pointer model, String word);
  /** true if the word is in the model's dictionary (or the check isn't available). */
  static boolean known(org.vosk.Model m, String w) { if (!ok) return true; try { return vosk_model_find_word(m.getPointer(), w) >= 0; } catch (Throwable t) { return true; } }
}
