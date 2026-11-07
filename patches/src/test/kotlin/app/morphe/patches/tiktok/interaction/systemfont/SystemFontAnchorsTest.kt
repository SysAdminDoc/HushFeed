package app.morphe.patches.tiktok.interaction.systemfont

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What "Use system font" rests on, held to each declared TikTok build.
 *
 * The patch swaps the typeface at the exit of TikTok's text font engine. This pins that the patch's
 * own rule finds the engine's two implementations and nothing else, that both of their build
 * methods return a typeface somewhere the hook can stand, and two things behind the promise to
 * leave the other fonts alone: icons are drawn by TuxIconDrawable without any typeface, and none
 * of the font files the engine names itself is the @ and # font or the gift combo font. A caller
 * handing the asset loader one of those paths is not something a constant scan can rule out;
 * that half is the device check.
 */
class SystemFontAnchorsTest {
    @Test
    fun `the font engine is two implementations of one interface on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val engines = classesOf(apk).filter(::isFontEngine)
            assertEquals("$version: font engines ${engines.map { it.type }}", 2, engines.size)
            val shared = engines[0].interfaces.intersect(engines[1].interfaces.toSet())
            assertTrue("$version: the two engines share no interface", shared.isNotEmpty())

            engines.forEach { engine ->
                val builders = engine.methods.filter { it.engineShape() != null }
                assertEquals(
                    "$version: ${engine.type} build methods ${builders.map { it.name }}",
                    2,
                    builders.map { it.engineShape() }.toSet().size,
                )
                builders.forEach { method ->
                    val returns = method.implementation?.instructions
                        ?.count { it.opcode == Opcode.RETURN_OBJECT } ?: 0
                    assertTrue("$version: ${engine.type}->${method.name} returns no typeface", returns > 0)
                }
                // #346: the entry hook reads the request from the first parameters, p0 on a static
                // builder (47.7.2 has a static asset loader beside the instance one) and p1 on an
                // instance one, so both kinds must keep the weight and slant, or the file name, first.
                assertTrue(
                    "$version: ${engine.type} has no instance build method",
                    builders.any { !AccessFlags.STATIC.isSet(it.accessFlags) },
                )
            }
        }
    }

    /**
     * #346: each build method hands its request over at entry from its own parameter registers,
     * the static asset loader's p0 as well as the instance builders' p1 and p2..p3.
     */
    @Test
    fun `the request hook reads each build method's own parameters on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var statics = 0
            classesOf(apk).filter(::isFontEngine).forEach { engine ->
                engine.methods.filter { it.engineShape() != null }.forEach { method ->
                    val static = AccessFlags.STATIC.isSet(method.accessFlags)
                    if (static) statics++
                    val registers = method.implementation!!.registerCount
                    val parameterRegisters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } +
                        if (static) 0 else 1
                    val firstParameter = registers - parameterRegisters + if (static) 0 else 1
                    val variation = method.engineShape() == "[Landroid/graphics/fonts/FontVariationAxis;IZ"
                    val mutable = MutableMethod(method)
                    mutable.systemizeReturns()
                    val entry = mutable.implementation!!.instructions.first()
                    val where = "$version: ${engine.type}->${method.name}"
                    assertEquals(where, registers, mutable.implementation!!.registerCount)
                    assertEquals(where, Opcode.INVOKE_STATIC_RANGE, entry.opcode)
                    val range = entry as RegisterRangeInstruction
                    assertEquals(where, if (variation) firstParameter + 1 else firstParameter, range.startRegister)
                    assertEquals(where, if (variation) 2 else 1, range.registerCount)
                    assertEquals(where, if (variation) "requested" else "requestedAsset",
                        ((entry as ReferenceInstruction).reference as MethodReference).name)
                }
            }
            assertTrue("$version: no static build method, so this checks only the instance shape", statics > 0)
        }
    }

    @Test
    fun `the icon, mention and gift fonts stay outside the engine on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)

            val engineFiles = classes.filter(::isFontEngine)
                .flatMap { engine -> stringsOf(engine) }
                .filter { it.endsWith(".otf") || it.endsWith(".ttf") }
                .toSet()
            assertTrue("$version: engine files $engineFiles", "font/TikTokSans-VF.otf" in engineFiles)
            engineFiles.forEach { file ->
                assertFalse("$version: the engine loads $file", OUTSIDE_FONTS.any { file.contains(it, ignoreCase = true) })
            }

            val icon = classes.firstOrNull { it.type == ICON_DRAWABLE }
            assertNotNull("$version: $ICON_DRAWABLE is gone", icon)
            assertFalse(
                "$version: $ICON_DRAWABLE now touches a Typeface, so icons may reach the font hook",
                touchesTypeface(icon!!),
            )
        }
    }

    /**
     * TikTok has other ways to a text face than the engine's two build methods: a static factory
     * taking nine parameters (size, weight, width, slant, optical size and a mask of defaults),
     * which picks TikTok Display or TikTok Text, and a Compose font loader. Another TikTok patch
     * bundle hooks the factory to swap in a font file. Use system font and Use an imported font
     * stand only at the engine's exit, which covers these too only while each of them builds
     * through the engine's interface and never asks Android for a typeface itself. This holds
     * that, and that the nine-parameter factory is among what it checked.
     */
    @Test
    fun `TikTok's other text font entry points build through the engine on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val engines = classes.filter(::isFontEngine)
            val engineTypes = engines.map { it.type }.toSet()
            val engineInterfaces = engines.map { it.interfaces.toSet() }.reduce { a, b -> a intersect b }
            assertTrue("$version: the engines share no interface", engineInterfaces.isNotEmpty())

            val entryPoints = classes.filter { it.type !in engineTypes }.flatMap { classDef ->
                classDef.methods.filter { method ->
                    method.returnType == TYPEFACE && method.implementation?.instructions?.any { instruction ->
                        val text = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                        text != null && TEXT_FONT_FILES.any { text.startsWith(it) }
                    } == true
                }
            }
            assertTrue(
                "$version: no nine-parameter factory among ${entryPoints.map { "${it.definingClass}->${it.name}" }}",
                entryPoints.any { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.parameterTypes.map { it.toString() } == NINE_PARAMETER_FACTORY
                },
            )
            entryPoints.forEach { method ->
                val calls = method.implementation!!.instructions.mapNotNull {
                    (it as? ReferenceInstruction)?.reference as? MethodReference
                }
                val where = "$version: ${method.definingClass}->${method.name}"
                assertTrue("$where never calls the engine", calls.any { call ->
                    call.definingClass in engineInterfaces && call.returnType == TYPEFACE &&
                        call.parameterTypes.joinToString("").let { it == VARIATION || it == ASSET }
                })
                val own = calls.filter { it.returnType == TYPEFACE && it.definingClass.startsWith("Landroid/") }
                assertTrue("$where asks Android for a typeface itself: ${own.map { "${it.definingClass}->${it.name}" }}",
                    own.isEmpty())
            }
        }
    }

    /**
     * Activity center and Watch history are TikTok web pages (#7). The patch styles them from the
     * page-finished and page-visible callbacks of TikTok's WebViewClients, so this holds that the
     * patch's rule finds callbacks on each build, among them the hybrid kit's web clients, which
     * every Spark web page passes through (on 47.4.3 its script injector and TTP client, beside
     * 17 obfuscated clients).
     */
    @Test
    fun `TikTok's web pages reach a page callback the font patch hooks on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val callbacks = webPageCallbacks(classesOf(apk))
            val owners = callbacks.map { "${it.definingClass}->${it.name}" }
            assertTrue("$version: no onPageFinished among $owners", callbacks.any { it.name == "onPageFinished" })
            assertTrue(
                "$version: no hybrid kit web client callback among $owners",
                callbacks.any { it.definingClass.startsWith(HYBRID_WEB_KIT) },
            )
        }
    }

    private companion object {
        const val HYBRID_WEB_KIT = "Lcom/ss/android/ugc/aweme/hybridkit/web/"
        const val TYPEFACE = "Landroid/graphics/Typeface;"
        const val VARIATION = "[Landroid/graphics/fonts/FontVariationAxis;IZ"
        const val ASSET = "Ljava/lang/String;"

        /** The static faces the factory and the Compose loader fall back to below Android 8. */
        val TEXT_FONT_FILES = listOf("font/TikTok-Display-", "font/TikTok-Text-")
        val NINE_PARAMETER_FACTORY = listOf(
            "F", "I", "F", "F", "Ljava/lang/Float;", "F", "I", "Ljava/util/Map;", "I",
        )
        const val ICON_DRAWABLE = "Lcom/bytedance/tux/drawable/TuxIconDrawable;"

        /** Font files the patch description says keep their own look. */
        val OUTSIDE_FONTS = listOf("icon", "mention_and_hashtag", "gift_combo")

        fun classesOf(apk: File): List<ClassDef> {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            return container.dexEntryNames.flatMap { entry -> container.getEntry(entry)!!.dexFile.classes }
        }

        fun stringsOf(classDef: ClassDef): List<String> = classDef.methods.flatMap { method ->
            method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            }
        }

        fun touchesTypeface(classDef: ClassDef): Boolean {
            if (classDef.fields.any { it.type == TYPEFACE }) return true
            return classDef.methods.any { method ->
                method.returnType == TYPEFACE || method.parameterTypes.any { it.toString() == TYPEFACE } ||
                    method.implementation?.instructions?.toList().orEmpty().any { instruction ->
                        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                            is TypeReference -> reference.type == TYPEFACE
                            is MethodReference -> reference.definingClass == TYPEFACE ||
                                reference.returnType == TYPEFACE || reference.parameterTypes.any { it.toString() == TYPEFACE }
                            is FieldReference -> reference.type == TYPEFACE
                            else -> false
                        }
                    }
            }
        }
    }
}
