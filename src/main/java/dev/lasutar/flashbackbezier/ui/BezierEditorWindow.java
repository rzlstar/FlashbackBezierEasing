package dev.lasutar.flashbackbezier.ui;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierPreset;
import dev.lasutar.flashbackbezier.integration.BezierKeyframeData;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImBoolean;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * After-Effects-style cubic bezier editor window, drawn inside Flashback's
 * ImGui overlay every frame while it is open.
 *
 * <p>Layout:</p>
 * <ul>
 *   <li>a graph area showing the easing curve with two draggable handles
 *       P1 / P2 (the end points are locked at (0,0) and (1,1)),</li>
 *   <li>a preset dropdown that snaps the handles back to a known curve,</li>
 *   <li>numeric input fields for exact handle coordinates,</li>
 *   <li>Reset (back to what was there when the window opened),
 *       Apply (commit + undo history entry) and Cancel (discard draft).</li>
 * </ul>
 *
 * <p>While the window is open the draft easing is written straight onto the
 * target keyframes for live preview; Apply snapshots that state into the
 * normal Flashback undo history, Cancel restores the saved copies.</p>
 */
public final class BezierEditorWindow {

    private BezierEditorWindow() {
    }

    private static boolean open = false;
    private static boolean multi = false;
    private static final List<BezierTarget> targets = new ArrayList<>();
    /** Editable keyframe copies taken when the window opened (Reset / Cancel / undo). */
    private static final Map<BezierTarget, Keyframe> originals = new HashMap<>();
    private static boolean initialized = false;

    /** The live draft easing. {@link #writeDraft} keeps the numeric input arrays in sync. */
    private static final BezierEasing draft = new BezierEasing(0.25f, 0.1f, 0.25f, 1.0f, "ease");
    private static BezierPreset comboSelection = BezierPreset.EASE;

    /** Numeric input buffers, kept mirroring {@link #draft} by {@link #writeDraft}. */
    private static final float[] p1xArray = new float[]{0.25f};
    private static final float[] p1yArray = new float[]{0.1f};
    private static final float[] p2xArray = new float[]{0.25f};
    private static final float[] p2yArray = new float[]{1.0f};

    /** 0 = not dragging, 1 = P1, 2 = P2. */
    private static int grabbedHandle = 0;

    public static void open(List<BezierTarget> newTargets, boolean newMulti) {
        targets.clear();
        targets.addAll(newTargets);
        multi = newMulti;
        originals.clear();
        initialized = false;
        open = true;
    }

    public static void close() {
        open = false;
        targets.clear();
        originals.clear();
    }

    private static @Nullable Keyframe resolve(EditorScene editorScene, BezierTarget target) {
        if (target.trackIndex() < 0 || target.trackIndex() >= editorScene.keyframeTracks.size()) {
            return null;
        }
        KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
        return track.keyframesByTick.get(target.tick());
    }

    /**
     * Per-frame entry point, hooked right before Flashback's ImGui frame ends.
     */
    public static void render() {
        if (!open) {
            return;
        }

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            close();
            return;
        }

        // ---- resolve live targets (read lock) & check they still exist ----
        List<BezierTarget> validTargets = new ArrayList<>();
        long readStamp = editorState.acquireRead();
        try {
            EditorScene editorScene = editorState.getCurrentScene(readStamp);
            if (editorScene == null) {
                close();
                return;
            }
            for (BezierTarget target : targets) {
                if (resolve(editorScene, target) != null) {
                    validTargets.add(target);
                }
            }
        } finally {
            editorState.release(readStamp);
        }
        if (validTargets.isEmpty()) {
            close();
            return;
        }

        if (!initialized) {
            initializeDraft();
            initialized = true;
        }

        ImGui.setNextWindowSize(ReplayUI.scaleUi(380), ReplayUI.scaleUi(500), ImGuiCond.FirstUseEver);
        ImBoolean openFlag = new ImBoolean(true);
        boolean visible = ImGui.begin(I18n.get("flashbackbezier.editor.title"), openFlag,
            ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking);
        if (!visible) {
            // Dear ImGui rule: End() must always be called after Begin().
            // The window is clipped/collapsed this frame; only close when the
            // user explicitly pressed the window's X button (discard draft).
            ImGui.end();
            if (!openFlag.get()) {
                cancelAndClose();
            }
            return;
        }

        ImGui.textWrapped(I18n.get(multi ? "flashbackbezier.editor.applies_to_multi" : "flashbackbezier.editor.applies_to"));

        // ---- preset selector (writes back to draft) ----
        BezierPreset selected = ImGuiHelper.enumCombo(I18n.get("flashbackbezier.editor.preset"),
            comboSelection, BezierPreset.values());
        if (selected != comboSelection) {
            writeFromPreset(selected);
            applyDraft(validTargets);
        }

