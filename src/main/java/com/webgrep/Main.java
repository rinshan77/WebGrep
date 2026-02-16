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

public class Main {
    private static Tika TIKA;
    private static final int MAX_PAGES = 1000; // Limit total pages to prevent runaway crawl
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
                String content = "";
                List<String> links = new ArrayList<>();

                URLConnection connection = new URL(current.url).openConnection();
                // Add User-Agent to the manual connection to avoid 403s from some sites (like Cloudflare)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                // Check size if possible
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_FILE_SIZE) {
                    continue;
                }

                String contentType = connection.getContentType();
                // If contentType is null, Tika will try to detect it from the stream

                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                    // Jsoup handles the connection and parsing
                    Document doc = Jsoup.connect(current.url)
                            .timeout(5000)
                            .followRedirects(true)
                            .ignoreContentType(true)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Cache-Control", "no-cache")
                            .header("Pragma", "no-cache")
                            .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                            .get();
                    content = doc.text();
                    // System.out.println("[DEBUG] Processing URL: " + current.url + " Content length: " + content.length());

                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        for (Element element : elements) {
                            String link = element.attr("abs:href");
                            if (link.isEmpty()) {
                                // Try to construct abs href manually if Jsoup failed due to base URI issues
                                String href = element.attr("href");
                                if (!href.isEmpty()) {
                                    try {
                                        URL baseUrl = new URL(current.url);
                                        URL absUrl = new URL(baseUrl, href);
                                        link = absUrl.toString();
                                    } catch (Exception e) {
                                        // Ignore
                                    }
                                }
                            }
                            String normalizedLink = normalizeUrl(link);
                            if (!normalizedLink.isEmpty()) {
                                // Filter out common non-article/non-content links to focus on actual pages
                                if (!isIgnoredLink(normalizedLink)) {
                                    // System.out.println("[DEBUG] Adding link from " + current.url + " -> " + normalizedLink);
                                    links.add(normalizedLink);
                                }
                            }
                        }
                    }
                }

                // If content is empty (not HTML or Jsoup failed to get text), use Tika
                // ALSO force Tika for PDF to ensure deep extraction
                if (content.isEmpty() || (contentType != null && contentType.contains("application/pdf"))) {
                    try (InputStream is = connection.getInputStream()) {
                        // Check size during download if not provided by headers
                        byte[] buffer = new byte[8192];
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        int bytesRead;
                        long totalRead = 0;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            totalRead += bytesRead;
                            if (totalRead > MAX_FILE_SIZE) {
                                break;
                            }
                            baos.write(buffer, 0, bytesRead);
                        }

                        if (totalRead <= MAX_FILE_SIZE) {
                            try (InputStream bis = new java.io.ByteArrayInputStream(baos.toByteArray())) {
                                org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
                                metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, current.url);
                                if (contentType != null) {
                                    metadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, contentType);
                                }

                                // Configure Tika for exhaustive extraction
                                TIKA.setMaxStringLength(-1);

                                // For PDF documents, we might want to try to extract even more details if Tika core allows.
                                // The standard parseToString uses the AutoDetectParser.
                                String tikaContent = TIKA.parseToString(bis, metadata);

                                if (content.isEmpty() || (contentType != null && contentType.contains("application/pdf"))) {
                                    content = tikaContent;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore
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
                if (current.depth == 0) {
                    System.err.println("Error connecting to start URL " + current.url + ": " + e.getMessage());
                }
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
}
