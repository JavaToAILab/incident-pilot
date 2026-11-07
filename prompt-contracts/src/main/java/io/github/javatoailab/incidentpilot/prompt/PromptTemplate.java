package io.github.javatoailab.incidentpilot.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One released version of a prompt, treated like an API contract.
 *
 * <p>A prompt file has a small header and a body, separated by a line containing only {@code ---}:
 * <pre>
 * id: incident-summary
 * version: 2
 * description: what changed in this version
 * variables: max_summary_words, severity_definitions
 * ---
 * You are IncidentPilot ... at most {{max_summary_words}} words ...
 * </pre>
 *
 * <p>The contract is checked twice. When the file is parsed, every {@code {{placeholder}}} in the
 * body must be declared in {@code variables}, and every declared variable must be used. When it is
 * rendered, the caller must supply exactly the declared variables: none missing, none extra.
 */
public record PromptTemplate(String id, int version, String description, Set<String> variables,
                             String body, String sha256) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z][a-z0-9_]*)\\s*}}");
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public PromptTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(sha256, "sha256");
        variables = Set.copyOf(variables);
    }

    public PromptRef ref() {
        return new PromptRef(id, version, sha256);
    }

    /** Parses a prompt file. {@code source} names the file in error messages. */
    public static PromptTemplate parse(String text, String source) {
        // Hash and parse the same normalized text, so a checkout that converts line endings
        // (Git on Windows, for example) doesn't look like a changed prompt.
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int separator = normalized.indexOf("\n---\n");
        if (separator < 0) {
            throw new PromptContractException(source + ": missing the '---' line between header and body");
        }
        Map<String, String> header = parseHeader(normalized.substring(0, separator), source);
        String body = normalized.substring(separator + "\n---\n".length()).strip();

        String id = required(header, "id", source);
        if (!ID.matcher(id).matches()) {
            throw new PromptContractException(source + ": id must be lowercase words separated by dashes: " + id);
        }
        int version;
        try {
            version = Integer.parseInt(required(header, "version", source));
        } catch (NumberFormatException e) {
            throw new PromptContractException(source + ": version must be a whole number");
        }
        Set<String> declared = Arrays.stream(header.getOrDefault("variables", "").split(","))
                .map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> used = placeholders(body);
        Set<String> undeclared = new TreeSet<>(used);
        undeclared.removeAll(declared);
        if (!undeclared.isEmpty()) {
            throw new PromptContractException(source + ": placeholders not declared in 'variables': " + undeclared);
        }
        Set<String> unused = new TreeSet<>(declared);
        unused.removeAll(used);
        if (!unused.isEmpty()) {
            throw new PromptContractException(source + ": declared variables never used in the body: " + unused);
        }

        return new PromptTemplate(id, version, header.getOrDefault("description", ""), declared, body,
                sha256(normalized));
    }

    /** Fills in every declared variable. Missing or unexpected values are contract violations. */
    public RenderedPrompt render(Map<String, ?> values) {
        Set<String> missing = new TreeSet<>(variables);
        missing.removeAll(values.keySet());
        if (!missing.isEmpty()) {
            throw new PromptContractException(ref() + ": missing values for " + missing);
        }
        Set<String> unexpected = new TreeSet<>(values.keySet());
        unexpected.removeAll(variables);
        if (!unexpected.isEmpty()) {
            throw new PromptContractException(ref() + ": unexpected values for " + unexpected
                    + " (the prompt does not declare them)");
        }
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = values.get(m.group(1));
            if (value == null || value.toString().isBlank()) {
                throw new PromptContractException(ref() + ": blank value for " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value.toString()));
        }
        m.appendTail(out);
        return new RenderedPrompt(out.toString(), ref());
    }

    static Set<String> placeholders(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static Map<String, String> parseHeader(String header, String source) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 1) {
                throw new PromptContractException(source + ": header lines must look like 'key: value': " + line);
            }
            fields.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
        return fields;
    }

    private static String required(Map<String, String> header, String key, String source) {
        String value = header.get(key);
        if (value == null || value.isBlank()) {
            throw new PromptContractException(source + ": header is missing '" + key + "'");
        }
        return value;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JDK", e);
        }
    }
}
