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
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
                                                    @Local(type = Map.Entry.class, ordinal = 0) @Nullable Map.Entry<Integer, Keyframe> captured0,
                                                    @Local(type = Map.Entry.class, ordinal = 1) @Nullable Map.Entry<Integer, Keyframe> captured1) {
        BezierEasing easing = BezierHooks.resolveSegmentEasing(captured0, captured1);
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
                                                             @Local(type = Map.Entry.class, ordinal = 0) @Nullable Map.Entry<Integer, Keyframe> captured0,
                                                             @Local(type = Map.Entry.class, ordinal = 1) @Nullable Map.Entry<Integer, Keyframe> captured1,
                                                             @Local(type = Map.Entry.class, ordinal = 2) @Nullable Map.Entry<Integer, Keyframe> captured2,
                                                             @Local(type = Map.Entry.class, ordinal = 3) @Nullable Map.Entry<Integer, Keyframe> captured3) {
        BezierEasing easing = BezierHooks.resolveSmoothSegmentEasing(captured0, captured1, captured2, captured3);
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
    private KeyframeChange flashbackbezier$easeHermiteSegment(Keyframe receiver, Map<Float, Keyframe> keyframes, float tick,
                                                              Operation<KeyframeChange> original,
                                                              @Local(type = Map.Entry.class, ordinal = 0) @Nullable Map.Entry<Integer, Keyframe> captured0,
                                                              @Local(type = Map.Entry.class, ordinal = 1) @Nullable Map.Entry<Integer, Keyframe> captured1,
                                                              @Local(type = RealTimeMapping.class) @Nullable RealTimeMapping realTimeMapping) {
        BezierEasing easing = BezierHooks.resolveSegmentEasing(captured0, captured1);
        if (easing == null) {
            return original.call(receiver, keyframes, tick);
        }

        float easedTick;
        if (realTimeMapping != null) {
            float lowerReal = realTimeMapping.getRealTime(captured0 == null ? 0 : Math.min(captured0.getKey(), captured1.getKey()));
            float ceilReal = realTimeMapping.getRealTime(Math.max(captured0 == null ? 0 : captured0.getKey(), captured1.getKey()));
            easedTick = BezierHooks.easeSegmentPosition(easing, lowerReal, ceilReal, tick);
        } else {
            int lowerTick = Math.min(captured0 == null ? 0 : captured0.getKey(), captured1 == null ? 0 : captured1.getKey());
            int ceilTick = Math.max(captured0 == null ? 0 : captured0.getKey(), captured1 == null ? 0 : captured1.getKey());
            easedTick = BezierHooks.easeSegmentPosition(easing, lowerTick, ceilTick, tick);
        }

        return original.call(receiver, keyframes, easedTick);
    }
}
