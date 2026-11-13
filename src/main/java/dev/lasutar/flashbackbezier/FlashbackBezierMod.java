package dev.lasutar.flashbackbezier;

import dev.lasutar.flashbackbezier.io.NamedPresetStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Flashback Bezier Easing - a standalone client-side add-on that adds
 * cubic-bezier easing to Flashback replay camera keyframes.
 */
public final class FlashbackBezierMod implements ClientModInitializer {

    public static final String MOD_ID = "flashbackbezier";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlashbackBezierEasing");

    /** User-created named presets, persisted in the config folder. */
    private static NamedPresetStore namedPresets;

    public static NamedPresetStore namedPresets() {
        return namedPresets;
    }

    @Override
    public void onInitializeClient() {
        // Normally unreachable if the mod was loaded via fabric.mod.json depends,
        // but keep the check for the (rare) shaded/dev-environment case.
        if (!FabricLoader.getInstance().isModLoaded("flashback")) {
            LOGGER.error("Flashback Bezier Easing is an add-on for the Flashback mod, but Flashback is not installed!");
            LOGGER.error("Please install Flashback (MC 26.3 compatible version) from https://modrinth.com/mod/flashback");
        } else {
            LOGGER.info("Flashback Bezier Easing initialized (Flashback {} detected).",
                FabricLoader.getInstance().getModContainer("flashback")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown"));
        }

        Path configDir = FabricLoader.getInstance().getConfigDir();
        namedPresets = new NamedPresetStore(NamedPresetStore.defaultPath(configDir));
    }
}
