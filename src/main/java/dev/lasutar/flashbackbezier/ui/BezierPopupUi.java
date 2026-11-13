package dev.lasutar.flashbackbezier.ui;

import com.moulberry.flashback.editor.SelectedKeyframes;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierPreset;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import dev.lasutar.flashbackbezier.integration.BezierKeyframeData;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Bezier Easing" section drawn at the top of Flashback's keyframe
 * context menu (right-click popup on a timeline keyframe).
 *
 * <p>Called from {@code TimelineWindowMixin} while still inside Flashback's
 * own read lock, so scene access here is cheap. Everything the user picks
 * that needs a write lock is delegated to {@link BezierEditorWindow}.</p>
 */
public final class BezierPopupUi {

    private BezierPopupUi() {
    }

    /**
     * Computes the keyframes the popup applies to: the full multi-selection
     * when the edited keyframe is part of one, otherwise just the edited
     * keyframe itself.
     */
    public static List<BezierTarget> collectTargets(EditorScene editorScene, int trackIndex, int tick,
                                                    List<SelectedKeyframes> selectedKeyframesList) {
        List<BezierTarget> targets = new ArrayList<>();

        boolean selectionCoversEditedKeyframe = false;
        for (SelectedKeyframes selected : selectedKeyframesList) {
            if (selected.trackIndex() == trackIndex && selected.keyframeTicks().contains(tick)) {
                selectionCoversEditedKeyframe = true;
                break;
            }
        }

        if (selectionCoversEditedKeyframe) {
            // Multi selection: every selected keyframe on every selected track
            for (SelectedKeyframes selected : selectedKeyframesList) {
                if (selected.trackIndex() < 0 || selected.trackIndex() >= editorScene.keyframeTracks.size()) {
                    continue;
                }
                KeyframeTrack track = editorScene.keyframeTracks.get(selected.trackIndex());
                for (int selectedTick : selected.keyframeTicks()) {
                    Keyframe keyframe = track.keyframesByTick.get(selectedTick);
                    if (keyframe != null) {
                        targets.add(new BezierTarget(selected.trackIndex(), selectedTick));
                    }
                }
            }
        } else {
            targets.add(new BezierTarget(trackIndex, tick));
        }

        // Sanity cap (pathological select-all plus sticky-drag situations)
        if (targets.size() > 1024) {
            targets.subList(1024, targets.size()).clear();
        }
        return targets;
    }

    /**
     * Prompt shown above the popup buttons - tells the user which easing is
     * currently attached to the targets ("None", a preset name or "Mixed").
     */
    public static String summarize(List<BezierTarget> targets, EditorScene editorScene) {
        BezierEasing first = null;
        boolean mixed = false;
        boolean any = false;

        for (BezierTarget target : targets) {
            Keyframe keyframe = resolve(editorScene, target);
            if (keyframe == null) {
                continue;
            }
            BezierEasing easing = BezierKeyframeData.get(keyframe);
            if (!any) {
                first = easing;
                any = true;
            } else {
                boolean differs = (easing == null) != (first == null)
                    || (easing != null && easing.difference(first) != 0);
                if (differs) {
                    mixed = true;
                }
            }
        }

        if (!any) {
            return I18n.get("flashbackbezier.popup.current_none");
        }
        if (mixed) {
            return I18n.get("flashbackbezier.popup.current_mixed");
        }
        if (first == null) {
            return I18n.get("flashbackbezier.popup.current_none");
        }
        return BezierPreset.byId(first.preset).text();
    }

    private static @Nullable Keyframe resolve(EditorScene editorScene, BezierTarget target) {
        if (target.trackIndex() < 0 || target.trackIndex() >= editorScene.keyframeTracks.size()) {
            return null;
        }
        KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
        return track.keyframesByTick.get(target.tick());
    }

