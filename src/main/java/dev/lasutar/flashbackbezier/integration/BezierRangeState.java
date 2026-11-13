package dev.lasutar.flashbackbezier.integration;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Pure (Flashback-free) logic for range easings: finding the active range for
 * a tick and warping the tick by the range's easing.
 *
 * <p>A range is stored on the START keyframe of the span and covers
 * {@code [startTick, range.endTick]}. For a replay tick inside the span, the
 * tick is warped to</p>
 *
 * <pre>{@code
 * warped = startTick + (endTick - startTick) * easing((tick - startTick) / span)
 * }</pre>
 *
 * <p>The warp is continuous and clamped to the span, so the vanilla keyframe
 * lookup runs on a monotonic-ish timeline that still reaches both end ticks
 * exactly: the camera trajectory keeps passing through every intermediate
 * keyframe's value, but its traversal speed follows one easing curve for the
 * whole span. (Curves with Back-style overshoot are clamped to the span here,
 * so a range never evaluates keyframes outside {@code [startTick, endTick]}.)</p>
 */
public final class BezierRangeState {

    private BezierRangeState() {
    }

    /** An active range plus the tick of the keyframe that defines its start. */
    public record ActiveRange(int startTick, BezierRange range) {
    }

    /** Safety cap for the backwards scan (pathological long chains of start keys). */
    private static final int MAX_SCAN = 256;

    /**
     * Finds the range whose span covers {@code tick}, or null. Scans backwards
     * from the keyframe at or before the tick, because a range can only cover
     * a tick when its start keyframe is at or before it.
     *
     * @param keyframesByTick tick -> keyframe (values are inspected via
     *                        {@link BezierKeyframeData}, so tests can supply
     *                        fake values)
     */
    public static @Nullable ActiveRange findActive(Map<Integer, ?> keyframesByTick, float tick) {
        if (!(keyframesByTick instanceof NavigableMap<Integer, ?> navigable) || navigable.isEmpty()) {
            return null;
        }
        // floor before the int cast: a plain (int) cast truncates toward zero,
        // which would let a range starting at tick 0 claim a tick like -0.1
        Map.Entry<Integer, ?> entry = navigable.floorEntry((int) Math.floor(tick));
        int scanned = 0;
        while (entry != null && scanned++ < MAX_SCAN) {
            BezierRange range = BezierKeyframeData.getRange(entry.getValue());
            if (range != null && range.endTick >= tick) {
                return new ActiveRange(entry.getKey(), range);
            }
            entry = navigable.floorEntry(entry.getKey() - 1);
        }
        return null;
    }

    /**
     * Warps an absolute replay tick inside {@code [startTick, endTick]} by the
     * easing. Outside the span (or for degenerate spans) the tick is returned
     * unchanged.
     */
    public static float warp(ActiveRange active, float tick) {
        return warp(active.startTick(), active.range().endTick, active.range().easing, tick);
    }

    public static float warp(float startTick, float endTick, @Nullable BezierEasing easing, float tick) {
        if (easing == null) {
            return tick;
        }
        float span = endTick - startTick;
        if (span <= 0.0f) {
            return tick;
        }
        if (tick <= startTick) {
            return startTick;
        }
        if (tick >= endTick) {
            return endTick;
        }
        double fraction = (tick - startTick) / span;
        double eased = easing.apply(fraction);
        float warped = (float) (startTick + eased * span);
        return Math.max(startTick, Math.min(endTick, warped));
    }

    /**
     * Whether an easing attached to a keyframe inside an active range should
     * be suppressed (range easings take precedence over per-segment easings
     * while they are active).
     */
    public static boolean suppressesPerKeyframeEasing(Map<Integer, ?> keyframesByTick, float tick) {
        return findActive(keyframesByTick, tick) != null;
    }

    /** Convenience for defensive checks in editor code. */
    public static boolean isValidSpan(int startTick, int endTick) {
        return endTick > startTick;
    }

    /** Small helper mirroring {@link TreeMap#floorEntry} semantics for tests. */
    public static @Nullable ActiveRange findActive(TreeMap<Integer, ?> keyframesByTick, float tick) {
        return findActive((Map<Integer, ?>) keyframesByTick, tick);
    }
}
