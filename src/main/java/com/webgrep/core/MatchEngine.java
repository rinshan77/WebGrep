package com.webgrep.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts keyword occurrences in a string using one of three pluggable matching strategies.
 *
 * <ul>
 *   <li><b>default</b> - Case-insensitive with Unicode and diacritic support. {@code cafe}
 *       matches {@code Café}, {@code CAFE}, {@code café}. If no match is found via regex, a
 *       simplified (diacritic-stripped, punctuation-removed) fallback pass is attempted.</li>
 *   <li><b>exact</b> - Strict case-sensitive literal match. {@code hello} does not match
 *       {@code Hello}.</li>
 *   <li><b>fuzzy</b> - First tries a normalised substring match; if that fails, splits the
 *       text into words and accepts any word within Levenshtein edit distance 1 (for keywords
 *       ≤ 4 characters) or 2 (longer keywords). Catches common typos and spelling variants.</li>
 * </ul>
 *
 * <p>Compiled {@link java.util.regex.Pattern} objects are cached for the most recently used
 * keyword/mode pair to avoid recompilation on repeated calls from a scan loop.
 */
public class MatchEngine {

    /**
     * Cache of compiled regex patterns, keyed by {@code keyword + "\0" + mode}.
     * Uses {@link ConcurrentHashMap} so the cache is safe to share across threads if needed.
     * Compiling a pattern is expensive; caching avoids recompilation on every page of a crawl.
     */
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Returns (or creates and caches) the compiled regex pattern for the given keyword and mode.
     *
     * <p>For {@code exact} mode: a case-sensitive, literal-quoted pattern.
     * For all other modes: a case-insensitive, Unicode-aware, literal-quoted pattern.
     * {@link Pattern#quote} escapes any regex metacharacters in the keyword so it is always
     * treated as a plain string.
     *
     * @param keyword the search keyword; must not be {@code null}.
     * @param mode    {@code "exact"} for case-sensitive, anything else for case-insensitive.
     * @return the compiled {@link Pattern}.
     */
    private Pattern getPattern(String keyword, String mode) {
        return patternCache.computeIfAbsent(keyword + "\0" + mode, k ->
                mode.equals("exact")
                        ? Pattern.compile(Pattern.quote(keyword))
                        : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /**
     * Counts the number of times {@code keyword} appears in {@code text} using the specified mode.
     *
     * <p><b>Default mode:</b> runs both a regex (case-insensitive, Unicode-aware) pass and a
     * simplified (diacritic-stripped, punctuation-removed) substring pass, returning the higher
     * of the two counts. The higher result is taken because a text like {@code "cafe Café"} with
     * keyword {@code "cafe"} yields only 1 from regex (which misses the accented variant) but 2
     * from the simplified pass.
     *
     * <p><b>Exact mode:</b> case-sensitive regex only.
     *
     * <p><b>Fuzzy mode:</b> delegates to {@link #countFuzzyMatches}.
     *
     * @param text    the string to search; returns 0 if {@code null} or empty.
     * @param keyword the keyword to search for; returns 0 if {@code null} or empty.
     * @param mode    one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @return the number of keyword occurrences found; never negative.
     */
    public int countMatches(String text, String keyword, String mode) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }

        if (mode.equals("exact")) {
            int count = 0;
            Matcher matcher = getPattern(keyword, mode).matcher(text);
            while (matcher.find()) {
                count++;
            }
            return count;
        } else if (mode.equals("fuzzy")) {
            return countFuzzyMatches(text, keyword);
        } else {
            // Default: case-insensitive with Unicode and diacritic support.
            // Regex handles exact case-insensitive matches; the simplified pass catches
            // accented variants (e.g. "cafe" matches "Café"). Both are always counted and
            // the higher result is returned, because a text like "cafe Café" would yield
            // only 1 from regex (missing "Café") but 2 from the simplified pass.
            int count = 0;
            Matcher matcher = getPattern(keyword, mode).matcher(text);
            while (matcher.find()) {
                count++;
            }

            // Skip the simplified pass when the keyword contains ASCII special characters
            // (e.g. "more*", "node.js", ".NET") - stripping them would change the intended
            // search term and produce false positives. The pass is safe when the only
            // non-alphanumeric characters are Unicode diacritics (e.g. "café" → "cafe").
            // Guard is evaluated before superSimplify(text) to avoid a wasted NFD pass over
            // potentially large page text when the simplified path will be skipped anyway.
            if (!hasAsciiSpecialChars(keyword)) {
                // Use simplifyWithSpaces for BOTH keyword and text so that:
                // (a) word boundaries are preserved — "sofa" does not match "so far away"
                //     because the space prevents a contiguous substring match, and
                // (b) multi-word keywords with diacritics work — "cafe latte" finds "café latte".
                // .strip() removes leading/trailing spaces that could arise from non-alphanumeric
                // chars at the keyword boundaries (e.g. keyword "  café  " → "cafe").
                String simpleKeyword = simplifyWithSpaces(keyword).strip();
                // Require at least 2 chars so that keywords like "(C)" or "C++" that reduce to
                // a single letter don't flood results. (ASCII-special keywords are already
                // excluded by hasAsciiSpecialChars above; this guard handles the rare case of
                // a keyword that simplifies to a single alphanumeric char.)
                if (simpleKeyword.length() >= 2
                        && !simplifiedPassIsRedundant(text, simpleKeyword, keyword)) {
                    String simpleText = simplifyWithSpaces(text);
                    int simpleCount = 0;
                    int idx = 0;
                    while ((idx = simpleText.indexOf(simpleKeyword, idx)) != -1) {
                        simpleCount++;
                        idx += simpleKeyword.length();
                    }
                    count = Math.max(count, simpleCount);
                }
            }
            return count;
        }
    }

    /**
     * Finds up to {@code maxSnippets} context excerpts from {@code text} that contain the keyword.
     *
     * <p>Each snippet is built by taking 60 characters before and after the match position,
     * then extending both boundaries to the nearest word edge so snippets never cut mid-word.
     * Ellipsis ({@code ...}) is prepended/appended when the snippet does not reach the
     * start/end of the text.
     *
     * <p><b>Regex pass:</b> uses the same pattern as {@link #countMatches} for {@code default}
     * and {@code exact} modes.
     *
     * <p><b>Simplified pass:</b> runs when mode is not {@code exact} and the result list has
     * not yet reached {@code maxSnippets}. Builds a character-position map from the simplified
     * string back to the original string so snippets contain the original accented characters.
     * For example, searching {@code "Tomas"} will highlight {@code "Tomáš"} in its original form.
     * Running the simplified pass alongside the regex pass (not only as a fallback) ensures that
     * text containing both a plain variant ({@code "cafe"}) and an accented variant ({@code "Café"})
     * produces a snippet for each - consistent with {@link #countMatches} which always runs both
     * passes. In default mode the simplified pass is still skipped when the keyword contains ASCII
     * special characters (e.g. {@code "node.js"}) to avoid false positives; in fuzzy mode it is
     * always allowed because fuzzy matching intentionally strips punctuation.
     *
     * @param text        the string to search.
     * @param keyword     the keyword to search for.
     * @param mode        one of {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @param maxSnippets maximum number of distinct snippets to return.
     * @return a list of up to {@code maxSnippets} context strings; empty if no matches found.
     */
    public List<String> findSnippets(String text, String keyword, String mode, int maxSnippets) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) return List.of();

