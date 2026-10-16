package com.neuereatec.javiswhisper;
/** 0.1.19 pure speaker rules (no Android deps → unit-testable off-device). Pitch is checked against Calton's SAVED pitch (prox_prof). */
final class Classify {
  static final double CLASS_TOL = 0.25, LEARN_TOL = 0.15, PHONE_ME_TOL = 0.20, OWNER_T_PHONE = 1.2;
  static double ratio(double f0, double ownerF0) { return ownerF0 > 0 && f0 > 0 ? Math.abs(f0 / ownerF0 - 1) : -1; }
  /** within tol of his saved pitch (unknown pitch or no profile = no evidence against). */
  static boolean pitchWithin(double f0, double ownerF0, double tol) { double r = ratio(f0, ownerF0); return r < 0 || r <= tol; }
  /** learning needs POSITIVE evidence: a measured pitch within 15% of his saved pitch (no profile yet = first learning allowed). */
  static boolean canLearn(double f0, double ownerF0) { if (ownerF0 <= 0) return true; return f0 > 0 && Math.abs(f0 / ownerF0 - 1) <= LEARN_TOL; }
  /** refine the proximity band: never Calton when pitch is clearly off; with no earbuds pitch leads (proximity is unreliable on the phone mic). */
  static String refine(String band, double f0, double ownerF0, boolean noEarbud) {
    double r = ratio(f0, ownerF0); if (r < 0) return band;
    if (r > CLASS_TOL) return noEarbud ? "Them" : "Me".equals(band) ? "Unsure" : band;
    if (noEarbud) return r <= PHONE_ME_TOL ? "Me" : "Unsure";
    return band;
  }
  /** may diarization collapse this line into the owner / anchor it to him? Never for a Them line or an off-pitch line. */
  static boolean allowOwner(String spk, double f0, double ownerF0) { return !"Them".equals(spk) && pitchWithin(f0, ownerF0, CLASS_TOL); }
  /** short replies ("Go ahead", "That's true", a name) are kept as short lines instead of dropped. */
  static boolean keepShort(String why) { return "fragment".equals(why); }
  /** "talked over" is only believable with a real earbud + phone mic split. */
  static boolean overlapBelievable(boolean noEarbud, boolean dual) { return dual && !noEarbud; }
}
