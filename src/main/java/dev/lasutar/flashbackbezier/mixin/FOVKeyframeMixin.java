package dev.lasutar.flashbackbezier.mixin;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import dev.lasutar.flashbackbezier.integration.BezierHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FOVKeyframe.class)
public abstract class FOVKeyframeMixin {

    @Inject(method = "copy", at = @At("RETURN"))
    public void flashbackbezier$transferEasing(CallbackInfoReturnable<Keyframe> cir) {
        BezierHooks.transferBetween((Keyframe) (Object) this, cir.getReturnValue());
    }
}
