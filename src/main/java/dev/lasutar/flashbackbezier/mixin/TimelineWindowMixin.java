package dev.lasutar.flashbackbezier.mixin;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.editor.SelectedKeyframes;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import dev.lasutar.flashbackbezier.ui.BezierPopupUi;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the "Bezier Easing" section to the keyframe context menu (right-click
 * popup on the timeline), showing the currently applied preset and opening
 * the bezier editor.
 */
@Mixin(TimelineWindow.class)
public abstract class TimelineWindowMixin {

    @Shadow(remap = false)
    private static int editingKeyframeTrack;

    @Shadow(remap = false)
    private static int editingKeyframeTick;

    @Shadow(remap = false)
    private static List<SelectedKeyframes> selectedKeyframesList;

    @Inject(method = "renderKeyframeOptionsPopup(I)V", at = @At("HEAD"))
    private static void flashbackbezier$renderBezierSection(int totalTicks, CallbackInfo ci) {
        var editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            return;
        }

        long stamp = editorState.acquireRead();
        try {
            EditorScene editorScene = editorState.getCurrentScene(stamp);
            if (editorScene == null || editingKeyframeTrack < 0 || editingKeyframeTrack >= editorScene.keyframeTracks.size()) {
                return;
            }
            KeyframeTrack keyframeTrack = editorScene.keyframeTracks.get(editingKeyframeTrack);
            if (keyframeTrack == null) {
                return;
            }
            @Nullable Keyframe editingKeyframe = keyframeTrack.keyframesByTick.get(editingKeyframeTick);
            if (editingKeyframe == null) {
                return;
            }

            BezierPopupUi.renderPopupSection(editorState, editorScene, editingKeyframe, editingKeyframeTick,
                editingKeyframeTrack, keyframeTrack, selectedKeyframesList);
        } finally {
            editorState.release(stamp);
        }
    }
}
