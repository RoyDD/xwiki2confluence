# XWiki XAR to Markdown Exporter — with optional Confluence sync (attachments included)

## Goal

Export XWiki XAR packages to Markdown, with optional sync to Confluence (including attachments).

## Data Directory

- Input dir example: `xwiki/`
- Data is **read-only** (tool never modifies original files)
- The directory contains exported `.xar` files

## Usage

### 1) Build

Requires JDK 11+ (Java HttpClient is used for Confluence sync).

```bash
javac -encoding UTF-8 XwikiXarExporter.java
```

### 2) Export (XAR → Markdown)

- Default output directory: `out`
- Example (input is a directory — recursively scans `.xar` files):
  ```bash
  java XwikiXarExporter --input "xwiki" --output out
  ```
- Optional flags:
  - `--format markdown|raw` (default `markdown`; `raw` outputs original XWiki syntax)
  - `--include-preferences` (by default `WebPreferences.xml` is skipped)
  - `--only-entry <entry>` (export a single page/entry — useful for quick testing)

### Single-page test

1. Find the target page's entry name inside the XAR (typically `.../WebHome.xml`):
   ```bash
   jar tf <your.xar> | grep WebHome.xml | head
   ```
2. Export only that entry:
   ```bash
   java XwikiXarExporter --input <your.xar> --output out --only-entry "<Space>/<Page>/WebHome.xml"
   ```
3. Check the output:
   ```
   out/<xar-basename>/<Space>/<Page>/index.md
   ```

### 3) Sync to Confluence (optional)

Enable sync by providing the following flags:

- `--confluence-url <url>`
- `--space <space>`
- `--user <user>`
- `--password <apiToken>`
- `--dry-run` (optional — prints intended create/update/upload operations without making HTTP requests)

Example:

```bash
java XwikiXarExporter --input "xwiki" --output out \
  --confluence-url <url> --space <space> --ancestor <parentId> \
  --user <user> --password <token>
```

Notes:
- `--ancestor` is optional; if omitted, pages are created at the Space root

### Upload from existing `out` directory (skip XAR re-parsing)

Useful when an export has already been run and `out/...` contains `pages.csv`, per-page `index.md`, and attachment files.

Example (upload a single export directory):

```bash
java XwikiXarExporter --upload-from out/<xar-basename> \
  --confluence-url <url> --space <space> \
  --user <user> --password <token>
```

Example (upload the entire `out` root — automatically scans subdirectories containing `pages.csv`):

```bash
java XwikiXarExporter --upload-from out \
  --confluence-url <url> --space <space> \
  --user <user> --password <token>
```

### 4) Attachment / Image Handling

- XAR files may contain attachments: attachment content is typically embedded as base64 inside the page XML under `<attachment><content>`. Running `jar tf` does not always reveal standalone `.png` / `.docx` files.
- The tool automatically extracts inline attachments from page XML into the page directory, then uploads them to Confluence after the page is created.
- If the Markdown references `image:xxx.png` / `attach:xxx.ext` but the attachment is **not** embedded in the XAR, you can provide an external XWiki attachments directory:
  ```bash
  java XwikiXarExporter --input "xwiki" --output out \
    --attachments-dir <xwiki-attachments-root>
  ```

Expected attachments directory structure:

```
<attachments-dir>/<space>/<page>/<filename.ext>
```

## Confluence Configuration

- `--confluence-url` format: `https://your-confluence.example.com`
