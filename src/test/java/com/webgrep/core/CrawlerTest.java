package com.webgrep.core;

import com.webgrep.config.CliOptions;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link Crawler}'s cookie scoping rules.
 *
 * <p>Lives in {@code com.webgrep.core} so it can reach the package-private
 * {@link Crawler#storeCookies} and {@link Crawler#cookiesFor} without reflection.
 */
public class CrawlerTest {

    private static Crawler crawlerFor(String seedUrl) {
        CliOptions options = CliOptions.parse(new String[]{"-u", seedUrl, "-k", "x", "-e"});
        options.validate();
        return new Crawler(options, new ContentExtractor(1024), new MatchEngine());
    }

    private static String refreshTarget(String html) {
        return Crawler.metaRefreshTarget(
                org.jsoup.Jsoup.parse(html, "https://example.com/docs/intro"),
                "https://example.com/docs/intro");
    }

    @Test
    public void testZeroDelayMetaRefreshIsFollowed() {
        assertEquals("https://example.com/docs/4.x/intro",
                refreshTarget("<html><head><meta http-equiv=\"refresh\""
                        + " content=\"0; url=/docs/4.x/intro\"></head></html>"));
        // No space, quoted target, and uppercase URL= must all parse.
        assertEquals("https://example.com/a",
                refreshTarget("<html><head><meta http-equiv=refresh content='0;url=/a'></head></html>"));
        assertEquals("https://example.com/b",
                refreshTarget("<html><head><meta http-equiv=refresh content=\"0; URL='/b'\"></head></html>"));
        assertEquals("https://other.org/c",
                refreshTarget("<html><head><meta http-equiv=refresh"
                        + " content=\"0; url=https://other.org/c\"></head></html>"));
    }

    @Test
    public void testDelayedOrUnusableMetaRefreshIsIgnored() {
        // A delayed refresh is a normal page that reloads itself, not a redirect.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"30; url=/home\"></head></html>"));
        // Refresh with no target, or no refresh at all.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh content=\"0\"></head></html>"));
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh content=\"0; url=\"></head></html>"));
        assertNull(refreshTarget("<html><head></head><body>plain</body></html>"));
        // Non-http schemes must not slip through as redirect targets.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"0; url=javascript:alert(1)\"></head></html>"));
        // A refresh pointing at the page itself would loop.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"0; url=https://example.com/docs/intro\"></head></html>"));
    }

    @Test
    public void testCookiesAreSharedAcrossSubdomainsOfSeedDomain() {
        // Seed starts with www., so subdomain crawling (and cookie sharing) is enabled.
        Crawler crawler = crawlerFor("https://www.example.com");
        crawler.storeCookies("https://www.example.com/login", Map.of("SESSION", "abc"));

        assertEquals("Session cookie must reach a sibling subdomain",
                "abc", crawler.cookiesFor("https://docs.example.com/page").get("SESSION"));
        assertEquals("Session cookie must reach the bare root domain",
                "abc", crawler.cookiesFor("https://example.com/page").get("SESSION"));
    }

    @Test
    public void testCookiesAreNotSentToUnrelatedDomain() {
        // With --allow-external the crawler may hop to a third-party host. The seed domain's
        // session cookies must not travel with it.
        Crawler crawler = crawlerFor("https://www.example.com");
        crawler.storeCookies("https://www.example.com/login", Map.of("SESSION", "secret"));

        assertEquals("Unrelated host must receive no cookies",
                Map.of(), crawler.cookiesFor("https://other-site.org/collect"));
    }

    @Test
    public void testCookieSharingIsNotDefeatedBySecondHostInJar() {
        // A second host in the jar must not change the answer for an unrelated target.
        Crawler crawler = crawlerFor("https://www.example.com");
        crawler.storeCookies("https://www.example.com/login", Map.of("SESSION", "secret"));
        crawler.storeCookies("https://api.example.com/token", Map.of("CSRF", "xyz"));

        assertEquals("Unrelated host must still receive no cookies",
                Map.of(), crawler.cookiesFor("https://other-site.org/collect"));
        Map<String, String> sibling = crawler.cookiesFor("https://docs.example.com/page");
        assertEquals("secret", sibling.get("SESSION"));
        assertEquals("xyz", sibling.get("CSRF"));
    }

    @Test
    public void testSuffixMatchDoesNotLeakToLookalikeDomain() {
        // "notexample.com" ends with "example.com" as a plain string but is a different domain.
        Crawler crawler = crawlerFor("https://www.example.com");
        crawler.storeCookies("https://www.example.com/login", Map.of("SESSION", "secret"));

        assertEquals("Look-alike suffix domain must receive no cookies",
                Map.of(), crawler.cookiesFor("https://notexample.com/collect"));
    }

    @Test
    public void testNonWwwSeedKeepsCookiesHostExact() {
        // Seed without www. disables subdomain scoping, so cookies stay pinned to their host.
        Crawler crawler = crawlerFor("https://docs.example.com");
        crawler.storeCookies("https://docs.example.com/login", Map.of("SESSION", "abc"));

        assertEquals("abc", crawler.cookiesFor("https://docs.example.com/page").get("SESSION"));
        assertEquals("Sibling subdomain must not inherit cookies when seed had no www.",
                Map.of(), crawler.cookiesFor("https://api.example.com/page"));
    }
}
