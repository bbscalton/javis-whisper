package com.neuereatec.javiswhisper;
import android.content.Context;
import androidx.room.*;
import java.util.List;
/** Local text-only history (Room). Never stores audio. */
@Database(entities = {Db.Session.class, Db.Turn.class}, version = 1, exportSchema = false)
public abstract class Db extends RoomDatabase {
  @Entity(tableName = "sessions") public static class Session {
    @PrimaryKey(autoGenerate = true) public long id; public long started, ended; public String mode, prebrief, summary; public boolean synced;
  }
  @Entity(tableName = "turns", indices = @Index("sessionId")) public static class Turn {
    @PrimaryKey(autoGenerate = true) public long id; public long sessionId, ts; public String kind, speaker, lang, text;
  }
  @Dao public interface D {
    @Insert long add(Session s); @Update void update(Session s); @Insert long add(Turn t);
    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 200") List<Session> sessions();
    @Query("SELECT * FROM sessions WHERE id=:id") Session session(long id);
    @Query("SELECT * FROM sessions WHERE summary IS NOT NULL AND summary<>'' AND id<>:not ORDER BY id DESC LIMIT :n") List<Session> summaries(long not, int n);
    @Query("SELECT * FROM turns WHERE sessionId=:sid ORDER BY id") List<Turn> turns(long sid);
    @Query("SELECT COUNT(*) FROM turns WHERE sessionId=:sid") int count(long sid);
  }
  public abstract D d();
  private static Db inst;
  public static synchronized Db get(Context c) { if (inst == null) inst = Room.databaseBuilder(c.getApplicationContext(), Db.class, "javis-whisper.db").allowMainThreadQueries().build(); return inst; }
}
