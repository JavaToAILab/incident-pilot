package io.github.javatoailab.incidentpilot.context;

/**
 * The classic rule of thumb: about four characters of English per token.
 *
 * <p>Fast and dependency-free, and good enough for prose. Logs are not prose: timestamps,
 * UUIDs, hex IDs and stack traces split into many more tokens per character. Measure your
 * own data with {@link TokenReport} before trusting the default ratio.
 */
public final class HeuristicTokenCounter implements TokenCounter {

    public static final double ENGLISH_CHARS_PER_TOKEN = 4.0;

    private final double charsPerToken;

    public HeuristicTokenCounter(double charsPerToken) {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken must be positive");
        }
        this.charsPerToken = charsPerToken;
    }

    public HeuristicTokenCounter() {
        this(ENGLISH_CHARS_PER_TOKEN);
    }

    public double charsPerToken() {
        return charsPerToken;
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken);
    }
}
