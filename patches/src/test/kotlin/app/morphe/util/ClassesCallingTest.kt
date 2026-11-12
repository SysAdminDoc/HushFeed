/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.patch
import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.methodCall
import app.morphe.patches.tiktok.interaction.haptics.VIBRATIONS
import app.morphe.patches.tiktok.interaction.haptics.VIEW_HAPTICS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [classesCalling] answers from the patcher's type index instead of reading every method, and that
 * index isn't documented (#54 review). The privacy patches, Ghost mode and Allow screenshots only
 * read the classes it names, so a caller it misses is a call they leave alone while still reporting
 * Applied. Held here to a full walk on each declared build, through a real patcher run, and
 * [classesCallingMethods] with it, which those patches now use so a common owner like View doesn't
 * fill the heap (#220).
 */
class ClassesCallingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the type index names every class a full walk finds`() {
        Fixtures.forEachDeclared { apk ->
            val problems = mutableListOf<String>()
            var checked = 0
            var namedChecked = 0
            val probe = patch(name = "classesCalling probe") {
                execute {
                    val callers = HashMap<String, MutableSet<String>>()
                    val interfaces = HashSet<String>()
                    classDefForEach { classDef ->
                        if (classDef.accessFlags and AccessFlags.INTERFACE.value != 0) interfaces += classDef.type
                        classDef.methods.forEach { method ->
                            method.implementation?.instructions?.forEach { instruction ->
                                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                    ?: return@forEach
                                callers.getOrPut(reference.definingClass) { HashSet() } += classDef.type
                            }
                        }
                    }
                    // The framework owners the patches name, then a fixed draw of TikTok's own
                    // classes and interfaces, since Ghost mode's owners are obfuscated ones.
                    val random = Random(54)
                    val owners = (
                        FRAMEWORK_OWNERS +
                            callers.keys.filter { it.startsWith("LX/") && it !in interfaces }.sorted().shuffled(random).take(40) +
                            callers.keys.filter { it in interfaces }.sorted().shuffled(random).take(20)
                        ).distinct()
                    for (owner in owners) {
                        val wanted = callers[owner].orEmpty()
                        val missed = wanted - classesCalling(listOf(owner))
                        if (missed.isNotEmpty()) {
                            problems += "$owner: missed ${missed.size} of ${wanted.size} callers, such as ${missed.take(3)}"
                        }
                    }
                    // The patches ask for several owners at once.
                    val together = owners.flatMapTo(HashSet()) { callers[it].orEmpty() } - classesCalling(owners)
                    if (together.isNotEmpty()) problems += "all ${owners.size} owners at once: missed ${together.size} callers"
                    checked = owners.size

                    // The same owners again by method name, plus View and Vibrator, whose type
                    // lookups held a match for most of TikTok (#220).
                    val named = (owners + HAPTIC_OWNERS).toSet()
                    val methodCallers = HashMap<String, MutableSet<String>>()
                    val descriptors = HashMap<String, String>()
                    classDefForEach { classDef ->
                        classDef.methods.forEach { method ->
                            method.implementation?.instructions?.forEach { instruction ->
                                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                    ?: return@forEach
                                if (reference.definingClass !in named) return@forEach
                                val key = reference.definingClass + "->" + reference.name
                                methodCallers.getOrPut(key) { HashSet() } += classDef.type
                                descriptors.getOrPut(key) { reference.toString() }
                            }
                        }
                    }
                    val pairs = named.flatMap { owner ->
                        methodCallers.keys.filter { it.startsWith("$owner->") }.sorted().shuffled(random).take(4)
                    }
                    for (pair in pairs) {
                        val missed = methodCallers.getValue(pair) - classesCallingMethods(listOf(descriptors.getValue(pair)))
                        if (missed.isNotEmpty()) {
                            problems += "$pair: missed ${missed.size} of ${methodCallers.getValue(pair).size} callers by name"
                        }
                    }
                    val all = pairs.flatMapTo(HashSet()) { methodCallers.getValue(it) } -
                        classesCallingMethods(pairs.map { descriptors.getValue(it) })
                    if (all.isNotEmpty()) problems += "all ${pairs.size} pairs at once: missed ${all.size} callers by name"
                    namedChecked = pairs.size

                    // What Turn off haptics asks for, both ways.
                    val haptics = VIEW_HAPTICS.keys + VIBRATIONS.keys
                    val wantedHaptics = haptics.flatMapTo(HashSet()) { target ->
                        methodCallers[target.substringBefore("->") + "->" + target.substringAfter("->").substringBefore('(')].orEmpty()
                    }
                    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                    val beforeType = threads.currentThreadAllocatedBytes
                    val byType = HAPTIC_OWNERS.sumOf { owner ->
                        Fingerprint(filters = listOf(methodCall(definingClass = owner))).matchAllOrNull().orEmpty().size
                    }
                    val typeBytes = threads.currentThreadAllocatedBytes - beforeType
                    val beforeName = threads.currentThreadAllocatedBytes
                    val byName = classesCallingMethods(haptics)
                    val nameBytes = threads.currentThreadAllocatedBytes - beforeName
                    val nameMatches = haptics.map { it.substringBefore("->") to it.substringAfter("->").substringBefore('(') }
                        .distinct().sumOf { (owner, name) ->
                            Fingerprint(filters = listOf(methodCall(definingClass = owner, name = name))).matchAllOrNull().orEmpty().size
                        }
                    println(
                        "${apk.name}: haptics by type $byType matches, ${typeBytes shr 20} MB allocated; " +
                            "by name $nameMatches matches in ${byName.size} classes, ${nameBytes shr 20} MB allocated",
                    )
                    if (!byName.containsAll(wantedHaptics)) problems += "haptics by name missed ${(wantedHaptics - byName).size} callers"
                    // The point of the name lookup: a few hundred matches, not one for every View caller.
                    if (nameMatches * 50 > byType) problems += "haptics by name kept $nameMatches matches against $byType by type"
                }
            }
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("the probe never ran", checked > 60)
            assertTrue("the name probe never ran", namedChecked > 100)
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private companion object {
        /** Window flags, clipboard, WebView, camera and mic, contacts, telephony and location owners. */
        val FRAMEWORK_OWNERS = listOf(
            "Landroid/view/Window;",
            "Landroid/view/SurfaceView;",
            "Landroid/content/ClipboardManager;",
            "Landroid/webkit/WebView;",
            "Landroid/hardware/Camera;",
            "Landroid/media/AudioRecord;",
            "Landroid/content/ContentResolver;",
            "Landroid/telephony/TelephonyManager;",
            "Landroid/location/LocationManager;",
            "Landroid/content/SharedPreferences\$Editor;",
            "Landroid/app/NotificationManager;",
        )

        val HAPTIC_OWNERS = listOf("Landroid/view/View;", "Landroid/os/Vibrator;")
    }
}
