package dev.lasutar.flashbackbezier.bezier;

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

    public BezierEasing() {
    }

    public BezierEasing(float p1x, float p1y, float p2x, float p2y, String preset) {
        this.set(p1x, p1y, p2x, p2y);
        this.preset = preset;
    }

    /**
     * Simple copy constructor.
     */
    public BezierEasing(BezierEasing other) {
        this(other.p1x, other.p1y, other.p2x, other.p2y, other.preset);
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
        return diff;
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

        // Coefficients of the expanded cubic polynomial, per axis:
        // P(t) = (a*t + b)*t + c)*t + 1  with c = 3*p1, b = 3*p2 - 3*p1 - c, a = 1 - c - b
        double cX = 3.0 * this.p1x;
        double bX = 3.0 * (this.p2x - this.p1x) - cX;
        double aX = 1.0 - cX - bX;

        double cY = 3.0 * this.p1y;
        double bY = 3.0 * (this.p2y - this.p1y) - cY;
        double aY = 1.0 - cY - bY;

        double t = solveParameterT(aX, bX, cX, x);

        return ((aY * t + bY) * t + cY) * t;
    }

    /**
     * Newton-Raphson iteration over x(t) with a bisection fallback for the
     * (rare) cases where the derivative vanishes near the target. Because the
     * x handles are clamped to [0, 1], x(t) is monotonically non-decreasing, so
     * bisection is always a safe fallback.
     */
    private static double solveParameterT(double aX, double bX, double cX, double x) {
        double t = x;

        // Newton-Raphson, 8 iterations, converges quadratically near the root
        for (int i = 0; i < 8; i++) {
            double currentX = ((aX * t + bX) * t + cX) * t;
            double error = currentX - x;
            if (Math.abs(error) < 1e-7) {
                return t;
            }
            double derivative = (3.0 * aX * t + 2.0 * bX) * t + cX;
            if (Math.abs(derivative) < 1e-6) {
                break;
            }
            t = t - error / derivative;
        }

        // Bisection fallback (guaranteed convergence on a monotonic axis)
        double lower = 0.0;
        double upper = 1.0;
        t = Math.max(0.0, Math.min(1.0, t));
        for (int i = 0; i < 28; i++) {
            double currentX = ((aX * t + bX) * t + cX) * t;
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
        return result;
    }
}
