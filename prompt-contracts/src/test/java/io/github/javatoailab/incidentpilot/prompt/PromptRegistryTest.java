package io.github.javatoailab.incidentpilot.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PromptRegistryTest {

    private final PromptRegistry registry = PromptRegistry.load();

    @Test
    void loadsEveryReleasedVersion() {
        assertEquals(Set.of(1, 2), registry.versions("incident-summary").keySet());
        assertEquals(2, registry.latest("incident-summary").version());
    }

    @Test
    void resolvesLatestOrAPinnedVersion() {
        assertEquals(2, registry.resolve("incident-summary").version());
        assertEquals(1, registry.resolve("incident-summary@1").version());
        assertEquals(1, registry.resolve("incident-summary@v1").version());
    }

    @Test
    void unknownIdsAndVersionsFailWithTheKnownOptions() {
        PromptContractException e = assertThrows(PromptContractException.class, () -> registry.get("incident-summary", 9));
        assertTrue(e.getMessage().contains("released versions: [1, 2]"), e.getMessage());
        assertThrows(PromptContractException.class, () -> registry.latest("triage-plan"));
    }

    @Test
    void aReleasedPromptEditedInPlaceFailsToLoad() {
        // tampered-prompts holds v1 with one extra phrase, but the original v1 hash in its lock file.
        PromptContractException e = assertThrows(PromptContractException.class,
                () -> PromptRegistry.load(getClass().getClassLoader(), "tampered-prompts"));
        assertTrue(e.getMessage().contains("changed after it was released"), e.getMessage());
        assertTrue(e.getMessage().contains("put the change in v2"), e.getMessage());
    }

    @Test
    void everyPromptFileInTheSourceTreeIsLocked() {
        // Catches the other mistake: adding v3.prompt but forgetting to lock (and so release) it.
        Set<String> onDisk = PromptRegistry.lockLines(Path.of("src/main/resources/prompts")).stream()
                .map(line -> line.replaceAll("\\s+", " ")).collect(Collectors.toSet());
        Set<String> locked = PromptRegistry.readLock(getClass().getClassLoader(), PromptRegistry.DEFAULT_ROOT).stream()
                .map(e -> e.id() + " " + e.version() + " " + e.sha256()).collect(Collectors.toSet());
        assertEquals(locked, onDisk, "prompts.lock is out of date; run PromptReport --lock");
    }

    @Test
    void lockLinesListEveryVersion() {
        List<String> lines = PromptRegistry.lockLines(Path.of("src/main/resources/prompts"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("incident-summary"));
    }
}
