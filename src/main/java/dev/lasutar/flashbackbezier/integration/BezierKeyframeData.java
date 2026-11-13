package dev.lasutar.flashbackbezier.integration;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import org.jetbrains.annotations.Nullable;

/**
 * Mixed onto every Flashback {@code Keyframe} instance, so a bezier easing can
 * be attached directly to a keyframe object. Implemented via
 * {@code KeyframeMixin}, therefore every Flashback keyframe class gets these
 * methods without modifying Flashback's data structures.
 */
public interface BezierKeyframeData {

    /**
     * The easing applied to the keyframe's outgoing segment (this keyframe -> next),
     * or null when the keyframe uses vanilla Flashback interpolation.
     */
    @Nullable
    BezierEasing flashbackbezier$getEasing();

    void flashbackbezier$setEasing(@Nullable BezierEasing easing);

    /**
     * The range easing starting at this keyframe (one easing across this
     * keyframe -> {@link BezierRange#endTick}), or null when the keyframe has
     * no range. While active it takes precedence over per-segment easings of
     * all keyframes inside the span.
     */
    @Nullable
    BezierRange flashbackbezier$getRange();

    void flashbackbezier$setRange(@Nullable BezierRange range);

    static @Nullable BezierEasing get(Object keyframe) {
        if (keyframe instanceof BezierKeyframeData data) {
            return data.flashbackbezier$getEasing();
        }
        return null;
    }

    static void set(Object keyframe, @Nullable BezierEasing easing) {
        if (keyframe instanceof BezierKeyframeData data) {
            data.flashbackbezier$setEasing(easing);
        }
    }

    static @Nullable BezierRange getRange(Object keyframe) {
        if (keyframe instanceof BezierKeyframeData data) {
            return data.flashbackbezier$getRange();
        }
        return null;
    }

    static void setRange(Object keyframe, @Nullable BezierRange range) {
        if (keyframe instanceof BezierKeyframeData data) {
            data.flashbackbezier$setRange(range);
        }
    }
}
