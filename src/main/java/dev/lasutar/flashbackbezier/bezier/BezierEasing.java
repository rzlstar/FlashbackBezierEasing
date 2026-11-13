package dev.lasutar.flashbackbezier.bezier;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A cubic-bezier easing function, exactly like the CSS {@code cubic-bezier()} / the
 * After Effects value-graph ease curve.
 *
 * <p>The curve is defined by four control points: the two fixed end points
 * (0,0) and (1,1), plus two freely editable "handles" {@code (p1x, p1y)} and
 * {@code (p2x, p2y)}. The x coordinates are reused for the time axis, the y
 * coordinates for the progress/easing axis. Handle y values may go outside
 * [0, 1] to create anticipation / overshoot (e.g. the "Back" preset).</p>
 *
 * <p>To evaluate the easing at a normalized time {@code x} we first numerically
 * invert the x axis (Newton-Raphson, with a bisection fallback) and then read
 * the y value at the solved parameter. This is the standard, numerically stable
 * approach used by CSS engines; simply substituting {@code x} for {@code t}
 * would be incorrect because bezier parameters are not linear in time.</p>
 *
 * <p><b>Multi-point curves:</b> when {@link #anchors} is non-null the easing is
 * a chain of cubic bezier segments between consecutive anchors (see
 * {@link BezierAnchor}). The evaluation walks to the segment containing
 * {@code x} and runs the same Newton + bisection solve inside it. Endpoints
 * stay fixed at (0,0)/(1,1) and anchor x values are kept strictly increasing,
 * so the inverse solve always has a unique solution and the curve is C0
 * continuous by construction (anchors are shared) and C1 smooth with the
 * default horizontal auto-tangents.</p>
 */
public final class BezierEasing {

    /** Player-visible preset id ("linear", "ease", ..., "custom"); kept for nicer JSON output. */
    public String preset = "custom";

    /** X coordinate of the first (incoming) handle, clamped to [0, 1]. */
    public float p1x = 0.25f;
    /** Y coordinate of the first (incoming) handle. May leave [0, 1] for Back-style eases. */
    public float p1y = 0.1f;
    /** X coordinate of the second (outgoing) handle, clamped to [0, 1]. */
    public float p2x = 0.25f;
    /** Y coordinate of the second (outgoing) handle. May leave [0, 1] for Back-style eases. */
    public float p2y = 1.0f;

    /**
     * Multi-point curve mode: anchors of the chained cubic segments, or null
     * for the classic single-cubic (P1/P2) representation above.
     *
     * <p>Invariants: at least 2 anchors, first is (0,0), last is (1,1), x
     * strictly increasing (see {@link BezierAnchor#normalize}). Curves with
     * 2 anchors are always stored in the classic form instead.</p>
     */
    @Nullable
    public List<BezierAnchor> anchors = null;

    public BezierEasing() {
    }

    public BezierEasing(float p1x, float p1y, float p2x, float p2y, String preset) {
        this.set(p1x, p1y, p2x, p2y);
        this.preset = preset;
    }

    /**
     * Simple copy constructor (deep-copies anchors).
     */
    public BezierEasing(BezierEasing other) {
        this.set(other.p1x, other.p1y, other.p2x, other.p2y);
        this.preset = other.preset;
        if (other.anchors != null) {
            this.anchors = BezierAnchor.copyOf(other.anchors);
        }
    }

    public static BezierEasing fromPreset(BezierPreset preset) {
        return preset.createEasing();
    }

    public void set(float p1x, float p1y, float p2x, float p2y) {
        this.p1x = clampX(p1x);
        this.p1y = clampY(p1y);
        this.p2x = clampX(p2x);
        this.p2y = clampY(p2y);
    }

    public static float clampX(float x) {
        return Math.max(0.0f, Math.min(1.0f, x));
    }

    public static float clampY(float y) {
        return Math.max(-4.0f, Math.min(4.0f, y));
    }

    /**
     * Switches this easing to multi-point curve mode with the given anchors.
     *
     * <p>Curves with two or fewer anchors are collapsed back into the classic
     * single-cubic representation (the first segment's handles become P1/P2),
     * keeping old data and old mod versions perfectly compatible. The anchor
     * list is deep-copied and normalized (endpoints pinned, x strictly
     * increasing).</p>
     */
    public void setAnchors(List<BezierAnchor> newAnchors) {
        if (newAnchors == null || newAnchors.size() <= 2) {
            // Collapse to the classic representation
            float p1x = this.p1x, p1y = this.p1y, p2x = this.p2x, p2y = this.p2y;
            if (newAnchors != null && newAnchors.size() == 2) {
                float[] first = segmentHandles(newAnchors.get(0), newAnchors.get(1));
                p1x = clampX(first[0]);
                p1y = clampY(first[1]);
                p2x = clampX(first[2]);
                p2y = clampY(first[3]);
            }
            this.anchors = null;
            this.set(p1x, p1y, p2x, p2y);
            return;
        }
        List<BezierAnchor> copy = BezierAnchor.copyOf(newAnchors);
        BezierAnchor.normalize(copy);
        this.anchors = copy;
    }

    /**
     * Returns whether this easing is in multi-point curve mode.
     */
    public boolean isCurveMode() {
        return this.anchors != null;
    }

    /**
     * Resolves the effective cubic control points of the segment between two
     * anchors: {p1x, p1y, p2x, p2y} in ABSOLUTE curve coordinates, using the
     * explicit handles when present and horizontal auto-tangents otherwise.
     */
    public static float[] segmentHandles(BezierAnchor a0, BezierAnchor a1) {
        float dx = a1.x - a0.x;
        float p1x = a0.hasOutgoingHandle() ? a0.hox : a0.x + dx / 3.0f;
        float p1y = a0.hasOutgoingHandle() ? a0.hoy : a0.y;
        float p2x = a1.hasIncomingHandle() ? a1.hix : a1.x - dx / 3.0f;
        float p2y = a1.hasIncomingHandle() ? a1.hiy : a1.y;
        // keep control x inside the segment (solvability invariant)
        p1x = Math.max(a0.x, Math.min(a1.x, p1x));
        p2x = Math.max(a0.x, Math.min(a1.x, p2x));
        return new float[]{p1x, clampY(p1y), p2x, clampY(p2y)};
    }

    /**
     * Marks this easing as manually edited, so preset detection shows "Custom Bezier".
     */
    public void markCustom() {
        this.preset = BezierPreset.CUSTOM.id();
    }

    /**
     * Returns the preset this easing's control points currently match,
     * or {@link BezierPreset#CUSTOM} when nothing matches exactly.
     */
    public BezierPreset detectPreset() {
        if (this.isCurveMode()) {
            return BezierPreset.CUSTOM;
        }
        for (BezierPreset candidate : BezierPreset.values()) {
            if (candidate.matches(this.p1x, this.p1y, this.p2x, this.p2y)) {
                return candidate;
            }
        }
        return BezierPreset.CUSTOM;
    }

    /**
     * The number of numeric values that differ between this and another easing; 0 = identical.
     */
    public int difference(BezierEasing other) {
        int diff = 0;
        if (Math.abs(this.p1x - other.p1x) > 1e-5f) diff++;
        if (Math.abs(this.p1y - other.p1y) > 1e-5f) diff++;
        if (Math.abs(this.p2x - other.p2x) > 1e-5f) diff++;
        if (Math.abs(this.p2y - other.p2y) > 1e-5f) diff++;
        if (!listEquals(this.anchors, other.anchors)) diff++;
        return diff;
    }

    private static boolean listEquals(@Nullable List<BezierAnchor> a, @Nullable List<BezierAnchor> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * Evaluates the eased progress at normalized time {@code x}.
     *
     * @param x normalized segment time in [0, 1]
     * @return eased progress in [0, 1] (may briefly over/undershoot for Back-style handles)
     */
    public double apply(double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }

        if (this.anchors != null) {
            return this.applyCurve(x);
        }

        // Classic single cubic: p0=(0,0), p3=(1,1)
        double cX = 3.0 * this.p1x;
        double bX = 3.0 * (this.p2x - this.p1x) - cX;
        double aX = 1.0 - cX - bX;

        double cY = 3.0 * this.p1y;
        double bY = 3.0 * (this.p2y - this.p1y) - cY;
        double aY = 1.0 - cY - bY;

        double t = solveCubicT(0.0, aX, bX, cX, x);

        return ((aY * t + bY) * t + cY) * t;
    }

    /**
     * Multi-point evaluation: locate the segment containing x, normalize into
     * segment-local coordinates, solve t (Newton + bisection), evaluate y.
     */
    private double applyCurve(double x) {
        List<BezierAnchor> anchors = this.anchors;
        int count = anchors.size();
        if (count < 2) {
            return x; // degenerate; should never happen (setAnchors enforces >= 2)
        }

        // Find the segment [i, i+1] with anchor[i].x <= x < anchor[i+1].x
        int lo = 0, hi = count - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (anchors.get(mid).x <= x) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        BezierAnchor a0 = anchors.get(lo);
        BezierAnchor a1 = anchors.get(lo + 1);
        float[] h = segmentHandles(a0, a1);

        double span = (double) a1.x - (double) a0.x;
        if (span < 1e-9) {
            return a1.y;
        }
        double localX = (x - (double) a0.x) / span;

        double t = solveSegmentT((double) a0.x, (double) h[0], (double) h[2], (double) a1.x, localX);
        return bezierY(t, (double) a0.y, (double) h[1], (double) h[3], (double) a1.y);
    }

    /**
     * Solves the bezier parameter t for a cubic segment spanning absolute
     * x coordinates p0x..p3x, targeting the normalized (segment-local) input
     * {@code localX} in [0, 1]. Requires p0x <= p1x, p2x <= p3x and
     * p0x < p3x (monotonic axis, guaranteed by the anchor/handle clamping),
     * so bisection is always a safe fallback.
     */
    private static double solveSegmentT(double p0x, double p1x, double p2x, double p3x, double localX) {
        double span = p3x - p0x;
        if (span < 1e-9) {
            return localX;
        }
        // Normalize the whole segment into [0, 1] on the x axis
        double n1 = (p1x - p0x) / span;
        double n2 = (p2x - p0x) / span;
        double cX = 3.0 * n1;
        double bX = 3.0 * (n2 - 2.0 * n1);
        double aX = 1.0 - cX - bX;
        return solveCubicT(0.0, aX, bX, cX, localX);
    }

    /**
     * Newton-Raphson iteration over x(t) = ((a*t + b)*t + c)*t with a
     * bisection fallback for the (rare) cases where the derivative vanishes
     * near the target. Because the x handles are clamped so that x(t) is
     * monotonically non-decreasing on t in [0, 1], bisection is always a safe
     * fallback.
     */
    private static double solveCubicT(double constant, double aX, double bX, double cX, double x) {
        double t = Math.max(0.0, Math.min(1.0, x));

        // Newton-Raphson, 8 iterations, converges quadratically near the root
        for (int i = 0; i < 8; i++) {
            double currentX = constant + ((aX * t + bX) * t + cX) * t;
            double error = currentX - x;
            if (Math.abs(error) < 1e-7) {
                return t;
            }
            double derivative = (3.0 * aX * t + 2.0 * bX) * t + cX;
            if (Math.abs(derivative) < 1e-6) {
                break;
            }
            t = t - error / derivative;
            if (t < 0.0) t = 0.0;
            if (t > 1.0) t = 1.0;
        }

        // Bisection fallback (guaranteed convergence on a monotonic axis)
        double lower = 0.0;
        double upper = 1.0;
        for (int i = 0; i < 28; i++) {
            double currentX = constant + ((aX * t + bX) * t + cX) * t;
            if (Math.abs(currentX - x) < 1e-7) {
                return t;
            }
            if (currentX < x) {
                lower = t;
            } else {
                upper = t;
            }
            t = 0.5 * (lower + upper);
        }
        return t;
    }

    /** Evaluates the y coordinate of a cubic bezier at parameter t (De Casteljau-free polynomial form). */
    private static double bezierY(double t, double y0, double y1, double y2, double y3) {
        double cY = 3.0 * (y1 - y0);
        double bY = 3.0 * (y0 - 2.0 * y1 + y2);
        double aY = -y0 + 3.0 * y1 - 3.0 * y2 + y3;
        return y0 + ((aY * t + bY) * t + cY) * t;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BezierEasing other)) return false;
        return this.difference(other) == 0 && this.preset.equals(other.preset);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Float.hashCode(this.p1x);
        result = 31 * result + Float.hashCode(this.p1y);
        result = 31 * result + Float.hashCode(this.p2x);
        result = 31 * result + Float.hashCode(this.p2y);
        if (this.anchors != null) {
            result = 31 * result + this.anchors.hashCode();
        }
        return result;
    }
}
