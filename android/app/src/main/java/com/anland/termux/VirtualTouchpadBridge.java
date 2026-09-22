package com.anland.termux;

import android.content.Context;
import android.view.MotionEvent;

/**
 * Thin lifecycle adapter around Anland's upstream VirtualTouchpad.
 *
 * VirtualTouchpad itself is vendored unchanged from lfdevs/anland-termux.
 * This class only exposes its package-private API to the Kotlin SurfaceView and
 * gives the host a safe reset hook when input modes or sessions change.
 */
public final class VirtualTouchpadBridge {
    private final Context context;
    private VirtualTouchpad touchpad;

    public VirtualTouchpadBridge(Context context) {
        this.context = context;
        this.touchpad = new VirtualTouchpad(context);
    }

    public boolean onTouch(MotionEvent event) {
        return touchpad.onTouch(event);
    }

    public void onSurfaceChanged() {
        touchpad.onSurfaceChanged();
    }

    public void setAccelStrength(float strength) {
        touchpad.setAccelStrength(strength);
    }

    public void reset() {
        // The only button VirtualTouchpad can hold across frames is the left
        // button during a long-press drag. Release it before replacing state.
        Native.nativeSendMouseButton(0x110, false);
        touchpad = new VirtualTouchpad(context);
    }
}
