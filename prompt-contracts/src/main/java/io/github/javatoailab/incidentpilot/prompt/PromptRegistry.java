package io.github.javatoailab.incidentpilot.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every released prompt, loaded from the classpath and checked against a lock file.
 *
 * <p>The lock file ({@code prompts.lock}) lists each released version with the SHA-256 of its
 * content. Released prompts are immutable, like a published API version: if a file no longer
 * matches its locked hash, loading fails. To change a prompt, add the next version and lock it.
 *
 * <pre>
 * # id               version  sha256
 * incident-summary   1        5c0f...
 * incident-summary   2        9a41...
 * </pre>
 */
public final class PromptRegistry {

    public static final String DEFAULT_ROOT = "prompts";

    private final Map<String, NavigableMap<Integer, PromptTemplate>> prompts;

    private PromptRegistry(Map<String, NavigableMap<Integer, PromptTemplate>> prompts) {
        this.prompts = prompts;
    }

    /** Loads {@code prompts/prompts.lock} and every prompt it lists from the classpath. */
    public static PromptRegistry load() {
        return load(PromptRegistry.class.getClassLoader(), DEFAULT_ROOT);
    }

    public static PromptRegistry load(ClassLoader loader, String root) {
        Map<String, NavigableMap<Integer, PromptTemplate>> prompts = new TreeMap<>();
        for (LockEntry entry : readLock(loader, root)) {
            String path = root + "/" + entry.id() + "/v" + entry.version() + ".prompt";
            PromptTemplate template = PromptTemplate.parse(resource(loader, path), path);
            if (!template.id().equals(entry.id()) || template.version() != entry.version()) {
                throw new PromptContractException(path + ": header says " + template.ref()
                        + " but the lock file lists it as " + entry.id() + " v" + entry.version());
            }
            if (!template.sha256().equals(entry.sha256())) {
                throw new PromptContractException(path + " changed after it was released (hash "
                        + template.sha256().substring(0, 8) + ", locked " + entry.sha256().substring(0, 8)
                        + "). Released prompts are immutable: put the change in v" + (entry.version() + 1)
                        + " and add it to prompts.lock.");
            }
            prompts.computeIfAbsent(entry.id(), k -> new TreeMap<>()).put(entry.version(), template);
        }
        return new PromptRegistry(prompts);
    }

    public PromptTemplate get(String id, int version) {
        PromptTemplate template = versions(id).get(version);
        if (template == null) {
            throw new PromptContractException("no released prompt " + id + " v" + version
                    + "; released versions: " + versions(id).keySet());
        }
        return template;
    }

    public PromptTemplate latest(String id) {
        return versions(id).lastEntry().getValue();
    }

    /** {@code "incident-summary"} means the latest version; {@code "incident-summary@2"} pins one. */
    public PromptTemplate resolve(String selector) {
        int at = selector.indexOf('@');
        if (at < 0) {
            return latest(selector.strip());
        }
        String version = selector.substring(at + 1).strip();
        if (version.startsWith("v")) {
            version = version.substring(1);
        }
        return get(selector.substring(0, at).strip(), Integer.parseInt(version));
    }

    public NavigableMap<Integer, PromptTemplate> versions(String id) {
        NavigableMap<Integer, PromptTemplate> versions = prompts.get(id);
        if (versions == null) {
            throw new PromptContractException("no prompt with id '" + id + "'; known ids: " + prompts.keySet());
        }
        return versions;
    }

    /** Lock-file lines for every prompt file under a directory: what an author pastes in after adding a version. */
    public static List<String> lockLines(Path promptsDir) {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.walk(promptsDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".prompt")).sorted(Comparator.naturalOrder()).toList()) {
                PromptTemplate t = PromptTemplate.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
                lines.add(String.format("%-20s %-4d %s", t.id(), t.version(), t.sha256()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    record LockEntry(String id, int version, String sha256) {
    }

    static List<LockEntry> readLock(ClassLoader loader, String root) {
        String path = root + "/prompts.lock";
        List<LockEntry> entries = new ArrayList<>();
        for (String raw : resource(loader, path).split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 3) {
                throw new PromptContractException(path + ": expected 'id version sha256' but got: " + line);
            }
            entries.add(new LockEntry(parts[0], Integer.parseInt(parts[1]), parts[2]));
        }
        return entries;
    }

    private static String resource(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new PromptContractException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
