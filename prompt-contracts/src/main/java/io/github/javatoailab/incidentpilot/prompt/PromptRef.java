package io.github.javatoailab.incidentpilot.prompt;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity of one released prompt: its id, its version and the first characters of its content hash.
 * It is what gets logged with every model call, the way you would log an API version.
 *
 * <p>Format: {@code incident-summary@v2#1a2b3c4d}
 */
public record PromptRef(String id, int version, String hash) {

    private static final Pattern FORMAT = Pattern.compile("([a-z0-9-]+)@v(\\d+)#([0-9a-f]{8,64})");

    public PromptRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hash, "hash");
        if (version < 1) {
            throw new IllegalArgumentException("version must be 1 or more");
        }
    }

    public static PromptRef parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a prompt ref: " + text);
        }
        return new PromptRef(m.group(1), Integer.parseInt(m.group(2)), m.group(3));
    }

    @Override
    public String toString() {
        return id + "@v" + version + "#" + hash.substring(0, Math.min(8, hash.length()));
    }
}
