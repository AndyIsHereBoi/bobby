package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private static int clampMaxValue(int viewDistance) {
        return Math.min(viewDistance, 32);
    }

    // Equivalent of upstream's WorldRendererMixin (fixes #152: sky rendering with render distance > 32).
    // Upstream injects into the lambda which calls applyFog with FogType.FOG_SKY, but that lambda is a
    // synthetic method ("method_37365") which Mixin cannot resolve on 1.18.2. Clamping the argument of
    // applyFog and filtering on the fog type has the same effect and does not touch the terrain fog.
    // 1.18.2 note: when a @ModifyVariable handler captures the target's arguments, Mixin requires the COMPLETE
    // argument list in declaration order after the modified value - you cannot pick just the ones you need.
    // applyFog(Camera, FogType, float, boolean) therefore needs (value, Camera, FogType, float, boolean), and the
    // third captured argument is the FogType while the modified value is the first.
    @ModifyVariable(method = "applyFog", at = @At("HEAD"), argsOnly = true)
    private static float clampSkyFogMaxValue(float value, Camera camera, BackgroundRenderer.FogType fogType, float viewDistance, boolean thickFog) {
        if (fogType == BackgroundRenderer.FogType.FOG_SKY) {
            return Math.min(value, 32 * 16);
        }
        return value;
    }
}
