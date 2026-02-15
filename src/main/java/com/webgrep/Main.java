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
    private static final Tika TIKA = new Tika();
    private static final int MAX_PAGES = 1000; // Limit total pages to prevent runaway crawl
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit

    public static void main(String[] args) {
        setupLogging();
        setupSsl();
        if (args.length < 3) {
            System.out.println("Usage: java -jar WebGrep.jar <URL> <keyword> <depth> [fuzzy]");
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

        boolean fuzzy = args.length >= 4 && args[3].equalsIgnoreCase("fuzzy");

        Map<String, Integer> results = crawl(normalizeUrl(startUrl), keyword, maxDepth, fuzzy);

        int totalCount = results.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("Total count: " + totalCount);
        if (totalCount > 0) {
            System.out.println("Found in:");
            results.forEach((url, count) -> {
                if (count > 0) {
                    System.out.println(url + " (" + count + ")");
                }
            });
        }
    }

    private static void setupLogging() {
        // Suppress PDFBox and other noisy loggers
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        // For SLF4J (slf4j-simple)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        // For Log4j2
        System.setProperty("log4j2.level", "error");
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

    private static Map<String, Integer> crawl(String startUrl, String keyword, int maxDepth, boolean fuzzy) {
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
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                // Check size if possible
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_FILE_SIZE) {
                    continue;
                }

                String contentType = connection.getContentType();

                if (contentType != null && contentType.contains("text/html")) {
                    // Jsoup handles the connection and parsing
                    Document doc = Jsoup.connect(current.url)
                            .timeout(5000)
                            .followRedirects(true)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                            .get();
                    content = doc.text();
                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        for (Element element : elements) {
                            String link = element.attr("abs:href");
                            String normalizedLink = normalizeUrl(link);
                            if (!normalizedLink.isEmpty()) {
                                links.add(normalizedLink);
                            }
                        }
                    }
                } else {
                    // Try to parse as other file types using already open connection
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
                            // Tika can handle many formats and tries to detect encoding
                            try (InputStream bis = new java.io.ByteArrayInputStream(baos.toByteArray())) {
                                content = TIKA.parseToString(bis);
                            }
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                }

                int count = countMatches(content, keyword, fuzzy);
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

    private static int countMatches(String text, String keyword, boolean fuzzy) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        if (!fuzzy) {
            int count = 0;
            Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }
            return count;
        } else {
            return countFuzzyMatches(text, keyword);
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

    private static class UrlDepth {
        String url;
        int depth;

        UrlDepth(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}
