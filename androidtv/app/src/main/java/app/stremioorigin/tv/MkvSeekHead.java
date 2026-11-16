package app.stremioorigin.tv;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/* Matroska files whose index (Cues) is listed only in a second SeekHead, the one the first SeekHead points to (large
   remuxes: the muxer wrote the index at the end and listed it there). Media3 reads only the first SeekHead's entries, so
   such a file played unseekable: the saved place, skips and the remote's seeks all went back to 0 s. This extractor is
   Media3's own with one step more: when the first cluster comes and no index is known, it reads the second SeekHead,
   then the index it lists, then goes on from the cluster, just as Media3 does for an index listed in the first one.
   It works through Media3's private fields (Media3 1.8.0, the app is not minified); if any is missing, or the step meets
   something unexpected, the file plays as before (unseekable), never worse. */
@OptIn(markerClass = UnstableApi.class)
final class MkvSeekHead {
  private static final String TAG = "StremioOrigin";
  private static final int ID_SEEK_HEAD = 0x114D9B74, ID_SEEK = 0x4DBB, ID_CUES = 0x1C53BB6B, ID_CLUSTER = 0x1F43B675;
  private static Field fSeekEntryId, fSeekEntryPos, fCuesPos, fSentSeekMap, fSeekForCues, fSeekForCuesOn, fAfterCues, fOutput, fDurationUs;
  private static Field dSub, dFlags, dTrans; /* DefaultExtractorsFactory's own settings, to make the same extractor */
  private static final boolean OK;
  static {
    boolean ok;
    try {
      Class<?> c = MatroskaExtractor.class;
      fSeekEntryId = f(c, "seekEntryId"); fSeekEntryPos = f(c, "seekEntryPosition"); fCuesPos = f(c, "cuesContentPosition");
      fSentSeekMap = f(c, "sentSeekMap"); fSeekForCues = f(c, "seekForCues"); fSeekForCuesOn = f(c, "seekForCuesEnabled");
      fAfterCues = f(c, "seekPositionAfterBuildingCues"); fOutput = f(c, "extractorOutput"); fDurationUs = f(c, "durationUs");
      Class<?> d = DefaultExtractorsFactory.class;
      dSub = f(d, "subtitleParserFactory"); dFlags = f(d, "matroskaFlags"); dTrans = f(d, "textTrackTranscodingEnabled");
      ok = fSeekEntryId.getType() == int.class && fSeekEntryPos.getType() == long.class && fCuesPos.getType() == long.class
          && fSentSeekMap.getType() == boolean.class && fSeekForCues.getType() == boolean.class && fSeekForCuesOn.getType() == boolean.class
          && fAfterCues.getType() == long.class && fDurationUs.getType() == long.class && dFlags.getType() == int.class && dTrans.getType() == boolean.class;
    } catch (Throwable t) { ok = false; }
    OK = ok;
    if (!OK) MainActivity.log("[video] Matroska: second SeekHead reading unavailable (Media3 changed): such files stay unseekable");
  }
  private static Field f(Class<?> c, String n) throws NoSuchFieldException { Field x = c.getDeclaredField(n); x.setAccessible(true); return x; }

  /* the app's extractors: Media3's, with this Matroska extractor in place of its own */
  static final class Factory implements ExtractorsFactory {
    private final DefaultExtractorsFactory d;
    Factory(DefaultExtractorsFactory d) { this.d = d; }
    @Override public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean on) { d.experimentalSetTextTrackTranscodingEnabled(on); return this; }
    @Override public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory f) { d.setSubtitleParserFactory(f); return this; }
    @Override public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int c) { d.experimentalSetCodecsToParseWithinGopSampleDependencies(c); return this; }
    @Override public Extractor[] createExtractors() { return swap(d.createExtractors()); }
    @Override public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) { return swap(d.createExtractors(uri, headers)); }
    private Extractor[] swap(Extractor[] a) {
      if (!OK) return a;
      for (int i = 0; i < a.length; i++) {
        if (a[i] == null || a[i].getClass() != MatroskaExtractor.class) continue;
        try {
          SubtitleParser.Factory sf; int flags;
          synchronized (d) { sf = (SubtitleParser.Factory) dSub.get(d); flags = dFlags.getInt(d) | (dTrans.getBoolean(d) ? 0 : MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA); } /* (as Media3 makes its own) */
          a[i] = new Ext(sf, flags);
        } catch (Throwable t) { /* Media3's own, as before */ }
      }
      return a;
    }
  }

  private static final class Ext extends MatroskaExtractor {
    private long second = -1;     /* where the second SeekHead is (from the first SeekHead's entry for it) */
    private long backTo = -1;     /* the first cluster, to go on from once the index is read */
    private int step;             /* 0 reading as usual, 1 gone to the second SeekHead, 2 done */
    Ext(SubtitleParser.Factory sf, int flags) { super(sf, flags); }

    @Override protected void startMasterElement(int id, long contentPosition, long contentSize) throws ParserException {
      if (step == 0 && id == ID_CLUSTER && second > 0) {
        try { /* the first cluster and no index known: go to the second SeekHead the way Media3 goes to an index */
          if (!fSentSeekMap.getBoolean(this) && fSeekForCuesOn.getBoolean(this) && fCuesPos.getLong(this) == -1) { fCuesPos.setLong(this, second); step = 1; }
        } catch (Throwable t) { step = 2; }
      } else if (step == 1 && id == ID_CLUSTER) { giveUp("a cluster where the second SeekHead should be"); } /* (never reads on past it as if it were the film) */
      super.startMasterElement(id, contentPosition, contentSize);
    }

    @Override protected void endMasterElement(int id) throws ParserException {
      if (step == 0 && id == ID_SEEK && second < 0) {
        try { if (fSeekEntryId.getInt(this) == ID_SEEK_HEAD) { long p = fSeekEntryPos.getLong(this); if (p > 0) second = p; } } catch (Throwable t) { step = 2; }
      }
      super.endMasterElement(id);
      if (step == 1 && id == ID_SEEK_HEAD) { /* the second SeekHead read: its index entry, if it has one, is now known */
        step = 2;
        try {
          long cues = fCuesPos.getLong(this);
          if (cues > 0 && cues != second) { backTo = fAfterCues.getLong(this); fSeekForCues.setBoolean(this, true); MainActivity.log("[video] Matroska: the index found through the second SeekHead, the film can be sought"); }
          else giveUp("no index in the second SeekHead");
        } catch (Throwable t) { giveUp("unexpected: " + t); }
      } else if (id == ID_CUES && backTo != -1) { /* the index read: on from the first cluster (Media3 would go back to the SeekHead) */
        try { fAfterCues.setLong(this, backTo); } catch (Throwable t) { /* (Media3's own way on) */ }
        backTo = -1;
      }
    }

    /* as Media3 does for a file without an index: unseekable, on from the first cluster */
    private void giveUp(String why) {
      step = 2; MainActivity.log("[video] Matroska: second SeekHead not used (" + why + "): the film plays unseekable");
      try {
        if (!fSentSeekMap.getBoolean(this)) { ExtractorOutput o = (ExtractorOutput) fOutput.get(this); if (o != null) o.seekMap(new SeekMap.Unseekable(fDurationUs.getLong(this))); fSentSeekMap.setBoolean(this, true); }
      } catch (Throwable t) { /* nothing more to do */ }
    }
  }

  private MkvSeekHead() {}
}
