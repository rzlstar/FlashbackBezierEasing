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
import dev.lasutar.flashbackbezier.FlashbackBezierMod;
import dev.lasutar.flashbackbezier.bezier.BezierAnchor;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.bezier.BezierPreset;
import dev.lasutar.flashbackbezier.bezier.BezierRange;
import dev.lasutar.flashbackbezier.integration.BezierKeyframeData;
import dev.lasutar.flashbackbezier.io.NamedPresetStore;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImBoolean;
import imgui.moulberry90.type.ImInt;
import imgui.moulberry90.type.ImString;
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
 *   <li>a graph area showing the easing curve - in classic mode with two
 *       draggable handles P1 / P2 (end points locked at (0,0) and (1,1)); in
 *       <b>curve mode</b> with any number of anchors, draggable anchor
 *       positions and per-anchor handles (double-click: add / delete),</li>
 *   <li>a preset dropdown that snaps the curve back to a known shape,
 *       plus user-created <b>named presets</b> (save / overwrite / delete),
 *       which persist across restarts with their actual parameters,</li>
 *   <li>numeric input fields for exact handle / anchor coordinates,</li>
 *   <li>Reset (back to what was there when the window opened),
 *       Apply (commit + undo history entry) and Cancel (discard draft).</li>
 * </ul>
 *
 * <p><b>Range mode:</b> opened from the keyframe popup's "Create Range"
 * action. The draft easing is attached to every target keyframe as a
 * {@link BezierRange} (one easing across a whole span of keyframes); each
 * target keeps its own end tick (per-target span in batch mode).</p>
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
    /** True when the draft is applied as a range easing instead of per-segment. */
    private static boolean rangeMode = false;
    private static final List<BezierTarget> targets = new ArrayList<>();
    /** Editable keyframe copies taken when the window opened (Reset / Cancel / undo). */
    private static final Map<BezierTarget, Keyframe> originals = new HashMap<>();
    /** Range mode: end tick of the range starting at each target. */
    private static final Map<BezierTarget, Integer> endTicks = new HashMap<>();
    private static boolean initialized = false;

    /** The live draft easing. {@link #syncClassicArrays} keeps the numeric inputs in sync. */
    private static final BezierEasing draft = new BezierEasing(0.25f, 0.1f, 0.25f, 1.0f, "ease");
    private static BezierPreset comboSelection = BezierPreset.EASE;

    /** Numeric input buffers, kept mirroring {@link #draft} by {@link #syncClassicArrays}. */
    private static final float[] p1xArray = new float[]{0.25f};
    private static final float[] p1yArray = new float[]{0.1f};
    private static final float[] p2xArray = new float[]{0.25f};
    private static final float[] p2yArray = new float[]{1.0f};

    // Curve mode state --------------------------------------------------

    /** 0 = not dragging, 1 = P1, 2 = P2 (classic),
     *  100+i = anchor i, 1000+i = outgoing handle of anchor i, 2000+i = incoming handle of anchor i. */
    private static int grabbedHandle = 0;
    private static int selectedAnchor = -1;
    private static final float[] anchorXArray = new float[]{0.5f};
    private static final float[] anchorYArray = new float[]{0.5f};

    // Named preset state ------------------------------------------------

    private static int namedPresetIndex = -1;
    private static final ImString presetNameBuffer = ImGuiHelper.createResizableImString("");
    private static final ImInt spanInput = new ImInt(40);

    // Open / close ------------------------------------------------------

    public static void open(List<BezierTarget> newTargets, boolean newMulti) {
        openInternal(newTargets, newMulti, false);
        endTicks.clear();
    }

    /**
     * Opens the window in range mode. {@code newEndTicks} supplies the initial
     * end tick for every target (computed by the popup from the selection /
     * next keyframes).
     */
    public static void openRange(List<BezierTarget> newTargets, Map<BezierTarget, Integer> newEndTicks, boolean newMulti) {
        openInternal(newTargets, newMulti, true);
        endTicks.clear();
        endTicks.putAll(newEndTicks);
    }

    private static void openInternal(List<BezierTarget> newTargets, boolean newMulti, boolean newRangeMode) {
        targets.clear();
        targets.addAll(newTargets);
        multi = newMulti;
        rangeMode = newRangeMode;
        originals.clear();
        initialized = false;
        grabbedHandle = 0;
        open = true;
    }

    public static void close() {
        open = false;
        targets.clear();
        originals.clear();
        endTicks.clear();
    }

    private static @Nullable Keyframe resolve(EditorScene editorScene, BezierTarget target) {
        if (target.trackIndex() < 0 || target.trackIndex() >= editorScene.keyframeTracks.size()) {
            return null;
        }
        KeyframeTrack track = editorScene.keyframeTracks.get(target.trackIndex());
        return track.keyframesByTick.get(target.tick());
    }

    // ------------------------------------------------------------------
    // Per-frame render
    // ------------------------------------------------------------------

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

        ImGui.setNextWindowSize(ReplayUI.scaleUi(380), ReplayUI.scaleUi(560), ImGuiCond.FirstUseEver);
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

        if (rangeMode) {
            ImGui.textWrapped(I18n.get("flashbackbezier.editor.range_applies_to", rangeSpan(validTargets)));
            // ---- range span input ----
            spanInput.set(Math.max(1, rangeSpan(validTargets)));
            if (ImGui.inputInt(I18n.get("flashbackbezier.editor.range_span"), spanInput)) {
                int span = Math.max(1, spanInput.get());
                for (BezierTarget target : validTargets) {
                    endTicks.put(target, target.tick() + span);
                }
                applyDraft(validTargets);
            }
        } else {
            ImGui.textWrapped(I18n.get(multi ? "flashbackbezier.editor.applies_to_multi" : "flashbackbezier.editor.applies_to"));
        }

        // ---- preset selector (writes back to draft) ----
        BezierPreset selected = ImGuiHelper.enumCombo(I18n.get("flashbackbezier.editor.preset"),
            comboSelection, BezierPreset.values());
        if (selected != comboSelection) {
            writeFromPreset(selected);
            applyDraft(validTargets);
        }

        // ---- curve mode toggle ----
        if (draft.isCurveMode()) {
            if (ImGui.button(I18n.get("flashbackbezier.editor.curve_mode_exit"), ReplayUI.scaleUi(120), 0f)) {
                collapseToClassic();
                applyDraft(validTargets);
            }
        } else {
            if (ImGui.button(I18n.get("flashbackbezier.editor.curve_mode_enter"), ReplayUI.scaleUi(120), 0f)) {
                enterCurveMode();
                applyDraft(validTargets);
            }
        }
        ImGui.sameLine();

        // ---- named presets ----
        renderNamedPresets(validTargets);

        // ---- graph area ----
        drawGraph(validTargets);

        // ---- numeric inputs ----
        if (draft.isCurveMode()) {
            renderAnchorNumeric(validTargets);
        } else {
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

    // ------------------------------------------------------------------
    // Draft handling
    // ------------------------------------------------------------------

    private static String valueLabel(String handleKey, boolean isX) {
        return I18n.get(isX ? "flashbackbezier.editor.value_x" : "flashbackbezier.editor.value_y",
            I18n.get(handleKey));
    }

    /** Smallest span across the valid targets (range mode display value). */
    private static int rangeSpan(List<BezierTarget> validTargets) {
        int span = Integer.MAX_VALUE;
        for (BezierTarget target : validTargets) {
            Integer end = endTicks.get(target);
            if (end != null) {
                span = Math.min(span, end - target.tick());
            }
        }
        return span == Integer.MAX_VALUE ? 1 : Math.max(1, span);
    }

    /**
     * Sets the draft easing (clamped, classic mode) and mirrors the clamped
     * values into the numeric input arrays so both stay consistent.
     */
    private static void writeDraft(float p1x, float p1y, float p2x, float p2y, boolean markCustom) {
        draft.setAnchors(null); // exit curve mode
        draft.set(p1x, p1y, p2x, p2y);
        if (markCustom) {
            draft.markCustom();
        }
        syncClassicArrays();
    }

    private static void writeFromPreset(BezierPreset preset) {
        preset.applyTo(draft);
        draft.anchors = null; // builtin presets always exit curve mode
        syncClassicArrays();
        comboSelection = preset;
    }

    private static void syncClassicArrays() {
        p1xArray[0] = draft.p1x;
        p1yArray[0] = draft.p1y;
        p2xArray[0] = draft.p2x;
        p2yArray[0] = draft.p2y;
        comboSelection = draft.detectPreset();
    }

    /** Enters multi-point curve mode, seeded from the current classic curve. */
    private static void enterCurveMode() {
        List<BezierAnchor> anchors = new ArrayList<>();
        anchors.add(new BezierAnchor(0.0f, 0.0f));
        anchors.add(new BezierAnchor(0.5f, (float) draft.apply(0.5)));
        anchors.add(new BezierAnchor(1.0f, 1.0f));
        draft.setAnchors(anchors);
        draft.markCustom();
        selectedAnchor = 1;
        syncAnchorArrays();
    }

    /** Exits curve mode, keeping the first segment's shape as the classic curve. */
    private static void collapseToClassic() {
        List<BezierAnchor> anchors = draft.anchors;
        if (anchors != null && anchors.size() >= 2) {
            float[] first = BezierEasing.segmentHandles(anchors.getFirst(), anchors.get(1));
            draft.set(first[0], first[1], first[2], first[3]);
        }
        draft.anchors = null;
        draft.markCustom();
        syncClassicArrays();
    }

    /**
     * Re-normalizes the draft's anchor list in place (drag editing mutates the
     * anchors directly) and refreshes the editor state.
     */
    private static void commitCurve(List<BezierTarget> validTargets) {
        List<BezierAnchor> anchors = draft.anchors;
        if (anchors != null) {
            draft.setAnchors(anchors);
        }
        draft.markCustom();
        clampSelectedAnchor();
        syncAnchorArrays();
        applyDraft(validTargets);
    }

    private static void clampSelectedAnchor() {
        List<BezierAnchor> anchors = draft.anchors;
        if (anchors == null) {
            selectedAnchor = -1;
        } else if (selectedAnchor < 0 || selectedAnchor >= anchors.size()) {
            selectedAnchor = Math.min(Math.max(0, selectedAnchor), anchors.size() - 1);
        }
    }

    private static void syncAnchorArrays() {
        List<BezierAnchor> anchors = draft.anchors;
        if (anchors != null && selectedAnchor >= 0 && selectedAnchor < anchors.size()) {
            BezierAnchor anchor = anchors.get(selectedAnchor);
            anchorXArray[0] = anchor.x;
            anchorYArray[0] = anchor.y;
        }
        comboSelection = draft.detectPreset();
    }

    /**
     * Loads the draft from an existing easing (classic or curve mode).
     */
    private static void loadDraftFrom(BezierEasing found) {
        if (found.isCurveMode()) {
            draft.setAnchors(found.anchors);
            draft.preset = found.preset;
            selectedAnchor = Math.min(1, draft.anchors.size() - 1);
            syncAnchorArrays();
        } else {
            writeDraft(found.p1x, found.p1y, found.p2x, found.p2y, false);
            draft.preset = found.preset;
        }
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
                    if (rangeMode) {
                        BezierRange range = BezierKeyframeData.getRange(keyframe);
                        found = range == null ? null : range.easing;
                        if (range != null && !endTicks.containsKey(target)) {
                            endTicks.put(target, range.endTick);
                        }
                    } else {
                        found = BezierKeyframeData.get(keyframe);
                    }
                }
            }
        } finally {
            editorState.release(stamp);
        }

        if (endTicks.isEmpty()) {
            // Fallback: one span per target covering the segment to the next keyframe
            for (BezierTarget target : targets) {
                endTicks.put(target, target.tick() + Math.max(1, spanInput.get()));
            }
        }

        if (found != null) {
            loadDraftFrom(found);
        } else {
            writeFromPreset(BezierPreset.EASE);
        }
        spanInput.set(rangeSpan(validTargetsOrEmpty()));
    }

    private static List<BezierTarget> validTargetsOrEmpty() {
        return targets;
    }

    private static void resetDraft(List<BezierTarget> validTargets) {
        BezierEasing found = null;
        for (BezierTarget target : validTargets) {
            Keyframe original = originals.get(target);
            if (original == null) {
                continue;
            }
            BezierEasing easing = rangeMode
                ? (BezierKeyframeData.getRange(original) == null ? null : BezierKeyframeData.getRange(original).easing)
                : BezierKeyframeData.get(original);
            if (easing != null) {
                found = easing;
                break;
            }
        }
        if (found != null) {
            loadDraftFrom(found);
        } else {
            writeFromPreset(BezierPreset.EASE);
        }
        applyDraft(validTargets);
    }

    /**
     * Writes the draft easing onto every currently valid target keyframe
     * (write lock; called during interaction, so it is cheap and released
     * immediately). In range mode the draft is attached as a
     * {@link BezierRange} with each target's own end tick.
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
                if (keyframe == null) {
                    continue;
                }
                if (rangeMode) {
                    Integer end = endTicks.get(target);
                    int endTick = end == null ? target.tick() + spanInput.get() : end;
                    BezierKeyframeData.setRange(keyframe, new BezierRange(endTick, new BezierEasing(draft)));
                } else {
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
            String historyKey = rangeMode ? "flashbackbezier.editor.history_range" : "flashbackbezier.editor.history";
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
            editorScene.push(new EditorSceneHistoryEntry(undo, redo, I18n.get(historyKey)));
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
    // Named presets
    // ------------------------------------------------------------------

    private static void renderNamedPresets(List<BezierTarget> validTargets) {
        NamedPresetStore store = FlashbackBezierMod.namedPresets();
        if (store == null) {
            return;
        }
        List<String> names = store.names();
        if (!names.isEmpty()) {
            String[] items = names.toArray(new String[0]);
            int[] current = new int[]{Math.min(Math.max(0, namedPresetIndex), items.length - 1)};
            if (ImGuiHelper.combo(I18n.get("flashbackbezier.editor.named_presets"), current, items)) {
                namedPresetIndex = current[0];
                BezierEasing loaded = store.get(items[namedPresetIndex]);
                if (loaded != null) {
                    loadDraftFrom(loaded);
                    applyDraft(validTargets);
                }
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("flashbackbezier.editor.named_delete"), ReplayUI.scaleUi(60), 0f)
                && namedPresetIndex >= 0 && namedPresetIndex < names.size()) {
                store.delete(names.get(namedPresetIndex));
                namedPresetIndex = -1;
            }
        } else {
            namedPresetIndex = -1;
            ImGui.textDisabled(I18n.get("flashbackbezier.editor.named_none"));
        }

        // Save / overwrite row
        ImGui.inputText(I18n.get("flashbackbezier.editor.named_name"), presetNameBuffer);
        String name = presetNameBuffer.get();
        boolean exists = !name.isBlank() && store.exists(name);
        String saveLabel = I18n.get(exists ? "flashbackbezier.editor.named_overwrite" : "flashbackbezier.editor.named_save");
        if (!name.isBlank() && ImGui.button(saveLabel, ReplayUI.scaleUi(90), 0f)) {
            if (store.save(name, draft)) {
                List<String> refreshed = store.names();
                namedPresetIndex = refreshed.indexOf(name);
                presetNameBuffer.clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Anchor numeric section (curve mode)
    // ------------------------------------------------------------------

    private static void renderAnchorNumeric(List<BezierTarget> validTargets) {
        List<BezierAnchor> anchors = draft.anchors;
        if (anchors == null || anchors.isEmpty()) {
            return;
        }

        // Anchor selector combo
        String[] labels = new String[anchors.size()];
        for (int i = 0; i < anchors.size(); i++) {
            if (i == 0) {
                labels[i] = I18n.get("flashbackbezier.editor.anchor_start");
            } else if (i == anchors.size() - 1) {
                labels[i] = I18n.get("flashbackbezier.editor.anchor_end");
            } else {
                labels[i] = I18n.get("flashbackbezier.editor.anchor_n", i + 1);
            }
        }
        int[] current = new int[]{Math.min(Math.max(0, selectedAnchor), anchors.size() - 1)};
        if (ImGuiHelper.combo(I18n.get("flashbackbezier.editor.anchor"), current, labels)) {
            selectedAnchor = current[0];
            syncAnchorArrays();
        }

        clampSelectedAnchor();
        if (selectedAnchor < 0) {
            return;
        }
        BezierAnchor anchor = anchors.get(selectedAnchor);

        // Anchor position
        boolean anchorChanged = false;
        anchorChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.anchor", true), anchorXArray);
        ImGui.sameLine();
        anchorChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.anchor", false), anchorYArray);
        if (anchorChanged) {
            if (selectedAnchor > 0 && selectedAnchor < anchors.size() - 1) {
                anchor.x = BezierEasing.clampX(anchorXArray[0]);
                anchor.y = BezierEasing.clampY(anchorYArray[0]);
                commitCurve(validTargets);
            }
        }

        // Handles of the selected anchor
        if (anchor.hasIncomingHandle() && selectedAnchor > 0) {
            float[] inX = new float[]{anchor.hix};
            float[] inY = new float[]{anchor.hiy};
            boolean handleChanged = false;
            handleChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle_in", true), inX);
            ImGui.sameLine();
            handleChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle_in", false), inY);
            if (ImGui.button("x##fbz_in_" + selectedAnchor)) {
                anchor.hix = BezierAnchor.AUTO;
                anchor.hiy = BezierAnchor.AUTO;
                handleChanged = true;
            }
            if (handleChanged && selectedAnchor > 0) {
                float minX = anchors.get(selectedAnchor - 1).x;
                float maxX = anchor.x;
                anchor.hix = Math.max(minX, Math.min(maxX, inX[0]));
                anchor.hiy = BezierEasing.clampY(inY[0]);
                commitCurve(validTargets);
            }
        } else {
            ImGui.text(I18n.get("flashbackbezier.editor.handle_in_auto"));
        }
        if (anchor.hasOutgoingHandle() && selectedAnchor < anchors.size() - 1) {
            float[] outX = new float[]{anchor.hox};
            float[] outY = new float[]{anchor.hoy};
            boolean handleChanged = false;
            handleChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle_out", true), outX);
            ImGui.sameLine();
            handleChanged |= ImGuiHelper.inputFloat(valueLabel("flashbackbezier.editor.handle_out", false), outY);
            if (ImGui.button("x##fbz_out_" + selectedAnchor)) {
                anchor.hox = BezierAnchor.AUTO;
                anchor.hoy = BezierAnchor.AUTO;
                handleChanged = true;
            }
            if (handleChanged && selectedAnchor < anchors.size() - 1) {
                float minX = anchor.x;
                float maxX = anchors.get(selectedAnchor + 1).x;
                anchor.hox = Math.max(minX, Math.min(maxX, outX[0]));
                anchor.hoy = BezierEasing.clampY(outY[0]);
                commitCurve(validTargets);
            }
        } else {
            ImGui.text(I18n.get("flashbackbezier.editor.handle_out_auto"));
        }
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
        if (draft.isCurveMode()) {
            drawCurveGraph(validTargets);
        } else {
            drawClassicGraph(validTargets);
        }
    }

    private static void drawClassicGraph(List<BezierTarget> validTargets) {
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

    private static void drawCurveGraph(List<BezierTarget> validTargets) {
        int graphSide = ReplayUI.scaleUi(230);
        ImGui.beginChild("fbz_graph", graphSide, graphSide, 0);
        try {
            float x0 = ImGui.getCursorScreenPosX();
            float y0 = ImGui.getCursorScreenPosY();

            List<BezierAnchor> anchors = draft.anchors;
            if (anchors == null || anchors.size() < 2) {
                return;
            }

            // Expand the vertical display range over every anchor and handle
            float yLow = Y_LOW_DEFAULT;
            float yHigh = Y_HIGH_DEFAULT;
            for (BezierAnchor a : anchors) {
                yLow = Math.min(yLow, a.y - 0.25f);
                yHigh = Math.max(yHigh, a.y + 0.25f);
                if (a.hasIncomingHandle()) {
                    yLow = Math.min(yLow, a.hiy - 0.25f);
                    yHigh = Math.max(yHigh, a.hiy + 0.25f);
                }
                if (a.hasOutgoingHandle()) {
                    yLow = Math.min(yLow, a.hoy - 0.25f);
                    yHigh = Math.max(yHigh, a.hoy + 0.25f);
                }
            }
            float ySpan = yHigh - yLow;
            final float fyLow = yLow;
            final float fySpan = ySpan;
            YMapper mapY = value -> y0 + graphSide - (value - fyLow) / fySpan * graphSide;
            YMapper mapX = value -> x0 + value * graphSide;

            ImDrawList drawList = ImGui.getWindowDrawList();
            drawList.addRectFilled(x0, y0, x0 + graphSide, y0 + graphSide, 0xFF1E1E1E);

            int gridColour = 0x30FFFFFF;
            for (int i = 0; i <= 4; i++) {
                float gx = mapX.map(i / 4.0f);
                drawList.addLine(gx, y0, gx, y0 + graphSide, gridColour);
            }
            for (float gy = 0.0f; gy <= 1.5001f; gy += 0.5f) {
                float sy = mapY.map(gy);
                drawList.addLine(x0, sy, x0 + graphSide, sy, gridColour);
            }
            int axisColour = 0x90000000;
            drawList.addLine(x0, mapY.map(0.0f), x0 + graphSide, mapY.map(0.0f), axisColour);
            drawList.addLine(mapX.map(0.0f), y0, mapX.map(0.0f), y0 + graphSide, axisColour);

            int curveColour = ImGui.isMouseHoveringRect(x0, y0, x0 + graphSide, y0 + graphSide, false)
                ? 0xFF5AB2FF : 0xFF3A9AE0;
            int dimCurve = (curveColour & 0x00FFFFFF) | 0x60000000;

            // the full curve, sampled through the same solver used at playback
            float lastX = mapX.map(0.0f);
            float lastY = mapY.map((float) draft.apply(0.0));
            for (int i = 1; i <= 96; i++) {
                double x = i / 96.0;
                float sx = mapX.map((float) x);
                float sy = mapY.map((float) draft.apply(x));
                drawList.addLine(lastX, lastY, sx, sy, curveColour, ReplayUI.scaleUi(2));
                lastX = sx;
                lastY = sy;
            }

            float handleRadius = ReplayUI.scaleUi((int) HANDLE_RADIUS_UI);
            float anchorHalf = ReplayUI.scaleUi(4);
            float mx = ImGui.getMousePosX();
            float my = ImGui.getMousePosY();

            // handles (drawn under the anchors): explicit handles only
            for (int i = 0; i < anchors.size(); i++) {
                BezierAnchor anchor = anchors.get(i);
                if (i > 0 && anchor.hasIncomingHandle()) {
                    float hx = mapX.map(anchor.hix);
                    float hy = mapY.map(anchor.hiy);
                    drawList.addLine(mapX.map(anchor.x), mapY.map(anchor.y), hx, hy, dimCurve, 1.0f);
                    drawList.addCircleFilled(hx, hy, handleRadius,
                        grabbedHandle == 2000 + i ? 0xFFFFA000 : 0xFF90C0FF);
                }
                if (i < anchors.size() - 1 && anchor.hasOutgoingHandle()) {
                    float hx = mapX.map(anchor.hox);
                    float hy = mapY.map(anchor.hoy);
                    drawList.addLine(mapX.map(anchor.x), mapY.map(anchor.y), hx, hy, dimCurve, 1.0f);
                    drawList.addCircleFilled(hx, hy, handleRadius,
                        grabbedHandle == 1000 + i ? 0xFFFFA000 : 0xFF30C0FF);
                }
            }

            // anchors
            for (int i = 0; i < anchors.size(); i++) {
                BezierAnchor anchor = anchors.get(i);
                float ax = mapX.map(anchor.x);
                float ay = mapY.map(anchor.y);
                boolean isEndpoint = i == 0 || i == anchors.size() - 1;
                int colour = isEndpoint ? 0xFFAAAAAA : (grabbedHandle == 100 + i ? 0xFFFFA000 : 0xFFE0E0E0);
                drawList.addRectFilled(ax - anchorHalf, ay - anchorHalf, ax + anchorHalf, ay + anchorHalf, colour);
                if (selectedAnchor == i) {
                    drawList.addRect(ax - anchorHalf - 2, ay - anchorHalf - 2,
                        ax + anchorHalf + 2, ay + anchorHalf + 2, 0xFFFFFFFF);
                }
            }

            // ---- interaction ----
            ImGui.invisibleButton("fbz_graph_area", graphSide, graphSide);
            boolean hovered = ImGui.isItemHovered();
            float pickRadius = ReplayUI.scaleUi((int) HANDLE_PICKUP_RADIUS_UI);

            if (hovered && ImGui.isMouseDoubleClicked(0)) {
                int pick = pickCurveElement(anchors, mx, my, mapX, mapY, pickRadius);
                if (pick >= 2000) {
                    // double-click on an incoming handle -> reset it to auto
                    BezierAnchor anchor = anchors.get(pick - 2000);
                    anchor.hix = BezierAnchor.AUTO;
                    anchor.hiy = BezierAnchor.AUTO;
                    grabbedHandle = 0;
                    commitCurve(validTargets);
                } else if (pick >= 1000) {
                    // double-click on an outgoing handle -> reset it to auto
                    BezierAnchor anchor = anchors.get(pick - 1000);
                    anchor.hox = BezierAnchor.AUTO;
                    anchor.hoy = BezierAnchor.AUTO;
                    grabbedHandle = 0;
                    commitCurve(validTargets);
                } else if (pick >= 100) {
                    int index = pick - 100;
                    if (index > 0 && index < anchors.size() - 1) {
                        // double-click on an interior anchor -> delete it
                        List<BezierAnchor> remaining = new ArrayList<>(anchors);
                        remaining.remove(index);
                        draft.setAnchors(remaining);
                        selectedAnchor = -1;
                        grabbedHandle = 0;
                        commitCurve(validTargets);
                    }
                } else {
                    // double-click on empty space -> add an anchor there
                    float nx = BezierEasing.clampX((mx - x0) / graphSide);
                    float ny = BezierEasing.clampY(yLow + (y0 + graphSide - my) * ySpan / graphSide);
                    List<BezierAnchor> added = new ArrayList<>(anchors);
                    int insertAt = 1;
                    while (insertAt < added.size() && added.get(insertAt).x < nx) {
                        insertAt++;
                    }
                    added.add(insertAt, new BezierAnchor(nx, ny));
                    draft.setAnchors(added);
                    selectedAnchor = insertAt;
                    grabbedHandle = 0;
                    commitCurve(validTargets);
                }
            } else if (hovered && ImGui.isMouseClicked(0) && grabbedHandle == 0) {
                grabbedHandle = pickCurveElement(anchors, mx, my, mapX, mapY, pickRadius);
                if (grabbedHandle >= 100 && grabbedHandle < 1000) {
                    selectedAnchor = grabbedHandle - 100;
                    syncAnchorArrays();
                } else if (grabbedHandle >= 1000) {
                    int anchorIndex = grabbedHandle >= 2000 ? grabbedHandle - 2000 : grabbedHandle - 1000;
                    selectedAnchor = anchorIndex;
                    syncAnchorArrays();
                }
            }
            if (grabbedHandle != 0) {
                if (!ImGui.isMouseDown(0)) {
                    grabbedHandle = 0;
                } else {
                    float nx = BezierEasing.clampX((mx - x0) / graphSide);
                    float ny = BezierEasing.clampY(yLow + (y0 + graphSide - my) * ySpan / graphSide);
                    if (grabbedHandle >= 2000) {
                        int i = grabbedHandle - 2000;
                        BezierAnchor anchor = anchors.get(i);
                        float minX = anchors.get(i - 1).x;
                        float maxX = anchor.x;
                        if (maxX - minX >= BezierAnchor.MIN_X_GAP) {
                            anchor.hix = Math.max(minX, Math.min(maxX, nx));
                            anchor.hiy = ny;
                            commitCurve(validTargets);
                        }
                    } else if (grabbedHandle >= 1000) {
                        int i = grabbedHandle - 1000;
                        BezierAnchor anchor = anchors.get(i);
                        float minX = anchor.x;
                        float maxX = anchors.get(i + 1).x;
                        if (maxX - minX >= BezierAnchor.MIN_X_GAP) {
                            anchor.hox = Math.max(minX, Math.min(maxX, nx));
                            anchor.hoy = ny;
                            commitCurve(validTargets);
                        }
                    } else {
                        int i = grabbedHandle - 100;
                        if (i > 0 && i < anchors.size() - 1) {
                            BezierAnchor anchor = anchors.get(i);
                            float minX = anchors.get(i - 1).x + BezierAnchor.MIN_X_GAP;
                            float maxX = anchors.get(i + 1).x - BezierAnchor.MIN_X_GAP;
                            if (maxX >= minX) {
                                anchor.x = Math.max(minX, Math.min(maxX, nx));
                            }
                            anchor.y = ny;
                            commitCurve(validTargets);
                        }
                    }
                }
            }

            if (hovered) {
                ImGuiHelper.tooltip(I18n.get("flashbackbezier.editor.curve_graph_tooltip"));
            }
        } finally {
            ImGui.endChild();
        }
    }

    /**
     * Picks the curve element under the mouse: 1000+i outgoing handle,
     * 2000+i incoming handle, 100+i anchor, or -1. Handles win over anchors
     * (they are the smaller targets).
     */
    private static int pickCurveElement(List<BezierAnchor> anchors, float mx, float my,
                                        YMapper mapX, YMapper mapY, float pickRadius) {
        int best = -1;
        float bestDistance = pickRadius * pickRadius;
        for (int i = 0; i < anchors.size(); i++) {
            BezierAnchor anchor = anchors.get(i);
            if (i > 0 && anchor.hasIncomingHandle()) {
                float d = distanceSquared(mx, my, mapX.map(anchor.hix), mapY.map(anchor.hiy));
                if (d <= bestDistance) {
                    best = 2000 + i;
                    bestDistance = d;
                }
            }
            if (i < anchors.size() - 1 && anchor.hasOutgoingHandle()) {
                float d = distanceSquared(mx, my, mapX.map(anchor.hox), mapY.map(anchor.hoy));
                if (d <= bestDistance) {
                    best = 1000 + i;
                    bestDistance = d;
                }
            }
        }
        for (int i = 0; i < anchors.size(); i++) {
            BezierAnchor anchor = anchors.get(i);
            float d = distanceSquared(mx, my, mapX.map(anchor.x), mapY.map(anchor.y));
            if (d <= bestDistance) {
                best = 100 + i;
                bestDistance = d;
            }
        }
        return best;
    }

    private static float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }
}
