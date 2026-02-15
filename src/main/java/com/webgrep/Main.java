package com.webgrep;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.tika.Tika;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final Tika TIKA = new Tika();

    public static void main(String[] args) {
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

        Map<String, Integer> results = crawl(startUrl, keyword, maxDepth);

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

    private static Map<String, Integer> crawl(String startUrl, String keyword, int maxDepth) {
        Map<String, Integer> results = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<UrlDepth> queue = new LinkedList<>();

        queue.add(new UrlDepth(startUrl, 0));
        visited.add(startUrl);

        while (!queue.isEmpty()) {
            UrlDepth current = queue.poll();

            try {
                String content = "";
                List<String> links = new ArrayList<>();

                if (isHtml(current.url)) {
                    Document doc = Jsoup.connect(current.url).get();
                    content = doc.text();
                    if (current.depth < maxDepth) {
                        Elements elements = doc.select("a[href]");
                        for (Element element : elements) {
                            String link = element.attr("abs:href");
                            if (!link.isEmpty() && !visited.contains(link)) {
                                links.add(link);
                            }
                        }
                    }
                } else {
                    // Try to parse as other file types
                    try (InputStream is = new URL(current.url).openStream()) {
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
                        if (visited.add(link)) {
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
