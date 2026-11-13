package dev.lasutar.flashbackbezier.integration;

import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for range-easing logic: active-range lookup on a keyframe map,
 * the tick warp (monotonic, clamped, exact at the span ends) and the numeric
 * pass-through guarantee that intermediate keyframes keep receiving their
 * values on the warped timeline.
 */
class RangeWarpTest {

    /** Flashback-free fake keyframe carrying only the bezier attachments. */
    private static final class FakeKeyframe implements BezierKeyframeData {
        BezierEasing easing;
        BezierRange range;

        @Override
        public BezierEasing flashbackbezier$getEasing() {
            return this.easing;
        }

        @Override
        public void flashbackbezier$setEasing(BezierEasing easing) {
            this.easing = easing;
        }

        @Override
        public BezierRange flashbackbezier$getRange() {
            return this.range;
        }

        @Override
        public void flashbackbezier$setRange(BezierRange range) {
            this.range = range;
        }
    }

    private static FakeKeyframe keyframe(BezierRange range) {
        FakeKeyframe keyframe = new FakeKeyframe();
        keyframe.range = range;
        return keyframe;
    }

    // ---- findActive ----

    @Test
    void findsRangeWhoseSpanCoversTheTick() {
        TreeMap<Integer, FakeKeyframe> keyframes = new TreeMap<>();
        keyframes.put(0, keyframe(new BezierRange(30, new BezierEasing(0.42f, 0f, 0.58f, 1f, "custom"))));

        BezierRangeState.ActiveRange active = BezierRangeState.findActive(keyframes, 15f);
        assertNotNull(active);
        assertEquals(0, active.startTick());
        assertEquals(30, active.range().endTick);
    }

    @Test
    void rangeIsInactiveOutsideItsSpan() {
        TreeMap<Integer, FakeKeyframe> keyframes = new TreeMap<>();
        keyframes.put(0, keyframe(new BezierRange(30, new BezierEasing())));

        assertNull(BezierRangeState.findActive(keyframes, 30.5f));
        assertNull(BezierRangeState.findActive(keyframes, -0.1f));
        // exact end tick is still inside the span
        assertNotNull(BezierRangeState.findActive(keyframes, 30f));
    }

    @Test
    void backwardsScanPicksTheMostRecentCoveringRange() {
        TreeMap<Integer, FakeKeyframe> keyframes = new TreeMap<>();
        keyframes.put(0, keyframe(new BezierRange(100, new BezierEasing(0.42f, 0f, 0.58f, 1f, "custom"))));
        keyframes.put(50, keyframe(new BezierRange(80, new BezierEasing(0.87f, 0f, 0.13f, 1f, "custom"))));
        keyframes.put(70, keyframe(null)); // plain keyframe inside both spans

        // tick 60: start 50's span [50,80] covers it and is the closest start <= tick
        BezierRangeState.ActiveRange at60 = BezierRangeState.findActive(keyframes, 60f);
        assertNotNull(at60);
        assertEquals(50, at60.startTick());

        // tick 90: only start 0's span covers it
        BezierRangeState.ActiveRange at90 = BezierRangeState.findActive(keyframes, 90f);
        assertNotNull(at90);
        assertEquals(0, at90.startTick());
    }

    @Test
    void noRangesMeansNoActiveRange() {
        TreeMap<Integer, FakeKeyframe> keyframes = new TreeMap<>();
        keyframes.put(0, keyframe(null));
        keyframes.put(10, keyframe(null));
        assertNull(BezierRangeState.findActive(keyframes, 5f));
        assertNull(BezierRangeState.findActive(new TreeMap<>(), 5f));
    }

    @Test
    void nonNavigableMapIsRejectedGracefully() {
        // findActive only promises NavigableMap support; other maps yield null
        java.util.HashMap<Integer, FakeKeyframe> plain = new java.util.HashMap<>();
        plain.put(0, keyframe(new BezierRange(10, new BezierEasing())));
        assertNull(BezierRangeState.findActive(plain, 5f));
    }

    // ---- warp ----

    @Test
    void warpFixesSpanEndpoints() {
        BezierEasing easing = new BezierEasing(0.42f, 0f, 0.58f, 1f, "custom");
        assertEquals(0f, BezierRangeState.warp(0f, 30f, easing, 0f), 1e-5);
        assertEquals(30f, BezierRangeState.warp(0f, 30f, easing, 30f), 1e-5);
    }

