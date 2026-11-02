package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.extractor.mkv.EbmlProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects the Chapters element of a Matroska file. Uses the default (or first visible) edition,
 * leaves out hidden and disabled chapters, keeps only top level chapters and skips ordered
 * editions, whose times do not map onto the file timeline.
 */
final class MatroskaChapterParser {

  static final int ID_CHAPTERS = 0x1043A770;
  static final int ID_EDITION_ENTRY = 0x45B9;
  static final int ID_EDITION_FLAG_HIDDEN = 0x45BD;
  static final int ID_EDITION_FLAG_DEFAULT = 0x45DB;
  static final int ID_EDITION_FLAG_ORDERED = 0x45DD;
  static final int ID_CHAPTER_ATOM = 0xB6;
  static final int ID_CHAPTER_TIME_START = 0x91;
  static final int ID_CHAPTER_TIME_END = 0x92;
  static final int ID_CHAPTER_FLAG_HIDDEN = 0x98;
  static final int ID_CHAPTER_FLAG_ENABLED = 0x4598;
  static final int ID_CHAPTER_DISPLAY = 0x80;
  static final int ID_CHAP_STRING = 0x85;
  static final int ID_CHAP_LANGUAGE = 0x437C;
  static final int ID_CHAP_LANGUAGE_BCP47 = 0x437D;

  static final int MAX_CHAPTERS = 999;

  private static final class Edition {
    boolean hidden;
    boolean isDefault;
    boolean ordered;
    final List<ChaptersMetadata.Chapter> chapters = new ArrayList<>();
  }

  private final List<Edition> editions = new ArrayList<>();
  private boolean inChapters;
  private boolean finished;
  @Nullable private Edition edition;
  private int atomDepth;
  private long atomStartNs;
  private long atomEndNs;
  private boolean atomHidden;
  private boolean atomEnabled;
  private final List<ChaptersMetadata.Name> atomNames = new ArrayList<>();
  @Nullable private String displayText;
  @Nullable private String displayLanguage;
  @Nullable private String displayLanguageBcp47;

  static @EbmlProcessor.ElementType int elementType(int id) {
    switch (id) {
      case ID_CHAPTERS:
      case ID_EDITION_ENTRY:
      case ID_CHAPTER_ATOM:
      case ID_CHAPTER_DISPLAY:
        return EbmlProcessor.ELEMENT_TYPE_MASTER;
      case ID_EDITION_FLAG_HIDDEN:
      case ID_EDITION_FLAG_DEFAULT:
      case ID_EDITION_FLAG_ORDERED:
      case ID_CHAPTER_TIME_START:
      case ID_CHAPTER_TIME_END:
      case ID_CHAPTER_FLAG_HIDDEN:
      case ID_CHAPTER_FLAG_ENABLED:
        return EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT;
      case ID_CHAP_STRING:
      case ID_CHAP_LANGUAGE:
      case ID_CHAP_LANGUAGE_BCP47:
        return EbmlProcessor.ELEMENT_TYPE_STRING;
      default:
        return EbmlProcessor.ELEMENT_TYPE_UNKNOWN;
    }
  }

  /** Whether the Chapters element has been read to its end. */
  boolean isFinished() {
    return finished;
  }

  boolean isInChapters() {
    return inChapters;
  }

  void startMasterElement(int id) {
    if (id == ID_CHAPTERS) {
      if (!finished) {
        inChapters = true;
        editions.clear();
      }
      return;
    }
    if (!inChapters) {
      return;
    }
    switch (id) {
      case ID_EDITION_ENTRY:
        edition = new Edition();
        atomDepth = 0;
        break;
      case ID_CHAPTER_ATOM:
        atomDepth++;
        if (atomDepth == 1) {
          atomStartNs = C.TIME_UNSET;
          atomEndNs = C.TIME_UNSET;
          atomHidden = false;
          atomEnabled = true;
          atomNames.clear();
        }
        break;
      case ID_CHAPTER_DISPLAY:
        displayText = null;
        displayLanguage = null;
        displayLanguageBcp47 = null;
        break;
      default:
        break;
    }
  }

