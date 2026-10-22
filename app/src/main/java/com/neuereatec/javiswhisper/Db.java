package com.neuereatec.javiswhisper;
import android.content.Context;
import androidx.room.*;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.util.List;
/** Local text-only history (Room). Never stores audio. v2 (0.1.5) adds people memory + per-person conversation notes. */
@Database(entities = {Db.Session.class, Db.Turn.class, Db.Person.class, Db.Convo.class}, version = 4, exportSchema = false)
public abstract class Db extends RoomDatabase {
  @Entity(tableName = "sessions") public static class Session {
    @PrimaryKey(autoGenerate = true) public long id; public long started, ended; public String mode, prebrief, summary, version, ctx; public boolean synced; // 0.1.18 ctx = topic/domain snapshot (JSON) for resume
  }
  @Entity(tableName = "turns", indices = @Index("sessionId")) public static class Turn {
    @PrimaryKey(autoGenerate = true) public long id; public long sessionId, ts; public String kind, speaker, lang, text;
  }
  /** Someone Calton talks to. voiceSig = coarse distance/voice cues (a hint, never an identity on its own). */
  @Entity(tableName = "people") public static class Person {
    @PrimaryKey(autoGenerate = true) public long id; public String name, aliases, relationship, notes, voiceSig; public int voiceN; public long firstSeen, lastSeen;
  }
  /** One conversation with one person: what it was about, who promised what, what's still open. */
  @Entity(tableName = "convos", indices = @Index("personId")) public static class Convo {
    @PrimaryKey(autoGenerate = true) public long id; public long sessionId, personId, started, ended; public String setting, topic, keyFacts, promises, openItems, summary;
  }
  @Dao public interface D {
    @Insert long add(Session s); @Update void update(Session s); @Insert long add(Turn t);
    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 200") List<Session> sessions();
    @Query("SELECT * FROM sessions WHERE id=:id") Session session(long id);
    @Query("SELECT * FROM sessions WHERE summary IS NOT NULL AND summary<>'' AND id<>:not ORDER BY id DESC LIMIT :n") List<Session> summaries(long not, int n);
    @Query("SELECT * FROM turns WHERE sessionId=:sid ORDER BY id") List<Turn> turns(long sid);
    @Query("SELECT * FROM turns WHERE sessionId=:sid AND ts>=:from ORDER BY id") List<Turn> turnsSince(long sid, long from);
    @Query("SELECT COUNT(*) FROM turns WHERE sessionId=:sid") int count(long sid);
    /** 0.1.23 text-only advisory notes (newest first), across sessions */
    @Query("SELECT * FROM turns WHERE kind='advice_note' ORDER BY id DESC LIMIT :n") List<Turn> adviceNotes(int n);
    @Query("SELECT COALESCE(MAX(ts), 0) FROM turns WHERE sessionId=:sid") long lastTs(long sid);
    @Query("SELECT * FROM (SELECT * FROM turns WHERE sessionId=:sid AND ts>=:from ORDER BY id DESC LIMIT :n) ORDER BY id") List<Turn> tail(long sid, long from, int n);
    @Insert long add(Person p); @Update void update(Person p); @Delete void delete(Person p);
    @Query("SELECT * FROM people ORDER BY lastSeen DESC") List<Person> people();
    @Query("SELECT * FROM people WHERE id=:id") Person person(long id);
    @Insert long add(Convo c); @Update void update(Convo c);
    @Query("SELECT * FROM convos WHERE personId=:pid ORDER BY id DESC LIMIT :n") List<Convo> convos(long pid, int n);
    @Query("SELECT * FROM convos ORDER BY id DESC LIMIT :n") List<Convo> recentConvos(int n);
    @Query("DELETE FROM convos WHERE personId=:pid") void deleteConvos(long pid);
    @Query("UPDATE convos SET personId=:to WHERE personId=:from") void moveConvos(long from, long to);
  }
  public abstract D d();
  static final Migration M1_2 = new Migration(1, 2) { @Override public void migrate(SupportSQLiteDatabase db) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `aliases` TEXT, `relationship` TEXT, `notes` TEXT, `voiceSig` TEXT, `voiceN` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE IF NOT EXISTS `convos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, `started` INTEGER NOT NULL, `ended` INTEGER NOT NULL, `setting` TEXT, `topic` TEXT, `keyFacts` TEXT, `promises` TEXT, `openItems` TEXT, `summary` TEXT)");
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_convos_personId` ON `convos` (`personId`)");
  } };
  static final Migration M2_3 = new Migration(2, 3) { @Override public void migrate(SupportSQLiteDatabase db) { db.execSQL("ALTER TABLE `sessions` ADD COLUMN `version` TEXT"); } };
  static final Migration M3_4 = new Migration(3, 4) { @Override public void migrate(SupportSQLiteDatabase db) { db.execSQL("ALTER TABLE `sessions` ADD COLUMN `ctx` TEXT"); } };
  private static Db inst;
  public static synchronized Db get(Context c) { if (inst == null) inst = Room.databaseBuilder(c.getApplicationContext(), Db.class, "javis-whisper.db").addMigrations(M1_2, M2_3, M3_4).allowMainThreadQueries().build(); return inst; }
}
