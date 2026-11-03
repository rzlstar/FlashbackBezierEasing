package dev.lasutar.flashbackbezier.mixin;

import com.moulberry.flashback.keyframe.Keyframe;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.integration.BezierKeyframeData;
import imgui.moulberry90.ImDrawList;
import org.jetbrains.annotations.Nullable;

import java.util.TreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the bezier.easing data field to every Flashback Keyframe and
 * renders a distinct diamond icon for keyframes that carry a bezier easing.
 */
@Mixin(Keyframe.class)
public abstract class KeyframeMixin implements BezierKeyframeData {

    @Unique
    @Nullable
    private BezierEasing flashbackbezier$easing = null;

    @Override
    public @Nullable BezierEasing flashbackbezier$getEasing() {
        return this.flashbackbezier$easing;
    }

    @Override
    public void flashbackbezier$setEasing(@Nullable BezierEasing easing) {
        this.flashbackbezier$easing = easing;
    }

    /**
     * Keyframes with a bezier easing show a diamond instead of the vanilla
     * interpolation-type shape, making it obvious at a glance which keyframes
     * are affected by this add-on.
     */
    @Inject(method = "drawOnTimeline", at = @At("HEAD"), cancellable = true)
    public void flashbackbezier$drawBezierIcon(ImDrawList drawList, int keyframeSize, float x, float y, int colour,
                                               float timelineScale, float minTimelineX, float maxTimelineX,
                                               int tick, TreeMap<Integer, Keyframe> keyframeTimes,
                                               CallbackInfo ci) {
        if (this.flashbackbezier$easing == null) {
            return;
        }

        float s = keyframeSize;
        // Diamond (outlines - the vanilla keyframe shapes never use it)
        drawList.addQuadFilled(x, y - s, x + s, y, x, y + s, x - s, y, colour);
        // Thin highlight outline
        int outline = 0xFFFFFFFF;
        drawList.addLine(x, y - s, x + s, y, outline, 1.0f);
        drawList.addLine(x + s, y, x, y + s, outline, 1.0f);
        drawList.addLine(x, y + s, x - s, y, outline, 1.0f);
        drawList.addLine(x - s, y, x, y - s, outline, 1.0f);

        ci.cancel();
    }
}
