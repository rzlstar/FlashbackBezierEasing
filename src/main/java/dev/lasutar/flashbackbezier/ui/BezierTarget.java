package dev.lasutar.flashbackbezier.ui;

import org.jetbrains.annotations.NotNull;

/**
 * One editing target of the bezier editor: a single keyframe, identified by
 * its (trackIndex, tick) location in the current{@link com.moulberry.flashback.state.EditorScene}.
 *
 * <p>The target list is re-resolved against the live scene every frame, so
 * history operations that replace keyframe instances (undo/redo, SetKeyframe)
 * automatically keep the editor pointed at the right object.</p>
 */
public record BezierTarget(int trackIndex, int tick) implements Comparable<BezierTarget> {

    @Override
    public int compareTo(@NotNull BezierTarget other) {
        int c = Integer.compare(this.trackIndex, other.trackIndex);
        return c != 0 ? c : Integer.compare(this.tick, other.tick);
    }
}
