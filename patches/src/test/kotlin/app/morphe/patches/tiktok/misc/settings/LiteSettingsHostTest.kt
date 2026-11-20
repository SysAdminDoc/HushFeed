package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.extension.ExtensionHook
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * TikTok Lite (#48) has no TikTok screen to show Hushfeed's settings in, so the Settings patch
 * declares Hushfeed's own screen in Lite's manifest, and only Lite's start-up hooks go into Lite.
 */
class LiteSettingsHostTest {
    private fun manifest(): Document {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = xml.createElement("manifest")
        xml.appendChild(root)
        val application = xml.createElement("application")
        val existing = xml.createElement("activity")
        existing.setAttribute("android:name", "com.zhiliaoapp.musically.go.mini.MainActivity")
        application.appendChild(existing)
        root.appendChild(application)
        return xml
    }

    private fun Document.activities(): List<Element> =
        (0 until getElementsByTagName("activity").length).map { getElementsByTagName("activity").item(it) as Element }

    @Test
    fun `the settings screen is declared once, unexported, inside the application`() {
        val xml = manifest()
        declareOwnSettingsActivity(xml)
        declareOwnSettingsActivity(xml)
        val own = xml.activities().filter { it.getAttribute("android:name") == OWN_SETTINGS_ACTIVITY }
        assertEquals(1, own.size)
        assertEquals("application", (own.single().parentNode as Element).tagName)
        assertEquals("false", own.single().getAttribute("android:exported"))
        assertEquals("@android:style/Theme.DeviceDefault.NoActionBar", own.single().getAttribute("android:theme"))
        assertEquals(2, xml.activities().size)
    }

    @Test
    fun `only TikTok Lite gets its own settings screen`() {
        assertTrue(isTikTokLite(AppCompatibilities.TIKTOK_LITE_PACKAGE))
        assertFalse(isTikTokLite(AppCompatibilities.TIKTOK_PACKAGE))
    }

    @Test
    fun `a hook made for one app is left out of another`() {
        val fingerprint = Fingerprint(name = "onCreate")
        val tiktokOnly = ExtensionHook(fingerprint, packageNames = setOf(AppCompatibilities.TIKTOK_PACKAGE))
        assertTrue(tiktokOnly.appliesTo(AppCompatibilities.TIKTOK_PACKAGE))
        assertFalse(tiktokOnly.appliesTo(AppCompatibilities.TIKTOK_LITE_PACKAGE))
        assertTrue(ExtensionHook(fingerprint).appliesTo(AppCompatibilities.TIKTOK_LITE_PACKAGE))
    }

    @Test
    fun `TikTok's own compatibility list stays TikTok alone`() {
        assertEquals(listOf(AppCompatibilities.TIKTOK_PACKAGE), AppCompatibilities.tiktok().map { it.packageName })
        assertEquals(
            listOf(AppCompatibilities.TIKTOK_PACKAGE, AppCompatibilities.TIKTOK_LITE_PACKAGE),
            AppCompatibilities.tiktokAndLite().map { it.packageName },
        )
    }
}
