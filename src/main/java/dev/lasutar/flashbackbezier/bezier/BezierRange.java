package dev.lasutar.flashbackbezier.bezier;

/**
 * A "range easing": one easing curve applied across a whole span of keyframes
 * (e.g. A -> D over the intermediate B, C).
 *
 * <p>Stored on the START keyframe of the span; {@link #endTick} is the tick of
 * the END keyframe. While active, the absolute replay tick is warped by the
 * easing before the vanilla keyframe lookup runs, so the trajectory still
 * passes exactly through every intermediate keyframe value (the warp is
 * continuous and reaches both end ticks exactly), but the traversal speed
 * follows the single easing curve for the whole span. Per-keyframe easings of
 * the intermediate keyframes are temporarily suppressed while the range is
 * active (they are restored when the range is removed - nothing is deleted).</p>
 */
public final class BezierRange {

    /** Tick of the keyframe that ends the eased span. */
    public int endTick;

    /** The easing applied across the span. */
    public BezierEasing easing = new BezierEasing();

    public BezierRange() {
    }

    public BezierRange(int endTick, BezierEasing easing) {
        this.endTick = endTick;
        this.easing = easing;
    }

    public BezierRange(BezierRange other) {
        this(other.endTick, new BezierEasing(other.easing));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BezierRange other)) return false;
        return this.endTick == other.endTick && this.easing.equals(other.easing);
    }

    @Override
    public int hashCode() {
        return 31 * this.endTick + this.easing.hashCode();
    }
}
