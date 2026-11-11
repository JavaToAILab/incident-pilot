package io.github.javatoailab.incidentpilot.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How sure the model says it is. The JSON values are lower case because prompt v2 asks for them that way. */
public enum Confidence {
    @JsonProperty("high") HIGH,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("low") LOW
}
