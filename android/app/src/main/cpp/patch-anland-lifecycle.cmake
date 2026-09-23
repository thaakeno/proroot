if(NOT DEFINED ANLAND_SOURCE)
    message(FATAL_ERROR "ANLAND_SOURCE is required")
endif()

set(native_consumer "${ANLAND_SOURCE}/app/src/main/jni/native_consumer.c")
if(NOT EXISTS "${native_consumer}")
    message(FATAL_ERROR "Anland native_consumer.c not found: ${native_consumer}")
endif()

file(READ "${native_consumer}" source)

set(old_rebind [==[
    if (g_state.ctx) {
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }
    motion_has_last = false;
    motion_has_last = false;
    cleanup_dmabufs(&g_state);

    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }
]==])

set(new_rebind [==[
    /* A rebind can happen after Android recreates the view. Stop every user of
     * the old display_ctx before disconnecting it; otherwise the event/audio
     * threads can race the freed transport. */
    audio_set_ctx(NULL);
    if (g_state.ctx) {
        stop_event_thread(&g_state);
        join_event_thread(&g_state);
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }
    motion_has_last = false;
    motion_has_last = false;
    cleanup_dmabufs(&g_state);

    if (g_state.window) {
        anw_api_disconnect(g_state.window, ANW_API_CPU);
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }
]==])

string(FIND "${source}" "${old_rebind}" rebind_pos)
if(rebind_pos EQUAL -1)
    message(FATAL_ERROR "Anland lifecycle patch: nativeStart anchor not found")
endif()
string(REPLACE "${old_rebind}" "${new_rebind}" source "${source}")

set(old_audio [==[
    /* Stop audio before the ctx (and its fd) is torn down. */
    audio_set_ctx(NULL);
    audio_stop();
]==])

set(new_audio [==[
    /* Audio is process-global rather than SurfaceView-global. Detach it from the
     * transport but keep its worker/AAudio objects alive across Android surface
     * recreation. The Java side disables keepalive before stop, so the stream
     * idles without holding an active Linux connection. */
    audio_set_ctx(NULL);
]==])

string(FIND "${source}" "${old_audio}" audio_pos)
if(audio_pos EQUAL -1)
    message(FATAL_ERROR "Anland lifecycle patch: audio teardown anchor not found")
endif()
string(REPLACE "${old_audio}" "${new_audio}" source "${source}")

set(old_clip [==[
    // Disable clip listener on Java side
    if (g_jvm && g_activity_obj) {
        JNIEnv *env = NULL;
        bool attached = false;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0)
                attached = true;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
            jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
            if (mid)
                (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        }
        if (attached)
            (*g_jvm)->DetachCurrentThread(g_jvm);
    }
]==])

set(new_clip [==[
    // Disable clip listener on Java side. nativeStop already runs on a JNI
    // thread, so use its valid JNIEnv directly and drop the stale callback ref.
    if (g_activity_obj) {
        jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
        jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
        if (mid)
            (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        if (cls)
            (*env)->DeleteLocalRef(env, cls);
        (*env)->DeleteGlobalRef(env, g_activity_obj);
        g_activity_obj = NULL;
    }
]==])

string(FIND "${source}" "${old_clip}" clip_pos)
if(clip_pos EQUAL -1)
    message(FATAL_ERROR "Anland lifecycle patch: clipboard teardown anchor not found")
endif()
string(REPLACE "${old_clip}" "${new_clip}" source "${source}")

set(old_window [==[
    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }

    pthread_mutex_unlock(&g_state.lock);
}
]==])

set(new_window [==[
    /*
     * Do not drop the final native-window ref from SurfaceView teardown. Some
     * Android vendors destroy the BufferQueue concurrently with this callback.
     * The render thread is already joined, so retaining one inert ref is safe;
     * nativeStart releases it immediately before binding the next Surface.
     */
    if (g_state.window) {
        anw_api_disconnect(g_state.window, ANW_API_CPU);
    }

    pthread_mutex_unlock(&g_state.lock);
}
]==])

string(FIND "${source}" "${old_window}" window_pos)
if(window_pos EQUAL -1)
    message(FATAL_ERROR "Anland lifecycle patch: final window anchor not found")
endif()
string(REPLACE "${old_window}" "${new_window}" source "${source}")

