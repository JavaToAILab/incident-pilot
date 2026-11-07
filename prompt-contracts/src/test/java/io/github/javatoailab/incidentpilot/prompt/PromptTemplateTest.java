package io.github.javatoailab.incidentpilot.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateTest {

    private static final String SOURCE = """
            id: greeting
            version: 3
            description: test prompt
            variables: name, team
            ---
            Hello {{name}} from {{ team }}.
            """;

    @Test
    void parsesHeaderAndBody() {
        PromptTemplate t = PromptTemplate.parse(SOURCE, "greeting/v3.prompt");

        assertEquals("greeting", t.id());
        assertEquals(3, t.version());
        assertEquals(java.util.Set.of("name", "team"), t.variables());
        assertEquals("Hello {{name}} from {{ team }}.", t.body());
        assertEquals(64, t.sha256().length());
    }

    @Test
    void rendersEveryDeclaredVariable() {
        RenderedPrompt r = PromptTemplate.parse(SOURCE, "x").render(Map.of("name", "Ana", "team", "payments"));

        assertEquals("Hello Ana from payments.", r.text());
        assertEquals("greeting@v3#" + r.ref().hash().substring(0, 8), r.ref().toString());
    }

    @Test
    void rejectsAMissingValue() {
        PromptContractException e = assertThrows(PromptContractException.class,
                () -> PromptTemplate.parse(SOURCE, "x").render(Map.of("name", "Ana")));
        assertTrue(e.getMessage().contains("missing values for [team]"), e.getMessage());
    }

    @Test
    void rejectsAnUnexpectedValue() {
        // Like an unknown field in a strict API: probably a caller written against another version.
        PromptContractException e = assertThrows(PromptContractException.class,
                () -> PromptTemplate.parse(SOURCE, "x").render(Map.of("name", "Ana", "team", "x", "tone", "friendly")));
        assertTrue(e.getMessage().contains("unexpected values for [tone]"), e.getMessage());
    }

    @Test
    void rejectsABlankValue() {
        assertThrows(PromptContractException.class,
                () -> PromptTemplate.parse(SOURCE, "x").render(Map.of("name", " ", "team", "x")));
    }

    @Test
    void rejectsAPlaceholderThatIsNotDeclared() {
        String source = SOURCE.replace("variables: name, team", "variables: name");
        PromptContractException e = assertThrows(PromptContractException.class, () -> PromptTemplate.parse(source, "x"));
        assertTrue(e.getMessage().contains("not declared in 'variables': [team]"), e.getMessage());
    }

    @Test
    void rejectsADeclaredVariableThatIsNeverUsed() {
        String source = SOURCE.replace("variables: name, team", "variables: name, team, region");
        PromptContractException e = assertThrows(PromptContractException.class, () -> PromptTemplate.parse(source, "x"));
        assertTrue(e.getMessage().contains("never used in the body: [region]"), e.getMessage());
    }

    @Test
    void valuesAreInsertedLiterallyNotInterpreted() {
        // A value containing '$' or '\' must not be treated as a regex group reference.
        RenderedPrompt r = PromptTemplate.parse(SOURCE, "x").render(Map.of("name", "$1 \\n", "team", "{{name}}"));
        assertEquals("Hello $1 \\n from {{name}}.", r.text());
    }

    @Test
    void windowsLineEndingsDoNotChangeTheHash() {
        String crlf = SOURCE.replace("\n", "\r\n");
        assertEquals(PromptTemplate.parse(SOURCE, "x").sha256(), PromptTemplate.parse(crlf, "x").sha256());
    }

    @Test
    void anyRealEditChangesTheHash() {
        String edited = SOURCE.replace("Hello", "Hi");
        assertNotEquals(PromptTemplate.parse(SOURCE, "x").sha256(), PromptTemplate.parse(edited, "x").sha256());
    }

    @Test
    void refRoundTrips() {
        PromptRef ref = PromptRef.parse("incident-summary@v2#edd77dee");
        assertEquals("incident-summary", ref.id());
        assertEquals(2, ref.version());
        assertEquals("incident-summary@v2#edd77dee", ref.toString());
    }
}
