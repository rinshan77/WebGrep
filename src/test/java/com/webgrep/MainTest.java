package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.MatchEngine;
import com.webgrep.utils.UrlUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class MainTest {

    @Test
    public void testNormalizeUrl() {
        assertEquals("http://example.com/", UrlUtils.normalizeUrl("example.com", null));
        assertEquals("https://example.com/path", UrlUtils.normalizeUrl("//example.com/path", "https://other.com"));
        assertEquals("http://example.com/a/b", UrlUtils.normalizeUrl("b", "http://example.com/a/"));
        assertEquals("http://example.com/a/c", UrlUtils.normalizeUrl("/a/c", "http://example.com/a/b"));
    }

    @Test
    public void testMatchEngine() {
        MatchEngine engine = new MatchEngine();
        
        // Default mode (case-insensitive)
        assertEquals(2, engine.countMatches("Hello world, hello!", "hello", "default"));
        
        // Exact mode
        assertEquals(1, engine.countMatches("Hello world, hello!", "hello", "exact"));
        assertEquals(0, engine.countMatches("Hello world", "HELLO", "exact"));

        // Fuzzy mode
        assertEquals(1, engine.countMatches("H.e.l.l.o", "hello", "fuzzy"));
        assertEquals(1, engine.countMatches("Café", "cafe", "fuzzy"));
    }

    @Test
    public void testSuperSimplify() {
        MatchEngine engine = new MatchEngine();
        assertEquals("cafe", engine.superSimplify("Café"));
        assertEquals("helloworld123", engine.superSimplify("Hello-World_123!"));
    }
    
    @Test
    public void testCliOptions() {
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "2", "-m", "exact"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        
        assertEquals("http://example.com", options.getUrl());
        assertEquals("test", options.getKeyword());
        assertEquals(2, options.getDepth());
        assertEquals("exact", options.getMode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCliOptionsInvalidDepth() {
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "-1"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCliOptionsMissingUrl() {
        String[] args = {"-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }
}
