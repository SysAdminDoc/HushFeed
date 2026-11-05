/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show inbox filters answers the creator mode from TikTok's inbox mode getter and its tab list,
 * and keeps the creator Inbox pop-up from showing. Every one of those is found by a log line or a
 * setting key and the shape around it, so each is held here to be found exactly once on every
 * declared build, with the mode it hands over being TikTok's mode enum.
 */
class InboxFilterTabsAnchorsTest {
    private fun matches(classes: List<ClassDef>, fingerprint: Fingerprint) = classes.flatMap { classDef ->
        classDef.methods.filter { fingerprint.takes(it, classDef) }.map { classDef to it }
    }

    @Test
    fun `each declared build has one mode getter, one tab list and one creator Inbox pop-up`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val byType = classes.associateBy { it.type }

            val producers = matches(classes, InboxModeProducerFingerprint)
            assertEquals("$version produceCurrentInboxMode", 1, producers.size)
            val manager = producers.single().first

            val getters = matches(classes, InboxModeGetterFingerprint)
            assertEquals("$version mode getter", 1, getters.size)
            val (owner, getter) = getters.single()
            assertEquals("$version: the getter is the manager's", manager.type, owner.type)
            assertNotNull("$version: the getter returns once", inboxModeReturn(getter, owner))
            val mode = byType[getter.returnType]
            assertNotNull("$version: the mode type ${getter.returnType} is in the app", mode)
            val constants = mode!!.fields.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.map { it.name }
            assertTrue("$version: ${mode.type} is TikTok's inbox mode enum, has $constants",
                constants.containsAll(listOf("PERSONAL_INBOX", "PRO_CREATOR_INBOX")))

            val tabLists = matches(classes, InboxFilterTabListFingerprint)
            assertEquals("$version tab list", 1, tabLists.size)
            assertEquals("$version: the tab list is the manager's", manager.type, tabLists.single().first.type)
            inboxModeCast(tabLists.single().second, getter.returnType)

            val guides = matches(classes, ProInboxGuideCanShowFingerprint)
            assertEquals("$version creator Inbox pop-up", 1, guides.size)
            val guide: Method = guides.single().second
            assertTrue("$version: the pop-up check has a local for its answer",
                guide.implementation!!.registerCount > guide.numberOfParameterRegisters)
        }
    }

    @Test
    fun `the mode is handed over right before the getter returns it and right after the tab list casts it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val (manager, getter) = matches(classes, InboxModeGetterFingerprint).single()
            val tabList = matches(classes, InboxFilterTabListFingerprint).single().second
            val guideCheck = matches(classes, ProInboxGuideCanShowFingerprint).single().second
            val mode = getter.returnType

            val getterBefore = getter.implementation!!.instructions.toList()
            val tabsBefore = tabList.implementation!!.instructions.toList()
            val returnAt = inboxModeReturn(getter, manager)!!
            val castAt = inboxModeCast(tabList, mode)
            val mutableGetter = MutableMethod(getter)
            val mutableTabs = MutableMethod(tabList)
            val mutableGuide = MutableMethod(guideCheck)
            InboxFilterTabHooks(manager, mutableGetter, mutableTabs, mutableGuide).install("test")

            val getterAfter = mutableGetter.implementation!!.instructions.toList()
            assertEquals("$version getter grew by the hand-over", code(getterBefore) + 3, code(getterAfter))
            assertHandOver(version, getterAfter, returnAt, mode, (getterBefore[returnAt] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_OBJECT, getterAfter[returnAt + 3].opcode)
            // The cached-pair branch jumps straight to the return; it now lands on the hand-over.
            val branchTargets = getterAfter.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }
            assertTrue("$version: no branch skips the hand-over", returnAt + 3 !in branchTargets)

            val tabsAfter = mutableTabs.implementation!!.instructions.toList()
            assertEquals("$version tab list grew by the hand-over", code(tabsBefore) + 3, code(tabsAfter))
            assertHandOver(version, tabsAfter, castAt + 1, mode, (tabsBefore[castAt] as OneRegisterInstruction).registerA)

            val guideAfter = mutableGuide.implementation!!.instructions.toList()
            assertEquals("$version: the pop-up asks first", "hideProInboxGuide",
                guideAfter[0].getReference<MethodReference>()?.name)
        }
    }

    /** Instructions other than the alignment nops a switch payload can gain or lose. */
    private fun code(instructions: List<Instruction>) = instructions.count { it.opcode != Opcode.NOP }

    private fun assertHandOver(version: String, code: List<Instruction>, at: Int, mode: String, register: Int) {
        val call = code[at]
        assertEquals("$version hand-over call", "shownInboxMode", call.getReference<MethodReference>()?.name)
        assertEquals("$version hand-over reads the mode's register", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, code[at + 2].opcode)
        assertEquals(mode, code[at + 2].getReference<TypeReference>()?.type)
    }
}
