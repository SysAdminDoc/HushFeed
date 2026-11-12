/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.decode
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.colorOption
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildChoice
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildChoicePatch
import app.morphe.patches.tiktok.misc.settings.configurableResourcePatch as resourcePatch
import java.io.File
import java.util.TreeSet
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

@Suppress("unused")
val amoledThemePatch = resourcePatch(
    name = "AMOLED dark theme",
    description = "Makes TikTok's dark mode backgrounds pure black, or a dark color you pick " +
        "in this patch's options. Black lets OLED screens switch those pixels off. Light mode " +
        "doesn't change. The AMOLED dark mode switch in Hushfeed settings > App starts on, and " +
        "turning it off brings TikTok's own dark grays back.",
    default = false,
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.AMOLED))
    // The switch in Hushfeed settings: the hooks that put TikTok's own grays back while it's off (#372).
    dependsOn(amoledThemeSwitchPatch)
    val background by colorOption(
        key = "backgroundColor",
        default = "#000000",
        title = "Dark background color",
        description = "Pick a color, or type a solid color code like #000000. Black lets " +
            "OLED screens turn those pixels off completely.",
        values = mapOf("Black" to "#000000", "Mocha" to "#181825", "Dark gray" to "#121212"),
    )
    // The cards on TikTok's Compose settings pages come from a palette in code, not resources (#152).
    dependsOn(amoledComposeCardsPatch { background })
    // The comment bar under an opened post is see-through in every theme, so a photo showed through it (#171).
    dependsOn(amoledInputBarPatch { background })
    // Activity center and the history pages are TikTok web pages, out of the resources' reach (#263).
    dependsOn(amoledWebPagesPatch { background })
    execute {
        paintAmoledResources(background)
        BuildDetails.amoled(resourceFile(BUILD_DETAILS_ASSET), amoledColor(background))
    }
}

/**
 * The resource half of AMOLED dark theme: the dark palette's grays and the dark surface literals
 * become [background], and TikTok's own values are kept beside them for the switch in Hushfeed
 * settings (#372). Answers the theme attributes whose stock value was kept, by index.
 */
internal fun paintAmoledResources(background: String?): List<String> {
    val packageRoot = resourceFile("res").parentFile
    val decodedRoots = decodedPackageRoots(packageRoot)
    val decodable by lazy { listDecodableFiles("res/**").toSet() }
    val collisions = renamedPathCollisions(
        entries = listApkEntries("res/"),
        decodedDirectories = decodedResourceDirectories(decodedRoots),
        aliasOf = { name -> resourceFile(name, false).relativeTo(packageRoot).invariantSeparatorsPath },
        isDecoded = { name -> name in decodable || decodedRoots.any { it.resolve(name).isFile } },
    )
    if (collisions.isNotEmpty()) throw PatchException(renamedPathsRefusal(collisions.size))
    val color = amoledColor(background)
    // The dark token block's background grays under this build's own names. Read before any
    // file is touched: a build whose palette was never read is refused with nothing changed.
    val backgrounds = darkBackgroundColors(packageMetadata.versionName)
    val surfaces = surfaceStyleItems(packageMetadata.versionName)
    val raised = packageMetadata.versionName?.let { RAISED_STYLE_ITEMS[it] }.orEmpty()
    val found = mutableSetOf<String>()
    val styleItemsFound = mutableSetOf<String>()
    decode("res/values*/colors.xml", "res/values*/styles.xml", "res/values/attrs.xml")
    val valuesDirectories = resourceFile("res").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values") }
    val colorFiles = valuesDirectories.map { it.resolve("colors.xml") }.filter { it.exists() }
    val styleFiles = valuesDirectories.map { it.resolve("styles.xml") }.filter { it.exists() }
    // Read only, before anything is written: which attributes to keep, and whether a name the
    // switch adds is already taken, which refuses the build with nothing changed.
    val reader = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val styleDocuments = styleFiles.map { reader.parse(it) }
    val attrsFile = resourceFile("res/values/attrs.xml")
    val taken = restoreNamesTaken(
        styleDocuments + colorFiles.map { reader.parse(it) } + listOfNotNull(attrsFile.takeIf { it.isFile }?.let { reader.parse(it) }),
    )
    if (taken.isNotEmpty()) {
        throw PatchException("AMOLED dark theme: this TikTok already has resources named ${taken.take(3)}, so nothing was changed. Patch an unpatched APK.")
    }
    val restorable = restorableAttributes(styleDocuments, surfaces, backgrounds)
    colorFiles.forEach { file ->
        document(file.relativeTo(resourceFile(".")).invariantSeparatorsPath).use { xml ->
            // TikTok's gray goes on under a name of its own first, for the switch to point back at.
            addTwinColors(xml, backgrounds)
            val colors = xml.getElementsByTagName("color")
            for (index in 0 until colors.length) {
                val entry = colors.item(index) as Element
                val name = entry.getAttribute("name")
                if (name in backgrounds) {
                    entry.textContent = color
                    found.add(name)
                }
            }
        }
    }
    // The sheets, the long-press panel and the Inbox Activity page never touch colors.xml.
    // They fill with theme attributes whose dark values are literals inside a <style>
    // (SURFACE_STYLE_ITEMS). Only a dark literal is rewritten, so a light style's white
    // stays white. Every style keeps its own value beside each of those first.
    styleFiles.forEach { file ->
        document(file.relativeTo(resourceFile(".")).invariantSeparatorsPath).use { xml ->
            addStockSiblings(xml, restorable, backgrounds)
            styleItemsFound += rewriteDarkStyleItems(xml, surfaces, color, raised)
        }
    }
    if (found != backgrounds) throw PatchException("Dark background palette is incomplete: $found")
    // The palette check above refuses every build whose names weren't read, so this is a
    // build whose surface names are known, and one missing means an identity is wrong.
    if (styleItemsFound != surfaces) {
        throw PatchException("Dark surface style items are incomplete: $styleItemsFound")
    }
    document("res/values/attrs.xml").use { declareStockAttributes(it, restorable) }
    document("res/values/styles.xml").use { addRestoreStyles(it, restorable) }
    return restorable
}

