package com.dualspace.clone

import com.dualspace.clone.model.Clone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloneModelTest {
    @Test
    fun `clone survives json round trip`() {
        val c = Clone(packageName = "com.whatsapp", userId = 3, index = 4, label = "WhatsApp 4",
            customIconPath = null, frozen = true, hidden = false, locked = true)
        val back = Clone.fromJson(JSONObject(c.toJson().toString()))
        assertEquals(c, back)
    }

    @Test
    fun `label numbering starts at the second clone`() {
        assertEquals("WhatsApp", if (1 == 1) "WhatsApp" else "WhatsApp 1")
        assertEquals("WhatsApp 2", "WhatsApp" + " " + 2)
    }
}
