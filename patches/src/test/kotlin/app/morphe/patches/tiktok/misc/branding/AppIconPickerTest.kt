package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.patch.PatchException
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The icon picker's manifest and drawables (Discussion #114), on the decoded shape of TikTok's. */
class AppIconPickerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the picker offers every style, then the options' own icon when they drew one`() {
        val styles = listOf("tiktok", "black", "white_on_black", "black_on_white", "material_you")
        assertEquals(styles, pickerIconKeys(false).map(::iconId))
        assertEquals(styles + "custom", pickerIconKeys(true).map(::iconId))
        assertEquals("app.morphe.extension.tiktok.misc.AppIcon.white_on_black", iconAliasName("white-on-black"))
        assertEquals("hushfeed_icon_material_you", iconResourceName("material-you"))
    }

    @Test
    fun `the launcher moves to one alias per icon and only the chosen one is on`() {
        val manifest = parse(MANIFEST)
        addIconPicker(manifest, pickerIconKeys(true), CUSTOM_ICON_KEY)

        val splash = manifest.named("activity-alias", TIKTOK_LAUNCHER)
        assertFalse("TikTok's entry left the launcher", splash.isLauncherEntry())
        assertEquals("deep links still reach TikTok's entry",
            listOf("android.intent.action.MAIN", "android.intent.action.VIEW"),
            splash.getElementsByTagName("action").elements().map { it.getAttribute("android:name") })
        assertEquals("the browsable filter is untouched", listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            splash.getElementsByTagName("category").elements().map { it.getAttribute("android:name") })

        val trampoline = manifest.named("activity", ICON_LAUNCH_ACTIVITY)
        assertEquals("false", trampoline.getAttribute("android:exported"))
        assertEquals("@android:style/Theme.NoDisplay", trampoline.getAttribute("android:theme"))

        val aliases = manifest.getElementsByTagName("activity-alias").elements().filter { it.isLauncherEntry() }
        assertEquals(pickerIconKeys(true).map(::iconAliasName), aliases.map { it.getAttribute("android:name") })
        for (alias in aliases) {
            val id = alias.getAttribute("android:name").removePrefix(ICON_ALIAS_PREFIX)
            assertEquals(ICON_LAUNCH_ACTIVITY, alias.getAttribute("android:targetActivity"))
            assertEquals("true", alias.getAttribute("android:exported"))
            assertEquals((id == "custom").toString(), alias.getAttribute("android:enabled"))
            assertEquals("@mipmap/hushfeed_icon_$id", alias.getAttribute("android:icon"))
            assertEquals("@mipmap/hushfeed_icon_$id", alias.getAttribute("android:roundIcon"))
            assertEquals("@string/app_name", alias.getAttribute("android:label"))
        }
    }

    @Test
    fun `without the options' own icon the chosen style starts on`() {
        val manifest = parse(MANIFEST)
        addIconPicker(manifest, pickerIconKeys(false), IconStyle.BLACK_ON_WHITE.key)
        val on = manifest.getElementsByTagName("activity-alias").elements()
            .filter { it.getAttribute("android:enabled") == "true" }.map { it.getAttribute("android:name") }
        assertEquals(listOf(iconAliasName("black-on-white")), on)
        assertThrows(IllegalArgumentException::class.java) {
            addIconPicker(parse(MANIFEST), pickerIconKeys(false), CUSTOM_ICON_KEY)
        }
    }

    @Test
    fun `a launcher the picker doesn't know is refused before anything changes`() {
        val seasonal = MANIFEST.replace(
            "<service ",
            """<activity-alias android:name="com.ss.android.ugc.aweme.splash.SeasonalIcon"
                    android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                </intent-filter>
            </activity-alias>
            <service """,
        )
        val renamed = MANIFEST.replace(TIKTOK_LAUNCHER, "com.ss.android.ugc.aweme.splash.SplashActivityV2")
        for (xml in listOf(seasonal, renamed)) {
            val manifest = parse(xml)
            val before = serialize(manifest)
            assertThrows(PatchException::class.java) { pickerLauncherEntry(manifest) }
            assertThrows(PatchException::class.java) { addIconPicker(manifest, pickerIconKeys(false), "tiktok") }
            assertEquals(before, serialize(manifest))
        }
    }

    @Test
    fun `each picker icon is its style drawn fresh from TikTok's icon`() {
        val res = folder.newFolder("res")
        val original = res.resolve("mipmap-anydpi-v26/c.xml").apply { parentFile.mkdirs(); writeText(ADAPTIVE_ICON) }
        val layers = IconLayers(parse(BACKGROUND).documentElement, parse(FOREGROUND).documentElement)
        for (style in IconStyle.entries) {
            val expected = parse(ADAPTIVE_ICON).also { restyleAdaptiveIcon(it, layers, style) }
            assertEquals(style.key, serialize(expected), serialize(pickerIcon(original, layers, style.key, IconStyle.BLACK, null, "#ff123456")))
        }
        val custom = parse(ADAPTIVE_ICON).also { restyleAdaptiveIcon(it, layers, IconStyle.WHITE_ON_BLACK, null, "#ff123456") }
        assertEquals(serialize(custom), serialize(pickerIcon(original, layers, CUSTOM_ICON_KEY, IconStyle.WHITE_ON_BLACK, null, "#ff123456")))
        assertEquals("TikTok's own icon is only read", ADAPTIVE_ICON, original.readText())
        assertEquals(res.resolve("mipmap-anydpi-v26/hushfeed_icon_white_on_black.xml"), pickerIconFile(original, "white-on-black"))

        val written = res.resolve("mipmap-anydpi-v26/hushfeed_icon_custom.xml")
        writeXml(custom, written)
        assertEquals(serialize(custom), serialize(parse(written.readText())))
    }

    @Test
    fun `before Android 8 each picker icon is TikTok's own picture`() {
        val layer = parse(legacyPickerIcon("@mipmap/c")).documentElement
        assertEquals("layer-list", layer.tagName)
        val items = layer.getElementsByTagName("item").elements()
        assertEquals(listOf("@mipmap/c"), items.map { it.getAttribute("android:drawable") })
    }

    @Test
    fun `the picker option is off unless asked for`() {
        val option = customLauncherIconPatch.options["iconPicker"]
        assertEquals(false, option.default)
        assertFalse(option.required)
        assertTrue(option.description!!.contains("Android 8"))
    }

    private fun Document.named(tag: String, name: String): Element =
        getElementsByTagName(tag).elements().single { it.getAttribute("android:name") == name }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trim().byteInputStream())

    private fun serialize(document: Document): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it))
    }.toString()

    private companion object {
        /** TikTok's launcher entry as 47.9.1 declares it, with the deep-link filter beside it. */
        val MANIFEST = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zhiliaoapp.musically">
                <application android:icon="@mipmap/c" android:roundIcon="@mipmap/c" android:label="@string/app_name">
                    <activity android:name="com.ss.android.ugc.aweme.main.MainActivity" android:exported="false"/>
                    <activity-alias android:name="com.ss.android.ugc.aweme.splash.SplashActivity" android:exported="true"
                            android:label="@string/app_name" android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW"/>
                            <category android:name="android.intent.category.DEFAULT"/>
                            <category android:name="android.intent.category.BROWSABLE"/>
                        </intent-filter>
                    </activity-alias>
                    <service android:name="com.ss.android.ugc.aweme.tile.PublishTileService" android:icon="@drawable/d78"/>
                </application>
            </manifest>
        """.trimIndent()

        val ADAPTIVE_ICON = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@drawable/c26"/>
              <foreground android:drawable="@drawable/c27"/>
            </adaptive-icon>
        """.trimIndent()

        val BACKGROUND = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:height="108.0dp" android:width="108.0dp" android:viewportWidth="108.0" android:viewportHeight="108.0">
              <path android:fillColor="#ff161823" android:pathData="M 0 0 H 108 V 108 H 0 V 0 Z" android:fillType="evenOdd"/>
            </vector>
        """.trimIndent()

        val FOREGROUND = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:height="108.0dp" android:width="108.0dp" android:viewportWidth="108.0" android:viewportHeight="108.0">
              <path android:fillColor="#ff25f4ee" android:pathData="M48.8,48.9L48.8,46.8Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#fffe2c55" android:pathData="M66.2,39.3C64.53,37.37 63.51,34.85 63.5,32.1Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#ffffffff" android:pathData="M61.1,45.3C64.3,47.62 68.1,48.93 72.2,48.9L72.2,42.9L55.7,63.6Z" android:strokeColor="#00000000"/>
            </vector>
        """.trimIndent()
    }
}
