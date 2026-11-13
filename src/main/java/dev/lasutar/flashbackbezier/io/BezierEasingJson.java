package dev.lasutar.flashbackbezier.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lasutar.flashbackbezier.bezier.BezierAnchor;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON encoding for bezier easings and range easings.
 *
 * <p>Schema (all fields optional on read, defaults applied when missing):</p>
 * <pre>{@code
 * easing: {
 *   "preset": "ease_in_out",          // cosmetic, for nicer round-trips
 *   "p1x": 0.42, "p1y": 0, "p2x": 0.58, "p2y": 1,
 *   "anchors": [                      // present only in multi-point curve mode
 *     { "x": 0.5, "y": 0.35, "hix": 0.4, "hiy": 0.35, "hox": 0.6, "hoy": 0.4 }
 *   ]                                 // missing handle fields = auto (NaN)
 * }
 * range: { "endTick": 120, "easing": <easing> }
 * }</pre>
 *
 * <p>Compatibility: legacy single-cubic data (no "anchors") keeps loading with
 * the exact same behaviour as v1.0.0. When an easing is in curve mode, the
 * classic P1/P2 fields are still written (from the first segment's resolved
 * handles) so that users running the mod WITHOUT multi-point support still see
 * a sensible shape instead of an error.</p>
 */
public final class BezierEasingJson {

    private BezierEasingJson() {
    }

    /** Serializes an easing (or null -> null). */
    public static @Nullable JsonObject write(@Nullable BezierEasing easing) {
        if (easing == null) {
            return null;
        }
        JsonObject object = new JsonObject();
        object.addProperty("preset", easing.preset);
        if (easing.anchors != null) {
            // Keep the legacy P1/P2 fields synced from the first segment so
            // older mod versions render a reasonable approximation.
            float[] first = BezierEasing.segmentHandles(easing.anchors.getFirst(), easing.anchors.get(1));
            object.addProperty("p1x", clampWrite(first[0], 0.0f, 1.0f));
            object.addProperty("p1y", clampWrite(first[1], -4.0f, 4.0f));
            object.addProperty("p2x", clampWrite(first[2], 0.0f, 1.0f));
            object.addProperty("p2y", clampWrite(first[3], -4.0f, 4.0f));
            JsonArray anchorsArray = new JsonArray();
            for (BezierAnchor anchor : easing.anchors) {
                anchorsArray.add(writeAnchor(anchor));
            }
            object.add("anchors", anchorsArray);
        } else {
            object.addProperty("p1x", easing.p1x);
            object.addProperty("p1y", easing.p1y);
            object.addProperty("p2x", easing.p2x);
            object.addProperty("p2y", easing.p2y);
        }
        return object;
    }

    private static float clampWrite(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static JsonObject writeAnchor(BezierAnchor anchor) {
        JsonObject object = new JsonObject();
        object.addProperty("x", anchor.x);
        object.addProperty("y", anchor.y);
        if (anchor.hasIncomingHandle()) {
            object.addProperty("hix", anchor.hix);
            object.addProperty("hiy", anchor.hiy);
        }
        if (anchor.hasOutgoingHandle()) {
            object.addProperty("hox", anchor.hox);
            object.addProperty("hoy", anchor.hoy);
        }
        return object;
    }

    /** Deserializes an easing; returns null on malformed input (never throws). */
    public static @Nullable BezierEasing read(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject object = element.getAsJsonObject();
            BezierEasing easing = new BezierEasing();
            easing.preset = object.has("preset") ? object.get("preset").getAsString() : "custom";
            if (object.has("p1x")) easing.p1x = BezierEasing.clampX(object.get("p1x").getAsFloat());
            if (object.has("p1y")) easing.p1y = BezierEasing.clampY(object.get("p1y").getAsFloat());
            if (object.has("p2x")) easing.p2x = BezierEasing.clampX(object.get("p2x").getAsFloat());
            if (object.has("p2y")) easing.p2y = BezierEasing.clampY(object.get("p2y").getAsFloat());
            if (object.has("anchors") && object.get("anchors").isJsonArray()) {
                List<BezierAnchor> anchors = new ArrayList<>();
                for (JsonElement anchorElement : object.getAsJsonArray("anchors")) {
                    BezierAnchor anchor = readAnchor(anchorElement);
                    if (anchor != null) {
                        anchors.add(anchor);
                    }
                }
                if (anchors.size() >= 3) {
                    // setAnchors normalizes and pins the endpoints
                    easing.setAnchors(anchors);
                }
            }
            return easing;
        } catch (Exception exception) {
            return null;
        }
    }

    private static @Nullable BezierAnchor readAnchor(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("x") || !object.has("y")) {
                return null;
            }
            float x = object.get("x").getAsFloat();
            float y = object.get("y").getAsFloat();
            float hix = readHandle(object, "hix");
            float hiy = readHandle(object, "hiy");
            float hox = readHandle(object, "hox");
            float hoy = readHandle(object, "hoy");
            // Partial handles fall back to auto, never to (0,0) by accident
            if (Float.isNaN(hix) || Float.isNaN(hiy)) {
                hix = BezierAnchor.AUTO;
                hiy = BezierAnchor.AUTO;
            }
            if (Float.isNaN(hox) || Float.isNaN(hoy)) {
                hox = BezierAnchor.AUTO;
                hoy = BezierAnchor.AUTO;
            }
            return new BezierAnchor(x, y, hix, hiy, hox, hoy);
        } catch (Exception exception) {
            return null;
        }
    }

    private static float readHandle(JsonObject object, String key) {
        if (!object.has(key)) {
            return BezierAnchor.AUTO;
        }
        try {
            return object.get(key).getAsFloat();
        } catch (Exception exception) {
            return BezierAnchor.AUTO;
        }
    }

    /** Serializes a range easing (or null -> null). */
    public static @Nullable JsonObject writeRange(@Nullable BezierRange range) {
        if (range == null) {
            return null;
        }
        JsonObject object = new JsonObject();
        object.addProperty("endTick", range.endTick);
        JsonObject easing = write(range.easing);
        if (easing != null) {
            object.add("easing", easing);
        }
        return object;
    }

    /** Deserializes a range easing; returns null on malformed input (never throws). */
    public static @Nullable BezierRange readRange(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("endTick")) {
                return null;
            }
            int endTick = object.get("endTick").getAsInt();
            BezierEasing easing = read(object.get("easing"));
            if (easing == null || endTick < 0) {
                return null;
            }
            return new BezierRange(endTick, easing);
        } catch (Exception exception) {
            return null;
        }
    }
}
