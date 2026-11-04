package io.github.javatoailab.incidentpilot.context;

/**
 * Token prices, in US dollars per million tokens. Look up your provider's current rates;
 * nothing here hard-codes a real price list.
 */
public record ModelPricing(double inputPerMillion, double outputPerMillion) {

    /** A local model through Ollama: no per-token bill (you pay in hardware and electricity). */
    public static final ModelPricing LOCAL = new ModelPricing(0.0, 0.0);

    public ModelPricing {
        if (inputPerMillion < 0 || outputPerMillion < 0) {
            throw new IllegalArgumentException("prices must not be negative");
        }
    }

    /** Worst-case cost of one call: the full prompt plus a reply that uses its whole reservation. */
    public double worstCaseCost(int inputTokens, int maxOutputTokens) {
        return inputTokens * inputPerMillion / 1_000_000.0 + maxOutputTokens * outputPerMillion / 1_000_000.0;
    }

    /** Worst-case spend per hour for a given incident rate. This is the number to put in front of finance. */
    public double worstCaseHourly(IncidentContext context, int incidentsPerHour) {
        return incidentsPerHour * worstCaseCost(context.estimatedInputTokens(), context.budget().reservedForOutput());
    }
}
