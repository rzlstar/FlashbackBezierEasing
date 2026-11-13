package dev.lasutar.flashbackbezier.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persists user-created NAMED bezier presets across restarts, storing the
 * ACTUAL curve parameters (never just a preset reference), so a saved preset
 * keeps reproducing its curve exactly even if the built-in presets change in
 * a future version.
 *
 * <p>Storage: {@code <config>/flashbackbezier/presets.json}, a plain map of
 * {"name": <bezier easing JSON>} with insertion order preserved (named after
 * the same schema as {@link BezierEasingJson}). Load is lazy + defensive:
 * a malformed file yields an empty store instead of a crash, and the first
 * successful save rewrites it.</p>
 */
public final class NamedPresetStore {

    /** Shared pretty printer for the presets file. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    /** name -> easing (actual parameters). Insertion-ordered. */
    private final Map<String, BezierEasing> presets = new LinkedHashMap<>();
    private boolean loaded = false;

    public NamedPresetStore(Path file) {
        this.file = file;
    }

    /** Default location: {@code <configDir>/flashbackbezier/presets.json}. */
    public static Path defaultPath(Path configDir) {
        return configDir.resolve("flashbackbezier").resolve("presets.json");
    }

    private void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        this.presets.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            String text = Files.readString(this.file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                BezierEasing easing = BezierEasingJson.read(entry.getValue());
                if (easing != null && !entry.getKey().isBlank()) {
                    this.presets.put(entry.getKey(), easing);
                }
            }
        } catch (Exception exception) {
            // Malformed file: start empty; the next save rewrites it cleanly.
            this.presets.clear();
        }
    }

    /** All preset names in display order (alphabetical, case-insensitive). */
    public List<String> names() {
        this.ensureLoaded();
        List<String> names = new ArrayList<>(this.presets.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean exists(String name) {
        this.ensureLoaded();
        return this.presets.containsKey(name);
    }

    public @Nullable BezierEasing get(String name) {
        this.ensureLoaded();
        BezierEasing easing = this.presets.get(name);
        return easing == null ? null : new BezierEasing(easing);
    }

    /**
     * Adds or overwrites a preset, storing a deep copy of the current easing
     * parameters. Returns false when the write failed.
     */
    public boolean save(String name, BezierEasing easing) {
        this.ensureLoaded();
        if (name == null || name.isBlank()) {
            return false;
        }
        this.presets.put(name, new BezierEasing(easing));
        return this.write();
    }

    /** Removes a preset; returns false when nothing was removed or the write failed. */
    public boolean delete(String name) {
        this.ensureLoaded();
        if (this.presets.remove(name) == null) {
            return false;
        }
        return this.write();
    }

    private boolean write() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, BezierEasing> entry : this.presets.entrySet()) {
                JsonObject easingObject = BezierEasingJson.write(entry.getValue());
                if (easingObject != null) {
                    root.add(entry.getKey(), easingObject);
                }
            }
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(root), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
