package dev.lasutar.flashbackbezier.mixin;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonParseException;
import com.moulberry.flashback.keyframe.Keyframe;
import dev.lasutar.flashbackbezier.bezier.BezierEasing;
import dev.lasutar.flashbackbezier.integration.BezierKeyframeData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/**
 * Persists the attached bezier easing inside the keyframe's own JSON block
 * ("bezier_easing") when editor state / clipboard data is serialized.
 *
 * <p>This is purely additive metadata: Flashback and older replays ignore the
 * unknown property, and replays written without this add-on keep loading
 * exactly as before.</p>
 */
@Mixin(Keyframe.TypeAdapter.class)
public abstract class KeyframeTypeAdapterMixin {

    private static final String PROPERTY = "bezier_easing";

    @Inject(method = "serialize(Lcom/moulberry/flashback/keyframe/Keyframe;Ljava/lang/reflect/Type;Lcom/google/gson/JsonSerializationContext;)Lcom/google/gson/JsonElement;",
        at = @At("RETURN"))
    public void flashbackbezier$writeEasingJson(Keyframe src, Type typeOfSrc, JsonSerializationContext context,
                                                CallbackInfoReturnable<JsonElement> cir) {
        if (!(cir.getReturnValue() instanceof JsonObject jsonObject)) {
            return;
        }
        BezierEasing easing = BezierKeyframeData.get(src);
        if (easing == null) {
            jsonObject.remove(PROPERTY);
            return;
        }

        JsonObject easingObject = new JsonObject();
        easingObject.addProperty("preset", easing.preset);
        easingObject.addProperty("p1x", easing.p1x);
        easingObject.addProperty("p1y", easing.p1y);
        easingObject.addProperty("p2x", easing.p2x);
        easingObject.addProperty("p2y", easing.p2y);
        jsonObject.add(PROPERTY, easingObject);
    }

    @Nullable
    private static BezierEasing flashbackbezier$readEasing(JsonElement element) {
        try {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject easingObject = element.getAsJsonObject();
            BezierEasing easing = new BezierEasing();
            easing.preset = easingObject.has("preset") ? easingObject.get("preset").getAsString() : "custom";
            if (easingObject.has("p1x")) easing.p1x = BezierEasing.clampX(easingObject.get("p1x").getAsFloat());
            if (easingObject.has("p1y")) easing.p1y = BezierEasing.clampY(easingObject.get("p1y").getAsFloat());
            if (easingObject.has("p2x")) easing.p2x = BezierEasing.clampX(easingObject.get("p2x").getAsFloat());
            if (easingObject.has("p2y")) easing.p2y = BezierEasing.clampY(easingObject.get("p2y").getAsFloat());
            return easing;
        } catch (Exception exception) {
            return null;
        }
    }

    @Inject(method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Lcom/moulberry/flashback/keyframe/Keyframe;",
        at = @At("TAIL"))
    public void flashbackbezier$readEasingJson(JsonElement json, Type typeOfT, JsonDeserializationContext context,
                                               CallbackInfoReturnable<Keyframe> cir) {
        Keyframe keyframe = cir.getReturnValue();
        if (keyframe == null || !json.isJsonObject()) {
            return;
        }
        JsonElement easingElement = json.getAsJsonObject().get(PROPERTY);
        if (easingElement == null || easingElement.isJsonNull()) {
            return;
        }
        BezierEasing easing = flashbackbezier$readEasing(easingElement);
        if (easing != null) {
            BezierKeyframeData.set(keyframe, easing);
        }
    }
}
