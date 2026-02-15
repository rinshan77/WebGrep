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
        setupSsl();
        if (args.length < 3) {
            System.out.println("Usage: java -jar WebGrep.jar <URL> <keyword> <depth>");
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

        Map<String, Integer> results = crawl(normalizeUrl(startUrl), keyword, maxDepth);

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

    private static Map<String, Integer> crawl(String startUrl, String keyword, int maxDepth) {
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
                    Document doc = Jsoup.connect(current.url).get();
                    content = doc.text();
                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        for (Element element : elements) {
                            String link = element.attr("abs:href");
                            String normalizedLink = normalizeUrl(link);
                            if (!normalizedLink.isEmpty() && !visited.contains(normalizedLink)) {
                                links.add(normalizedLink);
                            }
                        }
                    }
                } else {
                    // Try to parse as other file types
                    try (InputStream is = connection.getInputStream()) {
                        // Tika can handle many formats and tries to detect encoding
                        content = TIKA.parseToString(is);
                    } catch (Exception e) {
                        // Ignore or log
                    }
                }

                int count = countMatches(content, keyword);
                if (count > 0) {
                    results.put(current.url, count);
                }

                if (current.depth < maxDepth) {
                    for (String link : links) {
                        if (visited.size() < MAX_PAGES && visited.add(link)) {
                            queue.add(new UrlDepth(link, current.depth + 1));
                        }
                    }
                }

            } catch (Exception e) {
                // System.err.println("Error processing " + current.url + ": " + e.getMessage());
            }
        }

        return results;
    }

    private static String normalizeUrl(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "";
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

    private static boolean isHtml(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            String contentType = connection.getContentType();
            return contentType != null && contentType.contains("text/html");
        } catch (Exception e) {
            return url.toLowerCase().endsWith(".html") || url.toLowerCase().endsWith(".htm");
        }
    }

    private static int countMatches(String text, String keyword) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
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
