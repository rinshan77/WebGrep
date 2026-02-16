package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.ContentExtractor;
import com.webgrep.core.Crawler;
import com.webgrep.core.MatchEngine;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.ReportWriter;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * WebGrep - A professional CLI web crawler and keyword searcher.
 * @author Simon D.
 */
public class Main {

    public static void main(String[] args) {
        setupLogging();

        try {
            CliOptions options = CliOptions.parse(args);

            if (options.isHelp() || args.length == 0) {
                printHelp();
                return;
            }

            options.validate();

            ContentExtractor extractor = new ContentExtractor();
            MatchEngine matchEngine = new MatchEngine();
            Crawler crawler = new Crawler(options, extractor, matchEngine);

            CrawlResult result = crawler.crawl();

            ReportWriter reportWriter = new ReportWriter();
            if ("json".equals(options.getOutput())) {
                reportWriter.printJsonOutput(result, options);
            } else {
                reportWriter.printTextOutput(result);
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            System.err.println("Use -h or --help for usage information.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void setupLogging() {
        // Suppress noisy library logging
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        try {
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.OFF);
        } catch (Exception ignored) {}
    }

    private static void printHelp() {
        System.out.println("WebGrep - A high-performance web crawler and keyword searcher");
        System.out.println("\nUsage: java -jar WebGrep.jar -u <URL> -k <keyword> [options]");
        System.out.println("\nOptions:");
        System.out.println("  -u, --url <URL>          The starting URL (required)");
        System.out.println("  -k, --keyword <word>     The keyword to search for (required)");
        System.out.println("  -d, --depth <n>          Maximum crawl depth (default: 1)");
        System.out.println("  -m, --mode <mode>        Match mode: default, exact, or fuzzy");
        System.out.println("  -p, --max-pages <n>      Maximum number of pages to crawl (default: 5000)");
        System.out.println("  -b, --max-bytes <n>      Maximum file size in bytes (default: 10MB)");
        System.out.println("  -t, --timeout-ms <n>     Request timeout in milliseconds (default: 20000)");
        System.out.println("  -e, --allow-external     Allow crawling external domains");
        System.out.println("  -i, --insecure           Trust all SSL certificates (dangerous)");
        System.out.println("  -o, --output <format>    Output format: text (default) or json");
        System.out.println("  -h, --help               Show this help message");
    }
}