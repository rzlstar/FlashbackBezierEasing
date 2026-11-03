package dev.lasutar.flashbackbezier.mixin;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import dev.lasutar.flashbackbezier.integration.BezierHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.TreeMap;

/**
 * Carries the attached bezier easing through Flashback's {@code copy()}
 * (used for clipboard copies, undo snapshots and keyframe type changes), so
 * easing follows the keyframe through every editor operation.
 */
@Mixin(CameraKeyframe.class)
public abstract class CameraKeyframeMixin {

    @Inject(method = "copy", at = @At("RETURN"))
    public void flashbackbezier$transferEasing(CallbackInfoReturnable<Keyframe> cir) {
        BezierHooks.transferBetween((Keyframe) (Object) this, cir.getReturnValue());
    }
}
