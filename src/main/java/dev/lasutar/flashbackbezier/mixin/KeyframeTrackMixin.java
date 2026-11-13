package dev.lasutar.flashbackbezier.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.interpolation.SidedInterpolationType;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.state.RealTimeMapping;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.integration.BezierHooks;
import dev.lasutar.flashbackbezier.integration.BezierRangeState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * Applies the bezier easing attached to a keyframe during playback.
 *
 * <p>The easing only warps TIME inside the segment between two keyframes - the
 * spatial path (linear, Catmull-Rom smooth or Hermite) is handed to Flashback
 * unchanged, only the `amount`/evaluation position moving along it is eased.
 * This means position/rotation/FOV interpolation math is left fully intact.</p>
 *
 * <p>Hooks cover all three vanilla interpolation paths:
 * <ul>
 *   <li>the sided linear/ease branch ({@link SidedInterpolationType#interpolate}),</li>
 *   <li>the Catmull-Rom "smooth" branch (createSmoothInterpolatedChange),</li>
 *   <li>the Hermite branch (createHermiteInterpolatedChange).</li>
 * </ul>
 * When no bezier easing is attached, all hooks are pure pass-through.</p>
 *
 * <p><b>Range easings:</b> a {@link dev.lasutar.flashbackbezier.bezier.BezierRange}
 * attached to a keyframe warps the absolute replay tick before the vanilla
 * lookup (see {@link #flashbackbezier$warpRangeTick}), producing one easing
 * curve across a whole span of keyframes. While a range is active, per-keyframe
 * easings inside the span are suppressed so the two mechanisms never fight.</p>
 *
 * <p><b>Local capture:</b> all {@code @Local} captures are name-based and were
 * verified against the LocalVariableTable of the released Flashback 0.43.6 jar
 * (javap): at every injection point the matching locals are in scope and no
 * other same-typed local covers them, so the ordinal-free matching is strict.</p>
 */
@Mixin(KeyframeTrack.class)
public abstract class KeyframeTrackMixin {

    /**
     * LINEAR/EASE branch: replaces the sided "ease" transform with our cubic
     * bezier easing for the segment, when the left keyframe carries one.
     */
    @WrapOperation(method = "createKeyframeChange",
        at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/keyframe/interpolation/SidedInterpolationType;interpolate(Lcom/moulberry/flashback/keyframe/interpolation/SidedInterpolationType;Lcom/moulberry/flashback/keyframe/interpolation/SidedInterpolationType;F)F"))
    private float flashbackbezier$easeLinearSegment(SidedInterpolationType left, SidedInterpolationType right,
                                                     float amount, Operation<Float> original,
                                                     @Local(name = "lowerEntry") @Nullable Map.Entry<Integer, Keyframe> lowerEntry,
                                                     @Local(name = "ceilEntry") @Nullable Map.Entry<Integer, Keyframe> ceilEntry,
                                                     @Local(name = "tick") float warpedTick) {
        if (this.flashbackbezier$isRangeActive(warpedTick)) {
            return original.call(left, right, amount);
        }
        BezierEasing easing = BezierHooks.resolveSegmentEasing(lowerEntry, ceilEntry);
        if (easing == null) {
            return original.call(left, right, amount);
        }
        // Linear sided pairs would return `amount` unchanged, so returning the
        // bezier transform here is exactly "easing applied to this segment".
        // (When the user mixed in vanilla EASE_* siding we intentionally let
        // our explicit bezier easing take precedence.)
        return (float) easing.apply(amount);
    }

    /**
     * SMOOTH (Catmull-Rom) branch: eases the `amount` handed to the smooth
     * interpolation, keeping the exact same spatial spline. The spline uses
     * `t = t1 + (t2 - t1) * amount`, so warping the amount warps the traversal
     * speed without touching the path shape.
     */
    @WrapOperation(method = "createKeyframeChange",
        at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/keyframe/Keyframe;createSmoothInterpolatedChange(Lcom/moulberry/flashback/keyframe/Keyframe;Lcom/moulberry/flashback/keyframe/Keyframe;Lcom/moulberry/flashback/keyframe/Keyframe;FFFFF)Lcom/moulberry/flashback/keyframe/change/KeyframeChange;"))
    private KeyframeChange flashbackbezier$easeSmoothSegment(Keyframe receiver, Keyframe p1, Keyframe p2, Keyframe p3,
                                                              float t0, float t1, float t2, float t3, float amount,
                                                              Operation<KeyframeChange> original,
                                                              @Local(name = "lowerEntry") @Nullable Map.Entry<Integer, Keyframe> lowerEntry,
                                                              @Local(name = "ceilEntry") @Nullable Map.Entry<Integer, Keyframe> ceilEntry,
                                                              @Local(name = "beforeEntry") @Nullable Map.Entry<Integer, Keyframe> beforeEntry,
                                                              @Local(name = "afterAfterEntry") @Nullable Map.Entry<Integer, Keyframe> afterAfterEntry,
                                                              @Local(name = "tick") float warpedTick) {
        if (this.flashbackbezier$isRangeActive(warpedTick)) {
            return original.call(receiver, p1, p2, p3, t0, t1, t2, t3, amount);
        }
        BezierEasing easing = BezierHooks.resolveSmoothSegmentEasing(beforeEntry, lowerEntry, ceilEntry, afterAfterEntry);
        if (easing == null) {
            return original.call(receiver, p1, p2, p3, t0, t1, t2, t3, amount);
        }
        float easedAmount = (float) easing.apply(amount);
        return original.call(receiver, p1, p2, p3, t0, t1, t2, t3, easedAmount);
    }

    /**
     * HERMITE branch: eases the evaluation position within the segment in
     * real-time space, keeping all sample points and the polynomial untouched.
     * bezier(0)=0 / bezier(1)=1 guarantee continuity with adjacent segments.
     */
    @WrapOperation(method = "createKeyframeChange",
        at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/keyframe/Keyframe;createHermiteInterpolatedChange(Ljava/util/Map;F)Lcom/moulberry/flashback/keyframe/change/KeyframeChange;"))
    private KeyframeChange flashbackbezier$easeHermiteSegment(Keyframe receiver, Map<Float, Keyframe> keyframes, float realTimeTick,
                                                              Operation<KeyframeChange> original,
                                                              @Local(name = "lowerEntry") @Nullable Map.Entry<Integer, Keyframe> lowerEntry,
                                                              @Local(name = "ceilEntry") @Nullable Map.Entry<Integer, Keyframe> ceilEntry,
                                                              @Local(type = RealTimeMapping.class) @Nullable RealTimeMapping realTimeMapping,
                                                              @Local(name = "tick") float warpedTick) {
        if (this.flashbackbezier$isRangeActive(warpedTick)) {
            return original.call(receiver, keyframes, realTimeTick);
        }
        BezierEasing easing = BezierHooks.resolveSegmentEasing(lowerEntry, ceilEntry);
        if (easing == null) {
            return original.call(receiver, keyframes, realTimeTick);
        }

        float easedTick;
        if (realTimeMapping != null) {
            float lowerReal = realTimeMapping.getRealTime(lowerEntry.getKey());
            float ceilReal = realTimeMapping.getRealTime(ceilEntry.getKey());
            easedTick = BezierHooks.easeSegmentPosition(easing, lowerReal, ceilReal, realTimeTick);
        } else {
            easedTick = BezierHooks.easeSegmentPosition(easing, lowerEntry.getKey(), ceilEntry.getKey(), realTimeTick);
        }

        return original.call(receiver, keyframes, easedTick);
    }

    /**
     * Range easing: warps the absolute replay tick before any keyframe lookup
     * happens, when a range attached to one of this track's keyframes covers
     * the tick. The warp maps the span [startTick, endTick] onto itself
     * (clamped), so the vanilla evaluation - amount computation, Catmull-Rom
     * knot selection, Hermite sampling, holds - keeps working exactly as
     * designed, just on the eased timeline.
     */
    @ModifyVariable(method = "createKeyframeChange", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float flashbackbezier$warpRangeTick(float tick) {
        KeyframeTrack self = (KeyframeTrack) (Object) this;
        BezierRangeState.ActiveRange active = BezierRangeState.findActive(self.keyframesByTick, tick);
        if (active == null) {
            return tick;
        }
        return BezierRangeState.warp(active, tick);
    }

    /**
     * Whether a range easing is active at (the possibly already-warped) tick
     * on this track. While one is active, per-keyframe easings are suppressed
     * to keep "one easing across the whole span" semantics clean.
     */
    @Unique
    private boolean flashbackbezier$isRangeActive(float tick) {
        KeyframeTrack self = (KeyframeTrack) (Object) this;
        return BezierRangeState.findActive(self.keyframesByTick, tick) != null;
    }
}