        // ---- graph area ----
        drawGraph(validTargets);

        // ---- numeric inputs ----
        boolean numericChanged = false;
        numericChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle1", true), p1xArray);
        ImGui.sameLine();
        numericChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle1", false), p1yArray);
        numericChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle2", true), p2xArray);
        ImGui.sameLine();
        numericChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle2", false), p2yArray);
        if (numericChanged) {
            writeDraft(p1xArray[0], p1yArray[0], p2xArray[0], p2yArray[0], false);
            applyDraft(validTargets);
        }

        // ---- Reset / Apply / Cancel ----
        boolean requestedApply = false;
        boolean requestedCancel = false;
        if (ImGui.button(I18n.get("flashbackbezier.editor.reset"), ReplayUI.scaleUi(80), 0f)) {
            resetDraft(validTargets);
        }
        ImGui.sameLine();
        if (ImGui.button(I18n.get("flashbackbezier.editor.apply"), ReplayUI.scaleUi(80), 0f)) {
            requestedApply = true;
        }
        ImGui.sameLine();
        if (ImGui.button(I18n.get("flashbackbezier.editor.cancel"), ReplayUI.scaleUi(80), 0f)) {
            requestedCancel = true;
        }

        // Always finish the window first - no early returns between begin/end.
        ImGui.end();
        if (!openFlag.get()) {
            // window X button pressed - treat like Cancel (discard draft)
            cancelAndClose();
            return;
        }