    @Test
    void warpIsClampedOutsideSpan() {
        BezierEasing easing = new BezierEasing(0.42f, 0f, 0.58f, 1f, "custom");
        assertEquals(0f, BezierRangeState.warp(0f, 30f, easing, -5f), 1e-5);
        assertEquals(30f, BezierRangeState.warp(0f, 30f, easing, 100f), 1e-5);
    }

    @Test
    void warpStaysMonotonicForMonotoneEasing() {
        BezierEasing easing = new BezierEasing(0.25f, 0.1f, 0.25f, 1f, "custom");
        float start = 0f;
        float end = 30f;
        float prev = start;
        for (int i = 1; i <= 600; i++) {
            float tick = start + (end - start) * i / 600f;
            float warped = BezierRangeState.warp(start, end, easing, tick);
            assertTrue(warped >= prev - 1e-6, "warp must be non-decreasing at tick " + tick);
            assertTrue(warped >= start && warped <= end, "warp must stay inside the span");
            prev = warped;
        }
    }

    @Test
    void warpIgnoresOvershootEasingsByClamping() {
        // Back-style overshoot would leave the span; range warp clamps it
        BezierEasing back = new BezierEasing(0.68f, -0.55f, 0.267f, 1.55f, "custom");
        for (int i = 0; i <= 100; i++) {
            float warped = BezierRangeState.warp(0f, 30f, back, 30f * i / 100f);
            assertTrue(warped >= 0f && warped <= 30f, "overshoot must be clamped to the span");
        }
    }

    @Test
    void warpIsIdentityWithoutEasingOrSpan() {
        assertEquals(7f, BezierRangeState.warp(0f, 30f, null, 7f), 0f);
        assertEquals(7f, BezierRangeState.warp(5f, 5f, new BezierEasing(), 7f), 0f);
        assertEquals(7f, BezierRangeState.warp(9f, 5f, new BezierEasing(), 7f), 0f);
    }

    // ---- pass-through guarantee (A -> B -> C -> D with one range on A) ----

    /** Linear-interpolation keyframe track: A(0)=0, B(10)=5, C(20)=5, D(30)=10. */
    private static float vanillaValue(float tick) {
        float[] keyTicks = {0f, 10f, 20f, 30f};
        float[] keyValues = {0f, 5f, 5f, 10f};
        if (tick <= keyTicks[0]) {
            return keyValues[0];
        }
        for (int i = 0; i < keyTicks.length - 1; i++) {
            if (tick < keyTicks[i + 1]) {
                float t = (tick - keyTicks[i]) / (keyTicks[i + 1] - keyTicks[i]);
                return keyValues[i] + (keyValues[i + 1] - keyValues[i]) * t;
            }
        }
        return keyValues[keyValues.length - 1];
    }

    @Test
    void warpedTimelinePassesThroughIntermediateKeyframeValues() {
        BezierEasing easing = new BezierEasing(0.25f, 0.1f, 0.25f, 1f, "custom");
        float start = 0f;
        float end = 30f;

        // the eased trajectory still reaches B's value (5) and C's value (5):
        // sweep the eased output and confirm value 5 is hit exactly
        boolean hitFive = false;
        for (int i = 0; i <= 30000; i++) {
            float tick = (end - start) * i / 30000f;
            float warped = BezierRangeState.warp(start, end, easing, tick);
            float value = vanillaValue(warped);
            if (Math.abs(value - 5f) < 1e-4) {
                hitFive = true;
                break;
            }
        }
        assertTrue(hitFive, "the eased trajectory must still pass through the plateau value 5");

        // and the end value is reached exactly at the end tick
        assertEquals(10f, vanillaValue(BezierRangeState.warp(start, end, easing, end)), 1e-5);
        assertEquals(0f, vanillaValue(BezierRangeState.warp(start, end, easing, start)), 1e-5);
    }

    // ---- suppression ----

    @Test
    void activeRangesuppressesPerKeyframeEasing() {
        TreeMap<Integer, FakeKeyframe> keyframes = new TreeMap<>();
        FakeKeyframe start = keyframe(new BezierRange(30, new BezierEasing()));
        start.easing = new BezierEasing(0.87f, 0f, 0.13f, 1f, "custom"); // also has per-segment easing
        keyframes.put(0, start);
        keyframes.put(20, keyframe(null));

        assertTrue(BezierRangeState.suppressesPerKeyframeEasing(keyframes, 10f));
        assertTrue(BezierRangeState.suppressesPerKeyframeEasing(keyframes, 30f));
        assertFalse(BezierRangeState.suppressesPerKeyframeEasing(keyframes, 35f));
    }
}
