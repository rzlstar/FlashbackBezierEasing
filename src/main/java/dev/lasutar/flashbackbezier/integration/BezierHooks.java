package dev.lasutar.flashbackbezier.integration;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Static helpers shared by the mixins that resolve which bezier easing (if
 * any) applies to the keyframe pair that is currently being interpolated.
 */
public final class BezierHooks {

    private BezierHooks() {
    }

    public static @Nullable BezierEasing getEasing(@Nullable Object keyframe) {
        return BezierKeyframeData.get(keyframe);
    }

    /**
     * Resolves the easing for the segment between the two currently
     * interpolated keyframes. The easing of the LEFT keyframe (lower tick) of
     * the segment is used - consistent with "Ease In" semantics attaching to
     * the keyframe it eases away from.
     *
     * <p>Two Map.Entry locals are captured at the vanilla interpolate call
     * (they are provably the only live candidates there); sorting by key makes
     * the argument order irrelevant.</p>
     */
    public static @Nullable BezierEasing resolveSegmentEasing(@Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> captured0,
                                                              @Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> captured1) {
        if (captured0 == null || captured1 == null) {
            return null;
        }
        Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> lower =
            captured0.getKey() <= captured1.getKey() ? captured0 : captured1;
        return getEasing(lower.getValue());
    }

    /**
     * Resolves the easing for the smooth (Catmull-Rom) branch, where four
     * Map.Entry locals are live at the injection point. Sorted by tick, the
     * sequence is [beforeEntry, lowerEntry, ceilEntry, afterAfterEntry]
     * (with ties allowed when an edge keyframe is missing), so the middle
     * element after sorting is always {@code lowerEntry}, the keyframe whose
     * outgoing segment is being eased.
     */
    public static @Nullable BezierEasing resolveSmoothSegmentEasing(@Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> e0,
                                                                    @Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> e1,
                                                                    @Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> e2,
                                                                    @Nullable Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> e3) {
        if (e0 == null || e1 == null || e2 == null || e3 == null) {
            return null;
        }

        // Sort ascending by key: [before, lower, ceil, afterAfter] with possible ties.
        Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe>[] sorted = new Map.Entry[4];
        sorted[0] = e0;
        sorted[1] = e1;
        sorted[2] = e2;
        sorted[3] = e3;
        for (int i = 1; i < 4; i++) {
            Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> current = sorted[i];
            int j = i - 1;
            while (j >= 0 && sorted[j].getKey() > current.getKey()) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = current;
        }

        Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> lower = sorted[1];
        Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> ceil = sorted[2];
        if (lower.getKey() > ceil.getKey()) {
            // Defensive: should be impossible after sorting
            return null;
        }
        return getEasing(lower.getValue());
    }

    /**
     * Resolves and applies the easing to a normalized segment amount
     * (used by the LINEAR branch where the sided interpolation call receives
     * the raw amount).
     */
    public static float easeLinearAmount(@Nullable BezierEasing easing, float amount) {
        return (float) easing.apply(amount);
    }

    /**
     * Snaps a realtime range used by the Hermite branch: replaces the center
     * evaluation time with an eased one while keeping the endpoints (bezier(0)=0
     * and bezier(1)=1 guarantee C0 continuity with neighbouring segments).
     */
    public static float easeSegmentPosition(@Nullable BezierEasing easing, float lowerPosition,
                                            float ceilPosition, float current) {
        double span = (double) ceilPosition - (double) lowerPosition;
        if (Math.abs(span) < 1e-9) {
            return current;
        }
        double fraction = (current - lowerPosition) / span;
        double easedFraction = easing.apply(fraction);
        return (float) (lowerPosition + easedFraction * span);
    }

    /**
     * Used by the editor window to write easings to live keyframes.
     */
    public static void transferBetween(com.moulberry.flashback.keyframe.Keyframe from, com.moulberry.flashback.keyframe.Keyframe to) {
        BezierEasing easing = BezierKeyframeData.get(from);
        if (easing == null) {
            BezierKeyframeData.set(to, null);
        } else {
            BezierKeyframeData.set(to, new BezierEasing(easing));
        }
    }

    @SuppressWarnings("unused")
    private static void keepImports(TreeMap<?, ?> ignored) {
        // no-op; keeps the TreeMap import symmetrical for future use
    }
}