    /**
     * Renders the popup section. Runs under Flashback's read lock.
     */
    public static void renderPopupSection(EditorState editorState, EditorScene editorScene,
                                          Keyframe editingKeyframe, int editingKeyframeTick,
                                          int editingTrackIndex, KeyframeTrack editingTrack,
                                          List<SelectedKeyframes> selectedKeyframesList) {
        List<BezierTarget> targets = collectTargets(editorScene, editingTrackIndex, editingKeyframeTick,
            selectedKeyframesList);
        if (targets.isEmpty()) {
            return;
        }
        boolean multi = targets.size() > 1;

        ImGuiHelper.separatorWithText(I18n.get("flashbackbezier.popup.section"));
        ImGui.text(I18n.get("flashbackbezier.popup.current",
            summarize(targets, editorScene)));

        String editLabel = I18n.get("flashbackbezier.popup.open");
        String clearLabel = I18n.get("flashbackbezier.popup.clear");
        float buttonWidth = Math.max(
            ImGuiHelper.calcTextWidth(editLabel),
            ImGuiHelper.calcTextWidth(clearLabel)
        ) + ReplayUI.scaleUi(16);

        if (ImGui.button(editLabel, buttonWidth, ReplayUI.scaleUi(18))) {
            BezierEditorWindow.open(targets, multi);
        }
        ImGui.sameLine();
        if (ImGui.button(clearLabel, buttonWidth, ReplayUI.scaleUi(18))) {
            clearEasing(editorState, editorScene, targets);
        }

        // ---- range easing section ----
        renderRangeSection(editorState, editorScene, targets, editingTrackIndex, editingKeyframeTick,
            selectedKeyframesList, multi);
    }

    /**
     * The "Range Easing" section: one easing curve applied across a whole span
     * of keyframes. Creating a range gives EVERY target keyframe its own range
     * (each spanning to its own end tick) sharing one draft easing, so a
     * multi-selection applies the same pacing to a batch of segments.
     */
    private static void renderRangeSection(EditorState editorState, EditorScene editorScene,
                                           List<BezierTarget> targets, int editingTrackIndex, int editingKeyframeTick,
                                           List<SelectedKeyframes> selectedKeyframesList, boolean multi) {
        Keyframe editingKeyframe = resolve(editorScene, new BezierTarget(editingTrackIndex, editingKeyframeTick));
        if (editingKeyframe == null) {
            return;
        }
        BezierRange existing = BezierKeyframeData.getRange(editingKeyframe);

        ImGuiHelper.separatorWithText(I18n.get("flashbackbezier.popup.range_section"));
        if (existing != null) {
            ImGui.text(I18n.get("flashbackbezier.popup.range_info", existing.endTick,
                BezierPreset.byId(existing.easing.preset).text()));

            String editLabel = I18n.get("flashbackbezier.popup.range_edit");
            String removeLabel = I18n.get("flashbackbezier.popup.range_remove");
            float buttonWidth = Math.max(
                ImGuiHelper.calcTextWidth(editLabel),
                ImGuiHelper.calcTextWidth(removeLabel)
            ) + ReplayUI.scaleUi(16);

            if (ImGui.button(editLabel, buttonWidth, ReplayUI.scaleUi(18))) {
                BezierEditorWindow.openRange(targets, collectExistingEndTicks(editorScene, targets), multi);
            }
            ImGui.sameLine();
            if (ImGui.button(removeLabel, buttonWidth, ReplayUI.scaleUi(18))) {
                clearRange(editorState, editorScene, targets);
            }
        } else {
            Map<BezierTarget, Integer> endTicks = collectNewEndTicks(editorScene, targets, selectedKeyframesList);
            if (endTicks.isEmpty()) {
                ImGui.text(I18n.get("flashbackbezier.popup.range_unavailable"));
                return;
            }
            if (ImGui.button(I18n.get("flashbackbezier.popup.range_create"), ReplayUI.scaleUi(120), ReplayUI.scaleUi(18))) {
                BezierEditorWindow.openRange(targets, endTicks, multi);
            }
        }
    }

