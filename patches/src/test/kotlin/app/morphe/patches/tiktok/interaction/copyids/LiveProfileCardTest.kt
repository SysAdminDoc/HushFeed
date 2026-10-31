/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.copyids

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Copy bio and IDs on the profile card inside a LIVE room (#268), held to each declared build:
 * the card's bio setup and user bind are each found once, the bio is set in three places and the
 * name and username in two, and after the hooks every one of those calls is preceded by a hand-over
 * of its view and text while TikTok's own instructions keep their order.
 */
class LiveProfileCardTest {
    @Test
    fun `each declared build's LIVE card bio and names are handed to the extension before they are set`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val bios = ArrayList<MutableMethod>()
            val names = ArrayList<MutableMethod>()
            for (entry in container.dexEntryNames) {
                for (classDef: ClassDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (LiveCardBioFingerprint.takes(method, classDef)) bios.add(MutableMethod(method))
                        if (LiveCardNamesFingerprint.takes(method, classDef)) names.add(MutableMethod(method))
                    }
                }
            }
            assertEquals("$version: card bio setups", 1, bios.size)
            assertEquals("$version: card user binds", 1, names.size)
            val bio = bios.single()
            val bind = names.single()
            assertEquals("$version: bio sets", 3, liveBioSets(bio).size)
            assertEquals("$version: name sets", 2, liveNameSets(bind).size)

            val bioBefore = bio.implementation!!.instructions.map { it.opcode }.filter { it != Opcode.NOP }
            val bindBefore = bind.implementation!!.instructions.map { it.opcode }.filter { it != Opcode.NOP }
            hookLiveCard(bio, bind)

            val bioAfter = bio.implementation!!.instructions.toList()
            val bindAfter = bind.implementation!!.instructions.toList()

            fun hooks(list: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, name: String) =
                list.withIndex().filter {
                    it.value.opcode == Opcode.INVOKE_STATIC &&
                        it.value.getReference<MethodReference>()?.let { m -> m.name == name } == true
                }.map { it.index }

            for (index in hooks(bioAfter, "onLiveBio")) {
                val next = bioAfter[index + 1].getReference<MethodReference>()
                assertEquals("setOriginalText", next!!.name)
            }
            assertEquals(3, hooks(bioAfter, "onLiveBio").size)
            val textHooks = hooks(bindAfter, "onLiveText")
            assertEquals(2, textHooks.size)
            textHooks.forEach { assertEquals("setText", bindAfter[it + 1].getReference<MethodReference>()!!.name) }
            assertEquals(Opcode.INVOKE_STATIC_RANGE, bindAfter[0].opcode)
            assertEquals("onLiveUser", bindAfter[0].getReference<MethodReference>()!!.name)

            val added = (hooks(bioAfter, "onLiveBio")).toSet()
            assertEquals(
                "$version: the bio's own instructions keep their order",
                bioBefore,
                bioAfter.filterIndexed { i, _ -> i !in added }.map { it.opcode }.filter { it != Opcode.NOP },
            )
            val addedBind = (textHooks + 0).toSet()
            assertEquals(
                "$version: the bind's own instructions keep their order",
                bindBefore,
                bindAfter.filterIndexed { i, _ -> i !in addedBind }.map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }
}
