package app.morphe.patches.tiktok.misc.settings

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * #377: the update card's Update button opens Morphe Manager, which a TikTok targeting Android 11
 * or later can only start when its manifest names Manager in a queries element.
 */
class ManagerQueryManifestTest {
    private fun manifest(vararg queried: String): Document {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = xml.createElement("manifest")
        xml.appendChild(root)
        queried.forEach { name ->
            val queries = xml.createElement("queries")
            val element = xml.createElement("package")
            element.setAttribute("android:name", name)
            queries.appendChild(element)
            root.appendChild(queries)
        }
        root.appendChild(xml.createElement("uses-permission"))
        root.appendChild(xml.createElement("application"))
        return xml
    }

    private fun Document.queried(): List<String> =
        (0 until getElementsByTagName("package").length)
            .map { getElementsByTagName("package").item(it) as Element }
            .filter { (it.parentNode as Element).tagName == "queries" }
            .map { it.getAttribute("android:name") }

    private fun Document.rootTags(): List<String> =
        (0 until documentElement.childNodes.length)
            .mapNotNull { documentElement.childNodes.item(it) as? Element }
            .map { it.tagName }

    @Test
    fun `Manager is named in its own queries element before the application`() {
        val xml = manifest("com.zhiliao.musically.livewallpaper")
        declareManagerQuery(xml)
        assertEquals(listOf("com.zhiliao.musically.livewallpaper", "app.morphe.manager"), xml.queried())
        assertEquals(listOf("queries", "uses-permission", "queries", "application"), xml.rootTags())
    }

    @Test
    fun `a manifest that already names Manager is left alone`() {
        val xml = manifest("app.morphe.manager")
        declareManagerQuery(xml)
        declareManagerQuery(xml)
        assertEquals(listOf("app.morphe.manager"), xml.queried())
    }

    @Test
    fun `the package is the one the update card opens`() {
        assertEquals("app.morphe.manager", MORPHE_MANAGER_PACKAGE)
    }
}
