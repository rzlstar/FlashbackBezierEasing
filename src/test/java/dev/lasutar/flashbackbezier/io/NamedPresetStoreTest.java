package dev.lasutar.flashbackbezier.io;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the named preset store: persistence across restarts stores
 * actual parameters, alphabetical ordering, overwrite/delete and defensive
 * behaviour against a malformed file.
 */
class NamedPresetStoreTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("flashbackbezier").resolve("presets.json");
    }

    @Test
    void saveGetRoundTripWithinOneInstance() {
        NamedPresetStore store = new NamedPresetStore(file());
        BezierEasing easing = new BezierEasing(0.68f, -0.55f, 0.267f, 1.55f, "custom");
        assertTrue(store.save("My Snappy Ease", easing));

        BezierEasing loaded = store.get("My Snappy Ease");
        assertNotNull(loaded);
        assertEquals(0.68f, loaded.p1x, 1e-6);
        assertEquals(-0.55f, loaded.p1y, 1e-6);
        assertTrue(store.exists("My Snappy Ease"));
        assertFalse(store.exists("Other"));
        assertNull(store.get("Other"));
    }

    @Test
    void presetsPersistAcrossRestartWithActualParameters() throws IOException {
        // first "session"
        NamedPresetStore session1 = new NamedPresetStore(file());
        BezierEasing curve = new BezierEasing();
        curve.setAnchors(new java.util.ArrayList<>(java.util.List.of(
            new dev.lasutar.flashbackbezier.bezier.BezierAnchor(0f, 0f),
            new dev.lasutar.flashbackbezier.bezier.BezierAnchor(0.4f, 0.15f, 0.3f, 0.05f, 0.5f, 0.25f),
            new dev.lasutar.flashbackbezier.bezier.BezierAnchor(1f, 1f))));
        assertTrue(session1.save("Two Point Curve", curve));

        // the file contains actual parameters, not just a preset name
        String contents = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(contents.contains("anchors"), "named presets must store actual curve parameters");
        assertTrue(contents.contains("0.4"));

        // second "session" (fresh instance = fresh load, as after a game restart)
        NamedPresetStore session2 = new NamedPresetStore(file());
        BezierEasing loaded = session2.get("Two Point Curve");
        assertNotNull(loaded);
        assertTrue(loaded.isCurveMode());
        assertEquals(curve.anchors, loaded.anchors);
    }

    @Test
    void namesAreSortedCaseInsensitively() {
        NamedPresetStore store = new NamedPresetStore(file());
        store.save("beta", new BezierEasing(0.2f, 0f, 0.8f, 1f, "custom"));
        store.save("Alpha", new BezierEasing(0.3f, 0f, 0.7f, 1f, "custom"));
        store.save("gamma", new BezierEasing(0.4f, 0f, 0.6f, 1f, "custom"));
        List<String> names = store.names();
        assertEquals(List.of("Alpha", "beta", "gamma"), names);
    }

    @Test
    void overwriteReplacesParameters() {
        NamedPresetStore store = new NamedPresetStore(file());
        store.save("Preset", new BezierEasing(0.2f, 0f, 0.8f, 1f, "custom"));
        assertTrue(store.save("Preset", new BezierEasing(0.5f, 0.5f, 0.5f, 0.5f, "custom")));

        NamedPresetStore reloaded = new NamedPresetStore(file());
        BezierEasing loaded = reloaded.get("Preset");
        assertNotNull(loaded);
        assertEquals(0.5f, loaded.p1x, 1e-6);
        assertEquals(0.5f, loaded.p1y, 1e-6);
        assertEquals(1, reloaded.names().size(), "overwrite must not duplicate the entry");
    }

    @Test
    void deleteRemovesPersistently() throws IOException {
        NamedPresetStore store = new NamedPresetStore(file());
        store.save("Doomed", new BezierEasing(0.2f, 0f, 0.8f, 1f, "custom"));
        assertTrue(store.delete("Doomed"));
        assertFalse(store.exists("Doomed"));
        assertFalse(store.delete("Doomed"), "deleting a missing preset returns false");
        assertFalse(Files.readString(file(), StandardCharsets.UTF_8).contains("Doomed"));
    }

    @Test
    void blankNamesAreRejected() {
        NamedPresetStore store = new NamedPresetStore(file());
        assertFalse(store.save("", new BezierEasing()));
        assertFalse(store.save("   ", new BezierEasing()));
    }

    @Test
    void getReturnsDefensiveCopy() {
        NamedPresetStore store = new NamedPresetStore(file());
        store.save("Preset", new BezierEasing(0.2f, 0f, 0.8f, 1f, "custom"));
        BezierEasing first = store.get("Preset");
        first.p1x = 0.9f;
        assertEquals(0.2f, store.get("Preset").p1x, 1e-6, "get must return a copy");
    }

    @Test
    void malformedFileYieldsEmptyStoreNotCrash() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{ this is not json", StandardCharsets.UTF_8);
        NamedPresetStore store = new NamedPresetStore(file());
        assertTrue(store.names().isEmpty());
        assertNull(store.get("Anything"));
        // and the store still works afterwards (first save rewrites the file)
        assertTrue(store.save("Fresh", new BezierEasing(0.1f, 0f, 0.9f, 1f, "custom")));
        assertTrue(new NamedPresetStore(file()).exists("Fresh"));
    }

    @Test
    void malformedEntriesAreSkipped() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
            {"Good": {"p1x": 0.3, "p1y": 0, "p2x": 0.7, "p2y": 1, "preset": "custom"},
             "": {"p1x": 0.5},
             "Bad": {"p1x": "not-a-number"},
             "Array": [1, 2, 3]}
            """, StandardCharsets.UTF_8);
        NamedPresetStore store = new NamedPresetStore(file());
        assertEquals(List.of("Good"), store.names());
    }

    @Test
    void missingFileStartsEmpty() {
        NamedPresetStore store = new NamedPresetStore(file());
        assertTrue(store.names().isEmpty());
        assertFalse(store.exists("Any"));
    }
}
