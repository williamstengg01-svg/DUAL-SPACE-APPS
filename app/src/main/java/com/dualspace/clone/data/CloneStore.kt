package com.dualspace.clone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.dualspace.clone.model.Clone
import org.json.JSONArray

/**
 * Persistent registry of clones (metadata only — the sandboxes themselves live in the
 * engine's virtual root). Stored as JSON in SharedPreferences; small enough that a DB is
 * unnecessary, and it survives app updates because it is host-app private storage.
 */
object CloneStore {
    private const val FILE = "dualspace_clones"
    private const val KEY = "clones"

    private lateinit var sp: SharedPreferences
    private val clones = mutableListOf<Clone>()
    private val live = MutableLiveData<List<Clone>>()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load()
    }

    val clonesLive: LiveData<List<Clone>> get() = live

    @Synchronized
    fun all(): List<Clone> = clones.toList()

    @Synchronized
    fun byId(id: String): Clone? = clones.firstOrNull { it.id == id }

    @Synchronized
    fun byPackage(pkg: String): List<Clone> = clones.filter { it.packageName == pkg }

    @Synchronized
    fun add(clone: Clone) {
        clones.add(clone)
        save()
    }

    @Synchronized
    fun update(clone: Clone) {
        val i = clones.indexOfFirst { it.id == clone.id }
        if (i >= 0) clones[i] = clone else clones.add(clone)
        save()
    }

    @Synchronized
    fun remove(id: String) {
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
        val used = clones.filter { it.packageName == pkg }.map { it.userId }.toSet()
        var id = 0
        while (id in used) id++
        return id
    }

    @Synchronized
    fun nextIndexFor(pkg: String): Int = (clones.filter { it.packageName == pkg }.maxOfOrNull { it.index } ?: 0) + 1

    /** All user ids that hold at least one clone — used to keep GMS linked everywhere. */
    @Synchronized
    fun userIds(): Set<Int> = clones.map { it.userId }.toSet()

    private fun load() {
        clones.clear()
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            runCatching { clones.add(Clone.fromJson(arr.getJSONObject(i))) }
        }
        live.postValue(clones.toList())
    }

    private fun save() {
        val arr = JSONArray()
        clones.forEach { arr.put(it.toJson()) }
        sp.edit { putString(KEY, arr.toString()) }
        live.postValue(clones.toList())
    }
}
