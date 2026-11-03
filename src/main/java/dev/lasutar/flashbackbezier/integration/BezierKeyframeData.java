package dev.lasutar.flashbackbezier.integration;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
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
}
