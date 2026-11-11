package io.github.javatoailab.incidentpilot.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JSON output contract derived from a Java record: the schema to send to the model, and a strict
 * parser for what comes back.
 *
 * <p>Supported component types are the ones a model can reliably produce: {@code String}, enums,
 * {@code int}, {@code boolean} and {@code List<String>}. Anything else fails when the contract is
 * built, not when the first reply arrives.
 *
 * <p>Parsing is deliberately strict, like rejecting an unknown field in a REST request:
 * every field is required, unknown fields are errors, and enum values must match exactly. The one
 * thing tolerated is a markdown code fence around the object, which models add out of habit; it is
 * recorded in the result's notes so you can see how often it happens.
 */
public final class JsonContract<T extends Record> {

    private static final Pattern FENCE = Pattern.compile("^```[a-zA-Z]*\\s*\\n(.*)\\n\\s*```$", Pattern.DOTALL);
    private static final int MAX_QUOTED = 60;

    private enum Kind { STRING, ENUM, INT, BOOLEAN, STRING_LIST }

    private record Field(String jsonName, Kind kind, List<String> allowed) {
    }

    private final Class<T> type;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public JsonContract(Class<T> type) {
        this.type = type;
        for (RecordComponent c : type.getRecordComponents()) {
            Field field = fieldFor(c);
            fields.put(field.jsonName(), field);
        }
    }

    /** The JSON field names, in record order. */
    public List<String> fieldNames() {
        return List.copyOf(fields.keySet());
    }

    /** A JSON Schema for the record: every field required, no extra fields, enums spelled out. */
    public String schema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (Field f : fields.values()) {
            ObjectNode p = props.putObject(f.jsonName());
            switch (f.kind()) {
                case STRING -> p.put("type", "string");
                case INT -> p.put("type", "integer");
                case BOOLEAN -> p.put("type", "boolean");
                case ENUM -> {
                    p.put("type", "string");
                    f.allowed().forEach(p.putArray("enum")::add);
                }
                case STRING_LIST -> {
                    p.put("type", "array");
                    p.putObject("items").put("type", "string");
                }
            }
            required.add(f.jsonName());
        }
        root.put("additionalProperties", false);
        return root.toString();
    }

    /** Checks a raw model reply against the contract and, if it passes, binds it to the record. */
    public ParseResult<T> parse(String reply) {
        List<String> notes = new ArrayList<>();
        String text = reply == null ? "" : reply.strip();

        Matcher fence = FENCE.matcher(text);
        if (fence.matches()) {
            text = fence.group(1).strip();
            notes.add("removed a markdown code fence around the JSON");
        }
        if (text.isEmpty()) {
            return new ParseResult.Invalid<>(List.of("the reply was empty"));
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return new ParseResult.Invalid<>(List.of(
                    "the reply must be a single JSON object and nothing else, but it "
                            + (text.startsWith("{") ? "does not end with '}' (text after the object, or a cut-off reply)"
                                    : "starts with \"" + quote(text) + "\"")));
        }

        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return new ParseResult.Invalid<>(List.of("the reply is not valid JSON: " + e.getOriginalMessage()
                    + " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")"));
        }

        List<String> violations = new ArrayList<>();
        for (Field f : fields.values()) {
            JsonNode value = root.get(f.jsonName());
            if (value == null) {
                violations.add("missing field \"" + f.jsonName() + "\"");
            } else {
                check(f, value, violations);
            }
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!fields.containsKey(name)) {
                violations.add("unexpected field \"" + name + "\"; the allowed fields are " + fields.keySet());
            }
        }
        if (!violations.isEmpty()) {
            return new ParseResult.Invalid<>(violations);
        }

        try {
            return new ParseResult.Valid<>(mapper.treeToValue(root, type), notes);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // The record's own constructor rejected it: a domain rule, not a shape problem.
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            return new ParseResult.Invalid<>(List.of(cause.getMessage()));
        }
    }

    private static void check(Field f, JsonNode value, List<String> violations) {
        String name = "\"" + f.jsonName() + "\"";
        switch (f.kind()) {
            case STRING -> {
                if (!value.isTextual()) {
                    violations.add(name + " must be a string, got " + describe(value));
                }
            }
            case INT -> {
                if (!value.isInt()) {
                    violations.add(name + " must be an integer, got " + describe(value));
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    violations.add(name + " must be true or false, got " + describe(value));
                }
            }
            case ENUM -> {
                if (!value.isTextual() || !f.allowed().contains(value.asText())) {
                    violations.add(name + " must be one of " + f.allowed() + ", got " + describe(value));
                }
            }
            case STRING_LIST -> {
                if (!value.isArray()) {
                    violations.add(name + " must be a list of strings, got " + describe(value));
                    return;
                }
                for (int i = 0; i < value.size(); i++) {
                    if (!value.get(i).isTextual()) {
                        violations.add(name + "[" + i + "] must be a string, got " + describe(value.get(i)));
                    }
                }
            }
        }
    }

    private static String describe(JsonNode value) {
        if (value.isTextual()) {
            return "\"" + quote(value.asText()) + "\"";
        }
        if (value.isNull()) {
            return "null";
        }
        return value.getNodeType().name().toLowerCase();
    }

    /**
     * Model output is quoted back into error messages, which may be shown to the model in a repair
     * prompt. Keep the quote short and on one line.
     */
    private static String quote(String text) {
        String oneLine = text.replaceAll("\\s+", " ").replace("\"", "'");
        return oneLine.length() <= MAX_QUOTED ? oneLine : oneLine.substring(0, MAX_QUOTED) + "...";
    }

    private static Field fieldFor(RecordComponent c) {
        String json = PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(c.getName());
        Class<?> raw = c.getType();
        if (raw == String.class) {
            return new Field(json, Kind.STRING, List.of());
        }
        if (raw == int.class) {
            return new Field(json, Kind.INT, List.of());
        }
        if (raw == boolean.class) {
            return new Field(json, Kind.BOOLEAN, List.of());
        }
        if (raw.isEnum()) {
            return new Field(json, Kind.ENUM, enumValues(raw));
        }
        if (raw == List.class && isListOfString(c.getGenericType())) {
            return new Field(json, Kind.STRING_LIST, List.of());
        }
        throw new IllegalArgumentException("unsupported type for " + c.getDeclaringRecord().getSimpleName()
                + "." + c.getName() + ": " + c.getGenericType().getTypeName()
                + " (supported: String, enum, int, boolean, List<String>)");
    }

    private static boolean isListOfString(Type type) {
        return type instanceof ParameterizedType p && p.getActualTypeArguments()[0] == String.class;
    }

    /** The JSON spelling of each constant: its {@code @JsonProperty} value if it has one, else its name. */
    private static List<String> enumValues(Class<?> enumType) {
        List<String> values = new ArrayList<>();
        for (Object constant : enumType.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            try {
                JsonProperty p = enumType.getField(name).getAnnotation(JsonProperty.class);
                values.add(p != null ? p.value() : name);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        return values;
    }
}