  void endMasterElement(int id) {
    if (!inChapters) {
      return;
    }
    switch (id) {
      case ID_CHAPTERS:
        inChapters = false;
        finished = true;
        break;
      case ID_EDITION_ENTRY:
        if (edition != null) {
          editions.add(edition);
        }
        edition = null;
        break;
      case ID_CHAPTER_ATOM:
        if (atomDepth == 1
            && edition != null
            && atomStartNs != C.TIME_UNSET
            && !atomHidden
            && atomEnabled
            && edition.chapters.size() < MAX_CHAPTERS) {
          edition.chapters.add(
              new ChaptersMetadata.Chapter(
                  atomStartNs / 1000,
                  atomEndNs == C.TIME_UNSET ? C.TIME_UNSET : atomEndNs / 1000,
                  atomNames));
        }
        atomDepth = Math.max(0, atomDepth - 1);
        break;
      case ID_CHAPTER_DISPLAY:
        if (atomDepth == 1 && displayText != null && !displayText.trim().isEmpty()) {
          String language = displayLanguageBcp47 != null ? displayLanguageBcp47 : displayLanguage;
          atomNames.add(new ChaptersMetadata.Name(displayText.trim(), language));
        }
        break;
      default:
        break;
    }
  }

  void integerElement(int id, long value) {
    if (!inChapters) {
      return;
    }
    Edition edition = this.edition;
    switch (id) {
      case ID_EDITION_FLAG_HIDDEN:
        if (edition != null) {
          edition.hidden = value == 1;
        }
        break;
      case ID_EDITION_FLAG_DEFAULT:
        if (edition != null) {
          edition.isDefault = value == 1;
        }
        break;
      case ID_EDITION_FLAG_ORDERED:
        if (edition != null) {
          edition.ordered = value == 1;
        }
        break;
      case ID_CHAPTER_TIME_START:
        if (atomDepth == 1) {
          atomStartNs = value;
        }
        break;
      case ID_CHAPTER_TIME_END:
        if (atomDepth == 1) {
          atomEndNs = value;
        }
        break;
      case ID_CHAPTER_FLAG_HIDDEN:
        if (atomDepth == 1) {
          atomHidden = value == 1;
        }
        break;
      case ID_CHAPTER_FLAG_ENABLED:
        if (atomDepth == 1) {
          atomEnabled = value != 0;
        }
        break;
      default:
        break;
    }
  }

  void stringElement(int id, String value) {
    if (!inChapters || atomDepth != 1) {
      return;
    }
    switch (id) {
      case ID_CHAP_STRING:
        displayText = value;
        break;
      case ID_CHAP_LANGUAGE:
        if (displayLanguage == null) {
          displayLanguage = value;
        }
        break;
      case ID_CHAP_LANGUAGE_BCP47:
        if (displayLanguageBcp47 == null) {
          displayLanguageBcp47 = value;
        }
        break;
      default:
        break;
    }
  }

  /** The chapters to show, or null when the file has fewer than two usable ones. */
  @Nullable
  ChaptersMetadata result() {
    if (!finished) {
      return null;
    }
    Edition chosen = null;
    for (Edition candidate : editions) {
      if (candidate.ordered || candidate.chapters.isEmpty()) {
        continue;
      }
      if (candidate.isDefault && !candidate.hidden) {
        chosen = candidate;
        break;
      }
      if (chosen == null || (chosen.hidden && !candidate.hidden)) {
        chosen = candidate;
      }
    }
    if (chosen == null) {
      return null;
    }
    List<ChaptersMetadata.Chapter> sorted = new ArrayList<>(chosen.chapters);
    Collections.sort(sorted, (a, b) -> Long.compare(a.startUs, b.startUs));
    List<ChaptersMetadata.Chapter> unique = new ArrayList<>();
    for (ChaptersMetadata.Chapter chapter : sorted) {
      if (unique.isEmpty() || unique.get(unique.size() - 1).startUs != chapter.startUs) {
        unique.add(chapter);
      }
    }
    return unique.size() < 2 ? null : new ChaptersMetadata(unique);
  }
}
