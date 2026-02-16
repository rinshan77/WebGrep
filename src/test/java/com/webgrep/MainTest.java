package com.webgrep;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Method;

public class MainTest {

    @Test
    public void testNormalizeUrl() throws Exception {
        Method method = Main.class.getDeclaredMethod("normalizeUrl", String.class, String.class);
        method.setAccessible(true);

        assertEquals("http://example.com/", method.invoke(null, "example.com", null));
        assertEquals("https://example.com/path", method.invoke(null, "//example.com/path", "https://other.com"));
        assertEquals("http://example.com/a/b", method.invoke(null, "b", "http://example.com/a/"));
        assertEquals("http://example.com/a/c", method.invoke(null, "/a/c", "http://example.com/a/b"));
    }

    @Test
    public void testCountMatches() throws Exception {
        Method method = Main.class.getDeclaredMethod("countMatches", String.class, String.class, String.class);
        method.setAccessible(true);

        // Default mode (case-insensitive)
        assertEquals(2, method.invoke(null, "Hello world, hello!", "hello", "default"));
        
        // Exact mode
        assertEquals(1, method.invoke(null, "Hello world, hello!", "hello", "exact"));
        assertEquals(0, method.invoke(null, "Hello world", "HELLO", "exact"));

        // Fuzzy mode (super simplify)
        assertEquals(1, method.invoke(null, "H.e.l.l.o", "hello", "fuzzy"));
        assertEquals(1, method.invoke(null, "Café", "cafe", "fuzzy"));
    }

    @Test
    public void testSuperSimplify() throws Exception {
        Method method = Main.class.getDeclaredMethod("superSimplify", String.class);
        method.setAccessible(true);

        assertEquals("cafe", method.invoke(null, "Café"));
        assertEquals("helloworld123", method.invoke(null, "Hello-World_123!"));
    }
}
