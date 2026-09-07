package com.dualspace.clone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.dualspace.clone.DualSpaceApp
import com.dualspace.clone.model.Clone
import com.dualspace.clone.util.DsLog
import org.json.JSONArray

/**
 * Persistent registry of clones (metadata only — the sandboxes themselves live in the
 * engine's virtual root). Stored as JSON in SharedPreferences; small enough that a DB is
 * unnecessary, and it survives app updates because it is host-app private storage.
 *
 * Self-initialising (see [Prefs]): touching it before `DualSpaceApp.onCreate` never throws.
 */
object CloneStore {
    private const val TAG = "CloneStore"
    private const val FILE = "dualspace_clones"
    private const val KEY = "clones"

    private var sp: SharedPreferences? = null
    private val clones = mutableListOf<Clone>()
    private val live = MutableLiveData<List<Clone>>()

    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load()
    }

    @Synchronized
    private fun ensure(): SharedPreferences {
        sp?.let { return it }
        init(DualSpaceApp.appContext)
        return sp!!
    }

    val clonesLive: LiveData<List<Clone>> get() { ensure(); return live }

    @Synchronized
    fun all(): List<Clone> { ensure(); return clones.toList() }

    @Synchronized
    fun byId(id: String): Clone? { ensure(); return clones.firstOrNull { it.id == id } }

    @Synchronized
    fun byPackage(pkg: String): List<Clone> { ensure(); return clones.filter { it.packageName == pkg } }

    @Synchronized
    fun add(clone: Clone) {
        ensure()
        clones.add(clone)
        save()
    }

    @Synchronized
    fun update(clone: Clone) {
        ensure()
        val i = clones.indexOfFirst { it.id == clone.id }
        if (i >= 0) clones[i] = clone else clones.add(clone)
        save()
    }

    @Synchronized
    fun remove(id: String) {
        ensure()
        clones.removeAll { it.id == id }
        save()
    }

    /**
     * Smallest virtual user id in which [pkg] is not yet installed. User 0 is the first
     * clone, 1 the second, … Different packages can share a user id; only the same package
     * needs a fresh slot for each additional copy — this is what makes clones unlimited.
     */
    @Synchronized
    fun nextUserIdFor(pkg: String): Int {
        ensure()
        val used = clones.filter { it.packageName == pkg }.map { it.userId }.toSet()
        var id = 0
        while (id in used) id++
        return id
    }

    @Synchronized
    fun nextIndexFor(pkg: String): Int {
        ensure()
        return (clones.filter { it.packageName == pkg }.maxOfOrNull { it.index } ?: 0) + 1
    }

    /** All user ids that hold at least one clone — used to keep GMS linked everywhere. */
    @Synchronized
    fun userIds(): Set<Int> { ensure(); return clones.map { it.userId }.toSet() }

    private fun load() {
        clones.clear()
        val raw = sp!!.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            DsLog.e(TAG, "clone registry unreadable, starting empty", it); JSONArray()
        }
        for (i in 0 until arr.length()) {
            runCatching { clones.add(Clone.fromJson(arr.getJSONObject(i))) }
                .onFailure { DsLog.w(TAG, "skipping unreadable clone entry #$i", it) }
        }
        DsLog.i(TAG, "loaded ${clones.size} clone(s)")
        live.postValue(clones.toList())
    }

    private fun save() {
        val arr = JSONArray()
        clones.forEach { arr.put(it.toJson()) }
        sp!!.edit { putString(KEY, arr.toString()) }
        live.postValue(clones.toList())
    }
}
