# WebGrep

WebGrep is a high-performance CLI crawler and search tool designed to find keywords across websites and binary documents (PDF, DOCX, etc.) with professional-grade features and reporting.

### Architecture
WebGrep is designed with a modular architecture for high performance and maintainability:
- **CliOptions**: Handles advanced argument parsing and strict input validation.
- **Crawler**: Manages the multi-level crawl queue, domain constraints, and politeness delays.
- **ContentExtractor**: Orchestrates intelligent text extraction from HTML (via Jsoup) and binary formats like PDF/DOCX (via Apache Tika).
- **MatchEngine**: Executes pluggable matching strategies including case-insensitive, exact, and fuzzy (Levenshtein) searches with Unicode support.
- **ReportWriter**: Generates human-readable text summaries or structured JSON for automation.

### Depth Definition
- **Depth 0**: Scans only the provided seed URL.
- **Depth 1**: Scans the seed URL and all immediate links discovered on that page.
- **Depth N**: Continues recursively up to N levels.

### Usage
```bash
java -jar WebGrep.jar --url <URL> --keyword <keyword> [options]
```

#### Options:
- `-u, --url <URL>`: The starting URL (required).
- `-k, --keyword <word>`: The keyword to search for (required).
- `-d, --depth <n>`: Maximum crawl depth (default: 1).
- `-m, --mode <mode>`: Match strategy (`default`, `exact`, `fuzzy`).
- `-p, --max-pages <n>`: Stop after crawling N pages (default: 5000).
- `-b, --max-bytes <n>`: Skip files larger than N bytes (default: 10MB).
- `-t, --timeout-ms <n>`: Network timeout per request (default: 20000ms).
- `-e, --allow-external`: Allow the crawler to leave the starting domain.
- `-i, --insecure`: Disable SSL certificate verification (use with caution).
- `-o, --output <format>`: Output format (`text` or `json`).
- `-h, --help`: Show help message.

### Matching Modes
- **Default**: Case-insensitive matching with Unicode support.
- **Exact**: Strict case-sensitive literal matching.
- **Fuzzy**: Normalizes diacritics, ignores punctuation, and uses Levenshtein distance to catch typos/variations.

### Examples
**Basic search:**
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar --url https://example.com --keyword Domain
```

**JSON output with detailed metrics:**
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar --url https://example.com --keyword Domain --output json
```

### Sample Output (JSON)
```json
{
  "query": {
    "url": "http://example.com",
    "keyword": "domain",
    "depth": 0,
    "mode": "default"
  },
  "stats": {
    "total_matches": 3,
    "pages_visited": 1,
    "pages_parsed": 1,
    "pages_blocked": 0,
    "errors": {
      "network_error": 0,
      "blocked": 0,
      "parse_error": 0,
      "skipped_size": 0,
      "skipped_type": 0
    }
  },
  "results": [
    { "url": "http://example.com/", "count": 3 }
  ],
  "blocked": []
}
```

### Limitations
- **JavaScript**: WebGrep processes static content. It does not execute JavaScript (SPA content may not be fully indexed).
- **Bot Protection**: JavaScript-based challenges (like Cloudflare Managed Challenges) cannot be bypassed, but are detected and reported.
- **Robots.txt**: Currently, the tool does not automatically parse robots.txt.

### Build
Requires Java 17+ and Maven.
```bash
mvn package
```

Written by and belongs to Simon D.  
Free to use for personal and educational purposes.  
For commercial use please contact me at simon . d . dev symbol proton . me.