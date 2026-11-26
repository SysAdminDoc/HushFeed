package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test

class TargetBuildTest {
    @Test fun `the supported compile defines every anchor`() = Fixtures.forEachDeclared { apk ->
        val owners = TARGET_BUILD_ANCHORS.map { it.substringBefore("->") }.toSet()
        val container = Fixtures.dexContainer(apk)
        val found = container.dexEntryNames.flatMap { name -> container.getEntry(name)!!.dexFile.classes.filter { it.type in owners } }
            .associateBy { it.type }
        assertEquals(emptyList<String>(), missingTargetAnchors { found[it] })
        checkTargetBuild(Fixtures.versionOf(apk)) { found[it] }
    }

    @Test fun `another compile of the same version is refused with the files to use`() {
        // A different compile of 48.0.2 defines LX/0IMZ; with other members, and the others not at all.
        val other = ImmutableClassDef("LX/0IMZ;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(ImmutableMethod("LX/0IMZ;", "LIZ", listOf(ImmutableMethodParameter("LX/0FCk;", null, null)),
                "V", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null)))
        val lookup: (String) -> ClassDef? = { if (it == "LX/0IMZ;") other else null }
        assertEquals(TARGET_BUILD_ANCHORS, missingTargetAnchors(lookup))
        val refusal = assertThrows(PatchException::class.java) { checkTargetBuild("48.0.2", lookup) }
        assertTrue(refusal.message!!, refusal.message!!.startsWith("This TikTok 48.0.2 file is a different build"))
        assertTrue(refusal.message!!, TARGET_BUILD_FILES in refusal.message!!)
    }

    @Test fun `one moved anchor is enough to refuse`() {
        val owners = TARGET_BUILD_ANCHORS.groupBy({ it.substringBefore("->") }, { it.substringAfter("->") })
        val classes = owners.mapValues { (type, members) ->
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(),
                members.drop(if (type == "LX/04Do;") 1 else 0).map { member ->
                    val parameters = Regex("L[^;]+;|\\[*[ZBSCIJFD]").findAll(member.substringAfter('(').substringBefore(')'))
                        .map { ImmutableMethodParameter(it.value, null, null) }.toList()
                    ImmutableMethod(type, member.substringBefore('('), parameters, member.substringAfter(')'),
                        AccessFlags.PUBLIC.value, emptySet(), emptySet(), null)
                })
        }
        assertEquals(listOf("LX/04Do;->LIZJ()Lcom/ss/android/ugc/aweme/ILoginService;"), missingTargetAnchors { classes[it] })
        assertThrows(PatchException::class.java) { checkTargetBuild(null) { classes[it] } }
    }

    @Test fun `every patch that loads the extension checks the compile first`() {
        assertNull(targetBuildPatch.name)
        assertEquals(targetBuildPatch, sharedExtensionPatch.dependencies.first())
    }
}
