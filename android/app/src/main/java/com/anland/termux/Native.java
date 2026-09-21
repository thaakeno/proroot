package com.anland.termux;

import android.view.Surface;

public final class Native {
    static {
        System.loadLibrary("anland_consumer");
    }

    private Native() {}

    public static native void nativeConfigure(String socketPath, boolean useRoot,
                                              String helperPath, String bridgePath);
    public static native void nativeSetCompatibleMode(boolean enabled);
    public static native void nativeSetCompatibleFd(int fd);
    public static native void nativeStart(Surface surface, Object callbackTarget);
    public static native void nativeStop();
    public static native void nativeSetCustomResolution(int width, int height);
    public static native void nativeSendTouch(int action, float x, float y, int pointerId);
    public static native void nativeSendTouchFrame();
    public static native void nativeSendKey(int action, int keycode);
    public static native void nativeSendMouseMotion(float x, float y, float dx, float dy);
    public static native void nativeSendMouseButton(int button, boolean pressed);
    public static native void nativeSendMouseScroll(int axis, float value);
    public static native void nativeSetRefreshRate(float hz);
    public static native void nativeSendClipboard(byte[] data);
    public static native void nativeSendTextInput(byte[] data);
    public static native void nativeSetMicEnabled(boolean enabled);
    public static native void nativeSetAudioLatency(int speakerMs, int micMs);
    public static native void nativeSetAudioKeepalive(boolean enabled);
}
