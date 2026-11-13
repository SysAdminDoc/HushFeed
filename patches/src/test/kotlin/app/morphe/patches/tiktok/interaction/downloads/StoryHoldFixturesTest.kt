package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the story saver reads when TikTok's press-and-hold timer fires, held to each declared
 * build. It read 46.2.3's field names (view.LLJIJIL, then .LLJIJIL and .LL) until 2026-09-27, and
 * neither 47.x build has them, so the hold never saved a story there. It goes by TikTok's own
 * names now: the monitor by the ability it implements and the story by the cell params it is
 * bound to. The two steps between them are generic Assem fields a dex can't type, so the saver
 * searches them and StoryHoldTest holds that search.
 */
class StoryHoldFixturesTest {
    @Test
    fun `the story hold's monitor and cell params keep TikTok's own names on every declared build`() {
        val saver = listOf("../$SAVER", SAVER).map(::File).firstOrNull { it.isFile }
        assertNotNull("could not find the story saver from ${File(".").absolutePath}", saver)
        val source = saver!!.readText()
        val ability = typeOf(Regex("""MONITOR_ABILITY =\s*"([\w.]+)"""").find(source)?.groupValues?.get(1))
        val params = typeOf(Regex("""CELL_PARAMS =\s*"([\w.]+)"""").find(source)?.groupValues?.get(1))

        Fixtures.forEachDeclared { apk ->
            val kept = classesOf(apk) { def ->
                def.type == params || def.type == CELL_COMPONENT || ability in def.interfaces ||
                    (RUNNABLE in def.interfaces && def.fields.count() == 1) ||
                    def.fields.any { it.type == TOUCH_LISTENER } ||
                    // Interfaces shaped like the hold's listener: a pause and a release.
                    (AccessFlags.INTERFACE.isSet(def.accessFlags) && def.methods.count() == 2 &&
                        def.methods.all { it.returnType == "V" && it.parameterTypes.isEmpty() })
            }.associateBy { it.type }

            val monitors = kept.values.filter { ability in it.interfaces }
            assertTrue("$apk: nothing implements $ability", monitors.isNotEmpty())

            // The monitor is a cell component, and a cell component binds a VideoItemParams:
            // BaseCellLogicComponent's generic signature names it (X.0RhF<VideoItemParams> on
            // 46.2.3, X.05Ba on 47.0.3, X.04E1 on 47.1.3). The Assem state keeps that item in a
            // plain Object field, which is why the saver looks for it by class.
            for (monitor in monitors) {
                assertEquals("$apk: ${monitor.type} extends", CELL_COMPONENT, monitor.superclass)
            }
            val cellSignature = kept[CELL_COMPONENT]?.annotations
                ?.singleOrNull { it.type == "Ldalvik/annotation/Signature;" }
                ?.elements?.single()?.value as? ArrayEncodedValue
            assertNotNull("$apk: $CELL_COMPONENT's generic signature", cellSignature)
            val signature = cellSignature!!.value.joinToString("") { (it as StringEncodedValue).value }
            assertTrue("$apk: a cell component no longer binds $params: $signature", "<$params>" in signature)

            // The timer as resolveStoryLongPressTimer finds it: a Runnable built from the one view
            // it holds, which is a View holding a touch listener and a field back of the timer,
            // with one plain ()V method beside run.
            val timers = kept.values.filter { timer ->
                val held = timer.fields.singleOrNull()?.type
                RUNNABLE in timer.interfaces && held != null &&
                    kept[held]?.let { view ->
                        view.superclass == VIEW && view.fields.any { it.type == TOUCH_LISTENER } &&
                            view.fields.any { it.type == timer.type }
                    } == true &&
                    timer.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(held) } &&
                    timer.methods.count {
                        it.name != "<init>" && it.name != "run" && it.returnType == "V" &&
                            it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
                    } == 1
            }
            assertEquals("$apk: story hold timers ${timers.map { it.type }}", 1, timers.size)
            val view = kept.getValue(timers.single().fields.single().type)
            val monitorTypes = monitors.flatMap { it.interfaces + it.type }.toSet()
            assertTrue(
                "$apk: ${view.type} holds no field a ${ability} component can sit in",
                view.fields.any { it.type in monitorTypes },
            )

            // The timer pauses through the hold's listener before its panel, and the monitor
            // answers to that listener with a release of its own, which the story checklist holds
            // back while it is up (#393).
            val timer = timers.single().methods.single {
                it.name != "<init>" && it.name != "run" && it.returnType == "V" && it.parameterTypes.isEmpty()
            }
            val pause = timer.implementation!!.instructions.first { it.opcode == Opcode.INVOKE_INTERFACE }
                .let { (it as ReferenceInstruction).reference as MethodReference }
            val listener = kept[pause.definingClass]
            assertNotNull("$apk: the hold's listener ${pause.definingClass} is a pause and a release", listener)
            val release = listener!!.methods.single { it.name != pause.name }.name
            val answering = monitors.filter { listener.type in it.interfaces }
            assertTrue("$apk: no ${ability} component answers to ${listener.type}", answering.isNotEmpty())
            for (monitor in answering) {
                val own = monitor.methods.singleOrNull {
                    it.name == release && it.returnType == "V" && it.parameterTypes.isEmpty()
                }
                assertNotNull("$apk: ${monitor.type}.$release()", own?.implementation)
                assertTrue("$apk: ${monitor.type}.$release() has no free register below this",
                    own!!.implementation!!.registerCount in 2..16)
            }

            val cellParams = kept[params]
            assertNotNull("$apk: $params", cellParams)
            val getAweme = cellParams!!.methods.singleOrNull { it.name == "getAweme" && it.parameterTypes.isEmpty() }
            assertNotNull("$apk: $params.getAweme()", getAweme)
            assertEquals("$apk: $params.getAweme()", AWEME, getAweme!!.returnType)
            assertTrue("$apk: $params.getAweme() is public", AccessFlags.PUBLIC.isSet(getAweme.accessFlags))
        }
    }

    private fun typeOf(className: String?): String {
        assertNotNull("the saver no longer names its class", className)
        return "L" + className!!.replace('.', '/') + ";"
    }

    /** The APK's classes that [wanted] takes. Only those are kept: a whole 47.x build fills the test heap. */
    private fun classesOf(apk: File, wanted: (ClassDef) -> Boolean): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .filter(wanted)
            .toList()
    }

    private companion object {
        const val SAVER = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/download/StoryDownloads.java"
        const val RUNNABLE = "Ljava/lang/Runnable;"
        const val VIEW = "Landroid/view/View;"
        const val CELL_COMPONENT = "Lcom/ss/android/ugc/feed/platform/cell/BaseCellLogicComponent;"
        const val TOUCH_LISTENER = "Landroid/view/View\$OnTouchListener;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    }
}
