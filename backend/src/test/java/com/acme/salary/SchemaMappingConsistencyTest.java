package com.acme.salary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.StreamUtils;

/**
 * Checks that every mapped table and column exists in the Flyway migrations — the same
 * agreement {@code ddl-auto=validate} enforces at startup, but without a database.
 *
 * <p>This exists because the container-backed tests skip where Docker is unavailable
 * (ADR-012 keeps H2 off the table), which would otherwise leave a renamed column to be
 * discovered by a failed deployment. Hibernate builds its metadata offline here, so the
 * check costs nothing and runs everywhere.
 *
 * <p>What it does <em>not</em> verify: column types, nullability, lengths, and anything
 * about constraints or indexes. Those still need the real database, and the {@code *IT}
 * tests cover them once Docker is running.
 */
class SchemaMappingConsistencyTest {

    private static final String ENTITY_PACKAGE = "com.acme.salary";
    private static final String MIGRATION_PATTERN = "classpath*:db/migration/V*.sql";

    /** Words that begin a table-level clause rather than a column definition. */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "EXCLUDE", "LIKE");

    @Test
    void everyMappedTableAndColumnExistsInTheMigrations() {
        Map<String, Set<String>> schema = parseMigrations();
        Metadata metadata = buildMetadataOffline();

        List<String> problems = new ArrayList<>();
        for (PersistentClass entity : metadata.getEntityBindings()) {
            String table = entity.getTable().getName().toLowerCase(Locale.ROOT);
            Set<String> columnsInSchema = schema.get(table);
            if (columnsInSchema == null) {
                problems.add("table '%s' is mapped by %s but no migration creates it"
                        .formatted(table, entity.getEntityName()));
                continue;
            }
            for (Column column : entity.getTable().getColumns()) {
                String columnName = column.getName().toLowerCase(Locale.ROOT);
                if (!columnsInSchema.contains(columnName)) {
                    problems.add("column '%s.%s' is mapped by %s but does not exist in the schema"
                            .formatted(table, columnName, entity.getEntityName()));
                }
            }
        }

        assertThat(problems)
                .describedAs("mappings disagreeing with db/migration — startup would fail on ddl-auto=validate")
                .isEmpty();
    }

    @Test
    void theMigrationsAreParseableAndDefineTheExpectedTables() {
        // Guards the test above from silently passing because parsing produced nothing.
        Map<String, Set<String>> schema = parseMigrations();

        assertThat(schema).containsKeys(
                "users", "departments", "designations", "grades", "employees",
                "salary_components", "salary_structures", "salary_structure_components",
                "payroll_runs", "payslips", "payslip_lines", "audit_events");
        assertThat(schema.get("employees")).contains("employee_code", "work_email", "exit_date", "status");
    }

    @Test
    void atLeastOneEntityIsMapped() {
        // If entity scanning broke, the consistency test would pass vacuously.
        assertThat(buildMetadataOffline().getEntityBindings()).isNotEmpty();
    }

    /**
     * Builds Hibernate mapping metadata with no JDBC connection. Every {@code @Entity} in
     * the application is included by scanning, so a new entity is covered without anyone
     * remembering to add it here.
     */
    private static Metadata buildMetadataOffline() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        scanEntityClasses().forEach(sources::addAnnotatedClass);
        return sources.buildMetadata();
    }

    private static List<Class<?>> scanEntityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(jakarta.persistence.Entity.class));
        List<Class<?>> entities = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            try {
                entities.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned entity could not be loaded", e);
            }
        }
        return entities;
    }

    /** Table name to column names, read from the migration scripts. */
    private static Map<String, Set<String>> parseMigrations() {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        for (Resource migration : findMigrations()) {
            String sql = stripComments(read(migration));
            parseCreateTables(sql, schema);
            parseAddedColumns(sql, schema);
        }
        return schema;
    }

    private static Resource[] findMigrations() {
        try {
            Resource[] migrations = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
            if (migrations.length == 0) {
                throw new IllegalStateException("no migrations found at " + MIGRATION_PATTERN);
            }
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?(\\w+)\"?\\s*\\(",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN =
            Pattern.compile("ALTER\\s+TABLE\\s+\"?(\\w+)\"?\\s+ADD\\s+(?:COLUMN\\s+)?\"?(\\w+)\"?\\s+[^;]+;",
                    Pattern.CASE_INSENSITIVE);

    private static void parseCreateTables(String sql, Map<String, Set<String>> schema) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            String body = balancedBody(sql, matcher.end() - 1);
            Set<String> columns = schema.computeIfAbsent(table, key -> new LinkedHashSet<>());
            for (String definition : splitTopLevel(body)) {
                String trimmed = definition.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String firstWord = trimmed.split("\\s+")[0].replace("\"", "");
                if (!CLAUSE_KEYWORDS.contains(firstWord.toUpperCase(Locale.ROOT))) {
                    columns.add(firstWord.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private static void parseAddedColumns(String sql, Map<String, Set<String>> schema) {
        Matcher matcher = ADD_COLUMN.matcher(sql);
        while (matcher.find()) {
            String added = matcher.group(2).toLowerCase(Locale.ROOT);
            if (CLAUSE_KEYWORDS.contains(added.toUpperCase(Locale.ROOT))) {
                continue; // ALTER TABLE … ADD CONSTRAINT, not a column
            }
            schema.computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), key -> new LinkedHashSet<>())
                    .add(added);
        }
    }

    /** Returns the contents of the parenthesised block that starts at {@code openIndex}. */
    private static String balancedBody(String sql, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openIndex + 1, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced parentheses in migration SQL");
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
