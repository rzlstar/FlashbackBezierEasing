package dev.lasutar.flashbackbezier.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.lasutar.flashbackbezier.bezier.BezierAnchor;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the shared easing/range JSON encoding: round-trips, legacy
 * (v1.0.0) data migration and malformed-input safety.
 */
class BezierEasingJsonTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- classic round trip ----

    @Test
    void classicRoundTrip() {
        BezierEasing original = new BezierEasing(0.42f, 0f, 0.58f, 1f, "ease_in_out");
        JsonObject json = BezierEasingJson.write(original);
        assertNotNull(json);

        BezierEasing read = BezierEasingJson.read(json);
        assertNotNull(read);
        assertFalse(read.isCurveMode());
        assertEquals(original.p1x, read.p1x, 1e-6);
        assertEquals(original.p1y, read.p1y, 1e-6);
        assertEquals(original.p2x, read.p2x, 1e-6);
        assertEquals(original.p2y, read.p2y, 1e-6);
        assertEquals("ease_in_out", read.preset);
    }

    @Test
    void classicWriteMatchesLegacySchema() {
        JsonObject json = BezierEasingJson.write(new BezierEasing(0.25f, 0.1f, 0.25f, 1f, "ease"));
        assertEquals(0.25f, json.get("p1x").getAsFloat(), 1e-6);
        assertEquals(0.1f, json.get("p1y").getAsFloat(), 1e-6);
        assertEquals("ease", json.get("preset").getAsString());
        assertFalse(json.has("anchors"), "classic data must not gain an anchors array");
    }

    // ---- curve mode round trip ----

    @Test
    void curveModeRoundTripKeepsAnchors() {
        BezierEasing original = new BezierEasing();
        List<BezierAnchor> anchors = new ArrayList<>();
        anchors.add(new BezierAnchor(0f, 0f));
        anchors.add(new BezierAnchor(0.35f, 0.2f, BezierAnchor.AUTO, BezierAnchor.AUTO, 0.45f, 0.25f));
        anchors.add(new BezierAnchor(0.7f, 0.8f, 0.6f, 0.75f, BezierAnchor.AUTO, BezierAnchor.AUTO));
        anchors.add(new BezierAnchor(1f, 1f));
        original.setAnchors(anchors);
        assertTrue(original.isCurveMode());

        JsonObject json = BezierEasingJson.write(original);
        assertTrue(json.has("anchors"));
        // legacy fallback fields are still written for old mod versions
        assertTrue(json.has("p1x"));

        BezierEasing read = BezierEasingJson.read(json);
        assertNotNull(read);
        assertTrue(read.isCurveMode());
        List<BezierAnchor> readAnchors = read.anchors;
        assertEquals(original.anchors.size(), readAnchors.size());
        for (int i = 0; i < original.anchors.size(); i++) {
            assertEquals(original.anchors.get(i), readAnchors.get(i),
                "anchor " + i + " must survive the JSON round trip (NaN handles included)");
        }
    }

    @Test
    void curveModeWriteKeepsLegacyFieldsInSync() {
        BezierEasing original = new BezierEasing();
        List<BezierAnchor> anchors = new ArrayList<>();
        anchors.add(new BezierAnchor(0f, 0f, BezierAnchor.AUTO, BezierAnchor.AUTO, 0.1f, 0.3f));
        anchors.add(new BezierAnchor(0.4f, 0.6f));
        anchors.add(new BezierAnchor(1f, 1f));
        original.setAnchors(anchors);

        JsonObject json = BezierEasingJson.write(original);
        // p1x/p2x mirror the first segment's resolved handles (absolute x)
        float[] first = BezierEasing.segmentHandles(original.anchors.getFirst(), original.anchors.get(1));
        assertEquals(first[0], json.get("p1x").getAsFloat(), 1e-6);
        assertEquals(first[2], json.get("p2x").getAsFloat(), 1e-6);
    }

    @Test
    void curveModeWithFewAnchorsDegradesToClassic() {
        // a stored "anchors" array with < 3 usable entries falls back to classic
        JsonObject json = parse("""
            {"preset":"custom","p1x":0.3,"p1y":0.2,"p2x":0.7,"p2y":0.9,
             "anchors":[{"x":0.2,"y":0.1},{"x":0.8,"y":0.9}]}
            """);
        BezierEasing read = BezierEasingJson.read(json);
        assertNotNull(read);
        assertFalse(read.isCurveMode());
        assertEquals(0.3f, read.p1x, 1e-6);
    }

    // ---- malformed / legacy input ----

    @Test
    void nullAndNonObjectInputAreRejected() {
        assertNull(BezierEasingJson.read(null));
        assertNull(BezierEasingJson.read(new JsonPrimitive("nope")));
        assertNull(BezierEasingJson.read(new JsonArray()));
    }

    @Test
    void emptyObjectGivesDefaultEasing() {
        BezierEasing read = BezierEasingJson.read(new JsonObject());
        assertNotNull(read);
        assertEquals(0.25f, read.p1x, 1e-6);
        assertEquals(0.1f, read.p1y, 1e-6);
        assertEquals(0.25f, read.p2x, 1e-6);
        assertEquals(1.0f, read.p2y, 1e-6);
    }

    @Test
    void legacyV100DataLoadsUnchanged() {
        JsonObject json = parse("""
            {"preset":"expo","p1x":0.87,"p1y":0,"p2x":0.13,"p2y":1}
            """);
        BezierEasing read = BezierEasingJson.read(json);
        assertNotNull(read);
        assertEquals("expo", read.preset);
        assertEquals(0.87f, read.p1x, 1e-6);
        assertEquals(0.13f, read.p2x, 1e-6);
        assertFalse(read.isCurveMode());
    }

    @Test
    void malformedNumbersAreRejectedSafely() {
        JsonObject json = parse("""
            {"preset":"x","p1x":"not-a-number"}
            """);
        assertNull(BezierEasingJson.read(json));
    }

    @Test
    void partialHandlesFallBackToAuto() {
        JsonObject json = parse("""
            {"preset":"custom","anchors":[
              {"x":0,"y":0},
              {"x":0.5,"y":0.5,"hix":0.3},
              {"x":1,"y":1}]}
            """);
        BezierEasing read = BezierEasingJson.read(json);
        assertNotNull(read);
        assertTrue(read.isCurveMode());
        // hix without hiy is dropped entirely (auto), never (0.3, 0)
        assertFalse(read.anchors.get(1).hasIncomingHandle());
        assertTrue(Float.isNaN(read.anchors.get(1).hix));
    }

    // ---- range easing ----

    @Test
    void rangeRoundTrip() {
        BezierRange original = new BezierRange(120, new BezierEasing(0.42f, 0f, 0.58f, 1f, "ease_in_out"));
        JsonObject json = BezierEasingJson.writeRange(original);
        assertNotNull(json);
        assertEquals(120, json.get("endTick").getAsInt());

        BezierRange read = BezierEasingJson.readRange(json);
        assertNotNull(read);
        assertEquals(120, read.endTick);
        assertEquals(original.easing.p1x, read.easing.p1x, 1e-6);
        assertEquals(original.easing.preset, read.easing.preset);
    }

    @Test
    void rangeCurveModeRoundTrip() {
        BezierEasing curve = new BezierEasing();
        curve.setAnchors(new ArrayList<>(List.of(
            new BezierAnchor(0f, 0f),
            new BezierAnchor(0.5f, 0.35f),
            new BezierAnchor(1f, 1f))));
        BezierRange original = new BezierRange(80, curve);

        BezierRange read = BezierEasingJson.readRange(BezierEasingJson.writeRange(original));
        assertNotNull(read);
        assertTrue(read.easing.isCurveMode());
        assertEquals(original.easing.anchors, read.easing.anchors);
    }

    @Test
    void malformedRangeIsRejected() {
        assertNull(BezierEasingJson.readRange(null));
        assertNull(BezierEasingJson.readRange(new JsonObject())); // no endTick
        assertNull(BezierEasingJson.readRange(parse("""
            {"endTick":-5,"easing":{"p1x":0.2}}
            """)));
        assertNull(BezierEasingJson.readRange(parse("""
            {"endTick":100}
            """))); // missing easing
        assertNull(BezierEasingJson.readRange(new JsonPrimitive(3)));
    }

    @Test
    void jsonElementOverloadAcceptsTrees() {
        JsonElement element = JsonParser.parseString("""
            {"endTick":42,"easing":{"preset":"ease"}}
            """);
        BezierRange read = BezierEasingJson.readRange(element);
        assertNotNull(read);
        assertEquals(42, read.endTick);
    }
}
