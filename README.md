# Flashback Bezier Easing

[日本語](#概要) | English below the summary.

**Flashback Bezier Easing** is a standalone client-side Fabric add-on for the
[Flashback mod](https://modrinth.com/mod/flashback) (MC 26.3 / version 0.43.6).
It adds **cubic-bezier easing** to Flashback's camera keyframes, together with
an After Effects-style graphical bezier editor.

## Overview (English)

### What it does

- Right-click a keyframe in Flashback's timeline → a new **"Bezier Easing"**
  section appears in the popup, showing the currently applied preset.
- Click **Edit Bezier…** to open a graph editor where you drag the two bezier
  handles (P1 / P2) with real-time curve preview, or type exact coordinates,
  or snap to any of the presets:
  Linear / Ease / Ease In / Ease Out / Ease In Out / Cubic / Sine / Expo / Back,
  plus **Custom Bezier** for free-form curves.
- Apply to a single keyframe, or to a whole multi-selection at once
  ("Mixed" is shown when the selection has different easings).
- Keyframes that carry a bezier easing show a **diamond icon** on the timeline.
- Everything is stored per keyframe and persists across session reopen and
  restart (saved as additive `"bezier_easing"` JSON metadata inside Flashback's
  existing keyframe data - no extra files, no data corruption, and replays
  still load without this mod installed).

### How it eases (technical)

The easing only **warps time**: the same linear / Catmull-Rom (Smooth) /
Hermite spatial interpolation Flashback already provides is evaluated at an
eased progress value instead of the raw one. The bezier is solved by
numerically inverting the x axis (Newton-Raphson with a bisection fallback),
exactly like CSS `cubic-bezier()`. Because bezier(0)=0 and bezier(1)=1,
continuity at keyframes is preserved. Vanilla behaviors (Linear/Smooth/Hold/
Hermite interpolation types) are untouched - keyframes without a bezier
behave exactly as before.

### Requirements

- Minecraft 26.3 + Fabric Loader (>= 0.15.10) + Fabric API
- **Flashback 0.43.6 (MC 26.3)** - required, loaded as a hard dependency

### Usage

1. Put `flashbackbezier-1.0.0.jar` in your `mods` folder (with Flashback).
2. Enter a replay, open the timeline, right-click a keyframe.
3. New "Bezier Easing" section: click **Edit Bezier…**.
4. In the editor: drag handles / pick a preset / type coordinates, then press
   **Apply** (or **Cancel** to discard). **Reset** restores what the keyframe
   had when the editor opened.
5. Use **Clear Easing** in the popup to remove the easing again.

The easing applies to the segment **starting at** the keyframe it is attached
to (it shapes how the camera leaves that keyframe).

### Building from source

```powershell
.\gradlew.bat build
```

(JDK 25; the Gradle wrapper 9.4.1 is included. Output:
`build/libs/flashbackbezier-1.0.0.jar`.)

### Notes / limitations

- Compiled and verified against the actual Flashback 0.43.6 bytecode, but
  **in-game behavior was not tested** - please report issues.
- Bezier easing intentionally ignores Flashback's per-side Ease/Linear split
  when set; removing the bezier restores the vanilla interpolation type.

### Architecture (for developers)

- `bezier/BezierEasing` - the solver (Newton-Raphson + bisection).
- `bezier/BezierPreset` - the preset table.
- `mixin/KeyframeMixin` - attaches easing data to every `Keyframe`
  (implements `integration.BezierKeyframeData`) + diamond icon.
- `mixin/KeyframeTypeAdapterMixin` - additive `"bezier_easing"` JSON
  (de)serialization.
- `mixin/*KeyframeMixin` (Camera / CameraOrbit / TrackEntity / FOV) - easing
  survives `copy()` for undo/clipboard.
- `mixin/KeyframeTrackMixin` - the three `@WrapOperation` hooks in
  `KeyframeTrack.createKeyframeChange` (linear / Catmull-Rom / Hermite).
- `mixin/TimelineWindowMixin` + `ui/BezierPopupUi` - the context menu section.
- `mixin/ReplayUIMixin` + `ui/BezierEditorWindow` - the editor window inside
  Flashback's ImGui.

No Flashback source code is included or redistributed (Flashback is
All-Rights-Reserved); this add-on only integrates with its public/shipped
APIs via mixins.

## 概要（日本語）

**Flashback Bezier Easing** は、[Flashback mod](https://modrinth.com/mod/flashback)
（MC 26.3 / v0.43.6）向けの単体クライアントサイドFabricアドオンです。
Flashbackのカメラキーフレームに**3次ベジェイージング**を追加し、
After Effects風のグラフィカルなベジェエディタを提供します。

### 機能

- タイムラインのキーフレームを右クリック → ポップアップに **「ベジェイージング」** セクションが追加され、現在適用中のプリセットが表示されます。
- **「ベジェを編集…」** でエディタを開くと、2つのハンドル（P1 / P2）をドラッグして曲線をリアルタイムに調整できます。数値入力やプリセット選択にも対応：
  Linear / Ease / Ease In / Ease Out / Ease In Out / Cubic / Sine / Expo / Back ＋ 自由編集（Custom Bezier）。
- 1個のキーフレームに適用できるほか、複数選択への一括適用も可能です（適用値が異なる場合は「混在」と表示）。
- ベジェイージングが設定されたキーフレームは **ダイア形状のアイコン** で表示されます。
- 設定はキーフレームごとに保存され、再起動後も引き継がれます（Flashbackのキーフレームデータに追加の `"bezier_easing"` JSONを書き加える形なので、このMODが無くてもリプレイは問題なく読み込めます）。

### イージングの仕組み（技術的）

イージングは **時間のみを歪めます**。Flashbackが持つ線形 / Catmull-Rom（スムーズ）/ Hermiteの空間補間をそのまま使い、評価に渡す進捗値をベジェでイージングします。ベジェはx軸を数値的に逆解算（Newton法＋二分探索フォールバック）して求める、CSS `cubic-bezier()`と同じ方式です。bezier(0)=0、bezier(1)=1 なのでキーフレーム間の連続性は保たれます。従来の補間タイプ（Linear/Smooth/Hold/Hermite）の挙動は一切変更しません。

### 動作要件

- Minecraft 26.3 + Fabric Loader（0.15.10以降）+ Fabric API
- **Flashback 0.43.6（MC 26.3版）** - 必須依存として読み込みます

### 使い方

1. `flashbackbezier-1.0.0.jar` を `mods` フォルダに入れる（Flashbackと一緒に）。
2. リプレイに入り、タイムラインでキーフレームを右クリック。
3. 「ベジェイージング」セクションの **「ベジェを編集…」** をクリック。
4. エディタでハンドル操作 / プリセット選択 / 数値入力を行い、**適用**（**キャンセル**で破棄、**リセット**で編集開始時の状態に戻す）。
5. ポップアップの **「イージングを削除」** でベジェを解除できます。

イージングは **そのキーフレームから始まる区間**（そのキーフレームを「離れる」速度）に適用されます。

### ビルド方法

```powershell
.\gradlew.bat build
```

（JDK 25、Gradleラッパー9.4.1同梱。出力: `build/libs/flashbackbezier-1.0.0.jar`）

### 注意・制限事項

- 実際のFlashback 0.43.6バイトコードに対してコンパイル・検証しましたが、**ゲーム内での動作テストは未実施**です。
- ベジェ設定中はFlashbackの左右サイド別のEase/Linear設定より優先されます。ベジェを解除すると従来の補間タイプに戻ります。

### ライセンス

MIT（本文書のLICENSEを参照）。Flashback本体のコードは一切含んでいません。
