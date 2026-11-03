package dev.lasutar.flashbackbezier.bezier;

import com.moulberry.flashback.combo_options.ComboOption;
import net.minecraft.client.resources.language.I18n;

/**
 * Cubic bezier presets offered by the easing editor. Every preset is expressed
 * as a pair of handle coordinates, mirroring how CSS/Material easing curves are
 * defined (https://easings.net).
 */
public enum BezierPreset implements ComboOption {

    LINEAR(0.0f, 0.0f, 1.0f, 1.0f, "linear"),
    EASE(0.25f, 0.1f, 0.25f, 1.0f, "ease"),
    EASE_IN(0.42f, 0.0f, 1.0f, 1.0f, "ease_in"),
    EASE_OUT(0.0f, 0.0f, 0.58f, 1.0f, "ease_out"),
    EASE_IN_OUT(0.42f, 0.0f, 0.58f, 1.0f, "ease_in_out"),
    CUBIC(0.215f, 0.61f, 0.355f, 1.0f, "cubic"),
    SINE(0.37f, 0.0f, 0.63f, 1.0f, "sine"),
    EXPO(0.87f, 0.0f, 0.13f, 1.0f, "expo"),
    // easeInOutBack approximation (c1 = 1.70158, c2 = c1 * 1.525)
    BACK(0.68f, -0.55f, 0.267f, 1.55f, "back"),
    CUSTOM(0.25f, 0.1f, 0.25f, 1.0f, "custom");

    private final float p1x;
    private final float p1y;
    private final float p2x;
    private final float p2y;
    private final String id;

    BezierPreset(float p1x, float p1y, float p2x, float p2y, String id) {
        this.p1x = p1x;
        this.p1y = p1y;
        this.p2x = p2x;
        this.p2y = p2y;
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public void applyTo(BezierEasing easing) {
        easing.set(this.p1x, this.p1y, this.p2x, this.p2y);
        easing.preset = this.id;
    }

    public BezierEasing createEasing() {
        return new BezierEasing(this.p1x, this.p1y, this.p2x, this.p2y, this.id);
    }

    /**
     * Close-match detection (1e-4 epsilon) so the UI can show the current preset.
     */
    public boolean matches(float p1x, float p1y, float p2x, float p2y) {
        final float epsilon = 1e-4f;
        return Math.abs(this.p1x - p1x) <= epsilon
            && Math.abs(this.p1y - p1y) <= epsilon
            && Math.abs(this.p2x - p2x) <= epsilon
            && Math.abs(this.p2y - p2y) <= epsilon;
    }

    public static BezierPreset byId(String id) {
        for (BezierPreset preset : values()) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return CUSTOM;
    }

    @Override
    public String text() {
        return I18n.get("flashbackbezier.preset." + this.id);
    }

    @Override
    public String toString() {
        return this.text();
    }
}
