package dev.lasutar.flashbackbezier.bezier;

/**
 * One editable control-point ("anchor") of a multi-point easing curve.
 *
 * <p>An easing curve is a chain of cubic bezier segments between consecutive
 * anchors. Every anchor carries its position {@code (x, y)} and optional
 * incoming/outgoing handle coordinates. A handle value of {@code NaN} means
 * "auto": the segment control point is derived as a horizontal tangent at the
 * anchor, which keeps the whole curve C1-smooth (never a polyline) while
 * remaining trivially invertible.</p>
 *
 * <p>X coordinates are kept strictly increasing between anchors and inside
 * [0, 1]; this guarantees each segment's x(t) is monotonic, so the inverse
 * solve (Newton + bisection) always has a unique solution. The curve's
 * endpoints are fixed at (0,0) and (1,1).</p>
 */
public final class BezierAnchor {

    /** Marker for "no explicit handle - use the auto (horizontal) tangent". */
    public static final float AUTO = Float.NaN;

    public float x;
    public float y;
    /** Incoming (left side) handle, absolute coordinates; {@link #AUTO} = auto. */
    public float hix = AUTO;
    public float hiy = AUTO;
    /** Outgoing (right side) handle, absolute coordinates; {@link #AUTO} = auto. */
    public float hox = AUTO;
    public float hoy = AUTO;

    public BezierAnchor() {
    }

    public BezierAnchor(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public BezierAnchor(float x, float y, float hix, float hiy, float hox, float hoy) {
        this.x = x;
        this.y = y;
        this.hix = hix;
        this.hiy = hiy;
        this.hox = hox;
        this.hoy = hoy;
    }

    public BezierAnchor(BezierAnchor other) {
        this(other.x, other.y, other.hix, other.hiy, other.hox, other.hoy);
    }

    public boolean hasIncomingHandle() {
        return !Float.isNaN(this.hix) && !Float.isNaN(this.hiy);
    }

    public boolean hasOutgoingHandle() {
        return !Float.isNaN(this.hox) && !Float.isNaN(this.hoy);
    }

    public void clearHandles() {
        this.hix = AUTO;
        this.hiy = AUTO;
        this.hox = AUTO;
        this.hoy = AUTO;
    }

    /** Minimum horizontal distance between two neighbouring anchors. */
    public static final float MIN_X_GAP = 0.02f;

    /** Deep copy of a list of anchors. */
    public static java.util.List<BezierAnchor> copyOf(java.util.List<BezierAnchor> anchors) {
        java.util.List<BezierAnchor> copy = new java.util.ArrayList<>(anchors.size());
        for (BezierAnchor anchor : anchors) {
            copy.add(new BezierAnchor(anchor));
        }
        return copy;
    }

    /**
     * Enforces the curve invariants in place: endpoints pinned to (0,0)/(1,1),
     * x clamped inside [0,1] and strictly increasing between neighbours
     * (minimum gap {@link #MIN_X_GAP}), y clamped to the editor range.
     */
    public static void normalize(java.util.List<BezierAnchor> anchors) {
        if (anchors.isEmpty()) {
            return;
        }
        BezierAnchor first = anchors.getFirst();
        first.x = 0.0f;
        first.y = 0.0f;
        BezierAnchor last = anchors.getLast();
        last.x = 1.0f;
        last.y = 1.0f;

        for (int i = 1; i < anchors.size() - 1; i++) {
            BezierAnchor anchor = anchors.get(i);
            float minX = anchors.get(i - 1).x + MIN_X_GAP;
            float maxX = anchors.get(i + 1).x - MIN_X_GAP;
            anchor.x = Math.max(minX, Math.min(maxX, BezierEasing.clampX(anchor.x)));
            anchor.y = BezierEasing.clampY(anchor.y);
        }
        // Defensive final pass for the endpoints after neighbour clamping
        first.x = 0.0f;
        last.x = 1.0f;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BezierAnchor other)) return false;
        return Float.floatToIntBits(this.x) == Float.floatToIntBits(other.x)
            && Float.floatToIntBits(this.y) == Float.floatToIntBits(other.y)
            && Float.floatToIntBits(this.hix) == Float.floatToIntBits(other.hix)
            && Float.floatToIntBits(this.hiy) == Float.floatToIntBits(other.hiy)
            && Float.floatToIntBits(this.hox) == Float.floatToIntBits(other.hox)
            && Float.floatToIntBits(this.hoy) == Float.floatToIntBits(other.hoy);
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(this.x);
        result = 31 * result + Float.floatToIntBits(this.y);
        result = 31 * result + Float.floatToIntBits(this.hix);
        result = 31 * result + Float.floatToIntBits(this.hiy);
        result = 31 * result + Float.floatToIntBits(this.hox);
        result = 31 * result + Float.floatToIntBits(this.hoy);
        return result;
    }
}
