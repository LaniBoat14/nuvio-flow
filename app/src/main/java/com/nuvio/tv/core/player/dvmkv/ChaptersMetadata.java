package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The chapters of a file, in start order, carried on the track formats. */
@UnstableApi
public final class ChaptersMetadata implements Metadata.Entry {

  public static final String SOURCE_MKV = "mkv";
  public static final String SOURCE_MP4 = "mp4";

  /** One chapter. Times are in microseconds; {@link C#TIME_UNSET} when the file gives no end. */
  public static final class Chapter {
    public final long startUs;
    public final long endUs;
    /** Chapter names with their languages, in file order. May be empty. */
    public final List<Name> names;

    public Chapter(long startUs, long endUs, List<Name> names) {
      this.startUs = startUs;
      this.endUs = endUs;
      this.names = Collections.unmodifiableList(new ArrayList<>(names));
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Chapter)) {
        return false;
      }
      Chapter that = (Chapter) o;
      return startUs == that.startUs && endUs == that.endUs && names.equals(that.names);
    }

    @Override
    public int hashCode() {
      return Objects.hash(startUs, endUs, names);
    }
  }

  /** A chapter name. {@code language} is an ISO 639-2 or BCP 47 code, or null. */
  public static final class Name {
    public final String text;
    @Nullable public final String language;

    public Name(String text, @Nullable String language) {
      this.text = text;
      this.language = language;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Name)) {
        return false;
      }
      Name that = (Name) o;
      return text.equals(that.text) && Objects.equals(language, that.language);
    }

    @Override
    public int hashCode() {
      return Objects.hash(text, language);
    }
  }

  public final List<Chapter> chapters;

  /** The container the chapters were read from, {@link #SOURCE_MKV} or {@link #SOURCE_MP4}. */
  public final String source;

  public ChaptersMetadata(List<Chapter> chapters) {
    this(chapters, SOURCE_MKV);
  }

  public ChaptersMetadata(List<Chapter> chapters, String source) {
    this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
    this.source = source;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ChaptersMetadata that = (ChaptersMetadata) o;
    return chapters.equals(that.chapters) && source.equals(that.source);
  }

  @Override
  public int hashCode() {
    return Objects.hash(chapters, source);
  }

  @Override
  public String toString() {
    return "ChaptersMetadata: " + chapters.size() + " chapters from " + source;
  }
}
