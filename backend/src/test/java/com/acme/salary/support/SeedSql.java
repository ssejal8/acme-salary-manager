package com.acme.salary.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Reads the {@code VALUES} rows out of a seed migration so tests can assert on the data
 * itself.
 *
 * <p>Deliberately not a SQL parser: it strips comments, finds the {@code VALUES} list of a
 * named {@code INSERT}, and splits it into rows and fields. That is enough for the seed
 * files, whose value lists are plain literals, and it means the figures a seed claims can
 * be checked without a database.
 */
public final class SeedSql {

    private final String sql;

    private SeedSql(String sql) {
        this.sql = sql;
    }

    public static SeedSql load(String classpathLocation) {
        try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
            String raw = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            return new SeedSql(raw.replaceAll("--[^\\n]*", ""));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + classpathLocation, e);
        }
    }

    /**
     * The rows of the {@code VALUES} list belonging to {@code INSERT INTO <table>}, each as
     * a list of unquoted field values.
     *
     * @param expectedFields the field count every row must have; rows of any other width
     *     are a sign the statement was misread, and fail loudly rather than being skipped
     */
    public List<List<String>> rows(String table, int expectedFields) {
        String segment = valuesSegment(table);
        List<List<String>> rows = new ArrayList<>();
        for (String group : topLevelGroups(segment)) {
            List<String> fields = splitFields(group);
            if (fields.size() != expectedFields) {
                throw new IllegalStateException(
                        "expected %d fields per row for %s but read %d in: %s"
                                .formatted(expectedFields, table, fields.size(), group));
            }
            rows.add(fields);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("no VALUES rows found for " + table);
        }
        return rows;
    }

    private String valuesSegment(String table) {
        int insertAt = sql.toUpperCase(Locale.ROOT).indexOf("INSERT INTO " + table.toUpperCase(Locale.ROOT));
        if (insertAt < 0) {
            throw new IllegalStateException("no INSERT INTO " + table + " in this seed");
        }
        int valuesAt = sql.toUpperCase(Locale.ROOT).indexOf("VALUES", insertAt);
        int end = sql.indexOf(';', valuesAt);
        String segment = sql.substring(valuesAt + "VALUES".length(), end < 0 ? sql.length() : end);

        // The row list ends at the alias that names the derived table, where one is used.
        int aliasAt = segment.indexOf(") AS ");
        return aliasAt < 0 ? segment : segment.substring(0, aliasAt + 1);
    }

    /** Each parenthesised group at depth one — one per row. */
    private static List<String> topLevelGroups(String segment) {
        List<String> groups = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inQuotes = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
            }
            if (inQuotes) {
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && start >= 0) {
                    groups.add(segment.substring(start, i));
                }
            }
        }
        return groups;
    }

    private static List<String> splitFields(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int depth = 0;
        for (char c : row.toCharArray()) {
            if (c == '\'') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }
            if (!inQuotes) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    fields.add(clean(current.toString()));
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        fields.add(clean(current.toString()));
        return fields;
    }

    /** Strips whitespace, type prefixes such as {@code DATE}, and surrounding quotes. */
    private static String clean(String field) {
        String value = field.strip();
        for (String prefix : List.of("DATE ", "TIMESTAMPTZ ", "NUMERIC ")) {
            if (value.toUpperCase(Locale.ROOT).startsWith(prefix)) {
                value = value.substring(prefix.length()).strip();
            }
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
