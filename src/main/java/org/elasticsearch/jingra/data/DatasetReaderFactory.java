package org.elasticsearch.jingra.data;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates the appropriate {@link DatasetReader} based on the file path or glob pattern.
 *
 * <ul>
 *   <li>{@code .ndjson} / {@code .ndjson.gz} (or a glob expanding to such files) → {@link NdjsonReader}</li>
 *   <li>anything else → {@link ParquetReader}</li>
 * </ul>
 *
 * <p>Glob patterns (containing {@code *} or {@code ?}) are expanded against the filesystem.
 * The matched paths are sorted alphabetically so that chunk files like
 * {@code part-0000.ndjson.gz}, {@code part-0001.ndjson.gz}, … are processed in order.</p>
 */
public class DatasetReaderFactory {

    private DatasetReaderFactory() {}

    public static DatasetReader create(String path) {
        if (path == null) {
            return new ParquetReader(null);
        }
        List<String> paths = expandGlob(path);
        if (paths.isEmpty()) {
            throw new RuntimeException("No files matched: " + path);
        }
        String first = paths.get(0);
        if (first.endsWith(".ndjson") || first.endsWith(".ndjson.gz")) {
            return new NdjsonReader(paths);
        }
        if (paths.size() > 1) {
            throw new IllegalArgumentException(
                "Glob patterns are only supported for NDJSON files, got: " + path);
        }
        return new ParquetReader(first);
    }

    /**
     * Enumerate all strings that MATCH a glob pattern containing {@code [X-Y]} character ranges
     * or character lists {@code [abc]}, without touching the filesystem.
     *
     * <p>Wildcard {@code *} and {@code ?} are NOT supported — use explicit ranges instead.
     * Works on both file-system paths and URL strings.</p>
     *
     * <p>Example: {@code "dir/part-000[0-4].ndjson.gz"} →
     * {@code ["dir/part-0000.ndjson.gz", …, "dir/part-0004.ndjson.gz"]}</p>
     */
    public static List<String> enumerateGlobPattern(String pattern) {
        if (pattern.contains("*") || pattern.contains("?")) {
            throw new IllegalArgumentException(
                "enumerateGlobPattern does not support * or ? wildcards — "
                + "use explicit character ranges like [0-9] instead: " + pattern);
        }
        return expandPattern(pattern);
    }

    private static List<String> expandPattern(String pattern) {
        int start = pattern.indexOf('[');
        if (start < 0) {
            return List.of(pattern);
        }
        int end = pattern.indexOf(']', start);
        if (end < 0) {
            throw new IllegalArgumentException("Unclosed '[' in pattern: " + pattern);
        }
        String prefix = pattern.substring(0, start);
        List<Character> chars = parseCharClass(pattern.substring(start + 1, end));
        List<String> suffixes = expandPattern(pattern.substring(end + 1));
        List<String> results = new ArrayList<>();
        for (char c : chars) {
            for (String suffix : suffixes) {
                results.add(prefix + c + suffix);
            }
        }
        return results;
    }

    private static List<Character> parseCharClass(String spec) {
        List<Character> chars = new ArrayList<>();
        int i = 0;
        while (i < spec.length()) {
            if (i + 2 < spec.length() && spec.charAt(i + 1) == '-') {
                for (char c = spec.charAt(i); c <= spec.charAt(i + 2); c++) {
                    chars.add(c);
                }
                i += 3;
            } else {
                chars.add(spec.charAt(i));
                i++;
            }
        }
        return chars;
    }

    /**
     * If {@code path} contains glob characters expand it; otherwise return it as-is.
     * Results are sorted so chunk files arrive in a stable, predictable order.
     */
    public static List<String> expandGlob(String path) {
        if (!path.contains("*") && !path.contains("?") && !path.contains("[")) {
            return List.of(path);
        }
        Path p = Paths.get(path);
        Path parent = p.getParent();
        String glob = p.getFileName().toString();
        if (parent == null) {
            parent = Paths.get(".");
        }
        List<String> results = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, glob)) {
            for (Path match : stream) {
                results.add(match.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to expand glob '" + path + "': " + e.getMessage(), e);
        }
        Collections.sort(results);
        return results;
    }
}
