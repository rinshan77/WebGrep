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
    private static final int MAX_PAGES = 5000;
    private static final int MAX_LINKS_PER_PAGE = 5000; // Further increased limit
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

        Map<String, Integer> results = crawl(normalizeUrl(startUrl, null), keyword, maxDepth, mode);

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

        String normalizedStart = normalizeUrl(startUrl, null);
        queue.add(new UrlDepth(normalizedStart, 0));
        visited.add(normalizedStart);

        int pagesCrawled = 0;

        while (!queue.isEmpty() && pagesCrawled < MAX_PAGES) {
            UrlDepth current = queue.poll();
            pagesCrawled++;

            try {
                // Politeness delay
                Thread.sleep(100);

                String content = "";
                List<String> links = new ArrayList<>();

                org.jsoup.Connection.Response response = Jsoup.connect(current.url)
                        .timeout(15000)
                        .followRedirects(true)
                        .ignoreContentType(true)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9,bs;q=0.8,sr;q=0.7,hr;q=0.6")
                        .header("Cache-Control", "no-cache")
                        .header("Connection", "keep-alive")
                        .header("Pragma", "no-cache")
                        .header("Sec-Ch-Ua", "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Linux\"")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .header("Upgrade-Insecure-Requests", "1")
                        .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                        .execute();

                String contentType = response.contentType();
                byte[] body = response.bodyAsBytes();

                if (body.length > MAX_FILE_SIZE) {
                    continue;
                }

                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                    Document doc = response.parse();

                    if (doc.title().contains("Just a moment...") || doc.text().contains("Enable JavaScript and cookies to continue")) {
                        System.err.println("Warning: Cloudflare challenge detected for " + current.url);
                    }

                    // Extract text from body, title and meta description for better coverage
                    StringBuilder sb = new StringBuilder();
                    sb.append(doc.title()).append(" ");
                    Element bodyTag = doc.body();
                    if (bodyTag != null) {
                        sb.append(bodyTag.text());
                    } else {
                        sb.append(doc.text());
                    }
                    sb.append(" ").append(doc.select("meta[name=description]").attr("content"));
                    sb.append(" ").append(doc.select("meta[name=keywords]").attr("content"));
                    content = sb.toString();

                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        for (Element element : elements) {
                            if (links.size() >= MAX_LINKS_PER_PAGE) break;
                            String link = element.absUrl("href");
                            if (link.isEmpty()) {
                                link = element.attr("href");
                            }
                            String normalizedLink = normalizeUrl(link, current.url);
                            if (!normalizedLink.isEmpty() && !isIgnoredLink(normalizedLink)) {
                                if (normalizedLink.contains("klix.ba")) {
                                     System.err.println("Extracted: " + normalizedLink);
                                }
                                links.add(normalizedLink);
                            }
                        }

                        // Fallback Regex link extraction for links Jsoup might miss (e.g. malformed HTML)
                        if (links.size() < MAX_LINKS_PER_PAGE) {
                            Pattern linkPattern = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
                            Matcher linkMatcher = linkPattern.matcher(response.body());
                            while (linkMatcher.find() && links.size() < MAX_LINKS_PER_PAGE) {
                                String href = linkMatcher.group(1);
                                String normalizedLink = normalizeUrl(href, current.url);
                                if (!normalizedLink.isEmpty() && !isIgnoredLink(normalizedLink)) {
                                    links.add(normalizedLink);
                                }
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

                        // Use Parser instead of Tika facade for more control if needed,
                        // but Tika facade should work if configured correctly.
                        // Ensure we use the shared instance and it's properly initialized.
                        TIKA.setMaxStringLength(-1);
                        content = TIKA.parseToString(bis, metadata);

                        if (content == null || content.trim().isEmpty()) {
                            // Try again without content type hint if it failed
                            try (InputStream bis2 = new java.io.ByteArrayInputStream(body)) {
                                content = TIKA.parseToString(bis2);
                            }
                        }
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
                // System.err.println("Error crawling " + current.url + ": " + e.getMessage());
            }
        }

        return results;
    }

    private static String normalizeUrl(String urlString, String baseUrlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "";
        }
        if (urlString.startsWith("//")) {
            if (baseUrlString != null && baseUrlString.startsWith("https")) {
                urlString = "https:" + urlString;
            } else {
                urlString = "http:" + urlString;
            }
        }
        if (!urlString.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            if (baseUrlString != null && !baseUrlString.isEmpty()) {
                try {
                    URL base = new URL(baseUrlString);
                    URL abs = new URL(base, urlString);
                    urlString = abs.toString();
                } catch (Exception e) {
                    if (!urlString.startsWith("http")) {
                        urlString = "http://" + urlString;
                    }
                }
            } else if (!urlString.startsWith("http")) {
                urlString = "http://" + urlString;
            }
        }
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol().toLowerCase();
            String host = url.getHost().toLowerCase();
            if (host.isEmpty()) return "";

            int port = url.getPort();
            String path = url.getPath();
            String query = url.getQuery();

            // Normalize trailing slash in path if empty
            if (path.isEmpty()) {
                path = "/";
            }

            // Remove multiple slashes in path (except at beginning)
            path = path.replaceAll("/{2,}", "/");

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

            // Fallback for cases where standard matching might fail due to invisible characters or formatting
            if (count == 0) {
                String simpleKeyword = superSimplify(keyword);
                String simpleText = superSimplify(text);
                if (!simpleKeyword.isEmpty()) {
                    int idx = 0;
                    while ((idx = simpleText.indexOf(simpleKeyword, idx)) != -1) {
                        count++;
                        idx += simpleKeyword.length();
                    }
                }
            }
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
        // Remove fragment for comparison
        int hashIdx = lower.indexOf('#');
        if (hashIdx != -1) {
            lower = lower.substring(0, hashIdx);
        }

        // Only ignore clearly non-content static assets.
        // Be careful not to ignore .php, .aspx, .jsp etc.
        // Also ensure we don't ignore files that might contain text like .pdf, .doc, etc.
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt")) {
            return false;
        }

        return lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".woff")
                || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".otf")
                || lower.endsWith(".mp3") || lower.endsWith(".mp4") || lower.endsWith(".wav")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".wmv")
                || lower.contains("googleads") || lower.contains("doubleclick")
                || lower.contains("facebook.com/sharer") || lower.contains("twitter.com/intent/tweet")
                || lower.contains("linkedin.com/share") || lower.contains("pinterest.com/pin")
                || lower.contains("video.klix.ba") || lower.contains("static.klix.ba")
                || lower.contains("sdk.privacy-center.org");
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