    /** End ticks for EXISTING ranges on the targets (used by "Edit Range"). */
    private static Map<BezierTarget, Integer> collectExistingEndTicks(EditorScene editorScene, List<BezierTarget> targets) {
        Map<BezierTarget, Integer> endTicks = new HashMap<>();
        for (BezierTarget target : targets) {
            Keyframe keyframe = resolve(editorScene, target);
            if (keyframe == null) {
                continue;
            }
            BezierRange range = BezierKeyframeData.getRange(keyframe);
            if (range != null) {
                endTicks.put(target, range.endTick);
            } else {
                // No range yet: cover the segment to the next keyframe
                endTicks.put(target, nextKeyframeTick(editorScene, target.trackIndex(), target.tick()));
            }
        }
        return endTicks;
    }

    /**
     * End ticks for NEW ranges: the last selected tick on the same track when
     * the selection covers it, otherwise the track's next keyframe tick.
     * Targets without a valid end tick are skipped.
     */
    private static Map<BezierTarget, Integer> collectNewEndTicks(EditorScene editorScene, List<BezierTarget> targets,
                                                                 List<SelectedKeyframes> selectedKeyframesList) {
        Map<BezierTarget, Integer> endTicks = new HashMap<>();
        for (BezierTarget target : targets) {
            int endTick = lastSelectedTickAfter(selectedKeyframesList, target.trackIndex(), target.tick());
            if (endTick < 0) {
                endTick = nextKeyframeTick(editorScene, target.trackIndex(), target.tick());
            }
            if (endTick > target.tick()) {
                endTicks.put(target, endTick);
            }
        }
        return endTicks;
    }

    private static int lastSelectedTickAfter(List<SelectedKeyframes> selectedKeyframesList, int trackIndex, int startTick) {
        int best = -1;
        for (SelectedKeyframes selected : selectedKeyframesList) {
            if (selected.trackIndex() != trackIndex) {
                continue;
            }
            for (int tick : selected.keyframeTicks()) {
                if (tick > startTick && (best < 0 || tick < best)) {
                    best = tick;
                }
            }
        }
        return best;
    }

    private static int nextKeyframeTick(EditorScene editorScene, int trackIndex, int startTick) {
        if (trackIndex < 0 || trackIndex >= editorScene.keyframeTracks.size()) {
            return -1;
        }
        Map.Entry<Integer, com.moulberry.flashback.keyframe.Keyframe> next =
            editorScene.keyframeTracks.get(trackIndex).keyframesByTick.higherEntry(startTick);
        return next == null ? -1 : next.getKey();
    }

    /**
     * Removes the range easing from all targets and records it in the editor
     * undo history. Per-keyframe easings (if any) stay untouched.
     */
    public static void clearRange(EditorState editorState, EditorScene editorScene,
                                  List<BezierTarget> targets) {
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        for (BezierTarget target : targets) {
            Keyframe keyframe = resolve(editorScene, target);
            if (keyframe == null || BezierKeyframeData.getRange(keyframe) == null) {
                continue;
            }
            KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
            KeyframeType<?> type = track.keyframeType;
            undo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(),
                keyframe.copy()));

            BezierKeyframeData.setRange(keyframe, null);
            redo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(),
                keyframe.copy()));
        }

        if (undo.isEmpty()) {
            return;
        }
        editorScene.push(new EditorSceneHistoryEntry(undo, redo, I18n.get("flashbackbezier.info.range_cleared")));
        editorState.markDirty();
    }

    /**
     * Removes the easing from all targets and records it in the editor
     * undo history.
     */
    public static void clearEasing(EditorState editorState, EditorScene editorScene,
                                   List<BezierTarget> targets) {
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        for (BezierTarget target : targets) {
            Keyframe keyframe = resolve(editorScene, target);
            if (keyframe == null || BezierKeyframeData.get(keyframe) == null) {
                continue;
            }
            KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
            KeyframeType<?> type = track.keyframeType;
            undo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(),
                keyframe.copy()));

            BezierKeyframeData.set(keyframe, null);
            redo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(),
                keyframe.copy()));
        }

        if (undo.isEmpty()) {
            return;
        }
        editorScene.push(new EditorSceneHistoryEntry(undo, redo, I18n.get("flashbackbezier.info.cleared")));
        editorState.markDirty();
    }
}
