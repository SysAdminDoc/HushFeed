/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The For You show repair (#322, #326), held to every declared build: every member is read off
 * For You's own show and Hox's switch, each filled stub makes exactly its one call or read and
 * passes the verifier's typing, and the constructor the patch hooks has a plain return.
 */
class ForYouShowRepairTest {
    private val extension = "Lapp/morphe/extension/tiktok/navigation/ForYouShowRepair;"
    private val objectType = "Ljava/lang/Object;"
    private val stringType = "Ljava/lang/String;"
    private val bundleType = "Landroid/os/Bundle;"

    /** The members a S22 trace on 47.6.3 proved: Home's own switch to For You and For You's show. */
    private val proved4763 = mapOf(
        "nodeOf" to "N23", "container" to "LL", "current" to "LLJJJJ", "shown" to "LLJJJJJIL",
        "dispatch" to "LJJII", "visibleListeners" to "LLJJJJ", "switchListeners" to "LLJJJJJIL",
        "hoxOf" to "H23", "containerTagOf" to "O23", "panel" to "LLJJJJ",
    )

    /** Each stub as the extension declares it: parameter types, then the return type. */
    private val stubs = mapOf(
        "readView" to (listOf(objectType) to "Landroid/view/View;"),
        "readResumed" to (listOf(objectType) to "Z"),
        "readHidden" to (listOf(objectType) to "Z"),
        "readPanel" to (listOf(objectType) to objectType),
        "readHint" to (listOf(objectType) to "Z"),
        "readActivity" to (listOf(objectType) to objectType),
        "readHox" to (listOf(objectType) to objectType),
        "readContainerTag" to (listOf(objectType, stringType) to stringType),
        "readNode" to (listOf(objectType, stringType) to objectType),
        "readContainer" to (listOf(objectType) to objectType),
        "readCurrentChild" to (listOf(objectType) to objectType),
        "readShown" to (listOf(objectType) to "Z"),
        "readTag" to (listOf(objectType) to stringType),
        "callHomeSwitch" to (listOf(objectType, objectType, stringType, bundleType) to "V"),
        "callNodeShow" to (listOf(objectType, bundleType) to "V"),
    )

    @Test
    fun `each declared build gives the repair For You's own show and Home's own switch`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val calls = forYouShowCalls { app[it] }
            val node = calls.nodeOf.returnType
            assertEquals("$version: a node's container", node, calls.container.definingClass)
            assertEquals("$version: Home is a container node", node, app.getValue(calls.container.type).superclass)
            assertEquals(calls.container.type, calls.dispatch.definingClass)
            assertEquals(calls.container.type, calls.shown.definingClass)
            assertEquals("Z", calls.shown.type)
            assertEquals(node, calls.current.type)
            assertEquals(node, calls.tag.definingClass)
            assertNotEquals("$version: two listener lists", calls.visibleListeners.name, calls.switchListeners.name)
            assertEquals("$version: the show is For You's own", FOR_YOU_FRAGMENT, calls.nodeShow.definingClass)
            assertEquals("onNodeShow", calls.nodeShow.name)
            assertEquals(FOR_YOU_FRAGMENT, calls.panel.definingClass)
            assertEquals(HOX, calls.hoxOf.returnType)
            assertEquals(calls.activityOf.returnType, calls.hoxOf.parameterTypes.single().toString())
            assertTrue("$version: For You is a kind of ${calls.fragmentBase}", ancestors(app, FOR_YOU_FRAGMENT).contains(calls.fragmentBase))
            assertTrue("$version: the panel is a kind of ${calls.hintOwner}", ancestors(app, calls.panel.type).contains(calls.hintOwner))
            if (version == "47.6.3") {
                val read = mapOf(
                    "nodeOf" to calls.nodeOf.name, "container" to calls.container.name, "current" to calls.current.name,
                    "shown" to calls.shown.name, "dispatch" to calls.dispatch.name,
                    "visibleListeners" to calls.visibleListeners.name, "switchListeners" to calls.switchListeners.name,
                    "hoxOf" to calls.hoxOf.name, "containerTagOf" to calls.containerTagOf.name, "panel" to calls.panel.name,
                )
                assertEquals(proved4763, read)
            }

            // The constructor's returns are where the patch says a For You page exists.
            val built = app.getValue(FOR_YOU_FRAGMENT).methods.single { it.name == "<init>" && it.parameterTypes.isEmpty() }
            assertTrue("$version: the constructor has no try blocks", built.implementation!!.tryBlocks.isEmpty())
            assertTrue("$version: the constructor returns", built.implementation!!.instructions.any { it.opcode == Opcode.RETURN_VOID })
        }
    }

    @Test
    fun `each filled stub makes its one call and passes the verifier's typing`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val calls = forYouShowCalls { app[it] }
            stubs.forEach { (name, signature) ->
                val filled = fill(name, signature.first, signature.second, calls)
                assertEquals("$version $name", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(filled))
                val body = filled.implementation!!.instructions.toList().takeWhile { !it.opcode.returns() }
                val methods = body.mapNotNull { it.getReference<MethodReference>() }.map { "${it.definingClass}->${it.name}" }
                val fields = body.mapNotNull { it.getReference<FieldReference>() }.map { "${it.definingClass}->${it.name}" }
                val expected = when (name) {
                    "readView" -> "getView"
                    "readResumed" -> "isResumed"
                    "readHidden" -> "isHidden"
                    "readHint" -> "getUserVisibleHint"
                    "readActivity" -> calls.activityOf.name
                    "readHox" -> calls.hoxOf.name
                    "readContainerTag" -> calls.containerTagOf.name
                    "readNode" -> calls.nodeOf.name
                    "readTag" -> calls.tag.name
                    "callHomeSwitch" -> calls.dispatch.name
                    "callNodeShow" -> "onNodeShow"
                    else -> null
                }
                if (expected != null) {
                    assertEquals("$version $name calls", listOf(expected), methods.map { it.substringAfter("->") })
                } else {
                    assertEquals("$version $name reads one field and calls nothing", 1, fields.size)
                    assertTrue("$version $name calls nothing", methods.isEmpty())
                }
            }
            val switch = fill("callHomeSwitch", stubs.getValue("callHomeSwitch").first, "V", calls).implementation!!.instructions.toList()
            val lists = switch.mapNotNull { it.getReference<FieldReference>() }.map { it.name }
            assertEquals("$version: Home's switch gets Hox's lists in Hox's order", listOf(calls.visibleListeners.name, calls.switchListeners.name), lists)
        }
    }

    private fun Opcode.returns() = this == Opcode.RETURN_VOID || this == Opcode.RETURN || this == Opcode.RETURN_OBJECT

    /** The stub as the patch gets it from the extension (a null, false or plain return), filled the way the patch fills it. */
    private fun fill(name: String, parameters: List<String>, returnType: String, calls: ForYouShowCalls): MutableMethod {
        val unpatched: List<Instruction> = when (returnType) {
            "V" -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
            "Z" -> listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))
            else -> listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
        }
        val locals = if (returnType == "V") 0 else 1
        val stub = MutableMethod(
            ImmutableMethod(
                extension, name,
                parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(locals + parameters.size, unpatched, null, null),
            ),
        )
        val body = calls.stub(name)
        return stub.cloneMutable(additionalRegisters = body.locals).also { it.addInstructions(0, body.smali) }
    }

    private fun ancestors(app: Map<String, ClassDef>, type: String): List<String> =
        generateSequence(type) { app[it]?.superclass }.toList()

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
