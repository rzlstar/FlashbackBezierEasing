package dev.lasutar.flashbackbezier.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.ExportDoneWindow;
import dev.lasutar.flashbackbezier.ui.BezierEditorWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the bezier editor window inside Flashback's ImGui context, right
 * before the frame is finished and drawn.
 */
@Mixin(ReplayUI.class)
public abstract class ReplayUIMixin {

    @Inject(method = "drawOverlayInternal()V",
        at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/editor/ui/windows/ExportDoneWindow;render()V"))
    private static void flashbackbezier$renderBezierEditor(CallbackInfo ci) {
        BezierEditorWindow.render();
    }
}