/**
 * Every name the switch adds starts with this. The extension finds them by name, which is why an
 * APK already holding one is refused rather than patched twice.
 */
internal const val AMOLED_RESTORE_PREFIX = "hushfeed_amoled_"

/** TikTok's own value of a background color the patch repaints, kept under this name. */
internal fun twinColor(name: String) = "${AMOLED_RESTORE_PREFIX}twin_$name"

/** The attribute every style sets to its own stock value of restorable attribute [index]. */
internal fun stockAttribute(index: Int) = "${AMOLED_RESTORE_PREFIX}stock_$index"

/** The style pointing restorable attribute [index] at its stock attribute. */
internal fun restoreStyleName(index: Int) = "${AMOLED_RESTORE_PREFIX}restore_$index"

/** The style pointing every restorable attribute at its stock attribute at once. */
internal const val RESTORE_ALL_STYLE = "${AMOLED_RESTORE_PREFIX}restore_all"

/**
 * The theme attributes the switch can put back, sorted so each keeps one index: every surface
 * item, and every item whose value is one of the repainted colors itself (`@color/a2c`). On
 * 47.7.3 those are the dark app themes' g5d to g62, which point straight at the palette.
 */
internal fun restorableAttributes(styles: List<Document>, surfaces: Set<String>, colors: Set<String>): List<String> {
    val names = TreeSet(surfaces)
    val references = colors.mapTo(HashSet()) { "@color/$it" }
    styles.forEach { document ->
        amoledElements(document, "item").forEach { item ->
            if (item.textContent.trim() in references) names += item.getAttribute("name")
        }
    }
    return names.toList()
}

/** The names among [documents]' attrs, colors and styles that a switch resource would take. */
internal fun restoreNamesTaken(documents: List<Document>): List<String> =
    documents.flatMap { document -> listOf("attr", "color", "style").flatMap { amoledElements(document, it) } }
        .map { it.getAttribute("name") }
        .filter { it.startsWith(AMOLED_RESTORE_PREFIX) }

/** A twin holding TikTok's value for each of [names] this file defines, added at its end. */
internal fun addTwinColors(colors: Document, names: Set<String>): Int {
    val entries = amoledElements(colors, "color").filter { it.getAttribute("name") in names }
    entries.forEach { entry ->
        colors.documentElement.appendChild(colors.createElement("color").apply {
            setAttribute("name", twinColor(entry.getAttribute("name")))
            textContent = entry.textContent
        })
    }
    return entries.size
}

/**
 * Next to every item of a [restorable] attribute, an item of its stock attribute holding the same
 * value, so whichever style a theme applied last also says what TikTok gave the attribute. A
 * value naming one of the repainted [colors] names its twin instead. Answers how many it added.
 */
