package io.github.javatoailab.incidentpilot.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * A real BPE tokenizer in pure Java (JTokkit), using OpenAI's cl100k_base encoding.
 *
 * <p>It is exact for models that use cl100k_base and a close proxy for other modern
 * tokenizers, including Llama's. It is far more accurate than a character ratio on logs.
 */
public final class JTokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JTokkitTokenCounter(EncodingType type) {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(type);
    }

    public JTokkitTokenCounter() {
        this(EncodingType.CL100K_BASE);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}
