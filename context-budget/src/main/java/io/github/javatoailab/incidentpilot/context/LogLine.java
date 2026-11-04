package io.github.javatoailab.incidentpilot.context;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One log line, classified so the context builder knows what to keep when space runs out.
 *
 * @param template the line with its timestamp removed and its variable parts (IDs, numbers)
 *                 masked; lines with the same template are the same message
 * @param timestamp the leading timestamp, or null if the line has none
 */
record LogLine(int index, String text, int priority, String template, String timestamp) {

    static final int ERROR = 0, WARN = 1, INFO = 2, OTHER = 3;

    private static final Pattern LEVEL =
            Pattern.compile("\\b(FATAL|ERROR|SEVERE|WARN|WARNING|INFO|DEBUG|TRACE)\\b");
    private static final Pattern LEADING_TIMESTAMP =
            Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s*");
    /** Stack-trace lines: frames, "... N more", "Caused by:", and a bare exception header. */
    private static final Pattern CONTINUATION = Pattern.compile(
            "^\\s+(at |\\.\\.\\. \\d+ more)|^Caused by:|^[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+(Exception|Error)(:|$)");

    // Masks for the variable parts of a message, applied in this order.
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-fA-F]{8,}\\b");
    private static final Pattern MIXED_ID = Pattern.compile("\\b(?=[A-Za-z_]*\\d)(?=\\d*[A-Za-z])[A-Za-z0-9_]{5,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    /**
     * @param previousPriority priority of the line before, inherited by stack-trace continuation lines
     */
    static LogLine parse(int index, String raw, int previousPriority, int maxChars) {
        String text = raw.length() > maxChars ? raw.substring(0, maxChars) + " ...[truncated]" : raw;
        int priority;
        if (CONTINUATION.matcher(raw).find()) {
            priority = previousPriority;
        } else {
            Matcher m = LEVEL.matcher(raw);
            priority = m.find() ? priorityOf(m.group(1)) : OTHER;
        }
        Matcher ts = LEADING_TIMESTAMP.matcher(raw);
        String timestamp = ts.find() ? ts.group(1) : null;
        String body = ts.replaceFirst("").trim();
        return new LogLine(index, text, priority, template(body), timestamp);
    }

    static String template(String body) {
        String t = UUID.matcher(body).replaceAll("<id>");
        t = HEX.matcher(t).replaceAll("<id>");
        t = MIXED_ID.matcher(t).replaceAll("<id>");
        return NUMBER.matcher(t).replaceAll("<n>");
    }

    private static int priorityOf(String level) {
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "FATAL", "ERROR", "SEVERE" -> ERROR;
            case "WARN", "WARNING" -> WARN;
            case "INFO" -> INFO;
            default -> OTHER;
        };
    }
}