internal fun addStockSiblings(styles: Document, restorable: List<String>, colors: Set<String>): Int {
    val index = restorable.withIndex().associate { it.value to it.index }
    var added = 0
    amoledElements(styles, "item").forEach { item ->
        val at = index[item.getAttribute("name")] ?: return@forEach
        val value = item.textContent.trim()
        val named = value.removePrefix("@color/")
        val stock = if (value.startsWith("@color/") && named in colors) "@color/${twinColor(named)}" else item.textContent
        item.parentNode.insertBefore(styles.createElement("item").apply {
            setAttribute("name", stockAttribute(at))
            textContent = stock
        }, item.nextSibling)
        added++
    }
    return added
}

/**
 * A stock attribute per restorable attribute, in the format the attribute itself takes, or a
 * reference or color for one declared elsewhere (a framework attribute).
 */
internal fun declareStockAttributes(attrs: Document, restorable: List<String>) {
    val formats = amoledElements(attrs, "attr").filter { it.getAttribute("format").isNotBlank() }
        .associate { it.getAttribute("name") to it.getAttribute("format") }
    restorable.forEachIndexed { index, name ->
        attrs.documentElement.appendChild(attrs.createElement("attr").apply {
            setAttribute("name", stockAttribute(index))
            setAttribute("format", formats[name] ?: "reference|color")
        })
    }
}

/**
 * The styles the extension applies while the switch is off: one per restorable attribute, and
 * one with them all, each pointing the attribute at its stock attribute (`?attr/...`), which the
 * theme answers with the value of whichever style it applied last.
 */
internal fun addRestoreStyles(styles: Document, restorable: List<String>) {
    val all = styles.createElement("style").apply { setAttribute("name", RESTORE_ALL_STYLE) }
    restorable.forEachIndexed { index, name ->
        fun item() = styles.createElement("item").apply {
            setAttribute("name", name)
            textContent = "?attr/${stockAttribute(index)}"
        }
        styles.documentElement.appendChild(styles.createElement("style").apply {
            setAttribute("name", restoreStyleName(index))
            appendChild(item())
        })
        all.appendChild(item())
    }
    styles.documentElement.appendChild(all)
}

/** Every element named [tag] in [document], as a list taken before anything is added. */
internal fun amoledElements(document: Document, tag: String): List<Element> {
    val list = document.getElementsByTagName(tag)
    return (0 until list.length).map { list.item(it) as Element }
}

internal fun renamedPathsRefusal(collisions: Int, patch: String = "AMOLED dark theme") =
    "$patch: this TikTok APK was merged from a split bundle in a way that moved " +
        "its resource files into new folders, and $collisions of their paths clash with the " +
        "names the resource rebuild gives other files. The rebuild would drop files and " +
        "TikTok would crash at launch. Nothing was changed. Patch the .apkm in Morphe Manager, " +
        "which keeps TikTok's own paths, or patch the full APK from APKMirror."

/**
 * The decoded package directories: the one holding [packageRoot] and its siblings that have a
 * `res` directory. Every resource package decodes to its own directory, and a clash can be in
 * any of them.
 */
internal fun decodedPackageRoots(packageRoot: File): List<File> =
    (listOf(packageRoot) + packageRoot.parentFile?.listFiles().orEmpty()
        .filter { it.isDirectory && it.resolve("res").isDirectory })
        .distinctBy { it.absoluteFile.normalize() }

/** Every `res/<folder>` the decoder wrote, across [roots]. */
internal fun decodedResourceDirectories(roots: List<File>): Set<String> =
    roots.flatMapTo(mutableSetOf()) { root ->
        root.resolve("res").listFiles().orEmpty().filter { it.isDirectory }.map { "res/${it.name}" }
    }

/**
 * Input resource paths that Morphe's resource rebuild can drop: a path whose own file was
 * decoded under another name (the path map renamed it) while another resource's decoded file
 * sits at that same path.
 *
 * <p>The decoder writes every resource file to `res/<type>/<entry name>` and records the
 * original archive path beside it; the rebuild renames each file back. TikTok's own paths are
 * short folders (`res/b/cfz.xml`), so nothing clashes, and Morphe Manager's merge of an .apkm
 * keeps them. The desktop CLI's merge moves every file into a folder named for its type and
 * keeps the short name, so entry `ac`'s file becomes `res/drawable/a0.xml`, which is also where
 * entry `a0`'s file decodes. On APKMirror's 46.2.3 and 47.0.3 bundles that made 14,468 and
 * 14,925 such clashes, the rebuild lost 1,375 and 1,361 files, all clashing paths, and TikTok died
 * inflating its first feed layout on the S22 (2026-09-18). Manager's merge of the same bundles
 * and both universal APKs have none and lose nothing. A file kept at its own path (Play's
 * `res/xml/splits0.xml` in a universal APK) is not a clash: its alias is itself.
 */
