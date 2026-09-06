package com.dualspace.clone.model

import org.json.JSONObject
import java.util.UUID

/**
 * One cloned app instance.
 *
 * A clone is identified by (packageName, userId). The engine keeps a separate sandbox
 * per virtual user id, so cloning the same package N times simply means installing it
 * into N different user slots. There is no upper bound other than storage.
 */
data class Clone(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val userId: Int,
    /** 1-based index among clones of the same package: "WhatsApp 2", "WhatsApp 3"… */
    val index: Int,
    var label: String,
    /** Absolute path of a user-chosen icon, or null to use the original app icon with a badge. */
    var customIconPath: String? = null,
    var frozen: Boolean = false,
    var hidden: Boolean = false,
    var locked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("userId", userId)
        put("index", index)
        put("label", label)
        put("customIconPath", customIconPath ?: JSONObject.NULL)
        put("frozen", frozen)
        put("hidden", hidden)
        put("locked", locked)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Clone = Clone(
            id = o.getString("id"),
            packageName = o.getString("packageName"),
            userId = o.getInt("userId"),
            index = o.optInt("index", 1),
            label = o.getString("label"),
            customIconPath = if (o.isNull("customIconPath")) null else o.optString("customIconPath"),
            frozen = o.optBoolean("frozen", false),
            hidden = o.optBoolean("hidden", false),
            locked = o.optBoolean("locked", false),
            createdAt = o.optLong("createdAt", 0L)
        )
    }
}

/** An app installed on the phone that can be cloned. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val sourceDir: String,
    /** false when the app ships native libs only for the other CPU architecture. */
    val abiSupported: Boolean,
    val isSystem: Boolean
)