        String flat = flattenWhitespace(text);

        List<String> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Regex pass (handles default case-insensitive and exact)
        Pattern p = mode.equals("exact") ? getPattern(keyword, "exact") : getPattern(keyword, "default");
        Matcher m = p.matcher(flat);
        while (m.find() && results.size() < maxSnippets) {
            String snippet = buildSnippet(flat, m.start(), m.end());
            if (seen.add(snippet)) results.add(snippet);
        }

        // Simplified pass: catches diacritic variants the regex misses (e.g. "Tomas" matching "Tomáš").
        // Builds a per-character position map from simplified text back to flat so snippet boundaries
        // are accurate in the original string. Runs alongside the regex pass (not as a fallback) so
        // that text with both plain and accented variants (e.g. "cafe Café") produces snippets for
        // each - consistent with countMatches, which always runs both passes. The seen set prevents
        // duplicates when simplified finds the same position as regex.
        //
        // Guard logic:
        //  - Skipped in exact mode (user wants a literal match).
        //  - Skipped when results are already at capacity (results.size() >= maxSnippets) - avoids
        //    the expensive simpleBuf/origPos build when no more snippets can be added.
        //  - In default mode: skipped when the keyword contains ASCII special characters
        //    (e.g. "more*", "node.js") because stripping them changes the intended search term.
        //  - In fuzzy mode: always allowed, even for ASCII-special keywords. Fuzzy mode
        //    intentionally strips specials (mirrors countFuzzyMatches first pass), so
        //    "node.js" should find "nodejs" both in count and in snippets.
        boolean keywordHasAsciiSpecial = hasAsciiSpecialChars(keyword);
        if (results.size() < maxSnippets && !mode.equals("exact") && (!keywordHasAsciiSpecial || mode.equals("fuzzy"))) {
            // In default mode, preserve spaces so the position map respects word boundaries and
            // multi-word keywords find diacritic variants (mirrors the countMatches simplified pass).
            // Fuzzy mode keeps the space-stripping behaviour to match countFuzzyMatches.
            boolean preserveSpaces = mode.equals("default");
            String simpleKeyword = preserveSpaces
                    ? simplifyWithSpaces(keyword).strip()
                    : superSimplify(keyword);
            // Same redundancy check as countMatches: when the simplified pass cannot find
            // anything new, skip building the position map - it is the single most expensive
            // allocation in this class (an int per character of page text).
            if (simpleKeyword.length() >= 2
                    && !(preserveSpaces && simplifiedPassIsRedundant(flat, simpleKeyword, keyword))) {
                StringBuilder simpleBuf = new StringBuilder(flat.length());
                int[] origPos = new int[flat.length() * 2 + 1]; // *2 for rare ligature decomposition
                int sLen = 0;
                boolean lastWasSpace = false;
                for (int ci = 0; ci < flat.length(); ci++) {
                    char c = flat.charAt(ci);
                    boolean alnum = false;   // this source char produced an alphanumeric
                    boolean visible = false; // it produced something that is not a combining mark
                    if (c < 128) {
                        // ASCII fast path: NFD is a no-op for ASCII, and no ASCII char is a mark.
                        visible = true;
                        char lc = Character.toLowerCase(c);
                        if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) {
                            if (sLen < origPos.length) { origPos[sLen++] = ci; simpleBuf.append(lc); alnum = true; }
                        }
                    } else {
                        String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                        for (char nc : nfd.toCharArray()) {
                            int type = Character.getType(nc);
                            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                                    || type == Character.ENCLOSING_MARK) continue;
                            visible = true;
                            char lc = Character.toLowerCase(nc);
                            if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) {
                                if (sLen < origPos.length) { origPos[sLen++] = ci; simpleBuf.append(lc); alnum = true; }
                            }
                        }
                    }
                    // Mirror simplifyWithSpaces exactly: every run of non-alphanumeric characters
                    // collapses to a single space. Dropping them instead (the previous behaviour)
                    // built "realtime" here while countMatches saw "real time", so a multi-word
                    // keyword spanning punctuation was counted but yielded no snippet.
                    // Combining marks contribute nothing at all, matching the \p{M} strip.
                    if (alnum) {
                        lastWasSpace = false;
                    } else if (preserveSpaces && visible && !lastWasSpace) {
                        if (sLen < origPos.length) { origPos[sLen++] = ci; simpleBuf.append(' '); lastWasSpace = true; }
                    }
                }
                String simpleFlat = simpleBuf.toString();
                int idx = 0;
                while (results.size() < maxSnippets) {
                    idx = simpleFlat.indexOf(simpleKeyword, idx);
                    if (idx < 0) break;
                    int flatStart = origPos[idx];
                    int flatEnd = origPos[Math.min(idx + simpleKeyword.length() - 1, sLen - 1)] + 1;
                    String snippet = buildSnippet(flat, flatStart, flatEnd);
                    if (seen.add(snippet)) results.add(snippet);
                    idx += simpleKeyword.length();
                }
            }
        }

        return results;
    }

    /**
     * Collapses every run of space, CR, LF, tab, and U+00A0 into a single space, then trims.
     *
     * <p>Snippets are printed on one line, so the source text is flattened first. U+00A0
     * (non-breaking space) is included because SPA pages deliver it via {@code textContent},
     * where it would otherwise survive into snippet text as an invisible character.
     *
     * <p>This runs unconditionally on every page that has a match, over the full page text.
     * As a pair of {@code replaceAll} calls it was the dominant cost of {@link #findSnippets} -
     * two regex passes plus two intermediate copies of a multi-megabyte string - so it is a
     * single character pass instead.
     *
     * @param text the raw extracted text.
     * @return the flattened, trimmed text.
     */
    private static String flattenWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t' || c == ' ') {
                if (!lastWasSpace) { sb.append(' '); lastWasSpace = true; }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        // Matches the trailing .trim() of the original expression: at most one leading and one
        // trailing space can remain after collapsing.
        int from = 0, to = sb.length();
        while (from < to && sb.charAt(from) <= ' ') from++;
        while (to > from && sb.charAt(to - 1) <= ' ') to--;
        return sb.substring(from, to);
    }

    /**
     * Extracts a context excerpt centred on the character range [{@code start}, {@code end})
     * within {@code flat}.
     *
     * <p>Takes up to 60 characters before and after the match, then expands both boundaries
     * outward to the nearest space so the snippet does not cut a word in half. Adds
     * {@code "..."} at the beginning or end when the boundary does not reach the edge of the text.
     *
     * @param flat  the whitespace-flattened source string.
     * @param start the index of the first character of the match.
     * @param end   the index one past the last character of the match.
     * @return a trimmed snippet string.
     */
    private String buildSnippet(String flat, int start, int end) {
        int lo = Math.max(0, start - 60);
        int hi = Math.min(flat.length(), end + 60);
        while (lo > 0 && flat.charAt(lo - 1) != ' ') lo--;
        while (hi < flat.length() && flat.charAt(hi) != ' ') hi++;
        String snippet = (lo > 0 ? "..." : "") + flat.substring(lo, hi).trim() + (hi < flat.length() ? "..." : "");
        // Strip C0 control characters (0x00–0x1F, 0x7F) to prevent terminal escape injection
        // from binary or minified content that survived Tika/Jsoup extraction.
        snippet = snippet.replaceAll("[\\x00-\\x1F\\x7F]", "");
        // Cap length to prevent multi-KB snippets from minified JS or data-dense pages.
        if (snippet.length() > 500) snippet = snippet.substring(0, 497) + "...";
        return snippet;
    }

    /**
     * Counts keyword occurrences in fuzzy mode.
     *
     * <p><b>First pass:</b> uses a simplified (diacritic-stripped, punctuation-removed) substring
     * search. If any matches are found this way, returns the count immediately.
     *
     * <p><b>Second pass (Levenshtein):</b> if no substring matches were found, splits the text
     * into individual words and counts words within edit distance 1 (for keywords ≤ 4 characters)
     * or edit distance 2 (for longer keywords). An early exit skips words whose length difference
     * alone exceeds the threshold.
     *
     * @param text    the string to search.
     * @param keyword the keyword to match fuzzily.
     * @return the number of approximate matches found.
     */
    private int countFuzzyMatches(String text, String keyword) {
        String superSimpleKeyword = superSimplify(keyword);
        String superSimpleText = superSimplify(text);

        // Same guard as default mode: a 1-char simplified keyword (e.g. "C++" → "c") is not meaningful.
        if (superSimpleKeyword.length() < 2) return 0;

        int count = 0;
        int idx = 0;
        while ((idx = superSimpleText.indexOf(superSimpleKeyword, idx)) != -1) {
            count++;
            idx += superSimpleKeyword.length();
        }

        if (count == 0) {
            String normalizedKeyword = superSimpleKeyword;
            String normalizedTextWithSpaces = simplifyWithSpaces(text);
            String[] words = normalizedTextWithSpaces.split("\\s+");

            int threshold = normalizedKeyword.length() <= 4 ? 1 : 2;
            for (String word : words) {
                if (word.isEmpty()) continue;
                // skip words whose length difference alone exceeds the threshold
                if (Math.abs(word.length() - normalizedKeyword.length()) > threshold) continue;
                if (levenshteinDistance(word, normalizedKeyword) <= threshold) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if {@code keyword} contains any ASCII character that is not a letter,
     * digit, or space - for example {@code '*'}, {@code '.'}, {@code '+'}, {@code '#'}.
     *
     * <p>Used to gate the simplified (diacritic-stripping) pass in default mode: stripping such
     * characters from the keyword before matching would change its meaning and produce false
     * positives (e.g. {@code "more*"} → {@code "more"}).
     */
    private static boolean hasAsciiSpecialChars(String keyword) {
        return keyword.chars().anyMatch(c -> c < 128 && !Character.isLetterOrDigit(c) && c != ' ');
    }

    /**
     * Returns {@code true} if every character of {@code s} is ASCII. Exits at the first
     * non-ASCII character, so the common "page contains a curly quote near the top" case
     * costs almost nothing.
     */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /**
     * Returns {@code true} when the diacritic-stripping simplified pass provably cannot find
     * anything the case-insensitive regex pass has not already found, so it can be skipped.
     *
     * <p>Simplification only lowercases and turns runs of non-alphanumeric characters into a
     * single space; it never joins two alphanumeric runs that were not already adjacent. So when
     * the text is pure ASCII and the simplified keyword is a single all-ASCII word, every
     * simplified match corresponds to the same contiguous characters in the original text - which
     * the case-insensitive regex matches too. The count can therefore only be equal or lower, and
     * {@code countMatches} keeps the higher of the two.
     *
     * <p>All three conditions are load-bearing:
     * <ul>
     *   <li>Non-ASCII text: keyword {@code cafe} must still find {@code café}.</li>
     *   <li>Non-ASCII keyword: keyword {@code café} must still find {@code cafe}.</li>
     *   <li>Multi-word keyword: keyword {@code real time} must still find {@code real-time}.</li>
     * </ul>
     *
     * @param text          the text being searched.
     * @param simpleKeyword the already-simplified, stripped keyword.
     * @param rawKeyword    the keyword as the user typed it.
     * @return {@code true} if the simplified pass can be skipped without changing the result.
     */
    private static boolean simplifiedPassIsRedundant(String text, String simpleKeyword, String rawKeyword) {
        return simpleKeyword.indexOf(' ') < 0 && isAscii(rawKeyword) && isAscii(text);
    }

    /**
     * Strips diacritics, lowercases, and removes all non-alphanumeric characters from {@code input}.
     *
     * <p>Uses Unicode NFD normalisation to decompose characters into base letter + combining
     * mark(s), then discards all combining marks ({@code \p{M}}). The result contains only
     * ASCII letters and digits.
     *
     * <p>Examples: {@code "Café"} → {@code "cafe"}, {@code "C++"} → {@code "c"},
     * {@code "Tomáš"} → {@code "tomas"}.
     *
     * @param input the string to simplify; {@code null} is treated as {@code ""}.
     * @return the simplified string; never {@code null}.
     */
    public String superSimplify(String input) {
        return simplify(input, false);
    }

    /**
     * Same as {@link #superSimplify} but preserves spaces so the result can be split into words.
     * Used by fuzzy mode to tokenise the text before Levenshtein matching, and by the default-mode
     * simplified pass to find diacritic variants without collapsing across word boundaries.
     *
     * <p>Non-alphanumeric, non-space characters are replaced with a space rather than removed,
     * so word boundaries around punctuation are preserved. Any run of whitespace (including tabs
     * and newlines from Tika-extracted PDF/DOCX content) is collapsed to a single ASCII space so
     * that multi-word keywords match across line breaks in raw document text.
     *
     * @param input the string to simplify; {@code null} is treated as {@code ""}.
     * @return the simplified string with whitespace collapsed to single spaces; never {@code null}.
     */
    private String simplifyWithSpaces(String input) {
        return simplify(input, true);
    }

    /**
     * Shared implementation behind {@link #superSimplify} and {@link #simplifyWithSpaces}.
     *
     * <p>Strips diacritics (NFD decomposition, then discard combining marks), lowercases, and
     * reduces the result to ASCII letters and digits. When {@code keepSpaces} is {@code true},
     * each run of discarded characters collapses to a single space; when {@code false} they are
     * dropped entirely.
     *
     * <p>This is a single character pass rather than the chain of {@code replaceAll} calls it
     * replaces. That chain allocated an intermediate string per stage and ran a regex engine over
     * the whole page; on multi-megabyte pages it made default mode an order of magnitude slower
     * than exact mode. Text that is entirely ASCII skips {@link Normalizer} altogether, since NFD
     * is a no-op there and no ASCII character is a combining mark.
     *
     * @param input      the string to simplify; {@code null} is treated as {@code ""}.
     * @param keepSpaces whether to emit a collapsed space for runs of discarded characters.
     * @return the simplified string; never {@code null}.
     */
    private static String simplify(String input, boolean keepSpaces) {
        if (input == null) return "";

        boolean ascii = true;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) > 127) { ascii = false; break; }
        }
        String src = ascii ? input : Normalizer.normalize(input, Normalizer.Form.NFD);

        StringBuilder sb = new StringBuilder(src.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (!ascii && c > 127) {
                int type = Character.getType(c);
                if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK) continue;
            }
            char lc = Character.toLowerCase(c);
            if ((lc >= 'a' && lc <= 'z') || (lc >= '0' && lc <= '9')) {
                sb.append(lc);
                lastWasSpace = false;
            } else if (keepSpaces && !lastWasSpace) {
                sb.append(' ');
                lastWasSpace = true;
            }
        }
        return sb.toString();
    }

    /**
     * Computes the Levenshtein edit distance between two strings using dynamic programming.
     *
     * <p>The edit distance is the minimum number of single-character insertions, deletions,
     * or substitutions required to transform {@code s1} into {@code s2}. Used by fuzzy mode
     * to find words that are close to the keyword.
     *
     * @param s1 the first string.
     * @param s2 the second string.
     * @return the edit distance; 0 means the strings are identical.
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(Math.min(
                                    dp[i - 1][j] + 1,
                                    dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1));
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