# The upstream render loop queues a dequeued Android buffer even when the
# producer is in fallback or fails to finish a frame. Such a buffer has never
# been rendered and replaces the last good frame with black. Cancel it instead.
set(old_select [==[
        if (select_dmabuf(s->ctx, idx) < 0) {
            api.queueBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }
]==])
set(new_select [==[
        if (select_dmabuf(s->ctx, idx) < 0) {
            api.cancelBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }
]==])
string(FIND "${source}" "${old_select}" select_pos)
if(select_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: select_dmabuf anchor not found")
endif()
string(REPLACE "${old_select}" "${new_select}" source "${source}")

set(old_refresh [==[
        int rfence = refresh_done(s->ctx);
        api.queueBuffer(s->window, anb, rfence);
]==])
set(new_refresh [==[
        int rfence = refresh_done(s->ctx);
        if (rfence == -2) {
            /* No completed frame: retain the last displayed buffer. */
            api.cancelBuffer(s->window, anb, -1);
            continue;
        }
        api.queueBuffer(s->window, anb, rfence);
]==])
string(FIND "${source}" "${old_refresh}" refresh_pos)
if(refresh_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: refresh_done anchor not found")
endif()
string(REPLACE "${old_refresh}" "${new_refresh}" source "${source}")
file(WRITE "${native_consumer}" "${source}")

set(display_consumer "${ANLAND_SOURCE}/app/src/main/jni/anland_core/libdisplay_consumer/display_consumer.c")
if(NOT EXISTS "${display_consumer}")
    message(FATAL_ERROR "Anland display_consumer.c not found: ${display_consumer}")
endif()
file(READ "${display_consumer}" display_source)

set(old_fallback_select [==[
        if (ctx->fallback)
            return 0;
]==])
set(new_fallback_select [==[
        if (ctx->fallback)
            return -1;  /* No producer: do not queue an unrendered buffer. */
]==])
string(FIND "${display_source}" "${old_fallback_select}" fallback_select_pos)
if(fallback_select_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: fallback select anchor not found")
endif()
string(REPLACE "${old_fallback_select}" "${new_fallback_select}" display_source "${display_source}")

set(old_no_pending [==[
    if (!ctx->buffer_pending)
        return -1;
]==])
set(new_no_pending [==[
    if (!ctx->buffer_pending)
        return -2;  /* No frame was submitted to the producer. */
]==])
string(FIND "${display_source}" "${old_no_pending}" no_pending_pos)
if(no_pending_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: pending frame anchor not found")
endif()
string(REPLACE "${old_no_pending}" "${new_no_pending}" display_source "${display_source}")

set(old_poll_failure [==[
    if (ret <= 0 || !(pfd.revents & POLLIN)) {
        enter_fallback(ctx);
        return -1;
    }
]==])
set(new_poll_failure [==[
    if (ret <= 0 || !(pfd.revents & POLLIN)) {
        enter_fallback(ctx);
        return -2;
    }
]==])
string(FIND "${display_source}" "${old_poll_failure}" poll_failure_pos)
if(poll_failure_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: frame timeout anchor not found")
endif()
string(REPLACE "${old_poll_failure}" "${new_poll_failure}" display_source "${display_source}")

set(old_recv_failure [==[
    if (n == 0) {
        enter_fallback(ctx);
        return -1;
    }
    if (n > 0) {
]==])
set(new_recv_failure [==[
    if (n <= 0 || (msg.msg_flags & MSG_CTRUNC)) {
        enter_fallback(ctx);
        return -2;
    }
    if (n > 0) {
]==])
string(FIND "${display_source}" "${old_recv_failure}" recv_failure_pos)
if(recv_failure_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: frame receive anchor not found")
endif()
string(REPLACE "${old_recv_failure}" "${new_recv_failure}" display_source "${display_source}")

set(old_refresh_contract [==[
 * dedicated fence channel; the message itself is the "frame rendered" signal (no
 * separate eventfd, no cross-channel ordering) and the optional fence rides as
 * SCM_RIGHTS ancillary data. Returns the fence fd (caller owns it), or -1 if none /
 * on error. */
]==])
set(new_refresh_contract [==[
 * dedicated fence channel; the message itself is the "frame rendered" signal (no
 * separate eventfd, no cross-channel ordering) and the optional fence rides as
 * SCM_RIGHTS ancillary data. Returns the fence fd (caller owns it), -1 for a
 * completed frame without a fence, or -2 when no frame may be queued. */
]==])
string(FIND "${display_source}" "${old_refresh_contract}" refresh_contract_pos)
if(refresh_contract_pos EQUAL -1)
    message(FATAL_ERROR "Anland frame patch: refresh contract anchor not found")
endif()
string(REPLACE "${old_refresh_contract}" "${new_refresh_contract}" display_source "${display_source}")
file(WRITE "${display_consumer}" "${display_source}")
message(STATUS "Applied ProRoot Anland lifecycle and frame-delivery hardening")
