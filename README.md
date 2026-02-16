# WebGrep

WebGrep is a CLI tool for searching keywords in web pages and documents up to a specified depth.

### Limitations
- **JavaScript**: WebGrep only crawls raw HTML. It does not execute JavaScript, so content rendered dynamically via JS (e.g., React, Vue, etc.) will not be indexed.
- **Max Pages**: To prevent runaway crawls, there is a hard limit of 1000 pages per execution.
- **File Size**: Files larger than 10MB are skipped.

### Key Features
- **Recursive Crawling**: Explores links up to a user-defined depth.
- **Multi-Format Support**: Extracts text from HTML, PDF, DOCX, TXT, etc., using Apache Tika.
- **Robustness**: Normalizes URLs to avoid duplicates and enforces safety limits on page count and file size.
- **SSL Flexibility**: Automatically trusts all SSL certificates.
- **Matching Modes**: Optional 4th argument to set the matching strategy:
  - `default` (or omitted): Case-insensitive matching.
  - `fuzzy`: Approximate matching (ignores diacritics, symbols, and accepts minor typos).
  - `exact`: Case-sensitive literal matching.

### How to Build
The project uses Maven. You can build the shaded (executable) JAR with:
```bash
mvn package
```

### How to Run
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar <URL> <keyword> <depth> [fuzzy|exact]
```
Example:
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar https://example.com Domain 1
```
For fuzzy search:
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar https://example.com Domain 1 fuzzy
```
For exact search:
```bash
java -jar target/WebGrep-1.0-SNAPSHOT.jar https://example.com Domain 1 exact
```
