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
    private static int MAX_PAGES = 5000;
    private static final int MAX_LINKS_PER_PAGE = 5000;
    private static long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit
    private static int TIMEOUT_MS = 20000;
    private static boolean ALLOW_EXTERNAL = false;
    private static boolean INSECURE = false;
    private static String OUTPUT_FORMAT = "text";

    public static void main(String[] args) {
        setupLogging();
        TIKA = new Tika();

        Map<String, String> params = parseArgs(args);
        if (params.containsKey("help") || args.length == 0) {
            printHelp();
            return;
        }

        String startUrl = params.get("url");
        String keyword = params.get("keyword");
        if (startUrl == null || keyword == null) {
            System.err.println("Error: --url and --keyword are required.");
            printHelp();
            return;
        }

        int maxDepth = Integer.parseInt(params.getOrDefault("depth", "1"));
        String mode = params.getOrDefault("mode", "default");
        MAX_PAGES = Integer.parseInt(params.getOrDefault("max-pages", "5000"));
        MAX_FILE_SIZE = Long.parseLong(params.getOrDefault("max-bytes", String.valueOf(10 * 1024 * 1024)));
        TIMEOUT_MS = Integer.parseInt(params.getOrDefault("timeout-ms", "20000"));
        ALLOW_EXTERNAL = params.containsKey("allow-external");
        INSECURE = params.containsKey("insecure");
        OUTPUT_FORMAT = params.getOrDefault("output", "text").toLowerCase();

        if (INSECURE) {
            setupSsl();
        }

        CrawlResult crawlResult = crawl(normalizeUrl(startUrl, null), keyword, maxDepth, mode);
        
        if ("json".equals(OUTPUT_FORMAT)) {
            printJsonOutput(crawlResult, startUrl, keyword, maxDepth, mode);
        } else {
            printTextOutput(crawlResult);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    params.put(key, args[i + 1]);
                    i++;
                } else {
                    params.put(key, "true");
                }
            } else if (args[i].equals("-h")) {
                params.put("help", "true");
            }
        }
        return params;
    }

    private static void printHelp() {
        System.out.println("WebGrep - A simple web crawler and keyword searcher");
        System.out.println("\nUsage: java -jar WebGrep.jar --url <URL> --keyword <keyword> [options]");
        System.out.println("\nOptions:");
        System.out.println("  --url <URL>          The starting URL (required)");
        System.out.println("  --keyword <word>     The keyword to search for (required)");
        System.out.println("  --depth <n>          Maximum crawl depth (default: 1)");
        System.out.println("  --mode <mode>        Match mode: default (case-insensitive), exact, or fuzzy");
        System.out.println("  --max-pages <n>      Maximum number of pages to crawl (default: 5000)");
        System.out.println("  --max-bytes <n>      Maximum file size in bytes (default: 10MB)");
        System.out.println("  --timeout-ms <n>     Request timeout in milliseconds (default: 20000)");
        System.out.println("  --allow-external     Allow crawling external domains");
        System.out.println("  --insecure           Trust all SSL certificates (dangerous)");
        System.out.println("  --output <format>    Output format: text (default) or json");
        System.out.println("  -h, --help           Show this help message");
    }

    private static void printTextOutput(CrawlResult crawlResult) {
        Map<String, Integer> results = crawlResult.results;
        int totalCount = results.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("--- WebGrep Results ---");
        System.out.println("Total matches found: " + totalCount);
        System.out.println("Pages visited: " + crawlResult.visitedCount);
        System.out.println("Pages successfully parsed: " + crawlResult.parsedCount);
        System.out.println("Pages skipped/failed: " + (crawlResult.visitedCount - crawlResult.parsedCount));
        
        if (totalCount > 0) {
            System.out.println("\nFound in:");
            results.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> System.out.println(entry.getKey() + " (" + entry.getValue() + ")"));
        }

        if (!crawlResult.blockedUrls.isEmpty()) {
            System.out.println("\nNotice: Some URLs were blocked or could not be fully processed:");
            crawlResult.blockedUrls.forEach((url, reason) -> {
                System.out.println("Couldn't retrieve all links from the URL, blocked because of " + reason + ": " + url);
            });
        }
    }

    private static void printJsonOutput(CrawlResult crawlResult, String url, String keyword, int depth, String mode) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"query\": {\n");
        json.append("    \"url\": \"").append(escapeJson(url)).append("\",\n");
        json.append("    \"keyword\": \"").append(escapeJson(keyword)).append("\",\n");
        json.append("    \"depth\": ").append(depth).append(",\n");
        json.append("    \"mode\": \"").append(escapeJson(mode)).append("\"\n");
        json.append("  },\n");
        json.append("  \"stats\": {\n");
        json.append("    \"total_matches\": ").append(crawlResult.results.values().stream().mapToInt(Integer::intValue).sum()).append(",\n");
        json.append("    \"pages_visited\": ").append(crawlResult.visitedCount).append(",\n");
        json.append("    \"pages_parsed\": ").append(crawlResult.parsedCount).append(",\n");
        json.append("    \"pages_blocked\": ").append(crawlResult.blockedUrls.size()).append("\n");
        json.append("  },\n");
        json.append("  \"results\": [\n");
        
        List<Map.Entry<String, Integer>> sortedResults = new ArrayList<>(crawlResult.results.entrySet());
        sortedResults.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        
        for (int i = 0; i < sortedResults.size(); i++) {
            Map.Entry<String, Integer> entry = sortedResults.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ").append(entry.getValue()).append(" }");
            if (i < sortedResults.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"blocked\": [\n");
        List<Map.Entry<String, String>> blockedList = new ArrayList<>(crawlResult.blockedUrls.entrySet());
        for (int i = 0; i < blockedList.size(); i++) {
            Map.Entry<String, String> entry = blockedList.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"reason\": \"").append(escapeJson(entry.getValue())).append("\" }");
            if (i < blockedList.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        System.out.println(json.toString());
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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

    private static CrawlResult crawl(String startUrl, String keyword, int maxDepth, String mode) {
        Map<String, Integer> results = new LinkedHashMap<>();
        Map<String, String> blockedUrls = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<UrlDepth> queue = new LinkedList<>();

        String normalizedStart = normalizeUrl(startUrl, null);
        String startHost = "";
        try {
            startHost = new URL(normalizedStart).getHost();
        } catch (Exception e) {}

        queue.add(new UrlDepth(normalizedStart, 0));
        visited.add(normalizedStart);

        int pagesCrawled = 0;
        int pagesParsed = 0;

        while (!queue.isEmpty() && pagesCrawled < MAX_PAGES) {
            UrlDepth current = queue.poll();
            pagesCrawled++;

            try {
                // Politeness delay
                Thread.sleep(100);

                String content = "";
                List<String> links = new ArrayList<>();

                org.jsoup.Connection.Response response = Jsoup.connect(current.url)
                        .timeout(TIMEOUT_MS)
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

                String contentLengthHeader = response.header("Content-Length");
                if (contentLengthHeader != null) {
                    try {
                        long length = Long.parseLong(contentLengthHeader);
                        if (length > MAX_FILE_SIZE) {
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid content-length
                    }
                }

                String contentType = response.contentType();
                byte[] body = response.bodyAsBytes();

                if (body.length > MAX_FILE_SIZE) {
                    continue;
                }

                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                    Document doc = response.parse();
                    pagesParsed++;

                    if (doc.title().contains("Just a moment...") || doc.text().contains("Enable JavaScript and cookies to continue")) {
                        blockedUrls.put(current.url, "Cloudflare/Bot protection challenge");
                    }

                    // Extract text from body, title and meta description for better coverage
                    content = extractTextFromHtml(doc);

                    if (current.depth < maxDepth) {
                        links = extractLinks(doc, response.body(), current.url);
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

                        if (content == null || content.trim().isEmpty()) {
                            // Try again without content type hint if it failed
                            try (InputStream bis2 = new java.io.ByteArrayInputStream(body)) {
                                content = TIKA.parseToString(bis2);
                            }
                        }
                        pagesParsed++;
                    } catch (Throwable t) {
                        // Fallback to UTF-8 if Tika fails
                        content = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                        pagesParsed++;
                    }
                }

                int count = countMatches(content, keyword, mode);
                if (count > 0) {
                    results.put(current.url, count);
                }

                if (current.depth < maxDepth) {
                    for (String link : links) {
                        if (!ALLOW_EXTERNAL) {
                            try {
                                String linkHost = new URL(link).getHost();
                                if (!linkHost.equalsIgnoreCase(startHost)) {
                                    continue;
                                }
                            } catch (Exception e) {
                                continue;
                            }
                        }

                        if (visited.size() < MAX_PAGES && !visited.contains(link)) {
                            visited.add(link);
                            queue.add(new UrlDepth(link, current.depth + 1));
                        }
                    }
                }

            } catch (org.jsoup.HttpStatusException e) {
                if (e.getStatusCode() == 403 || e.getStatusCode() == 429) {
                    blockedUrls.put(current.url, "HTTP " + e.getStatusCode() + " (Access Denied/Rate Limited)");
                }
            } catch (Exception e) {
                // System.err.println("Error crawling " + current.url + ": " + e.getMessage());
            }
        }

        return new CrawlResult(results, blockedUrls, pagesCrawled, pagesParsed);
    }

    private static String extractTextFromHtml(Document doc) {
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
        return sb.toString();
    }

    private static List<String> extractLinks(Document doc, String rawBody, String baseUrl) {
        List<String> links = new ArrayList<>();
        Elements elements = doc.select("a[href]");
        for (Element element : elements) {
            if (links.size() >= MAX_LINKS_PER_PAGE) break;
            String link = element.absUrl("href");
            if (link.isEmpty()) {
                link = element.attr("href");
            }
            String normalizedLink = normalizeUrl(link, baseUrl);
            if (!normalizedLink.isEmpty() && !isIgnoredLink(normalizedLink)) {
                links.add(normalizedLink);
            }
        }

        // Fallback Regex link extraction for links Jsoup might miss (e.g. malformed HTML)
        if (links.size() < MAX_LINKS_PER_PAGE) {
            Pattern linkPattern = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher linkMatcher = linkPattern.matcher(rawBody);
            while (linkMatcher.find() && links.size() < MAX_LINKS_PER_PAGE) {
                String href = linkMatcher.group(1);
                String normalizedLink = normalizeUrl(href, baseUrl);
                if (!normalizedLink.isEmpty() && !isIgnoredLink(normalizedLink)) {
                    links.add(normalizedLink);
                }
            }
        }
        return links;
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
                || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar.gz")
                || lower.contains("googleads") || lower.contains("doubleclick")
                || lower.contains("facebook.com/sharer") || lower.contains("twitter.com/intent/tweet")
                || lower.contains("linkedin.com/share") || lower.contains("pinterest.com/pin")
                || lower.contains("/tag/") || lower.contains("/tags/") || lower.contains("/author/")
                || lower.contains("video.klix.ba") || lower.contains("static.klix.ba")
                || lower.contains("sdk.privacy-center.org");
    }

    private static class CrawlResult {
        Map<String, Integer> results;
        Map<String, String> blockedUrls;
        int visitedCount;
        int parsedCount;

        CrawlResult(Map<String, Integer> results, Map<String, String> blockedUrls, int visitedCount, int parsedCount) {
            this.results = results;
            this.blockedUrls = blockedUrls;
            this.visitedCount = visitedCount;
            this.parsedCount = parsedCount;
        }
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