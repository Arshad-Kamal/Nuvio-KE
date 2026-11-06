/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import com.nuvio.tv.core.player.PlaybackChapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NuvioTV fork (format-capability F2/G1): collects the Matroska Chapters element into {@link
 * PlaybackChapter}s, fed by {@link MatroskaExtractor}'s EBML callbacks. Stock Media3 1.8.0 skips
 * the element; 1.11 parses it inside its extractor, whose track-format pipeline differs from this
 * vendored copy, so the parse lives here as a self-contained unit.
 *
 * <p>Scope: one edition (the first flagged default, else the first with chapters); top-level
 * ChapterAtoms only (nested sub-chapters are ignored); hidden or disabled atoms dropped; ordered
 * editions skipped (ordered chapters remap the timeline, which only mpv can play, gap G4).
 * ChapterTimeStart is in nanoseconds, independent of TimestampScale.
 */
final class MatroskaChapterParser {

  static final int ID_CHAPTERS = 0x1043A770;
  static final int ID_EDITION_ENTRY = 0x45B9;
  static final int ID_EDITION_FLAG_DEFAULT = 0x45DB;
  static final int ID_EDITION_FLAG_ORDERED = 0x45DD;
  static final int ID_CHAPTER_ATOM = 0xB6;
  static final int ID_CHAPTER_TIME_START = 0x91;
  static final int ID_CHAPTER_FLAG_HIDDEN = 0x98;
  static final int ID_CHAPTER_FLAG_ENABLED = 0x4598;
  static final int ID_CHAPTER_DISPLAY = 0x80;
  static final int ID_CHAP_STRING = 0x85;

  private boolean inChapters;
  private boolean inEdition;
  private boolean editionDefault;
  private boolean editionOrdered;
  private final List<PlaybackChapter> editionChapters = new ArrayList<>();

  // ChapterAtom nesting depth inside the current edition; fields below track depth 1 only.
  private int atomDepth;
  private long atomStartNs;
  private boolean atomHidden;
  private boolean atomEnabled;
  @Nullable private String atomTitle;

  private List<PlaybackChapter> chosen = Collections.emptyList();
  private boolean chosenIsDefault;

  /** The element type for a chapter ID, else {@link EbmlProcessor#ELEMENT_TYPE_UNKNOWN}. */
  static @EbmlProcessor.ElementType int getElementType(int id) {
    switch (id) {
      case ID_CHAPTERS:
      case ID_EDITION_ENTRY:
      case ID_CHAPTER_ATOM:
      case ID_CHAPTER_DISPLAY:
        return EbmlProcessor.ELEMENT_TYPE_MASTER;
      case ID_EDITION_FLAG_DEFAULT:
      case ID_EDITION_FLAG_ORDERED:
      case ID_CHAPTER_TIME_START:
      case ID_CHAPTER_FLAG_HIDDEN:
      case ID_CHAPTER_FLAG_ENABLED:
        return EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT;
      case ID_CHAP_STRING:
        return EbmlProcessor.ELEMENT_TYPE_STRING;
      default:
        return EbmlProcessor.ELEMENT_TYPE_UNKNOWN;
    }
  }

  /** The chapters of the chosen edition, sorted by start; empty until an edition has ended. */
  List<PlaybackChapter> getChapters() {
    return chosen;
  }

  void startMasterElement(int id) {
    if (id == ID_CHAPTERS) {
      inChapters = true;
      return;
    }
    if (!inChapters) {
      return;
    }
    if (id == ID_EDITION_ENTRY) {
      inEdition = true;
      editionDefault = false;
      editionOrdered = false;
      editionChapters.clear();
      atomDepth = 0;
    } else if (id == ID_CHAPTER_ATOM && inEdition) {
      atomDepth++;
      if (atomDepth == 1) {
        atomStartNs = -1;
        atomHidden = false;
        atomEnabled = true;
        atomTitle = null;
      }
    }
  }

  void endMasterElement(int id) {
    if (!inChapters) {
      return;
    }
    if (id == ID_CHAPTERS) {
      inChapters = false;
    } else if (id == ID_EDITION_ENTRY && inEdition) {
      inEdition = false;
      if (!editionOrdered
          && !editionChapters.isEmpty()
          && (chosen.isEmpty() || (editionDefault && !chosenIsDefault))) {
        chosen = PlaybackChapter.normalized(editionChapters);
        chosenIsDefault = editionDefault;
      }
      editionChapters.clear();
    } else if (id == ID_CHAPTER_ATOM && atomDepth > 0) {
      if (atomDepth == 1
          && atomEnabled
          && !atomHidden
          && atomStartNs >= 0
          && editionChapters.size() < PlaybackChapter.MAX_CHAPTERS) {
        editionChapters.add(new PlaybackChapter(atomStartNs / 1_000_000L, atomTitle));
      }
      atomDepth--;
    }
  }

  void integerElement(int id, long value) {
    if (!inEdition) {
      return;
    }
    switch (id) {
      case ID_EDITION_FLAG_DEFAULT:
        editionDefault = value == 1;
        break;
      case ID_EDITION_FLAG_ORDERED:
        editionOrdered = value == 1;
        break;
      case ID_CHAPTER_TIME_START:
        if (atomDepth == 1) {
          // A start past Long.MAX_VALUE reads negative and is dropped with the unset ones.
          atomStartNs = value;
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
    // The first ChapterDisplay names the chapter; later ones are other languages.
    if (id == ID_CHAP_STRING && inEdition && atomDepth == 1 && atomTitle == null) {
      atomTitle = value;
    }
  }
}
