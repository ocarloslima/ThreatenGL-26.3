package lol.richy.threatengl.mixin;

import java.nio.IntBuffer;
import java.util.Locale;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import lol.richy.threatengl.ThreatenGL;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlBackend.class)
public class ThreatenGLSDLWindowMixin {
    private static final Logger LOGGER = ThreatenGL.LOGGER;

    @Redirect(
        method = "createWindow",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z",
            remap = false
        )
    )
    private boolean threatenGL$setContextAttribute(int attribute, int value) {
        if (attribute == SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION) {
            value = 4;
        } else if (attribute == SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION) {
            value = requestedMinor();
            LOGGER.info("Threatened Minecraft to request OpenGL 4.{}", value);
        }

        return SDLVideo.SDL_GL_SetAttribute(attribute, value);
    }

    @Redirect(
        method = "createWindow",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J",
            remap = false
        )
    )
    private long threatenGL$probeContext(
        CharSequence title,
        int width,
        int height,
        long flags
    ) {
        int requestedMinor = requestedMinor();
        long probeWindow = SDLVideo.SDL_CreateWindow(
            "ThreatenGL OpenGL capability probe",
            1,
            1,
            SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN
        );

        if (probeWindow == 0L) {
            useVanillaVersion(requestedMinor, SDLError.SDL_GetError());
            return SDLVideo.SDL_CreateWindow(title, width, height, flags);
        }

        long previousWindow = SDLVideo.SDL_GL_GetCurrentWindow();
        long previousContext = SDLVideo.SDL_GL_GetCurrentContext();
        long probeContext = SDLVideo.SDL_GL_CreateContext(probeWindow);
        boolean supported = probeContext != 0L && hasRequestedVersion(requestedMinor);

        if (probeContext != 0L) {
            if (previousContext != 0L && !SDLVideo.SDL_GL_MakeCurrent(previousWindow, previousContext)) {
                String error = SDLError.SDL_GetError();
                SDLVideo.SDL_GL_DestroyContext(probeContext);
                SDLVideo.SDL_DestroyWindow(probeWindow);
                throw new IllegalStateException("SDL could not restore Minecraft's OpenGL context: " + error);
            }
            if (!SDLVideo.SDL_GL_DestroyContext(probeContext)) {
                LOGGER.warn("SDL could not destroy the temporary OpenGL context: {}", SDLError.SDL_GetError());
            }
        }
        SDLVideo.SDL_DestroyWindow(probeWindow);

        if (supported) {
            LOGGER.info("The requested OpenGL 4.{} context is available.", requestedMinor);
        } else {
            useVanillaVersion(requestedMinor, SDLError.SDL_GetError());
        }

        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    private static boolean hasRequestedVersion(int requestedMinor) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major = stack.mallocInt(1);
            IntBuffer minor = stack.mallocInt(1);
            if (!SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, major)
                || !SDLVideo.SDL_GL_GetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, minor)) {
                return false;
            }

            return major.get(0) > 4 || (major.get(0) == 4 && minor.get(0) >= requestedMinor);
        }
    }

    private static void useVanillaVersion(int requestedMinor, String reason) {
        boolean majorSet = SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        boolean minorSet = SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 3);
        if (!majorSet || !minorSet) {
            throw new IllegalStateException(
                "SDL could not restore Minecraft's vanilla OpenGL 3.3 context request: " + SDLError.SDL_GetError()
            );
        }

        LOGGER.warn(
            "OpenGL 4.{} is unavailable ({}); retrying with Minecraft's vanilla OpenGL 3.3 request.",
            requestedMinor,
            reason
        );
    }

    private static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static int requestedMinor() {
        return isMacOS() ? 1 : 6;
    }
}
