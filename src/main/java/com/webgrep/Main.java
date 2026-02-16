package com.webgrep;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.tika.Tika;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Simon D.
 **/
public class Main {
    private static Tika TIKA;
    private static final int MAX_PAGES = 1000; // Reduced to avoid runaway crawls
    private static final int MAX_LINKS_PER_PAGE = 500; // Limit links per page to avoid getting stuck
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit

    public static void main(String[] args) {
        setupLogging();
        TIKA = new Tika();
        setupSsl();
        if (args.length < 3) {
            System.out.println("Usage: java -jar WebGrep.jar <URL> <keyword> <depth> [fuzzy|exact]");
            return;
        }

        String startUrl = args[0];
        String keyword = args[1];
        int maxDepth;
        try {
            maxDepth = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Depth must be an integer.");
            return;
        }

        String mode = args.length >= 4 ? args[3].toLowerCase() : "default";

        Map<String, Integer> results = crawl(normalizeUrl(startUrl), keyword, maxDepth, mode);

        int totalCount = results.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("Total count: " + totalCount);
        if (totalCount > 0) {
            System.out.println("Found in:");
            // Sort by URL to help see subcategories/structure better
            List<String> sortedUrls = new ArrayList<>(results.keySet());
            Collections.sort(sortedUrls);

            for (String url : sortedUrls) {
                int count = results.get(url);
                if (count > 0) {
                    System.out.println(url + " (" + count + ")");
                }
            }
        }
    }

    private static void setupLogging() {
        // Suppress PDFBox and other noisy loggers
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        // For SLF4J (slf4j-simple)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        // For Log4j2
        System.setProperty("log4j2.level", "error");

        // Use reflection to set PDFBox logging if available, to avoid direct dependency if not needed
        // But we have tika-parsers-standard-package which includes PDFBox
        try {
            java.util.logging.Logger pdfboxLogger = java.util.logging.Logger.getLogger("org.apache.pdfbox");
            pdfboxLogger.setLevel(java.util.logging.Level.OFF);
            java.util.logging.Logger fontboxLogger = java.util.logging.Logger.getLogger("org.apache.fontbox");
            fontboxLogger.setLevel(java.util.logging.Level.OFF);
        } catch (Exception e) {
            // Ignore
        }

        // Suppress java.util.logging (JUL) globally as well
        try {
            java.util.logging.LogManager.getLogManager().reset();
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            rootLogger.setLevel(java.util.logging.Level.OFF);
            for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private static void setupSsl() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            // Ignore
        }
    }

