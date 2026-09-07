package com.dualspace.clone.ui

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decodes and badges icons off the main thread and keeps the results in memory, so
 * scrolling the clone grid or the app picker never decodes a bitmap on the UI thread
 * (which is what made the lists stutter).
 */
object IconCache {
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Drawable>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Drawable): Int =
            (value as? BitmapDrawable)?.bitmap?.byteCount ?: (192 * 192 * 4)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun peek(key: String): Drawable? = cache.get(key)

    /**
     * Show the cached icon for [key] immediately, or [placeholder] and then the loaded one.
     * [loader] runs on a background thread. A recycled view that was re-bound to another key
     * in the meantime is left alone (tag check).
     */
    fun load(view: ImageView, key: String, placeholder: Drawable? = null, loader: () -> Drawable?) {
        cache.get(key)?.let {
            view.tag = key
            view.setImageDrawable(it)
            return
        }
        view.tag = key
        view.setImageDrawable(placeholder)
        scope.launch {
            val d = runCatching(loader).getOrNull() ?: return@launch
            cache.put(key, d)
            withContext(Dispatchers.Main) {
                if (view.tag == key) view.setImageDrawable(d)
            }
        }
    }

    fun invalidate(key: String) {
        cache.remove(key)
    }

    fun invalidatePrefix(prefix: String) {
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}
