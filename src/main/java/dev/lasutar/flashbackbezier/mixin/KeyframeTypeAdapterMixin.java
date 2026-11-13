package dev.lasutar.flashbackbezier.mixin;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import dev.lasutar.flashbackbezier.io.BezierEasingJson;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/**
 * Persists the attached bezier easing AND the range easing inside the
 * keyframe's own JSON block ("bezier_easing" / "bezier_range") when editor
 * state / clipboard data is serialized.
 *
 * <p>This is purely additive metadata: Flashback and older replays ignore the
 * unknown properties, and replays written without this add-on keep loading
 * exactly as before. Legacy v1.0.0 data (single cubic without "anchors")
 * round-trips unchanged.</p>
 */
@Mixin(Keyframe.TypeAdapter.class)
public abstract class KeyframeTypeAdapterMixin {

    private static final String PROPERTY = "bezier_easing";
    private static final String RANGE_PROPERTY = "bezier_range";

    @Inject(method = "serialize(Lcom/moulberry/flashback/keyframe/Keyframe;Ljava/lang/reflect/Type;Lcom/google/gson/JsonSerializationContext;)Lcom/google/gson/JsonElement;",
        at = @At("RETURN"))
    public void flashbackbezier$writeEasingJson(Keyframe src, Type typeOfSrc, JsonSerializationContext context,
                                                CallbackInfoReturnable<JsonElement> cir) {
        if (!(cir.getReturnValue() instanceof JsonObject jsonObject)) {
            return;
        }
        JsonObject easingObject = BezierEasingJson.write(dev.lasutar.flashbackbezier.integration.BezierKeyframeData.get(src));
        if (easingObject == null) {
            jsonObject.remove(PROPERTY);
        } else {
            jsonObject.add(PROPERTY, easingObject);
        }
        JsonObject rangeObject = BezierEasingJson.writeRange(dev.lasutar.flashbackbezier.integration.BezierKeyframeData.getRange(src));
        if (rangeObject == null) {
            jsonObject.remove(RANGE_PROPERTY);
        } else {
            jsonObject.add(RANGE_PROPERTY, rangeObject);
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
        JsonObject object = json.getAsJsonObject();
        JsonElement easingElement = object.get(PROPERTY);
        if (easingElement != null && !easingElement.isJsonNull()) {
            var easing = BezierEasingJson.read(easingElement);
            if (easing != null) {
                dev.lasutar.flashbackbezier.integration.BezierKeyframeData.set(keyframe, easing);
            }
        }
        JsonElement rangeElement = object.get(RANGE_PROPERTY);
        if (rangeElement != null && !rangeElement.isJsonNull()) {
            var range = BezierEasingJson.readRange(rangeElement);
            if (range != null) {
                dev.lasutar.flashbackbezier.integration.BezierKeyframeData.setRange(keyframe, range);
            }
        }
    }
}
