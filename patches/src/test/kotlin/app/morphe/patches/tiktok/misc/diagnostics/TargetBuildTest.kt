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
        // The arm64-v8a-only 47.7.3 APK defines LX/0HUC; with other members, and the others not at all.
        val other = ImmutableClassDef("LX/0HUC;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(ImmutableMethod("LX/0HUC;", "LIZ", listOf(ImmutableMethodParameter("LX/0HUB;", null, null)),
                "V", AccessFlags.PUBLIC.value, emptySet(), emptySet(), null)))
        val lookup: (String) -> ClassDef? = { if (it == "LX/0HUC;") other else null }
        assertEquals(TARGET_BUILD_ANCHORS, missingTargetAnchors(lookup))
        val refusal = assertThrows(PatchException::class.java) { checkTargetBuild("47.7.3", lookup) }
        assertTrue(refusal.message!!, refusal.message!!.startsWith("This TikTok 47.7.3 file is a different build"))
        assertTrue(refusal.message!!, TARGET_BUILD_FILES in refusal.message!!)
    }

    @Test fun `one moved anchor is enough to refuse`() {
        val owners = TARGET_BUILD_ANCHORS.groupBy({ it.substringBefore("->") }, { it.substringAfter("->") })
        val classes = owners.mapValues { (type, members) ->
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(),
                members.drop(if (type == "LX/041W;") 1 else 0).map { member ->
                    val parameters = Regex("L[^;]+;|\\[*[ZBSCIJFD]").findAll(member.substringAfter('(').substringBefore(')'))
                        .map { ImmutableMethodParameter(it.value, null, null) }.toList()
                    ImmutableMethod(type, member.substringBefore('('), parameters, member.substringAfter(')'),
                        AccessFlags.PUBLIC.value, emptySet(), emptySet(), null)
                })
        }
        assertEquals(listOf("LX/041W;->LIZJ()Lcom/ss/android/ugc/aweme/ILoginService;"), missingTargetAnchors { classes[it] })
        assertThrows(PatchException::class.java) { checkTargetBuild(null) { classes[it] } }
    }

    @Test fun `every patch that loads the extension checks the compile first`() {
        assertNull(targetBuildPatch.name)
        assertEquals(targetBuildPatch, sharedExtensionPatch.dependencies.first())
    }
}
