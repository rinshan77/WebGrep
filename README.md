# WebGrep

WebGrep is a high-performance CLI crawler and search tool designed to find keywords across websites and binary documents (PDF, DOCX, etc.) with professional-grade features and reporting.

### Architecture
- **Crawler**: Manages the crawl queue, depth logic, and domain constraints.
- **Fetcher**: Robust HTTP client based on Jsoup with browser mimicry and Cloudflare detection.
- **Extractor**: Uses Apache Tika for multi-format content extraction (PDF, DOCX, TXT) and Jsoup for intelligent HTML text extraction (including metadata).
- **Matcher**: Pluggable matching engine supporting case-insensitive, exact, and fuzzy (Levenshtein) strategies.
- **Reporter**: Flexible output engine supporting both human-readable text and machine-ready JSON.

### Depth Definition
- **Depth 0**: Scans only the provided seed URL.
- **Depth 1**: Scans the seed URL and all immediate links discovered on that page.
- **Depth N**: Continues recursively up to N levels.

### Usage
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar --url <URL> --keyword <keyword> [options]
```

#### Options:
- `--url <URL>`: The starting URL (required).
- `--keyword <word>`: The keyword to search for (required).
- `--depth <n>`: Maximum crawl depth (default: 1).
- `--mode <mode>`: Match strategy (`default`, `exact`, `fuzzy`).
- `--max-pages <n>`: Stop after crawling N pages (default: 5000).
- `--max-bytes <n>`: Skip files larger than N bytes (default: 10MB).
- `--timeout-ms <n>`: Network timeout per request (default: 20000ms).
- `--allow-external`: Allow the crawler to leave the starting domain.
- `--insecure`: Disable SSL certificate verification (use with caution).
- `--output <format>`: Output format (`text` or `json`).
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

**JSON output for automation:**
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar --url https://example.com --keyword Domain --output json
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