        if (requestedCancel) {
            cancelAndClose();
        } else if (requestedApply) {
            applyAndClose();
        }
    }

    private static String valueLabel(String handleKey, boolean isX) {
        return I18n.get(isX ? "flashbackbezier.editor.value_x" : "flashbackbezier.editor.value_y",
            I18n.get(handleKey));
    }

    /**
     * Sets the draft easing (clamped) and mirrors the clamped values into the
     * numeric input arrays so both stay consistent.
     */
    private static void writeDraft(float p1x, float p1y, float p2x, float p2y, boolean markCustom) {
        draft.set(p1x, p1y, p2x, p2y);
        if (markCustom) {
            draft.markCustom();
        }
        p1xArray[0] = draft.p1x;
        p1yArray[0] = draft.p1y;
        p2xArray[0] = draft.p2x;
        p2yArray[0] = draft.p2y;
        comboSelection = draft.detectPreset();
    }

    private static void writeFromPreset(BezierPreset preset) {
        preset.applyTo(draft);
        p1xArray[0] = draft.p1x;
        p1yArray[0] = draft.p1y;
        p2xArray[0] = draft.p2x;
        p2yArray[0] = draft.p2y;
        comboSelection = preset;
    }

    /**
     * Loads the eased state of the first target that has one; otherwise falls
     * back to the "Ease" preset. Saves editable copies of every target.
     */
    private static void initializeDraft() {
        originals.clear();
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            return;
        }
        BezierEasing found = null;
        long stamp = editorState.acquireRead();
        try {
            EditorScene editorScene = editorState.getCurrentScene(stamp);
            if (editorScene == null) {
                return;
            }
            for (BezierTarget target : targets) {
                Keyframe keyframe = resolve(editorScene, target);
                if (keyframe == null) {
                    continue;
                }
                originals.put(target, keyframe.copy());
                if (found == null) {
                    found = BezierKeyframeData.get(keyframe);
                }
            }
        } finally {
            editorState.release(stamp);
        }

        if (found != null) {
            writeDraft(found.p1x, found.p1y, found.p2x, found.p2y, false);
            draft.preset = found.preset;
        } else {
            writeFromPreset(BezierPreset.EASE);
        }
    }

    private static void resetDraft(List<BezierTarget> validTargets) {
        BezierEasing found = null;
        for (BezierTarget target : validTargets) {
            Keyframe original = originals.get(target);
            if (original == null) {
                continue;
            }
            BezierEasing easing = BezierKeyframeData.get(original);
            if (easing != null) {
                found = easing;
                break;
            }
        }
        if (found != null) {
            writeDraft(found.p1x, found.p1y, found.p2x, found.p2y, false);
            draft.preset = found.preset;
        } else {
            writeFromPreset(BezierPreset.EASE);
        }
        applyDraft(validTargets);
    }

    /**
     * Writes the draft easing onto every currently valid target keyframe
     * (write lock; called during interaction, so it is cheap and released
     * immediately).
     */
    private static void applyDraft(List<BezierTarget> validTargets) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            close();
            return;
        }
        long stamp = editorState.acquireWrite();
        try {
            EditorScene editorScene = editorState.getCurrentScene(stamp);
            if (editorScene == null) {
                return;
            }
            for (BezierTarget target : validTargets) {
                Keyframe keyframe = resolve(editorScene, target);
                if (keyframe != null) {
                    BezierKeyframeData.set(keyframe, new BezierEasing(draft));
                }
            }
        } finally {
            editorState.release(stamp);
        }
        editorState.markDirty();
    }

    /**
     * Commits the current preview state: pushes a normal Flashback history
     * entry whose undo restores the saved original keyframes, then closes.
     */
    private static void applyAndClose() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            close();
            return;
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        boolean appliedAny = false;

        long stamp = editorState.acquireWrite();
        try {
            EditorScene editorScene = editorState.getCurrentScene(stamp);
            if (editorScene == null) {
                close();
                return;
            }
            for (BezierTarget target : targets) {
                if (target.trackIndex() < 0 || target.trackIndex() >= editorScene.keyframeTracks.size()) {
                    continue;
                }
                KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
                Keyframe live = track.keyframesByTick.get(target.tick());
                Keyframe original = originals.get(target);
                if (live == null || original == null) {
                    continue;
                }
                KeyframeType<?> type = track.keyframeType;
                undo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(), original));
                redo.add(new EditorSceneHistoryAction.SetKeyframe(type, target.trackIndex(), target.tick(), live.copy()));
                appliedAny = true;
            }
            if (!appliedAny) {
                return;
            }
            editorScene.push(new EditorSceneHistoryEntry(undo, redo, I18n.get("flashbackbezier.editor.history")));
        } finally {
            editorState.release(stamp);
        }
        editorState.markDirty();
        close();
    }

    /**
     * Discards the draft: restores the saved original keyframe copies without
     * touching the undo history (the draft was never committed).
     */
    private static void cancelAndClose() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            close();
            return;
        }
        long stamp = editorState.acquireWrite();
        try {
            EditorScene editorScene = editorState.getCurrentScene(stamp);
            if (editorScene == null) {
                return;
            }
            for (Map.Entry<BezierTarget, Keyframe> entry : originals.entrySet()) {
                BezierTarget target = entry.getKey();
                Keyframe original = entry.getValue();
                if (resolve(editorScene, target) == null) {
                    continue;
                }
                editorScene.setKeyframe(target.trackIndex(), target.tick(), original.copy());
            }
        } finally {
            editorState.release(stamp);
        }
        editorState.markDirty();
        close();
    }

    // ------------------------------------------------------------------
    // Graph drawing & interaction
    // ------------------------------------------------------------------

    /** Default display range of the progress (Y) axis; widened for Back-style curves. */
    private static final float Y_LOW_DEFAULT = -0.5f;
    private static final float Y_HIGH_DEFAULT = 1.5f;
    private static final float HANDLE_RADIUS_UI = 6.0f;
    private static final float HANDLE_PICKUP_RADIUS_UI = 14.0f;

    private interface YMapper {
        float map(float value);
    }

    private static void drawGraph(List<BezierTarget> validTargets) {
        int graphSide = ReplayUI.scaleUi(230);
        ImGui.beginChild("fbz_graph", graphSide, graphSide, 0);
        try {
            float x0 = ImGui.getCursorScreenPosX();
            float y0 = ImGui.getCursorScreenPosY();

            // Expand the vertical display range when a handle leaves the default window
            float yLow = Math.min(Y_LOW_DEFAULT, Math.min(draft.p1y, draft.p2y) - 0.25f);
            float yHigh = Math.max(Y_HIGH_DEFAULT, Math.max(draft.p1y, draft.p2y) + 0.25f);
            float ySpan = yHigh - yLow;
            YMapper mapY = value -> y0 + graphSide - (value - yLow) / ySpan * graphSide;
            YMapper mapX = value -> x0 + value * graphSide;

            ImDrawList drawList = ImGui.getWindowDrawList();

            drawList.addRectFilled(x0, y0, x0 + graphSide, y0 + graphSide, 0xFF1E1E1E);

            // grid (x in quarters, y at 0, 0.5, 1, 1.5)
            int gridColour = 0x30FFFFFF;
            for (int i = 0; i <= 4; i++) {
                float gx = mapX.map(i / 4.0f);
                drawList.addLine(gx, y0, gx, y0 + graphSide, gridColour);
            }
            for (float gy = 0.0f; gy <= 1.5001f; gy += 0.5f) {
                float sy = mapY.map(gy);
                drawList.addLine(x0, sy, x0 + graphSide, sy, gridColour);
            }
            // axes: the value axes the handles are measured against
            int axisColour = 0x90000000;
            drawList.addLine(x0, mapY.map(0.0f), x0 + graphSide, mapY.map(0.0f), axisColour);
            drawList.addLine(mapX.map(0.0f), y0, mapX.map(0.0f), y0 + graphSide, axisColour);

            int curveColour = ImGui.isMouseHoveringRect(x0, y0, x0 + graphSide, y0 + graphSide, false)
                ? 0xFF5AB2FF : 0xFF3A9AE0;
            int dimCurve = (curveColour & 0x00FFFFFF) | 0x60000000;

            // tangent lines: (0,0) -> P1 and (1,1) -> P2
            drawList.addLine(mapX.map(0.0f), mapY.map(0.0f), mapX.map(draft.p1x), mapY.map(draft.p1y),
                dimCurve, 1.0f);
            drawList.addLine(mapX.map(1.0f), mapY.map(1.0f), mapX.map(draft.p2x), mapY.map(draft.p2y),
                dimCurve, 1.0f);

            // the curve itself, drawn in bezier parameter space
            float cX = 3.0f * draft.p1x;
            float bX = 3.0f * (draft.p2x - draft.p1x) - cX;
            float aX = 1.0f - cX - bX;
            float cY = 3.0f * draft.p1y;
            float bY = 3.0f * (draft.p2y - draft.p1y) - cY;
            float aY = 1.0f - cY - bY;

            float lastX = mapX.map(0.0f);
            float lastY = mapY.map(0.0f);
            for (int i = 1; i <= 64; i++) {
                float t = i / 64.0f;
                float px = ((aX * t + bX) * t + cX) * t;
                float py = ((aY * t + bY) * t + cY) * t;
                float sx = mapX.map(px);
                float sy = mapY.map(py);
                drawList.addLine(lastX, lastY, sx, sy, curveColour, ReplayUI.scaleUi(2));
                lastX = sx;
                lastY = sy;
            }

            // fixed endpoints
            float endpointHalf = ReplayUI.scaleUi(4);
            drawList.addRectFilled(mapX.map(0.0f) - endpointHalf, mapY.map(0.0f) - endpointHalf,
                mapX.map(0.0f) + endpointHalf, mapY.map(0.0f) + endpointHalf, 0xFFAAAAAA);
            drawList.addRectFilled(mapX.map(1.0f) - endpointHalf, mapY.map(1.0f) - endpointHalf,
                mapX.map(1.0f) + endpointHalf, mapY.map(1.0f) + endpointHalf, 0xFFAAAAAA);

            // draggable handles
            float h1x = mapX.map(draft.p1x);
            float h1y = mapY.map(draft.p1y);
            float h2x = mapX.map(draft.p2x);
            float h2y = mapY.map(draft.p2y);
            float handleRadius = ReplayUI.scaleUi((int) HANDLE_RADIUS_UI);
            drawList.addCircleFilled(h1x, h1y, handleRadius, grabbedHandle == 1 ? 0xFFFFA000 : 0xFF30C0FF);
            drawList.addCircleFilled(h2x, h2y, handleRadius, grabbedHandle == 2 ? 0xFFFFA000 : 0xFF30C0FF);

            // ---- interaction ----
            ImGui.invisibleButton("fbz_graph_area", graphSide, graphSide);
            if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
                float pickRadius = ReplayUI.scaleUi((int) HANDLE_PICKUP_RADIUS_UI);
                float d1 = distanceSquared(ImGui.getMousePosX(), ImGui.getMousePosY(), h1x, h1y);
                float d2 = distanceSquared(ImGui.getMousePosX(), ImGui.getMousePosY(), h2x, h2y);
                if (Math.min(d1, d2) <= pickRadius * pickRadius) {
                    grabbedHandle = d1 <= d2 ? 1 : 2;
                }
            }
            if (grabbedHandle != 0) {
                if (!ImGui.isMouseDown(0)) {
                    grabbedHandle = 0;
                } else {
                    float nx = BezierEasing.clampX((ImGui.getMousePosX() - x0) / graphSide);
                    float ny = BezierEasing.clampY(yLow + (y0 + graphSide - ImGui.getMousePosY())
                        * ySpan / graphSide);
                    boolean changed = false;
                    if (grabbedHandle == 1) {
                        changed = draft.p1x != nx || draft.p1y != ny;
                        draft.p1x = nx;
                        draft.p1y = ny;
                    } else {
                        changed = draft.p2x != nx || draft.p2y != ny;
                        draft.p2x = nx;
                        draft.p2y = ny;
                    }
                    if (changed) {
                        writeDraft(draft.p1x, draft.p1y, draft.p2x, draft.p2y, true);
                        applyDraft(validTargets);
                    }
                }
            }

            if (ImGui.isItemHovered()) {
                ImGuiHelper.tooltip(I18n.get("flashbackbezier.editor.graph_tooltip"));
            }
        } finally {
            ImGui.endChild();
        }
    }

    private static float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }
}
