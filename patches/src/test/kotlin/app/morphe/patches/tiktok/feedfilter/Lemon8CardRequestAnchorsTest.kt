package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Lemon8 promo card is stopped where its handler builds the card type requests, a
 * LIZJ(handler, FeedCardInsertData, List) keyed by the big card's not-interested time (LX/055i
 * on 47.9.3). TikTok 48.0.2 has no such handler: LemonServiceImpl, which built it, answers null
 * for its card handler, so the card is never asked for and the patch leaves the guard out. A
 * build that brings the handler back fails the first check, and the guard is due again.
 */
class Lemon8CardRequestAnchorsTest {
    private val insertData = "Lcom/ss/android/ugc/feed/platform/cardinsert/data/FeedCardInsertData;"
    private val lemonService = "Lcom/ss/android/ugc/aweme/lemon/impl/LemonServiceImpl;"

    @Test
    fun `each declared build asks for no Lemon8 card and has no request builder to guard`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val taken = classes.flatMap { classDef ->
                classDef.methods
                    .filter { it.implementation != null && Lemon8CardRequestFingerprint.takes(it, classDef) }
                    .map { "${classDef.type}->${it.name}" }
            }
            assertEquals("${apk.name}: Lemon8 request builders", emptyList<String>(), taken)

            val handlerBases = classes.filter { classDef ->
                classDef.methods.any { method ->
                    AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
                        method.returnType == "Ljava/util/List;" &&
                        method.parameterTypes.map(CharSequence::toString) ==
                        listOf(classDef.type, insertData, "Ljava/util/List;")
                }
            }.map { it.type }
            assertEquals("${apk.name}: card-insert handler bases $handlerBases", 1, handlerBases.size)
            val getters = classes.single { it.type == lemonService }.methods.filter {
                it.parameterTypes.isEmpty() && it.returnType == handlerBases.single()
            }
            assertEquals("${apk.name}: $lemonService card handler getters", 1, getters.size)
            assertEquals(
                "${apk.name}: the Lemon8 card handler getter answers null",
                listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
                getters.single().implementation!!.instructions.map { it.opcode },
            )
        }
    }
}
