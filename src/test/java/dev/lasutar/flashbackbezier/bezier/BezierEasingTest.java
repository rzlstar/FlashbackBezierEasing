package dev.lasutar.flashbackbezier.bezier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the cubic bezier solver: classic single-cubic behaviour,
 * multi-point curve mode, and the guarantee that the generalized solver
 * reproduces the legacy v1.0.0 math exactly for legacy-shaped data.
 */
class BezierEasingTest {

    private static final double EPS = 1e-5;

    private static List<BezierAnchor> anchors(double... xyPairs) {
        List<BezierAnchor> list = new ArrayList<>();
        for (int i = 0; i < xyPairs.length; i += 2) {
            list.add(new BezierAnchor((float) xyPairs[i], (float) xyPairs[i + 1]));
        }
        return list;
    }

    // ---- classic mode ----

    @Test
    void classicEndpointsAreFixed() {
        BezierEasing easing = new BezierEasing(0.42f, 0f, 0.58f, 1f, "ease_in_out");
        assertEquals(0.0, easing.apply(0.0), EPS);
        assertEquals(1.0, easing.apply(1.0), EPS);
    }

    @Test
    void classicEaseInOutMidpointIsHalf() {
        BezierEasing easing = new BezierEasing(0.42f, 0f, 0.58f, 1f, "ease_in_out");
        assertEquals(0.5, easing.apply(0.5), 1e-4);
    }

    @Test
    void classicLinearMatchesInput() {
        BezierEasing easing = new BezierEasing(0f, 0f, 1f, 1f, "linear");
        for (int i = 0; i <= 10; i++) {
            double x = i / 10.0;
            assertEquals(x, easing.apply(x), EPS);
        }
    }

    @Test
    void clampingKeepsHandlesInBounds() {
        BezierEasing easing = new BezierEasing(-3f, -9f, 7f, 9f, "custom");
        assertEquals(0f, easing.p1x);
        assertEquals(-4f, easing.p1y);
        assertEquals(1f, easing.p2x);
        assertEquals(4f, easing.p2y);
    }

    @Test
    void overshootAllowsYOutsideUnitRange() {
        BezierEasing easing = new BezierEasing(0.68f, -0.55f, 0.267f, 1.55f, "back");
        boolean under = false;
        boolean over = false;
        for (int i = 1; i < 100; i++) {
            double y = easing.apply(i / 100.0);
            under |= y < -0.05;
            over |= y > 1.05;
        }
        assertTrue(under, "Back-style easing should undershoot below 0");
        assertTrue(over, "Back-style easing should overshoot above 1");
    }

    // ---- multi-point curve mode ----

