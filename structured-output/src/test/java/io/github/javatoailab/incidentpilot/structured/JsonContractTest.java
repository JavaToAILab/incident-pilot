package io.github.javatoailab.incidentpilot.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonContractTest {

    static final String VALID = """
            {"severity":"SEV2","suspected_component":"payments-db pool","summary":"Payments fail: the connection pool is exhausted.",
             "evidence":["HikariPool-1 - Connection is not available"],"confidence":"medium","unknowns":["Was there a deploy?"]}
            """;

    private final JsonContract<IncidentSummary> contract = new JsonContract<>(IncidentSummary.class);

    @Test
    void bindsAValidReplyToTheRecord() {
        IncidentSummary s = valid(contract.parse(VALID));

        assertEquals(Severity.SEV2, s.severity());
        assertEquals("payments-db pool", s.suspectedComponent());
        assertEquals(Confidence.MEDIUM, s.confidence());
        assertEquals(List.of("HikariPool-1 - Connection is not available"), s.evidence());
    }

    @Test
    void toleratesAMarkdownFenceButRecordsIt() {
        ParseResult<IncidentSummary> r = contract.parse("```json\n" + VALID.strip() + "\n```");

        ParseResult.Valid<IncidentSummary> v = assertInstanceOf(ParseResult.Valid.class, r);
        assertEquals(List.of("removed a markdown code fence around the JSON"), v.notes());
    }

    @Test
    void rejectsTextAroundTheObject() {
        assertTrue(only(contract.parse("Here is the summary:\n" + VALID)).contains("starts with \"Here is the summary:"));
        assertTrue(only(contract.parse(VALID.strip() + "\nLet me know if you need more.")).contains("does not end with '}'"));
        assertEquals("the reply was empty", only(contract.parse("   ")));
    }

    @Test
    void reportsBrokenJsonWithItsPosition() {
        String broken = VALID.replace("\"medium\",", "\"medium\"");
        assertTrue(only(contract.parse(broken)).startsWith("the reply is not valid JSON"), only(contract.parse(broken)));
    }

    @Test
    void reportsEveryViolationAtOnceNotJustTheFirst() {
        String reply = """
                {"severity":"critical","component":"db","summary":"x","evidence":"one line","confidence":"medium","unknowns":[]}
                """;

        List<String> v = invalid(contract.parse(reply));

        assertEquals(List.of(
                "\"severity\" must be one of [SEV1, SEV2, SEV3, SEV4], got \"critical\"",
                "missing field \"suspected_component\"",
                "\"evidence\" must be a list of strings, got \"one line\"",
                "unexpected field \"component\"; the allowed fields are "
                        + "[severity, suspected_component, summary, evidence, confidence, unknowns]"), v);
    }

    @Test
    void enumValuesMustMatchExactly() {
        // "High" is close enough for a person, not for a contract. Case-insensitive matching would hide drift.
        assertTrue(only(contract.parse(VALID.replace("\"medium\"", "\"High\""))).contains("got \"High\""));
        assertTrue(only(contract.parse(VALID.replace("\"medium\"", "null"))).contains("got null"));
    }

    @Test
    void theRecordsOwnRulesStillApply() {
        assertEquals("summary must not be blank", only(contract.parse(VALID.replace(
                "\"Payments fail: the connection pool is exhausted.\"", "\"  \""))));
    }

    @Test
    void quotesOfModelOutputAreShortAndOnOneLine() {
        String longProse = "Sure!\n" + "This is a very long explanation. ".repeat(20) + VALID;
        String v = only(contract.parse(longProse));
        assertFalse(v.contains("\n"));
        assertTrue(v.length() < 200, v);
    }

    @Test
    void theSchemaIsGeneratedFromTheRecord() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(contract.schema());

        assertEquals(List.of("severity", "suspected_component", "summary", "evidence", "confidence", "unknowns"),
                contract.fieldNames());
        assertEquals(6, schema.get("required").size());
        assertFalse(schema.get("additionalProperties").asBoolean());
        assertEquals("[\"high\",\"medium\",\"low\"]", schema.at("/properties/confidence/enum").toString());
        assertEquals("string", schema.at("/properties/evidence/items/type").asText());
    }

    record Unsupported(String name, Map<String, Object> extras) {
    }

    @Test
    void unsupportedFieldTypesFailWhenTheContractIsBuilt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JsonContract<>(Unsupported.class));
        assertTrue(e.getMessage().contains("Unsupported.extras"), e.getMessage());
    }

    static IncidentSummary valid(ParseResult<IncidentSummary> r) {
        return switch (r) {
            case ParseResult.Valid<IncidentSummary> v -> v.value();
            case ParseResult.Invalid<IncidentSummary> i -> fail("expected valid, got " + i.violations());
        };
    }

    static List<String> invalid(ParseResult<IncidentSummary> r) {
        return switch (r) {
            case ParseResult.Valid<IncidentSummary> v -> fail("expected invalid, got " + v.value());
            case ParseResult.Invalid<IncidentSummary> i -> i.violations();
        };
    }

    static String only(ParseResult<IncidentSummary> r) {
        List<String> v = invalid(r);
        assertEquals(1, v.size(), v.toString());
        return v.getFirst();
    }
}