internal fun renamedPathCollisions(
    entries: Iterable<String>,
    decodedDirectories: Set<String>,
    aliasOf: (String) -> String,
    isDecoded: (String) -> Boolean,
): List<String> = entries.filter { name ->
    // The folder check comes first and is free: a clash can only sit where the decoder writes,
    // and on a clean input none of the archive's folders is one of those.
    name.substringBeforeLast('/', "") in decodedDirectories && aliasOf(name) != name && isDecoded(name)
}

/**
 * The dark theme's background grays, per build: the opaque dark grays of the color token block
 * the dark app themes set, which the surfaces read. The names are each build's own and move:
 * 47.0.3 and 47.1.3 each added a color ahead of the block, so every gray moved one name along
 * (47.4.3 added two), and an older build's names there are an accent and see-through white
 * overlays, which the patch painted black until this was read off the fixture (AmoledPaletteTest
 * holds every fixture's block to its entry here). Only the declared build is listed, so a build
 * the bundle moves to needs its own entry read off its fixture. 47.7.3's sit at a2c to a2p, well
 * short of 47.6.3's a3w to a49, so a shift by one would have painted other colors. The block's pure
 * black is a token of its own and stays.
 */
internal val DARK_BACKGROUND_COLORS: Map<String, Set<String>> = linkedMapOf(
    "47.7.3" to setOf("a2c", "a2e", "a2f", "a2h", "a2p"),
)

/** This build's background grays, or a refusal: a name carried over from another build is a guess. */
internal fun darkBackgroundColors(versionName: String?): Set<String> =
    versionName?.let { DARK_BACKGROUND_COLORS[it] } ?: throw PatchException(unreadPaletteRefusal(versionName))

internal fun unreadPaletteRefusal(versionName: String?): String {
    val build = if (versionName.isNullOrBlank()) "this TikTok build" else "TikTok $versionName"
    val known = DARK_BACKGROUND_COLORS.keys.toList()
    val builds = if (known.size == 1) known.single() else known.dropLast(1).joinToString(", ") + " and " + known.last()
    return "AMOLED dark theme hasn't read the dark palette of $build, so nothing was changed. " +
        "TikTok renames its colors from one build to the next, and a name taken from another " +
        "build can paint an accent or an overlay black. The palette is known for TikTok $builds."
}

/** The builds the bundle declares. */
internal fun declaredVersions(): Set<String> =
    AppCompatibilities.tiktok().flatMap { it.targets }.mapNotNull { it.version }.toSet()

/**
 * The dark style literals behind TikTok's sheets and pages per build, named by TikTok's own token
 * table (AmoledSheetTokensTest). On 47.7.3:
 * - aif is UISheetFlat1's dark value (attr/a26), the fill of the comment panel, the share sheet
 *   and TikTok's other sheets, panels and modals.
 * - ail is UISheetGrouped1's (attr/a2b), the fill of the feed's long-press panel and the other
 *   sheets that group their rows into cards.
 * - ain is UISheetGrouped2's (attr/a2d), those cards: the long-press panel's rows, the comment
 *   menu's and the profile menu panel's. They stayed gray on a black sheet through 0.80.0 (#312).
 * - bx is BGPrimary, a literal of its own in the dark themes, which the Inbox Activity page, its
 *   rows and New followers fill with.
 * - ags is UIPageGrouped2's (attr/a0i), the cards of TikTok's own settings pages: a TuxCell off
 *   a sheet fills with it. The pages behind them are UIPageGrouped1, black in TikTok's own dark
 *   mode already, so those cards were the only gray left on Settings and privacy (#152). A
 *   TuxCell on a sheet (the profile menu panel) fills with UISheetGrouped2 instead.
 * - agk and agq are UIPageFlat1's and UIPageGrouped1's (attr/a0a, attr/a0g), the pages
 *   themselves: Inbox, profiles and TikTok's other full screens. They're #000000 in three dark
 *   themes and #0C0E12 in the tinted one, so a picked color left those pages black (#348).
 *   agl and agr are the same pages raised (UIPageFlat1Elevated, UIPageGrouped1Elevated, #1E1E1E),
 *   and agm is UIPageFlat2 (attr/a0c), the gray band a page sets off a part of itself with.
 * - agp (UIPageFlat3Elevated) and c3 were the comments and share sheets' own through 46.x, under
 *   other names, and are dark surfaces of their own on 47.x.
 * - aij is UISheetFlat3's (attr/a2_), the fill of TikTok's toasts (the "You'll see fewer posts
 *   like this" one after Not interested) and of its popover menus, the comments' sort menu among
 *   them: TuxToast's background and TuxTooltip's popover background both point at a2_. Both
 *   stayed #3A3A3A under AMOLED through 0.81.0 (#368).
 * - aih is UISheetFlat2's (attr/a28), the second fill on a sheet: the circle behind the comment
 *   panel's close X (drawable af9, which CommentPageActionBarAssem sets on the X when TikTok draws
 *   round header buttons), TuxTextCell's background, and other shapes, rows and panels on sheets.
 *   Its 61 uses in 47.7.3's XML are all solids and backgrounds, none a text, icon or stroke color
 *   (one is a 1px line under a card, which goes black with the sheet). It's #2C2C2C, #3A3A3A,
 *   #2A2F35 or #292D32 by palette, all under the plain gate, and the light themes send a28 to axs.
 *   The X stayed on a gray circle on the black comments before (#371).
 * The token names move a place or two with every build, and on 46.x a name these use now was
 * another surface tier, which is why only a build whose names were read is patched.
 */