    @Test
    void curveModeRequiresThreeAnchors() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 1, 1));
        assertFalse(easing.isCurveMode(), "2 anchors collapse back to classic");
        easing.setAnchors(anchors(0, 0, 0.5, 0.5, 1, 1));
        assertTrue(easing.isCurveMode(), "3+ anchors switch to curve mode");
    }

    @Test
    void curveFirstSegmentMatchesEquivalentClassicCurve() {
        // Curve with an explicit first segment: (0,0) -> out handle (0.4, 0) -> anchor (0.6, 0.6)
        BezierAnchor a0 = new BezierAnchor(0f, 0f, BezierAnchor.AUTO, BezierAnchor.AUTO, 0.4f, 0.0f);
        BezierAnchor a1 = new BezierAnchor(0.6f, 0.6f, 0.5f, 0.6f, BezierAnchor.AUTO, BezierAnchor.AUTO);
        BezierAnchor a2 = new BezierAnchor(1f, 1f);
        BezierEasing curve = new BezierEasing();
        curve.setAnchors(new ArrayList<>(List.of(a0, a1, a2)));
        assertTrue(curve.isCurveMode());

        // The mathematically equivalent classic curve for the FIRST segment:
        // scaling both axes by 1/0.6 maps the segment onto the unit square,
        // so the absolute handles become p1=(0.4/0.6, 0) and p2=(0.5/0.6, 1).
        BezierEasing equivalent = new BezierEasing(0.4f / 0.6f, 0f, 0.5f / 0.6f, 1f, "custom");
        for (int i = 1; i <= 20; i++) {
            double x = i / 20.0 * 0.6;
            double segX = x / 0.6;
            assertEquals(curve.apply(x) / 0.6, equivalent.apply(segX), 1e-4,
                "generalized solver must reproduce the segment cubic at x=" + x);
        }
    }

    @Test
    void curveEndpointsAreFixed() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.35, 0.15, 0.65, 0.85, 1, 1));
        assertEquals(0.0, easing.apply(0.0), EPS);
        assertEquals(1.0, easing.apply(1.0), EPS);
    }

    @Test
    void curvePassesThroughInteriorAnchors() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.3, 0.2, 0.7, 0.8, 1, 1));
        assertEquals(0.2, easing.apply(0.3), EPS);
        assertEquals(0.8, easing.apply(0.7), EPS);
    }

    @Test
    void curveIsContinuousAcrossSegmentBoundaries() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.3, 0.2, 0.7, 0.8, 1, 1));
        for (double anchorX : new double[]{0.3, 0.7}) {
            double left = easing.apply(anchorX - 1e-4);
            double right = easing.apply(anchorX + 1e-4);
            assertEquals(left, right, 5e-3, "curve must be continuous at x=" + anchorX);
        }
    }

    @Test
    void curveMonotoneAnchorsGiveMonotoneCurve() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.3, 0.2, 0.55, 0.55, 0.8, 0.8, 1, 1));
        double prev = easing.apply(0.0);
        for (int i = 1; i <= 400; i++) {
            double y = easing.apply(i / 400.0);
            assertTrue(y >= prev - 1e-6, "monotone anchors must give non-decreasing curve at " + (i / 400.0));
            prev = y;
        }
    }

    @Test
    void curveNormalizeSortsAndPinsAnchors() {
        BezierEasing easing = new BezierEasing();
        // deliberately unordered / unclamped interior anchors
        easing.setAnchors(anchors(0.4, 0.4, 0, 0, 0.9, 0.9, 0.6, 0.6, 1, 1));
        assertTrue(easing.isCurveMode());
        List<BezierAnchor> normalized = easing.anchors;
        assertEquals(0f, normalized.getFirst().x);
        assertEquals(0f, normalized.getFirst().y);
        assertEquals(1f, normalized.getLast().x);
        assertEquals(1f, normalized.getLast().y);
        for (int i = 1; i < normalized.size(); i++) {
            assertTrue(normalized.get(i).x > normalized.get(i - 1).x,
                "anchor x must stay strictly increasing after normalize");
        }
    }

    @Test
    void curveInteriorYClampedToEditorRange() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.5, 42f, 1, 1));
        assertEquals(4f, easing.anchors.get(1).y);
    }

    // ---- legacy equivalence ----

    @Test
    void twoAnchorCollapseReproducesLegacyMathExactly() {
        // The legacy single cubic defined by handles P1=(0.42,0), P2=(0.58,1)
        BezierEasing legacy = new BezierEasing(0.42f, 0f, 0.58f, 1f, "ease_in_out");

        // The same curve expressed as a 2-anchor chain with the same explicit
        // handles; setAnchors collapses it to the classic representation.
        BezierAnchor a0 = new BezierAnchor(0f, 0f, BezierAnchor.AUTO, BezierAnchor.AUTO, 0.42f, 0f);
        BezierAnchor a1 = new BezierAnchor(1f, 1f, 0.58f, 1f, BezierAnchor.AUTO, BezierAnchor.AUTO);
        BezierEasing collapsed = new BezierEasing();
        collapsed.setAnchors(new ArrayList<>(List.of(a0, a1)));
        assertFalse(collapsed.isCurveMode());

        assertEquals(legacy.p1x, collapsed.p1x, 1e-6);
        assertEquals(legacy.p1y, collapsed.p1y, 1e-6);
        assertEquals(legacy.p2x, collapsed.p2x, 1e-6);
        assertEquals(legacy.p2y, collapsed.p2y, 1e-6);
        for (int i = 0; i <= 100; i++) {
            double x = i / 100.0;
            assertEquals(legacy.apply(x), collapsed.apply(x), 0.0,
                "generalized solver must reproduce legacy math identically");
        }
    }

    @Test
    void segmentHandlesAutoTangentsAreHorizontal() {
        // Auto handles for span [0.2, 0.8] with equal y are horizontal at anchor y
        BezierAnchor a0 = new BezierAnchor(0.2f, 0.4f);
        BezierAnchor a1 = new BezierAnchor(0.8f, 0.4f);
        float[] h = BezierEasing.segmentHandles(a0, a1);
        assertEquals(0.4f, h[1], EPS);
        assertEquals(0.4f, h[3], EPS);
        // control x inside the segment
        assertTrue(h[0] > 0.2f && h[0] < 0.8f);
        assertTrue(h[2] > 0.2f && h[2] < 0.8f);
    }

    @Test
    void deepCopyIsIndependent() {
        BezierEasing easing = new BezierEasing();
        easing.setAnchors(anchors(0, 0, 0.5, 0.5, 1, 1));
        BezierEasing copy = new BezierEasing(easing);
        copy.anchors.get(1).y = 0.1f;
        assertEquals(0.5f, easing.anchors.get(1).y, "anchor copy must be deep");
        copy.p1y = 99f;
        assertEquals(0.1f, easing.p1y, EPS, "classic fields must be independent too");
    }
}
