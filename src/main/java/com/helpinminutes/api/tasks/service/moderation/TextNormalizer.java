package com.helpinminutes.api.tasks.service.moderation;

import java.util.regex.Pattern;

/**
 * Normalises task text before policy matching.
 *
 * <p>Two competing failure modes shape every rule here:
 *
 * <ul>
 *   <li><b>Evasion.</b> "g.a.n.j.a", "c0caine", "g a n j a" must all match.
 *   <li><b>False positives.</b> The previous implementation stripped every
 *       separator including spaces, which turned "hero in the movie" into "heroin"
 *       and "the wine glass set" into a match. It also matched substrings, so its
 *       "weed" pattern flagged <i>"weeding the garden"</i> and its "adult" pattern
 *       flagged <i>"adulterated"</i> — on a marketplace whose whole business is
 *       gardening and errands.
 * </ul>
 *
 * <p>So: punctuation separators collapse, spaces do not — except in the one case
 * where every segment is a single character, which is unambiguous evasion and never
 * occurs in real prose.
 */
public final class TextNormalizer {

  private TextNormalizer() {}

  /** Zero-width and bidi characters used to break up words invisibly. */
  private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]");

  /** Punctuation used as an in-word separator: "g.a.n.j.a", "w-e-e-d", "s*e*x". */
  private static final Pattern INWORD_PUNCTUATION = Pattern.compile("(?<=\\p{L})[._*\\-+|/]+(?=\\p{L})");

  /** Three or more of the same letter: "weeeeed" → "weed". Two is left alone. */
  private static final Pattern TRIPLED_LETTER = Pattern.compile("(\\p{L})\\1{2,}");

  /** A run of three or more single letters separated by single spaces. */
  private static final Pattern SPACED_OUT_LETTERS =
      Pattern.compile("\\b(?:\\p{L} ){2,}\\p{L}\\b");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /**
   * Lowercased, punctuation-normalised text with runs of whitespace collapsed.
   *
   * <p>This is the string all policy matching runs against. Word boundaries are
   * preserved so matching can be boundary-anchored rather than substring-based.
   */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String text = INVISIBLE.matcher(raw).replaceAll("");
    text = text.toLowerCase(java.util.Locale.ROOT);
    text = INWORD_PUNCTUATION.matcher(text).replaceAll("");
    text = TRIPLED_LETTER.matcher(text).replaceAll("$1");
    text = collapseSpacedOutLetters(text);
    text = WHITESPACE.matcher(text).replaceAll(" ");
    return text.trim();
  }

  /**
   * Additional pass that undoes leetspeak, for hard-block matching only.
   *
   * <p>Kept separate from {@link #normalize} because these substitutions are lossy:
   * "5 star" becomes "s star". That is acceptable when checking a narrow list of
   * unambiguous illegal terms, and unacceptable for anything wider.
   */
  public static String deleet(String normalized) {
    if (normalized == null || normalized.isEmpty()) return "";
    StringBuilder out = new StringBuilder(normalized.length());
    for (char c : normalized.toCharArray()) {
      out.append(switch (c) {
        case '0' -> 'o';
        case '1', '!' -> 'i';
        case '3' -> 'e';
        case '4', '@' -> 'a';
        case '5', '$' -> 's';
        case '7' -> 't';
        case '8' -> 'b';
        default -> c;
      });
    }
    return out.toString();
  }

  /**
   * Joins "g a n j a" into "ganja", leaving ordinary prose untouched.
   *
   * <p>The every-segment-is-one-character condition is what makes this safe. A
   * blanket space strip is what produced "hero in" → "heroin".
   */
  private static String collapseSpacedOutLetters(String text) {
    var matcher = SPACED_OUT_LETTERS.matcher(text);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      out.append(text, last, matcher.start());
      out.append(matcher.group().replace(" ", ""));
      last = matcher.end();
    }
    out.append(text.substring(last));
    return out.toString();
  }
}