internal val SURFACE_STYLE_ITEMS: Map<String, Set<String>> = linkedMapOf(
    "47.7.3" to setOf("agp", "c3", "aif", "ail", "ain", "bx", "ags", "agk", "agl", "agm", "agq", "agr", "aij", "aih"),
)

/**
 * Surface items a palette sets lighter than [isDarkOpaqueLiteral]'s gate. UISheetFlat3 (aij) is
 * #484848 in a_j and #323C43 and #333D43 in the two tinted palettes the dark-mode experiment
 * applies, so under the 0x40 gate only the default palette's toast went black. These are taken
 * up to [RAISED_SURFACE_CEILING]; every value they hold is a raised sheet gray, never text.
 */
internal val RAISED_STYLE_ITEMS: Map<String, Set<String>> = linkedMapOf(
    "47.7.3" to setOf("aij"),
)

internal const val RAISED_SURFACE_CEILING = 0x50

/** This build's surface style items, or a refusal like the palette's. */
internal fun surfaceStyleItems(versionName: String?): Set<String> =
    versionName?.let { SURFACE_STYLE_ITEMS[it] } ?: throw PatchException(unreadPaletteRefusal(versionName))

/**
 * Sets every `<item name="...">` in the named set whose value is a dark opaque colour literal
 * to [color], and returns the names it changed. A value that is a reference, translucent or
 * light is left alone: those are the light theme's and the overlays', not the surface. A name in
 * [raised] is taken up to [RAISED_SURFACE_CEILING] instead.
 */
internal fun rewriteDarkStyleItems(
    styles: Document,
    names: Set<String>,
    color: String,
    raised: Set<String> = emptySet(),
): Set<String> {
    val changed = mutableSetOf<String>()
    val items = styles.getElementsByTagName("item")
    for (index in 0 until items.length) {
        val item = items.item(index) as Element
        val name = item.getAttribute("name")
        if (name !in names) continue
        val ceiling = if (name in raised) RAISED_SURFACE_CEILING else DARK_SURFACE_CEILING
        if (!isOpaqueLiteralUnder(item.textContent, ceiling)) continue
        item.textContent = color
        changed.add(name)
    }
    return changed
}

private const val DARK_SURFACE_CEILING = 0x40

/** `#RRGGBB` or `#FFRRGGBB`, every channel under 0x40: a surface colour, not a text or an overlay. */
internal fun isDarkOpaqueLiteral(value: String): Boolean = isOpaqueLiteralUnder(value, DARK_SURFACE_CEILING)

/** `#RRGGBB` or `#FFRRGGBB` with every channel under [ceiling]. */
internal fun isOpaqueLiteralUnder(value: String, ceiling: Int): Boolean {
    val hex = value.trim().removePrefix("#")
    val rgb = when (hex.length) {
        6 -> hex
        8 -> if (hex.startsWith("ff", ignoreCase = true)) hex.substring(2) else return false
        else -> return false
    }
    if (!rgb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
    return rgb.chunked(2).all { it.toInt(16) < ceiling }
}
