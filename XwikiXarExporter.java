import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class XwikiXarExporter {
    private static final Charset ZIP_CHARSET = StandardCharsets.UTF_8;
    private static final int HTTP_OK_START = 200;
    private static final int HTTP_OK_END = 299;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Path output = Paths.get(parsed.output);

        Files.createDirectories(output);

        ConfluenceApiClient confluence = null;
        if (parsed.confluenceUrl != null && parsed.space != null) {
            confluence = new ConfluenceApiClient(
                    parsed.confluenceUrl,
                    parsed.space,
                    parsed.ancestorId,
                    parsed.user,
                    parsed.password,
                    parsed.dryRun
            );
            System.out.println("Confluence target: " + parsed.confluenceUrl + "/spaces/" + parsed.space + (parsed.dryRun ? " [DRY RUN]" : ""));
        }

        if (parsed.uploadFrom != null && !parsed.uploadFrom.trim().isEmpty()) {
            if (confluence == null) {
                throw new IllegalArgumentException("Missing Confluence config for --upload-from. Provide --confluence-url, --space, --user, --password.");
            }
            uploadFromExistingOutput(Paths.get(parsed.uploadFrom), confluence);
            return;
        }

        Path input = Paths.get(parsed.input);
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input);
        }

        List<Path> xarFiles = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try {
                Files.walk(input)
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xar"))
                        .sorted()
                        .forEach(xarFiles::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan input directory: " + input, e);
            }
        } else {
            xarFiles.add(input);
        }

        if (xarFiles.isEmpty()) {
            throw new IllegalArgumentException("No .xar files found under: " + input);
        }

        Path attachmentsDir = parsed.attachmentsDir != null ? Paths.get(parsed.attachmentsDir) : null;
        if (attachmentsDir != null && Files.exists(attachmentsDir)) {
            System.out.println("Attachments dir: " + attachmentsDir.toAbsolutePath());
        }

        for (Path xar : xarFiles) {
            Path outDir = output.resolve(safePathSegment(stripExtension(xar.getFileName().toString())));
            exportSingleXar(xar, outDir, parsed.format, parsed.includePreferences, parsed.onlyEntry, confluence, attachmentsDir);
        }
    }

    private static void uploadFromExistingOutput(Path uploadFrom, ConfluenceApiClient confluence) throws Exception {
        if (!Files.exists(uploadFrom)) {
            throw new IllegalArgumentException("Upload-from does not exist: " + uploadFrom);
        }
        List<Path> targets = new ArrayList<>();
        Path directCsv = uploadFrom.resolve("pages.csv");
        if (Files.isRegularFile(directCsv)) {
            targets.add(uploadFrom);
        } else if (Files.isDirectory(uploadFrom)) {
            try {
                Files.list(uploadFrom)
                        .filter(Files::isDirectory)
                        .sorted()
                        .forEach(d -> {
                            if (Files.isRegularFile(d.resolve("pages.csv"))) {
                                targets.add(d);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan upload-from dir: " + uploadFrom, e);
            }
        }

        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No pages.csv found under: " + uploadFrom);
        }

        for (Path outDir : targets) {
            uploadSingleOutputDir(outDir, confluence);
        }
    }

    private static void uploadSingleOutputDir(Path outputDir, ConfluenceApiClient confluence) throws Exception {
        Path csv = outputDir.resolve("pages.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("Missing pages.csv under: " + outputDir);
        }
        List<String[]> rows = readCsv(csv);
        if (rows.isEmpty()) {
            System.out.println("No rows in: " + csv);
            return;
        }

        List<String[]> pageRows = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length < 6) continue;
            String entryName = r[1];
            if (entryName == null) continue;
            if (!entryName.replace('\\', '/').endsWith("WebHome.xml")) continue;
            pageRows.add(r);
        }

        pageRows.sort((a, b) -> {
            String an = (a.length > 1 ? a[1] : "").replace('\\', '/');
            String bn = (b.length > 1 ? b[1] : "").replace('\\', '/');
            int ad = countChar(an, '/');
            int bd = countChar(bn, '/');
            if (ad != bd) return Integer.compare(ad, bd);
            return an.compareTo(bn);
        });

        Map<String, String> confluenceIdsByEntry = new LinkedHashMap<>();
        for (String[] r : pageRows) {
            String entryName = r[1];
            String title = r.length > 3 ? r[3] : "";
            String outRel = r.length > 5 ? r[5] : "";
            if (outRel == null || outRel.trim().isEmpty()) continue;
            Path outFile = outputDir.resolve(outRel);
            if (!Files.isRegularFile(outFile)) {
                System.err.println("  [WARN] Missing output file: " + outFile);
                continue;
            }

            String pageTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : deriveTitleFromPath(entryName);
            String markdown = new String(Files.readAllBytes(outFile), StandardCharsets.UTF_8);
            AttachmentResolver resolver = buildAttachmentResolver(outFile.getParent());
            String bodyForConf = convertToConfluenceStorageFormat(markdown, resolver);

            String parentEntry = parentWebHomeEntry(entryName);
            String parentConfluenceId = parentEntry == null ? null : confluenceIdsByEntry.get(parentEntry);
            String newId = confluence.createOrUpdatePage(pageTitle, bodyForConf, parentConfluenceId);
            if (newId != null && !newId.isEmpty()) {
                confluenceIdsByEntry.put(entryName, newId);
                System.out.println("  [Confluence] " + pageTitle + " -> " + newId);

                Path pageDir = outFile.getParent();
                if (pageDir != null && Files.isDirectory(pageDir)) {
                    Map<String, Path> attachmentsByName = new LinkedHashMap<>();
                    try {
                        Files.walk(pageDir)
                                .filter(Files::isRegularFile)
                                .filter(p -> {
                                    String fn = p.getFileName().toString();
                                    return !fn.equalsIgnoreCase("index.md") && !fn.equalsIgnoreCase("pages.csv");
                                })
                                .sorted()
                                .forEach(p -> attachmentsByName.putIfAbsent(p.getFileName().toString(), p));
                    } catch (IOException e) {
                        System.err.println("  [WARN] scan attachments failed: " + e.getMessage());
                    }
                    if (!attachmentsByName.isEmpty()) {
                        List<Path> attachments = new ArrayList<>(attachmentsByName.values());
                        confluence.uploadAttachments(newId, attachments);
                        System.out.println("    Uploaded " + attachments.size() + " attachment(s)");
                    }
                }
            }
        }

        System.out.println("Uploaded " + pageRows.size() + " pages from output: " + outputDir.toAbsolutePath());
    }

    private static List<String[]> readCsv(Path csv) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (String line : lines) {
            rows.add(parseCsvLine(line));
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        if (line == null) return new String[]{""};
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"') {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static void exportSingleXar(Path xarFile, Path outputDir, ExportFormat format,
                                       boolean includePreferences, String onlyEntry, ConfluenceApiClient confluence,
                                       Path attachmentsDir) throws Exception {
        Files.createDirectories(outputDir);
        List<String[]> csvRows = new ArrayList<>();
        csvRows.add(new String[]{"xar", "entry", "reference", "title", "syntaxId", "output", "confluenceId"});

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        Map<String, String> confluenceIdsByEntry = new LinkedHashMap<>();

        int exported = 0;
        String[] onlyEntryCandidates = onlyEntryCandidates(onlyEntry);
        try (ZipFile zip = new ZipFile(xarFile.toFile(), ZIP_CHARSET)) {
            List<ZipEntry> selected = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".xml")) {
                    continue;
                }
                if (!includePreferences && name.endsWith("WebPreferences.xml")) {
                    continue;
                }
                if (!name.endsWith("WebHome.xml") && !name.endsWith("WebPreferences.xml")) {
                    continue;
                }
                if (onlyEntryCandidates != null && !matchesOnlyEntry(name, onlyEntryCandidates)) {
                    continue;
                }
                selected.add(entry);
            }

            selected.sort((a, b) -> {
                String an = a.getName().replace('\\', '/');
                String bn = b.getName().replace('\\', '/');
                int ad = countChar(an, '/');
                int bd = countChar(bn, '/');
                if (ad != bd) {
                    return Integer.compare(ad, bd);
                }
                int ar = an.endsWith("WebHome.xml") ? 0 : 1;
                int br = bn.endsWith("WebHome.xml") ? 0 : 1;
                if (ar != br) {
                    return Integer.compare(ar, br);
                }
                return an.compareTo(bn);
            });

            for (ZipEntry entry : selected) {
                String name = entry.getName();

                byte[] xmlBytes;
                try (InputStream is = new BufferedInputStream(zip.getInputStream(entry))) {
                    xmlBytes = readAllBytes(is);
                }

                Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));
                Element root = doc.getDocumentElement();

                String reference = textOfFirst(root, "reference");
                if (reference == null || reference.isEmpty()) {
                    reference = root.getAttribute("reference");
                }
                String title = textOfFirst(root, "title");
                String syntaxId = textOfFirst(root, "syntaxId");
                String content = textOfFirst(root, "content");
                if (content == null) content = "";

                String converted;
                if (format == ExportFormat.RAW_XWIKI) {
                    converted = content;
                } else {
                    converted = XwikiToMarkdown.convert(content);
                }

                Path pageDir = outputDir.resolve(relDirForEntry(name));
                Files.createDirectories(pageDir);

                List<Path> attachmentFiles = new ArrayList<>();
                extractEmbeddedAttachments(root, pageDir, attachmentFiles);

                List<AttachmentRef> attachmentRefs = new ArrayList<>();
                if (attachmentsDir != null) {
                    extractAttachmentRefs(converted, name, attachmentRefs);
                    for (AttachmentRef ref : attachmentRefs) {
                        copyAttachmentToPageDir(attachmentsDir, pageDir, ref);
                        Path localFile = pageDir.resolve(safeFileName(ref.filename));
                        if (Files.exists(localFile)) {
                            attachmentFiles.add(localFile);
                        }
                    }
                }

                Path outFile = pageDir.resolve("index.md");
                Files.write(outFile, converted.getBytes(StandardCharsets.UTF_8));

                String entryCsv = name;
                String confluenceId = "";
                if (confluence != null && name.endsWith("WebHome.xml")) {
                    String pageTitle = title != null && !title.isEmpty() ? title : deriveTitleFromPath(name);
                    AttachmentResolver resolver = buildAttachmentResolver(pageDir);
                    String bodyForConf = convertToConfluenceStorageFormat(converted, resolver);
                    String parentEntry = parentWebHomeEntry(name);
                    String parentConfluenceId = parentEntry == null ? null : confluenceIdsByEntry.get(parentEntry);

                    String newId = confluence.createOrUpdatePage(pageTitle, bodyForConf, parentConfluenceId);
                    if (newId != null && !newId.isEmpty()) {
                        confluenceId = newId;
                        confluenceIdsByEntry.put(name, newId);
                        System.out.println("  [Confluence] " + pageTitle + " -> " + newId);

                        if (!attachmentFiles.isEmpty()) {
                            confluence.uploadAttachments(newId, attachmentFiles);
                            System.out.println("    Uploaded " + attachmentFiles.size() + " attachment(s)");
                        }
                    }
                }

                csvRows.add(new String[]{
                        xarFile.getFileName().toString(),
                        entryCsv,
                        nullToEmpty(reference),
                        nullToEmpty(title),
                        nullToEmpty(syntaxId),
                        outputDir.relativize(outFile).toString(),
                        confluenceId
                });
                exported++;
            }
        }

        Path csv = outputDir.resolve("pages.csv");
        writeCsv(csv, csvRows);
        System.out.println("Exported " + exported + " pages from: " + xarFile);
        System.out.println("Output: " + outputDir.toAbsolutePath());
    }

    private static String parentWebHomeEntry(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (!normalized.endsWith("/WebHome.xml")) {
            return null;
        }
        String dir = normalized.substring(0, normalized.length() - "/WebHome.xml".length());
        int parentSlash = dir.lastIndexOf('/');
        if (parentSlash < 0) {
            return null;
        }
        String parentDir = dir.substring(0, parentSlash);
        if (parentDir.isEmpty()) {
            return null;
        }
        return parentDir + "/WebHome.xml";
    }

    private static String[] onlyEntryCandidates(String onlyEntry) {
        if (onlyEntry == null || onlyEntry.trim().isEmpty()) {
            return null;
        }
        String normalized = onlyEntry.trim().replace('\\', '/');
        if (normalized.endsWith(".xml")) {
            return new String[]{normalized};
        }
        return new String[]{
                normalized,
                normalized + ".xml",
                normalized + "/WebHome.xml",
                normalized + "/WebPreferences.xml"
        };
    }

    private static boolean matchesOnlyEntry(String entryName, String[] candidates) {
        String normalized = entryName.replace('\\', '/');
        for (String c : candidates) {
            if (normalized.equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static String deriveTitleFromPath(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (normalized.endsWith("/WebHome.xml")) {
            String dir = normalized.substring(0, normalized.length() - "/WebHome.xml".length());
            int slash = dir.lastIndexOf('/');
            if (slash >= 0 && slash < dir.length() - 1) {
                return dir.substring(slash + 1);
            }
            return dir.isEmpty() ? "Home" : dir;
        }
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0) {
            return stripExtension(entryName);
        }
        String name = normalized.substring(lastSlash + 1);
        if (name.endsWith(".xml")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String convertToConfluenceStorageFormat(String markdown) {
        return convertToConfluenceStorageFormat(markdown, null);
    }

    private interface AttachmentResolver {
        String resolveFileName(String ref);
    }

    private static AttachmentResolver buildAttachmentResolver(Path pageDir) {
        if (pageDir == null || !Files.isDirectory(pageDir)) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        try {
            Files.walk(pageDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return !fn.equalsIgnoreCase("index.md") && !fn.equalsIgnoreCase("pages.csv");
                    })
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        putResolverKey(map, fn, fn);
                        putResolverKey(map, fn.toLowerCase(Locale.ROOT), fn);
                        putResolverKey(map, safeFileName(fn), fn);
                        putResolverKey(map, safeFileName(fn).toLowerCase(Locale.ROOT), fn);
                    });
        } catch (IOException ignored) {
        }

        if (map.isEmpty()) {
            return null;
        }

        return ref -> {
            String raw = ref == null ? "" : ref.trim();
            if (raw.isEmpty()) return "";
            String noQuery = stripQueryAndFragment(raw);
            String base = basename(noQuery);
            String decoded = decodeUrlComponent(base);

            String direct = map.get(decoded);
            if (direct != null) return direct;
            direct = map.get(decoded.toLowerCase(Locale.ROOT));
            if (direct != null) return direct;

            String safe = safeFileName(decoded);
            direct = map.get(safe);
            if (direct != null) return direct;
            direct = map.get(safe.toLowerCase(Locale.ROOT));
            if (direct != null) return direct;

            return decoded;
        };
    }

    private static void putResolverKey(Map<String, String> map, String key, String value) {
        if (key == null) return;
        String k = key.trim();
        if (k.isEmpty()) return;
        map.putIfAbsent(k, value);
    }

    private static String stripQueryAndFragment(String s) {
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        int cut = -1;
        if (q >= 0 && h >= 0) cut = Math.min(q, h);
        else if (q >= 0) cut = q;
        else if (h >= 0) cut = h;
        return cut >= 0 ? s.substring(0, cut) : s;
    }

    private static String basename(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String base = slash >= 0 ? p.substring(slash + 1) : p;
        if (base.startsWith("\"") && base.endsWith("\"") && base.length() > 1) {
            base = base.substring(1, base.length() - 1);
        }
        return base;
    }

    private static String decodeUrlComponent(String s) {
        if (s == null) return "";
        String v = s;
        if (!v.contains("%") && !v.contains("+")) {
            return v;
        }
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static String convertToConfluenceStorageFormat(String markdown, AttachmentResolver resolver) {
        String[] lines = (markdown == null ? "" : markdown).replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();

        boolean inCode = false;
        String codeLang = "";
        StringBuilder code = new StringBuilder();

        boolean inUl = false;
        boolean inOl = false;

        StringBuilder paragraph = new StringBuilder();

        Pattern fence = Pattern.compile("^```\\s*([^\\s]*)\\s*$");

        for (String line : lines) {
            Matcher fm = fence.matcher(line);
            if (inCode) {
                if (fm.matches()) {
                    out.append(confluenceCodeMacro(codeLang, code.toString())).append("\n");
                    inCode = false;
                    codeLang = "";
                    code.setLength(0);
                } else {
                    if (code.length() > 0) code.append("\n");
                    code.append(line);
                }
                continue;
            }

            if (fm.matches()) {
                flushParagraph(out, paragraph, resolver);
                if (inUl) {
                    out.append("</ul>\n");
                    inUl = false;
                }
                if (inOl) {
                    out.append("</ol>\n");
                    inOl = false;
                }
                inCode = true;
                codeLang = fm.group(1) == null ? "" : fm.group(1).trim();
                code.setLength(0);
                continue;
            }

            String t = line.trim();
            if (t.isEmpty()) {
                flushParagraph(out, paragraph, resolver);
                if (inUl) {
                    out.append("</ul>\n");
                    inUl = false;
                }
                if (inOl) {
                    out.append("</ol>\n");
                    inOl = false;
                }
                continue;
            }

            String xwikiImageRef = parseXwikiImageLine(t);
            if (xwikiImageRef != null) {
                flushParagraph(out, paragraph, resolver);
                if (inUl) {
                    out.append("</ul>\n");
                    inUl = false;
                }
                if (inOl) {
                    out.append("</ol>\n");
                    inOl = false;
                }
                out.append(confluenceImageFromRef(xwikiImageRef, resolver)).append("\n");
                continue;
            }

            int headingLevel = headingLevel(t);
            if (headingLevel > 0) {
                flushParagraph(out, paragraph, resolver);
                if (inUl) {
                    out.append("</ul>\n");
                    inUl = false;
                }
                if (inOl) {
                    out.append("</ol>\n");
                    inOl = false;
                }
                String headingText = t.substring(headingLevel).trim();
                out.append("<h").append(headingLevel).append(">")
                        .append(renderInlineWithBreaks(headingText, resolver))
                        .append("</h").append(headingLevel).append(">\n");
                continue;
            }

            String ulItem = parseUnorderedListItem(t);
            if (ulItem != null) {
                flushParagraph(out, paragraph, resolver);
                if (inOl) {
                    out.append("</ol>\n");
                    inOl = false;
                }
                if (!inUl) {
                    out.append("<ul>\n");
                    inUl = true;
                }
                out.append("<li>").append(renderInlineWithBreaks(ulItem, resolver)).append("</li>\n");
                continue;
            }

            String olItem = parseOrderedListItem(t);
            if (olItem != null) {
                flushParagraph(out, paragraph, resolver);
                if (inUl) {
                    out.append("</ul>\n");
                    inUl = false;
                }
                if (!inOl) {
                    out.append("<ol>\n");
                    inOl = true;
                }
                out.append("<li>").append(renderInlineWithBreaks(olItem, resolver)).append("</li>\n");
                continue;
            }

            if (inUl) {
                out.append("</ul>\n");
                inUl = false;
            }
            if (inOl) {
                out.append("</ol>\n");
                inOl = false;
            }

            if (paragraph.length() > 0) paragraph.append("\n");
            paragraph.append(line);
        }

        if (inCode) {
            out.append(confluenceCodeMacro(codeLang, code.toString())).append("\n");
        }
        flushParagraph(out, paragraph, resolver);
        if (inUl) out.append("</ul>\n");
        if (inOl) out.append("</ol>\n");
        return out.toString();
    }

    private static String parseXwikiImageLine(String trimmedLine) {
        if (trimmedLine == null) return null;
        if (!trimmedLine.startsWith("!")) return null;

        String s = trimmedLine;
        if (s.endsWith("!")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("!")) return null;
        s = s.substring(1);

        String ref = s;
        int pipe = s.indexOf('|');
        if (pipe >= 0) {
            ref = s.substring(0, pipe);
        }
        ref = ref.trim();
        if (ref.isEmpty()) return null;

        String lower = ref.toLowerCase(Locale.ROOT);
        boolean looksLikeImage = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
        return looksLikeImage ? ref : null;
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        flushParagraph(out, paragraph, null);
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph, AttachmentResolver resolver) {
        if (paragraph.length() == 0) return;
        out.append("<p>").append(renderInlineWithBreaks(paragraph.toString(), resolver)).append("</p>\n");
        paragraph.setLength(0);
    }

    private static int headingLevel(String trimmedLine) {
        int n = 0;
        while (n < trimmedLine.length() && trimmedLine.charAt(n) == '#') n++;
        if (n == 0 || n > 6) return 0;
        if (n < trimmedLine.length() && trimmedLine.charAt(n) == ' ') return n;
        return 0;
    }

    private static String parseUnorderedListItem(String trimmedLine) {
        if (trimmedLine.length() < 2) return null;
        char c = trimmedLine.charAt(0);
        if (c != '-' && c != '*' && c != '+') return null;
        if (trimmedLine.charAt(1) != ' ' && trimmedLine.charAt(1) != '\t') return null;
        String item = trimmedLine.substring(2).trim();
        return item.isEmpty() ? null : item;
    }

    private static String parseOrderedListItem(String trimmedLine) {
        Matcher m = Pattern.compile("^\\d+\\.\\s+(.+)$").matcher(trimmedLine);
        if (!m.matches()) return null;
        String item = m.group(1) == null ? "" : m.group(1).trim();
        return item.isEmpty() ? null : item;
    }

    private static String confluenceCodeMacro(String lang, String code) {
        String safeCode = (code == null ? "" : code).replace("]]>", "]]]]><![CDATA[>");
        StringBuilder sb = new StringBuilder();
        sb.append("<ac:structured-macro ac:name=\"code\">");
        String l = lang == null ? "" : lang.trim();
        if (!l.isEmpty()) {
            sb.append("<ac:parameter ac:name=\"language\">").append(escapeXml(l)).append("</ac:parameter>");
        }
        sb.append("<ac:plain-text-body><![CDATA[").append(safeCode).append("]]></ac:plain-text-body>");
        sb.append("</ac:structured-macro>");
        return sb.toString();
    }

    private static String renderInlineWithBreaks(String text) {
        return renderInlineWithBreaks(text, null);
    }

    private static String renderInlineWithBreaks(String text, AttachmentResolver resolver) {
        String[] parts = (text == null ? "" : text).replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("<br/>");
            sb.append(renderInline(parts[i], resolver));
        }
        return sb.toString();
    }

    private static String renderInline(String text) {
        return renderInline(text, null);
    }

    private static String renderInline(String text, AttachmentResolver resolver) {
        String s = text == null ? "" : text;
        StringBuilder out = new StringBuilder();

        int i = 0;
        while (i < s.length()) {
            int codeStart = s.indexOf('`', i);
            int imgStart = s.indexOf("![", i);
            int linkStart = s.indexOf('[', i);

            int next = minPositive(codeStart, imgStart, linkStart);
            if (next < 0) {
                out.append(renderPlainText(s.substring(i), resolver));
                break;
            }
            if (next > i) {
                out.append(renderPlainText(s.substring(i, next), resolver));
            }

            if (next == codeStart) {
                int end = s.indexOf('`', codeStart + 1);
                if (end < 0) {
                    out.append(renderPlainText(s.substring(codeStart), resolver));
                    break;
                }
                String code = s.substring(codeStart + 1, end);
                out.append("<code>").append(escapeXml(code)).append("</code>");
                i = end + 1;
                continue;
            }

            if (next == imgStart) {
                int closeBracket = s.indexOf(']', imgStart + 2);
                if (closeBracket < 0 || closeBracket + 1 >= s.length() || s.charAt(closeBracket + 1) != '(') {
                    out.append(renderPlainText(s.substring(imgStart, imgStart + 2), resolver));
                    i = imgStart + 2;
                    continue;
                }
                int closeParen = s.indexOf(')', closeBracket + 2);
                if (closeParen < 0) {
                    out.append(renderPlainText(s.substring(imgStart), resolver));
                    break;
                }
                String url = s.substring(closeBracket + 2, closeParen).trim();
                out.append(confluenceImageFromRef(url, resolver));
                i = closeParen + 1;
                continue;
            }

            if (next == linkStart) {
                int closeBracket = s.indexOf(']', linkStart + 1);
                if (closeBracket < 0 || closeBracket + 1 >= s.length() || s.charAt(closeBracket + 1) != '(') {
                    out.append(renderPlainText(String.valueOf(s.charAt(linkStart)), resolver));
                    i = linkStart + 1;
                    continue;
                }
                int closeParen = s.indexOf(')', closeBracket + 2);
                if (closeParen < 0) {
                    out.append(renderPlainText(s.substring(linkStart), resolver));
                    break;
                }
                String label = s.substring(linkStart + 1, closeBracket);
                String url = s.substring(closeBracket + 2, closeParen).trim();
                out.append("<a href=\"").append(escapeXmlAttr(url)).append("\">").append(renderPlainText(label, resolver)).append("</a>");
                i = closeParen + 1;
                continue;
            }

            out.append(renderPlainText(s.substring(next, next + 1), resolver));
            i = next + 1;
        }

        return out.toString();
    }

    private static int minPositive(int... values) {
        int min = -1;
        for (int v : values) {
            if (v < 0) continue;
            if (min < 0 || v < min) min = v;
        }
        return min;
    }

    private static String renderPlainText(String raw) {
        return renderPlainText(raw, null);
    }

    private static String renderPlainText(String raw, AttachmentResolver resolver) {
        if (raw == null || raw.isEmpty()) return "";

        Pattern p = Pattern.compile("\\b(image|attach):([^\\s]+)");
        Matcher m = p.matcher(raw);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            String before = raw.substring(pos, m.start());
            sb.append(applyBoldItalic(escapeXml(before)));

            String kind = m.group(1);
            String refWithTrailing = m.group(2);
            String[] refAndTrail = splitTrailingPunctuation(refWithTrailing);
            String ref = refAndTrail[0];
            String trail = refAndTrail[1];

            if ("image".equalsIgnoreCase(kind)) {
                sb.append(confluenceImageFromRef(ref, resolver));
            } else {
                sb.append(confluenceAttachmentLinkFromRef(ref, ref, resolver));
            }
            sb.append(applyBoldItalic(escapeXml(trail)));
            pos = m.end();
        }
        sb.append(applyBoldItalic(escapeXml(raw.substring(pos))));
        return sb.toString();
    }

    private static String[] splitTrailingPunctuation(String s) {
        if (s == null) return new String[]{"", ""};
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' || c == '}') {
                end--;
                continue;
            }
            break;
        }
        return new String[]{s.substring(0, end), s.substring(end)};
    }

    private static String applyBoldItalic(String escapedText) {
        String s = escapedText == null ? "" : escapedText;
        s = s.replaceAll("\\*\\*\\*([^*]+?)\\*\\*\\*", "<strong><em>$1</em></strong>");
        s = s.replaceAll("___([^_]+?)___", "<strong><em>$1</em></strong>");
        s = s.replaceAll("\\*\\*([^*]+?)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__([^_]+?)__", "<strong>$1</strong>");
        s = s.replaceAll("\\*([^*]+?)\\*", "<em>$1</em>");
        s = s.replaceAll("_([^_]+?)_", "<em>$1</em>");
        return s;
    }

    private static String confluenceImageFromRef(String ref) {
        return confluenceImageFromRef(ref, null);
    }

    private static String confluenceImageFromRef(String ref, AttachmentResolver resolver) {
        String url = ref == null ? "" : ref.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return "<ac:image><ri:url ri:value=\"" + escapeXmlAttr(url) + "\"/></ac:image>";
        }
        String name = (resolver == null) ? basename(stripQueryAndFragment(url)) : resolver.resolveFileName(url);
        if (name == null || name.trim().isEmpty()) {
            name = basename(stripQueryAndFragment(url));
        }
        name = resolver == null ? safeFileName(name) : name.trim();
        return "<ac:image><ri:attachment ri:filename=\"" + escapeXmlAttr(name) + "\"/></ac:image>";
    }

    private static String confluenceAttachmentLinkFromRef(String ref, String label) {
        return confluenceAttachmentLinkFromRef(ref, label, null);
    }

    private static String confluenceAttachmentLinkFromRef(String ref, String label, AttachmentResolver resolver) {
        String raw = ref == null ? "" : ref.trim();
        String name = (resolver == null) ? basename(stripQueryAndFragment(raw)) : resolver.resolveFileName(raw);
        if (name == null || name.trim().isEmpty()) {
            name = basename(stripQueryAndFragment(raw));
        }
        name = resolver == null ? safeFileName(name) : name.trim();
        String linkLabel = (label == null || label.trim().isEmpty()) ? name : label.trim();
        linkLabel = linkLabel.replace("]]>", "]]]]><![CDATA[>");
        return "<ac:link><ri:attachment ri:filename=\"" + escapeXmlAttr(name) + "\"/>"
                + "<ac:plain-text-link-body><![CDATA[" + linkLabel + "]]></ac:plain-text-link-body></ac:link>";
    }

    private static String convertBoldItalicForConfluence(String input) {
        String s = input;
        s = s.replaceAll("(?m)\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>");
        s = s.replaceAll("(?m)___(.+?)___", "<strong><em>$1</em></strong>");
        s = s.replaceAll("(?m)\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("(?m)__(.+?)__", "<strong>$1</strong>");
        s = s.replaceAll("(?m)\\*(.+?)\\*", "<em>$1</em>");
        s = s.replaceAll("(?m)_(.+?)_", "<em>$1</em>");
        return s;
    }

    private static String convertInlineCodeForConfluence(String input) {
        return input.replaceAll("`([^`]+)`", "<code>$1</code>");
    }

    private static String convertCodeBlocksForConfluence(String input) {
        StringBuilder result = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        String lang = "";
        StringBuilder codeContent = new StringBuilder();
        Pattern fence = Pattern.compile("^```(\\w*)$");

        for (String line : lines) {
            Matcher m = fence.matcher(line);
            if (m.matches()) {
                if (!inCode) {
                    inCode = true;
                    lang = m.group(1);
                    codeContent.setLength(0);
                } else {
                    inCode = false;
                    String escape = escapeXml(codeContent.toString());
                    String langAttr = lang.isEmpty() ? "" : " language=\"" + lang + "\"";
                    result.append("<ac:parameter ac:name=\"code\">").append(lang).append("</ac:parameter>");
                    result.append("<ac:plain-text-body><![CDATA[").append(codeContent.toString()).append("]]></ac:plain-text-body>\n");
                    codeContent.setLength(0);
                    lang = "";
                }
            } else if (inCode) {
                if (codeContent.length() > 0) codeContent.append("\n");
                codeContent.append(line);
            } else {
                result.append(line).append("\n");
            }
        }
        return inCode ? input : result.toString();
    }

    private static String convertHeadingsForConfluence(String input) {
        StringBuilder result = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("######")) {
                result.append("<h6>").append(t.substring(6).trim()).append("</h6>\n");
            } else if (t.startsWith("#####")) {
                result.append("<h5>").append(t.substring(5).trim()).append("</h5>\n");
            } else if (t.startsWith("####")) {
                result.append("<h4>").append(t.substring(4).trim()).append("</h4>\n");
            } else if (t.startsWith("###")) {
                result.append("<h3>").append(t.substring(3).trim()).append("</h3>\n");
            } else if (t.startsWith("##")) {
                result.append("<h2>").append(t.substring(2).trim()).append("</h2>\n");
            } else if (t.startsWith("#")) {
                result.append("<h1>").append(t.substring(1).trim()).append("</h1>\n");
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }

    private static String convertTablesForConfluence(String input) {
        StringBuilder result = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").split("\n", -1);
        List<String[]> rows = new ArrayList<>();
        boolean inTable = false;
        int colCount = 0;

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("|")) {
                if (!inTable) {
                    inTable = true;
                    rows.clear();
                }
                String[] cells = t.split("\\|", -1);
                List<String> cellList = new ArrayList<>();
                for (int i = 1; i < cells.length - 1; i++) {
                    cellList.add(cells[i].trim());
                }
                rows.add(cellList.toArray(new String[0]));
                colCount = Math.max(colCount, cellList.size());
            } else {
                if (inTable && !rows.isEmpty()) {
                    boolean isHeaderSep = rows.get(rows.size() - 1).length == 0;
                    result.append("<table><tbody>\n");
                    for (int ri = 0; ri < rows.size(); ri++) {
                        if (isHeaderSep && ri == 0) continue;
                        result.append("<tr>\n");
                        String[] row = rows.get(ri);
                        for (int ci = 0; ci < colCount; ci++) {
                            String cell = ci < row.length ? row[ci] : "";
                            boolean isHeader = isHeaderSep || (ri == 0);
                            String tag = isHeader ? "th" : "td";
                            result.append("<").append(tag).append(">").append(cell).append("</").append(tag).append(">\n");
                        }
                        result.append("</tr>\n");
                    }
                    result.append("</tbody></table>\n");
                    rows.clear();
                    inTable = false;
                }
                result.append(line).append("\n");
            }
        }
        if (inTable && !rows.isEmpty()) {
            result.append("<table><tbody>\n");
            for (String[] row : rows) {
                result.append("<tr>\n");
                for (int ci = 0; ci < colCount; ci++) {
                    String cell = ci < row.length ? row[ci] : "";
                    result.append("<td>").append(cell).append("</td>\n");
                }
                result.append("</tr>\n");
            }
            result.append("</tbody></table>\n");
        }
        return result.toString();
    }

    private static String convertListsForConfluence(String input) {
        StringBuilder result = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").split("\n", -1);
        int listDepth = 0;
        boolean inList = false;
        boolean inOrderedList = false;

        for (String line : lines) {
            String t = line.trim();
            boolean isUl = t.matches("^[\\-\\*\\+].+");
            boolean isOl = t.matches("^\\d+\\.\\s+.+");
            int currentDepth = 0;
            if (isUl || isOl) {
                int dashCount = 0;
                for (char c : t.toCharArray()) {
                    if (c == '-' || c == '*' || c == '+') dashCount++;
                    else break;
                }
                currentDepth = dashCount;
                if (currentDepth > 0 && t.length() > currentDepth && (t.charAt(currentDepth) == ' ' || t.charAt(currentDepth) == '\t')) {
                } else {
                    currentDepth = 0;
                    isUl = false;
                    isOl = false;
                }
            }
            if (isUl || isOl) {
                if (!inList) {
                    inList = true;
                    inOrderedList = isOl;
                    result.append(isOl ? "<ol>\n" : "<ul>\n");
                }
                while (listDepth > currentDepth) {
                    result.append(inOrderedList ? "</ol>\n" : "</ul>\n");
                    inOrderedList = isOl;
                    listDepth--;
                }
                if (listDepth < currentDepth) {
                    while (listDepth < currentDepth) {
                        result.append(inOrderedList ? "<ol>\n" : "<ul>\n");
                        listDepth++;
                    }
                    inOrderedList = isOl;
                }
                String content = t.substring(currentDepth + (t.charAt(currentDepth) == ' ' || t.charAt(currentDepth) == '\t' ? 1 : 0)).trim();
                if (content.isEmpty()) continue;
                result.append("<li>").append(content).append("</li>\n");
            } else {
                if (inList) {
                    while (listDepth > 0) {
                        result.append(inOrderedList ? "</ol>\n" : "</ul>\n");
                        listDepth--;
                    }
                    inList = false;
                    inOrderedList = false;
                }
                result.append(line).append("\n");
            }
        }
        if (inList) {
            while (listDepth > 0) {
                result.append(inOrderedList ? "</ol>\n" : "</ul>\n");
                listDepth--;
            }
        }
        return result.toString();
    }

    private static String convertImagesForConfluence(String input) {
        Pattern p = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
        StringBuffer sb = new StringBuffer();
        Matcher m = p.matcher(input);
        while (m.find()) {
            String url = m.group(1);
            String name = url;
            int slash = url.lastIndexOf('/');
            if (slash >= 0) name = url.substring(slash + 1);
            if (url.startsWith("http://") || url.startsWith("https://")) {
                String repl = "<ac:image><ri:url ri:value=\"" + escapeXmlAttr(url) + "\"/></ac:image>";
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            } else {
                String repl = "<ac:image><ri:attachment ri:filename=\"" + escapeXmlAttr(name) + "\"/></ac:image>";
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String convertLinksForConfluence(String input) {
        Pattern p = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
        StringBuffer sb = new StringBuffer();
        Matcher m = p.matcher(input);
        while (m.find()) {
            String label = m.group(1);
            String url = m.group(2);
            String repl = "<a href=\"" + escapeXmlAttr(url) + "\">" + escapeXml(label) + "</a>";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeXmlAttr(String s) {
        return escapeXml(s);
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static Path relDirForEntry(String entryName) {
        String normalized = entryName.replace('\\', '/');
        List<String> parts = new ArrayList<>(Arrays.asList(normalized.split("/")));
        if (!parts.isEmpty() && parts.get(parts.size() - 1).endsWith(".xml")) {
            parts.remove(parts.size() - 1);
        }
        Path p = Paths.get("");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            p = p.resolve(safePathSegment(part));
        }
        return p;
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? fileName : fileName.substring(0, idx);
    }

    private static String safePathSegment(String raw) {
        String s = raw;
        s = s.replaceAll("[<>:\"/\\\\|?*]", "_");
        s = s.replaceAll("[\\p{Cntrl}]", "_");
        s = s.replaceAll("[\\s]+", " ").trim();
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return "_";
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("con") || lower.equals("prn") || lower.equals("aux") || lower.equals("nul")) return "_" + s;
        if (s.matches("(?i)com[1-9]") || s.matches("(?i)lpt[1-9]")) return "_" + s;
        return s;
    }

    private static String textOfFirst(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        if (nodes == null || nodes.getLength() == 0) return null;
        String t = nodes.item(0).getTextContent();
        return t == null ? null : t.trim();
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = inputStream.read(buf)) >= 0) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    private static void writeCsv(Path out, List<String[]> rows) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(out), StandardCharsets.UTF_8))) {
            for (String[] row : rows) {
                bw.write(csvLine(row));
                bw.write("\n");
            }
        }
    }

    private static String csvLine(String[] cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cols[i]));
        }
        return sb.toString();
    }

    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private enum ExportFormat {
        MARKDOWN,
        RAW_XWIKI
    }

    private static final class AttachmentRef {
        final String rawRef;
        final String filename;
        final String xwikiPath;
        AttachmentRef(String rawRef, String filename, String xwikiPath) {
            this.rawRef = rawRef;
            this.filename = filename;
            this.xwikiPath = xwikiPath;
        }
    }

    private static void extractAttachmentRefs(String markdownContent, String entryName,
                                              List<AttachmentRef> out) {
        Pattern imgPattern = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
        Matcher m = imgPattern.matcher(markdownContent);
        while (m.find()) {
            String filename = m.group(2);
            if (filename.startsWith("attach:")) {
                String attachPath = filename.substring("attach:".length());
                String name = attachPath;
                int slash = attachPath.lastIndexOf('/');
                if (slash >= 0) name = attachPath.substring(slash + 1);
                out.add(new AttachmentRef(filename, name, attachPath));
            } else if (!filename.startsWith("http://") && !filename.startsWith("https://")
                    && !filename.startsWith("data:")) {
                out.add(new AttachmentRef(filename, filename, attachPathForPage(entryName, filename)));
            }
        }
        Pattern rawPattern = Pattern.compile("image:([^\\s\\]]+)(?:\\]|$)");
        m = rawPattern.matcher(markdownContent);
        while (m.find()) {
            String raw = m.group(1);
            if (raw.endsWith("]]")) raw = raw.substring(0, raw.length() - 2);
            String filename = raw;
            int slash = raw.lastIndexOf('/');
            if (slash >= 0) filename = raw.substring(slash + 1);
            out.add(new AttachmentRef(raw, filename, attachPathForPage(entryName, filename)));
        }

        Pattern xwikiImage = Pattern.compile("(?m)^!([^!\\s|]+\\.(?:png|jpg|jpeg|gif|webp))(?:\\|.*)?$");
        m = xwikiImage.matcher(markdownContent);
        while (m.find()) {
            String raw = m.group(1);
            String filename = raw;
            int slash = raw.lastIndexOf('/');
            if (slash >= 0) filename = raw.substring(slash + 1);
            out.add(new AttachmentRef(raw, filename, attachPathForPage(entryName, filename)));
        }
    }

    private static String attachPathForPage(String entryName, String filename) {
        StringBuilder sb = new StringBuilder();
        int lastSlash = entryName.lastIndexOf('/');
        if (lastSlash > 0) {
            String dir = entryName.substring(0, lastSlash);
            String[] parts = dir.split("/");
            if (parts.length >= 2 && parts[0].equals("Main")) {
                sb.append(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    sb.append('/').append(parts[i]);
                }
            } else {
                sb.append(dir.replace('/', '/'));
            }
        }
        sb.append('/').append(filename);
        return sb.toString();
    }

    private static void copyAttachmentToPageDir(Path attachmentsDir, Path pageDir, AttachmentRef ref)
            throws IOException {
        Path attachFile = attachmentsDir.resolve(ref.xwikiPath);
        if (!Files.exists(attachFile)) {
            return;
        }
        Path dest = pageDir.resolve(safeFileName(ref.filename));
        if (Files.exists(dest)) {
            return;
        }
        Files.copy(attachFile, dest);
    }

    private static void extractEmbeddedAttachments(Element pageRoot, Path pageDir, List<Path> outFiles) {
        NodeList attachments = pageRoot.getElementsByTagName("attachment");
        if (attachments == null || attachments.getLength() == 0) {
            return;
        }
        for (int i = 0; i < attachments.getLength(); i++) {
            if (!(attachments.item(i) instanceof Element)) {
                continue;
            }
            Element att = (Element) attachments.item(i);
            String filename = directChildText(att, "filename");
            if (filename == null || filename.trim().isEmpty()) {
                filename = textOfFirst(att, "filename");
            }
            if (filename == null || filename.trim().isEmpty()) {
                continue;
            }
            String contentB64 = directChildText(att, "content");
            if (contentB64 == null || contentB64.trim().isEmpty()) {
                continue;
            }
            String normalized = contentB64.replaceAll("\\s+", "");
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(normalized);
            } catch (IllegalArgumentException e) {
                continue;
            }

            String safeName = safeFileName(filename);
            Path dest = pageDir.resolve(safeName);
            if (!Files.exists(dest)) {
                try {
                    Files.write(dest, bytes);
                } catch (IOException e) {
                    continue;
                }
            }
            outFiles.add(dest);
        }
    }

    private static String directChildText(Element parent, String childName) {
        org.w3c.dom.Node n = parent.getFirstChild();
        while (n != null) {
            if (n instanceof Element) {
                Element e = (Element) n;
                if (childName.equals(e.getTagName())) {
                    String t = e.getTextContent();
                    return t == null ? null : t.trim();
                }
            }
            n = n.getNextSibling();
        }
        return null;
    }

    private static String safeFileName(String rawName) {
        String name = rawName == null ? "" : rawName;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String safeBase = safePathSegment(base);
        String safeExt = ext.replaceAll("[<>:\"/\\\\|?*]", "_");
        safeExt = safeExt.replaceAll("[\\p{Cntrl}]", "_");
        while (safeExt.endsWith(".") || safeExt.endsWith(" ")) {
            safeExt = safeExt.substring(0, safeExt.length() - 1);
        }
        String combined = safeBase + safeExt;
        if (combined.isEmpty()) {
            return "_";
        }
        return combined;
    }

    private static final class Args {
        final String input;
        final String output;
        final ExportFormat format;
        final boolean includePreferences;
        final boolean dryRun;
        final String onlyEntry;
        final String uploadFrom;
        final String confluenceUrl;
        final String space;
        final String ancestorId;
        final String user;
        final String password;
        final String attachmentsDir;

        private Args(String input, String output, ExportFormat format, boolean includePreferences, boolean dryRun, String onlyEntry,
                     String uploadFrom,
                     String confluenceUrl, String space, String ancestorId, String user, String password,
                     String attachmentsDir) {
            this.input = input;
            this.output = output;
            this.format = format;
            this.includePreferences = includePreferences;
            this.dryRun = dryRun;
            this.onlyEntry = onlyEntry;
            this.uploadFrom = uploadFrom;
            this.confluenceUrl = confluenceUrl;
            this.space = space;
            this.ancestorId = ancestorId;
            this.user = user;
            this.password = password;
            this.attachmentsDir = attachmentsDir;
        }

        static Args parse(String[] args) {
            Map<String, String> flags = new LinkedHashMap<>();
            boolean includePreferences = false;
            boolean dryRun = false;
            int i = 0;
            while (i < args.length) {
                String a = args[i];
                if (a.equals("--include-preferences")) {
                    includePreferences = true;
                    i++;
                    continue;
                }
                if (a.equals("--dry-run")) {
                    dryRun = true;
                    i++;
                    continue;
                }

                if (a.startsWith("--")) {
                    String key = a.substring(2);
                    i++;
                    if (i >= args.length) throw usage("Missing value for --" + key);
                    List<String> parts = new ArrayList<>();
                    while (i < args.length && !args[i].startsWith("--")) {
                        parts.add(args[i]);
                        i++;
                    }
                    if (parts.isEmpty()) throw usage("Missing value for --" + key);
                    String value = String.join(" ", parts);
                    flags.put(key, value);
                    continue;
                }

                if (!flags.containsKey("input")) {
                    List<String> parts = new ArrayList<>();
                    while (i < args.length && !args[i].startsWith("--")) {
                        parts.add(args[i]);
                        i++;
                    }
                    String value = String.join(" ", parts).trim();
                    if (value.isEmpty()) throw usage("Missing --input");
                    flags.put("input", value);
                    continue;
                }

                throw usage("Unexpected argument: " + a);
            }

            String input = flags.get("input");
            String uploadFrom = flags.get("upload-from");
            if ((uploadFrom == null || uploadFrom.trim().isEmpty()) && (input == null || input.trim().isEmpty())) {
                throw usage("Missing --input");
            }

            String output = flags.getOrDefault("output", "out");

            String formatRaw = flags.get("format");
            ExportFormat format = ExportFormat.MARKDOWN;
            if (formatRaw != null && !formatRaw.trim().isEmpty()) {
                if (formatRaw.equalsIgnoreCase("markdown")) {
                    format = ExportFormat.MARKDOWN;
                } else if (formatRaw.equalsIgnoreCase("raw")) {
                    format = ExportFormat.RAW_XWIKI;
                } else {
                    throw usage("Unknown --format: " + formatRaw);
                }
            }

            String onlyEntry = flags.get("only-entry");
            String confluenceUrl = flags.get("confluence-url");
            String space = flags.get("space");
            String ancestorId = flags.get("ancestor");
            String user = flags.get("user");
            String password = flags.get("password");
            String attachmentsDir = flags.get("attachments-dir");

            return new Args(input, output, format, includePreferences, dryRun, onlyEntry, uploadFrom,
                    confluenceUrl, space, ancestorId, user, password, attachmentsDir);
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(message + "\n\nUsage:\n" +
                    "  javac -encoding UTF-8 XwikiXarExporter.java\n" +
                    "  java XwikiXarExporter --input <file-or-dir> [--output <dir>] [--format markdown|raw] [--include-preferences] [--only-entry <entry>] [--dry-run]\n" +
                    "  java XwikiXarExporter --upload-from <outDir> --confluence-url <url> --space <space> --user <user> --password <token> [--dry-run]\n\n" +
                    "  java XwikiXarExporter --input xwiki --output out \\\n" +
                    "    --confluence-url https://your-confluence.example.com \\\n" +
                    "    --space YOURSPACE \\\n" +
                    "    --ancestor <parent-page-id> \\\n" +
                    "    --user your_user --password your_api_token\n\n" +
                    "  java XwikiXarExporter --input xwiki --output out \\\n" +
                    "    --attachments-dir /path/to/xwiki/attachments\n\n" +
                    "Notes:\n" +
                    "  --confluence-url, --space, --user, --password are required for Confluence upload.\n" +
                    "  --ancestor is optional; if omitted, pages are created at space root.\n" +
                    "  --attachments-dir: local path to XWiki attachments directory (pages store attachments here).\n" +
                    "    Each page's attachments are under: <attachments-dir>/<space>/<page>/<filename.ext>\n" +
                    "    If provided, referenced image/attach files are exported alongside pages and uploaded to Confluence.\n" +
                    "  --only-entry: export a single zip entry (e.g. Space/Page/WebHome.xml) for quick testing.\n" +
                    "  --upload-from: upload from an existing export output dir (must contain pages.csv).\n" +
                    "  --dry-run: no HTTP requests; print what would be created/updated/uploaded.\n" +
                    "  For API token: https://id.atlassian.com/manage-profile/security/api-tokens\n");
        }
    }

    private static final class ConfluenceApiClient {
        private final String baseUrl;
        private final String space;
        private final String ancestorId;
        private final String authHeader;
        private final boolean dryRun;
        private final Map<String, String> pageCache = new LinkedHashMap<>();
        private final int connectTimeoutMs = 20_000;
        private final int readTimeoutMs = 60_000;
        private final HttpClient http;

        ConfluenceApiClient(String baseUrl, String space, String ancestorId, String user, String password, boolean dryRun) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.space = space;
            this.ancestorId = ancestorId;
            this.dryRun = dryRun;
            String auth = user + ":" + (password == null ? "" : password);
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String createOrUpdatePage(String title, String content, String parentId) {
            if (dryRun) {
                System.out.println("  [DRY RUN] upsert page: title=" + title + ", parentId=" + nullToEmpty(parentId));
                return "dry-" + Integer.toHexString((title + "|" + nullToEmpty(parentId)).hashCode());
            }
            String existingId = findPageByTitle(title, parentId);
            if (existingId != null) {
                return updatePage(existingId, title, content);
            }
            return createPage(title, content, parentId);
        }

        void uploadAttachments(String pageId, List<Path> files) {
            if (dryRun) {
                for (Path f : files) {
                    if (!Files.exists(f)) continue;
                    System.out.println("    [DRY RUN] upload attachment: pageId=" + pageId + ", file=" + f.getFileName());
                }
                return;
            }
            for (Path f : files) {
                if (!Files.exists(f)) continue;
                try {
                    uploadAttachment(pageId, f);
                } catch (Exception e) {
                    System.err.println("  [WARN] uploadAttachment " + f.getFileName() + " failed: " + e.getMessage());
                }
            }
        }

        private void uploadAttachment(String pageId, Path file) throws IOException {
            String boundary = "BOUNDARY_" + System.currentTimeMillis();
            String fileName = file.getFileName().toString();
            String mime = mimeType(fileName);

            byte[] fileBytes = Files.readAllBytes(file);
            List<byte[]> parts = new ArrayList<>();
            parts.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(fileBytes);
            parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/api/content/" + pageId + "/child/attachment"))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Authorization", authHeader)
                    .header("X-Atlassian-Token", "no-check")
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                    .build();

            HttpResponse<byte[]> response;
            try {
                response = send(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            int code = response.statusCode();
            if (!(code >= HTTP_OK_START && code <= HTTP_OK_END)) {
                System.err.println("  [WARN] uploadAttachment " + fileName + " HTTP " + code + ": " + bodyAsString(response));
            }
        }

        private String mimeType(String fileName) {
            String l = fileName.toLowerCase(Locale.ROOT);
            if (l.endsWith(".png")) return "image/png";
            if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
            if (l.endsWith(".gif")) return "image/gif";
            if (l.endsWith(".webp")) return "image/webp";
            if (l.endsWith(".pdf")) return "application/pdf";
            if (l.endsWith(".doc")) return "application/msword";
            if (l.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            if (l.endsWith(".xls")) return "application/vnd.ms-excel";
            if (l.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            if (l.endsWith(".zip")) return "application/zip";
            if (l.endsWith(".tar")) return "application/x-tar";
            if (l.endsWith(".gz") || l.endsWith(".gzip")) return "application/gzip";
            return "application/octet-stream";
        }

        private String findPageByTitle(String title, String parentId) {
            try {
                String encodedTitle = URLEncoder.encode(title, "UTF-8");
                String urlStr = baseUrl + "/rest/api/content?spaceKey=" + URLEncoder.encode(space, "UTF-8")
                        + "&title=" + encodedTitle + "&expand=version";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlStr))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("Authorization", authHeader)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = send(request);
                int code = response.statusCode();
                if (code >= HTTP_OK_START && code <= HTTP_OK_END) {
                    String body = bodyAsString(response);
                    int idStart = body.indexOf("\"id\":\"");
                    if (idStart >= 0) {
                        int begin = idStart + "\"id\":\"".length();
                        int end = body.indexOf("\"", begin);
                        if (end > begin) return body.substring(begin, end);
                    }
                }
            } catch (Exception e) {
                System.err.println("  [WARN] findPageByTitle failed for '" + title + "': " + e.getMessage());
            }
            return null;
        }

        private String createPage(String title, String content, String parentId) {
            int retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    String urlStr = baseUrl + "/rest/api/content";
                    String body = buildCreateJson(title, content, parentId != null ? parentId : ancestorId);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(urlStr))
                            .timeout(Duration.ofMillis(readTimeoutMs))
                            .header("Authorization", authHeader)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<byte[]> response = send(request);
                    int code = response.statusCode();
                    String responseBody = bodyAsString(response);

                    if (code >= HTTP_OK_START && code <= HTTP_OK_END) {
                        int idStart = responseBody.indexOf("\"id\":\"");
                        if (idStart >= 0) {
                            int begin = idStart + "\"id\":\"".length();
                            int end = responseBody.indexOf("\"", begin);
                            if (end > begin) return responseBody.substring(begin, end);
                        }
                    }
                    if (code == 429 || code == 503) {
                        retries++;
                        Thread.sleep(RETRY_DELAY_MS * retries);
                        continue;
                    }
                    System.err.println("  [WARN] createPage '" + title + "' HTTP " + code + ": " + responseBody.substring(0, Math.min(200, responseBody.length())));
                    return null;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    System.err.println("  [WARN] createPage '" + title + "' failed: " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        private String updatePage(String pageId, String title, String content) {
            int retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    String versionStr = getPageVersion(pageId);
                    int version = 1;
                    if (versionStr != null) {
                        try { version = Integer.parseInt(versionStr) + 1; } catch (NumberFormatException ignored) {}
                    }

                    String urlStr = baseUrl + "/rest/api/content/" + pageId;
                    String body = buildUpdateJson(title, content, version);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(urlStr))
                            .timeout(Duration.ofMillis(readTimeoutMs))
                            .header("Authorization", authHeader)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<byte[]> response = send(request);
                    int code = response.statusCode();
                    String responseBody = bodyAsString(response);

                    if (code >= HTTP_OK_START && code <= HTTP_OK_END) {
                        return pageId;
                    }
                    if (code == 429 || code == 503) {
                        retries++;
                        Thread.sleep(RETRY_DELAY_MS * retries);
                        continue;
                    }
                    System.err.println("  [WARN] updatePage '" + pageId + "' HTTP " + code + ": " + responseBody.substring(0, Math.min(200, responseBody.length())));
                    return null;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    System.err.println("  [WARN] updatePage '" + pageId + "' failed: " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        private String getPageVersion(String pageId) {
            try {
                String urlStr = baseUrl + "/rest/api/content/" + pageId + "?expand=version";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlStr))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("Authorization", authHeader)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = send(request);
                int code = response.statusCode();
                if (code >= HTTP_OK_START && code <= HTTP_OK_END) {
                    String body = bodyAsString(response);
                    int vStart = body.indexOf("\"number\":");
                    if (vStart >= 0) {
                        int begin = vStart + "\"number\":".length();
                        int end = body.indexOf(",", begin);
                        if (end < 0) end = body.indexOf("}", begin);
                        if (end > begin) return body.substring(begin, end).trim();
                    }
                }
            } catch (Exception e) {
                System.err.println("  [WARN] getPageVersion '" + pageId + "' failed: " + e.getMessage());
            }
            return "1";
        }

        private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }

        private String bodyAsString(HttpResponse<byte[]> response) {
            if (response == null || response.body() == null) return "";
            return new String(response.body(), StandardCharsets.UTF_8);
        }

        private String buildCreateJson(String title, String content, String ancestorPageId) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"page\",\"title\":\"").append(escapeJson(title)).append("\",");
            sb.append("\"space\":{\"key\":\"").append(escapeJson(space)).append("\"},");
            sb.append("\"body\":{\"storage\":{\"value\":\"").append(escapeJson(content)).append("\",");
            sb.append("\"representation\":\"storage\"}},");
            if (ancestorPageId != null && !ancestorPageId.isEmpty()) {
                sb.append("\"ancestors\":[{\"id\":\"").append(ancestorPageId).append("\"}],");
            }
            sb.append("\"metadata\":{\"labels\":[]}}");
            return sb.toString();
        }

        private String buildUpdateJson(String title, String content, int version) {
            return "{\"type\":\"page\",\"title\":\"" + escapeJson(title) + "\",\"version\":{\"number\":" + version
                    + ",\"minorEdit\":false},\"body\":{\"storage\":{\"value\":\"" + escapeJson(content)
                    + "\",\"representation\":\"storage\"}}}";
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }

        private String readStream(java.io.InputStream is) throws IOException {
            if (is == null) return "";
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int r;
            while ((r = br.read(buf)) >= 0) sb.append(buf, 0, r);
            return sb.toString();
        }
    }

    private static final class XwikiToMarkdown {
        static String convert(String xwiki) {
            if (xwiki == null) return "";
            String s = xwiki;
            s = s.replace("{{dashboard/}}", "");
            s = convertCodeMacros(s);
            s = convertHeadings(s);
            s = convertImages(s);
            s = convertLinks(s);
            s = s.replace("\r\n", "\n");
            if (!s.endsWith("\n")) s = s + "\n";
            return s;
        }

        private static String convertImages(String input) {
            String s = input;
            s = s.replaceAll("(?m)^\\s*image:([^\\s]+)\\s*$", "![]($1)");
            s = s.replaceAll("image:([\\w\\-.]+\\.(?:png|jpg|jpeg|gif|webp))", "![]($1)");
            return s;
        }

        private static String convertCodeMacros(String input) {
            String s = input;
            int guard = 0;
            while (true) {
                int open = s.indexOf("{{code");
                if (open < 0) break;
                int openEnd = s.indexOf("}}", open);
                if (openEnd < 0) break;
                int close = s.indexOf("{{/code}}", openEnd + 2);
                if (close < 0) break;
                String header = s.substring(open, openEnd + 2);
                String body = s.substring(openEnd + 2, close);
                String lang = extractLanguage(header);
                String fenceOpen = lang.isEmpty() ? "```" : "```" + lang;
                String replacement = "\n" + fenceOpen + "\n" + trimLeadingNewline(body) + "\n```\n";
                s = s.substring(0, open) + replacement + s.substring(close + "{{/code}}".length());
                guard++;
                if (guard > 10_000) break;
            }
            return s;
        }

        private static String extractLanguage(String codeHeader) {
            int idx = codeHeader.indexOf("language=");
            if (idx < 0) return "";
            int start = idx + "language=".length();
            while (start < codeHeader.length() && codeHeader.charAt(start) == ' ') start++;
            if (start >= codeHeader.length()) return "";
            char q = codeHeader.charAt(start);
            if (q == '"' || q == '\'') {
                int end = codeHeader.indexOf(q, start + 1);
                if (end < 0) return "";
                return normalizeLang(codeHeader.substring(start + 1, end));
            }
            int end = start;
            while (end < codeHeader.length()) {
                char c = codeHeader.charAt(end);
                if (c == ' ' || c == '}' || c == '/') break;
                end++;
            }
            return normalizeLang(codeHeader.substring(start, end));
        }

        private static String normalizeLang(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.equalsIgnoreCase("shell")) return "bash";
            if (s.equalsIgnoreCase("js")) return "javascript";
            return s;
        }

        private static String trimLeadingNewline(String s) {
            if (s == null || s.isEmpty()) return "";
            if (s.startsWith("\r\n")) return s.substring(2);
            if (s.startsWith("\n")) return s.substring(1);
            return s;
        }

        private static String convertHeadings(String input) {
            String[] lines = input.replace("\r\n", "\n").split("\n", -1);
            StringBuilder out = new StringBuilder(input.length());
            for (int i = 0; i < lines.length; i++) {
                out.append(convertHeadingLine(lines[i]));
                if (i < lines.length - 1) out.append("\n");
            }
            return out.toString();
        }

        private static String convertHeadingLine(String line) {
            String t = line.trim();
            if (!t.startsWith("=") || !t.endsWith("=")) return line;
            int left = 0;
            while (left < t.length() && t.charAt(left) == '=') left++;
            int right = 0;
            while (right < t.length() && t.charAt(t.length() - 1 - right) == '=') right++;
            int level = Math.min(left, right);
            if (level <= 0 || level > 6) return line;
            String inner = t.substring(level, t.length() - level).trim();
            if (inner.isEmpty()) return line;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < level; i++) sb.append('#');
            return sb.append(' ').append(inner).toString();
        }

        private static String convertLinks(String input) {
            String s = input;
            int guard = 0;
            while (true) {
                int open = s.indexOf("[[");
                if (open < 0) break;
                int close = s.indexOf("]]", open + 2);
                if (close < 0) break;
                String inside = s.substring(open + 2, close);
                s = s.substring(0, open) + linkReplacement(inside) + s.substring(close + 2);
                guard++;
                if (guard > 200_000) break;
            }
            return s;
        }

        private static String linkReplacement(String inside) {
            String t = inside.trim();
            int sep = t.indexOf(">>");
            if (sep >= 0) {
                String label = t.substring(0, sep).trim();
                String target = t.substring(sep + 2).trim();
                if (label.isEmpty()) label = target;
                return "[" + label + "](" + target + ")";
            }
            return t;
        }
    }
}