    private static Map<String, Integer> crawl(String startUrl, String keyword, int maxDepth, String mode) {
        Map<String, Integer> results = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<UrlDepth> queue = new LinkedList<>();

        String normalizedStart = normalizeUrl(startUrl);
        queue.add(new UrlDepth(normalizedStart, 0));
        visited.add(normalizedStart);

        int pagesCrawled = 0;

        while (!queue.isEmpty() && pagesCrawled < MAX_PAGES) {
            UrlDepth current = queue.poll();
            pagesCrawled++;

            try {
                System.err.println("Crawling: " + current.url + " (depth: " + current.depth + ")");
                // Politeness delay
                Thread.sleep(100);

                String content = "";
                List<String> links = new ArrayList<>();

                org.jsoup.Connection.Response response = Jsoup.connect(current.url)
                        .timeout(10000)
                        .followRedirects(true)
                        .ignoreContentType(true)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9,bs;q=0.8,sr;q=0.7,hr;q=0.6")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                        .execute();

                String contentType = response.contentType();
                byte[] body = response.bodyAsBytes();

                if (body.length > MAX_FILE_SIZE) {
                    continue;
                }

                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                    Document doc = response.parse();
                    content = doc.text();

                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        int linkCount = 0;
                        for (Element element : elements) {
                            if (linkCount++ >= MAX_LINKS_PER_PAGE) break;
                            String link = element.attr("abs:href");
                            if (link.isEmpty()) {
                                String href = element.attr("href");
                                if (!href.isEmpty()) {
                                    try {
                                        URL baseUrl = new URL(current.url);
                                        URL absUrl = new URL(baseUrl, href);
                                        link = absUrl.toString();
                                    } catch (Exception e) {}
                                }
                            }
                            String normalizedLink = normalizeUrl(link);
                            if (!normalizedLink.isEmpty() && !isIgnoredLink(normalizedLink)) {
                                links.add(normalizedLink);
                            }
                        }
                    }
                } else {
                    // Use Tika for non-HTML content (PDF, etc)
                    try (InputStream bis = new java.io.ByteArrayInputStream(body)) {
                        org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
                        metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, current.url);
                        if (contentType != null) {
                            metadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, contentType);
                        }
                        TIKA.setMaxStringLength(-1);
                        content = TIKA.parseToString(bis, metadata);
                    } catch (Throwable t) {
                        // Fallback to UTF-8 if Tika fails
                        content = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                int count = countMatches(content, keyword, mode);
                if (count > 0) {
                    results.put(current.url, count);
                }

                if (current.depth < maxDepth) {
                    for (String link : links) {
                        if (visited.size() < MAX_PAGES && !visited.contains(link)) {
                            visited.add(link);
                            queue.add(new UrlDepth(link, current.depth + 1));
                        }
                    }
                }

            } catch (Exception e) {
                // Silently skip problematic URLs
            }
        }

        return results;
    }

    private static String normalizeUrl(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "";
        }
        String original = urlString;
        if (!urlString.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            urlString = "http://" + urlString;
        }
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol().toLowerCase();
            String host = url.getHost().toLowerCase();
            int port = url.getPort();
            String path = url.getPath();
            String query = url.getQuery();

            // Normalize trailing slash in path
            if (path.isEmpty()) {
                path = "/";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(protocol).append("://").append(host);
            if (port != -1 && port != url.getDefaultPort()) {
                sb.append(":").append(port);
            }
            sb.append(path);
            if (query != null) {
                sb.append("?").append(query);
            }
            // Fragment is intentionally removed
            return sb.toString();
        } catch (Exception e) {
            return urlString;
        }
    }

    private static int countMatches(String text, String keyword, String mode) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }

        // Pre-process text to handle common PDF extraction issues like missing spaces between words
        // Some PDF extractors might join words together if they are visually close.
        // While we can't perfectly fix it, we can ensure the keyword matching is robust.

        if (mode.equals("exact")) {
            int count = 0;
            // Case-sensitive, literal match
            Pattern pattern = Pattern.compile(Pattern.quote(keyword));
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }
            return count;
        } else if (mode.equals("fuzzy")) {
            return countFuzzyMatches(text, keyword);
        } else {
            // Default: case-insensitive
            int count = 0;
            // Use UNICODE_CASE and CASE_INSENSITIVE for better international support
            Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }

            // Fallback for PDF-specific issues where words might be merged: "KeywordIsHere" instead of "Keyword Is Here"
            // If the standard matcher didn't find as many matches as a simple "contains" check would,
            // or if we want to be even more aggressive.
            // Actually Pattern.quote(keyword) already handles it.

            return count;
        }
    }

    private static int countFuzzyMatches(String text, String keyword) {
        String superSimpleKeyword = superSimplify(keyword);
        String superSimpleText = superSimplify(text);

        if (superSimpleKeyword.isEmpty()) return 0;

        int count = 0;
        int idx = 0;
        while ((idx = superSimpleText.indexOf(superSimpleKeyword, idx)) != -1) {
            count++;
            idx += superSimpleKeyword.length();
        }

        // If no exact matches (even after super simplification), try typo matching
        if (count == 0) {
            String normalizedKeyword = superSimpleKeyword;
            String normalizedTextWithSpaces = simplifyWithSpaces(text);
            String[] words = normalizedTextWithSpaces.split("\\s+");

            int threshold = normalizedKeyword.length() <= 4 ? 1 : 2;
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (levenshteinDistance(word, normalizedKeyword) <= threshold) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String superSimplify(String input) {
        if (input == null) return "";
        // 1. Normalize diacritics
        String normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        // 2. Lowercase and remove all non-alphanumeric
        return normalized.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String simplifyWithSpaces(String input) {
        if (input == null) return "";
        // 1. Normalize diacritics
        String normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        // 2. Lowercase and remove symbols, but keep spaces
        return normalized.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
    }

    private static int levenshteinDistance(String s1, String s2) {
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

    private static boolean isIgnoredLink(String url) {
        String lower = url.toLowerCase();
        // Remove fragment for comparison if not already removed by normalizeUrl
        int hashIdx = lower.indexOf('#');
        if (hashIdx != -1) {
            lower = lower.substring(0, hashIdx);
        }

        // Only ignore clearly non-content static assets.
        // Be careful not to ignore .php, .aspx, .jsp etc.
        return lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".woff")
                || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".otf")
                || lower.endsWith(".mp3") || lower.endsWith(".mp4") || lower.endsWith(".wav")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".wmv")
                || lower.contains("googleads") || lower.contains("doubleclick")
                || lower.contains("facebook.com/sharer") || lower.contains("twitter.com/intent/tweet")
                || lower.contains("linkedin.com/share") || lower.contains("pinterest.com/pin");
    }

    private static class UrlDepth {
        String url;
        int depth;

        UrlDepth(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
} // Code belongs to Simon D.