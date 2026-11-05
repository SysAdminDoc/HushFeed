/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MANAGER = "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"
private const val FACTORY = "Lcom/bytedance/ies/ugc/aweme/network/IRetrofitFactory;"
private const val CLIENT = "LX/0468;"
private const val SERVICE = "LX/1LBz;"

/**
 * What Account cleanup (#332) bridges to, held to each declared build: one liked videos list
 * service, one Favorites service and one service that clears unavailable Favorites, each made one
 * way in TikTok, and a bridge that makes it the same way and passes the verifier's typing.
 */
class AccountCleanupServicesTest {
    @Test
    fun `each build has one liked list, one Favorites and one unavailable Favorites service, each made one way`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val liked = mutableSetOf<String>()
            val favorites = mutableSetOf<String>()
            val unavailable = mutableSetOf<String>()
            val made = mutableMapOf<String, MutableSet<ServiceRecipe>>()
            val makers = mutableMapOf<String, MutableList<Method>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (isLikedListService(classDef)) liked += classDef.type
                    if (isFavoritesService(classDef)) favorites += classDef.type
                    if (isUnavailableFavoritesService(classDef)) unavailable += classDef.type
                    for (method in classDef.methods) {
                        for ((service, recipe) in serviceRecipes(method)) {
                            made.getOrPut(service) { mutableSetOf() } += recipe
                            makers.getOrPut(service) { mutableListOf() } += method
                        }
                    }
                }
            }
            assertEquals("$version: liked list services $liked", 1, liked.size)
            assertEquals("$version: Favorites services $favorites", 1, favorites.size)
            assertEquals("$version: unavailable Favorites services $unavailable", 1, unavailable.size)
            for (service in listOf(liked.single(), favorites.single(), unavailable.single())) {
                val recipes = made[service].orEmpty()
                assertEquals("$version: ways $service is made: $recipes", 1, recipes.size)
                // The patch finds the makers through the patcher's check-cast index.
                for (maker in makers[service].orEmpty()) {
                    assertTrue("$version: ${maker.definingClass}->${maker.name} casts to $service",
                        maker.implementation!!.instructions.any { it.casts(service) })
                }
                checkBridge("$version $service", service, recipes.single())
            }
        }
    }

    @Test
    fun `TikTok's own way of making a service is read whole`() {
        val recipes = serviceRecipes(maker())
        assertEquals(1, recipes.size)
        val (service, recipe) = recipes.single()
        assertEquals(SERVICE, service)
        assertEquals("$FACTORY->LIZ", recipe.factory.definingClass + "->" + recipe.factory.name)
        assertEquals(Opcode.INVOKE_INTERFACE, recipe.factoryCall)
        assertEquals("LX/048i;->LIZJ", recipe.host.definingClass + "->" + recipe.host.name)
        assertEquals("$CLIENT->create", recipe.create.definingClass + "->" + recipe.create.name)
        assertEquals(Opcode.INVOKE_INTERFACE, recipe.createCall)
        checkBridge("hand-built", service, recipe)
    }

    @Test
    fun `a maker missing a step is not taken`() {
        assertEquals("the host isn't a String", emptyList<Any>(),
            serviceRecipes(maker(host = ImmutableFieldReference("LX/048i;", "LIZJ", "LX/048i;"))))
        assertEquals("the factory isn't from ServiceManager", emptyList<Any>(), serviceRecipes(maker(fromManager = false)))
        assertEquals("the class handed to create isn't the one loaded", emptyList<Any>(),
            serviceRecipes(maker(createRegisters = 1 to 1)))
    }

    private fun Instruction.casts(type: String) =
        opcode == Opcode.CHECK_CAST && getReference<TypeReference>()?.type == type

    private fun checkBridge(label: String, service: String, recipe: ServiceRecipe) {
        val stub = ImmutableMethod(
            ACCOUNT_CLEANUP_SOURCES, "likedListService", listOf(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(1, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), null, null),
        )
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        bridge.makeService(service, recipe)
        val code = bridge.implementation!!.instructions.toList()
        assertEquals("$label: the bridge", listOf(
            Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_CLASS, Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.SGET_OBJECT, recipe.factoryCall,
            Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_CLASS, recipe.createCall, Opcode.MOVE_RESULT_OBJECT,
            Opcode.RETURN_OBJECT,
        ), code.take(13).map { it.opcode })
        assertEquals("$label: the factory's class", recipe.factory.definingClass, code[2].getReference<TypeReference>()!!.type)
        assertEquals("$label: the host", recipe.host.name, code[6].getReference<FieldReference>()!!.name)
        assertEquals("$label: the factory call", recipe.factory.name, code[7].getReference<MethodReference>()!!.name)
        assertEquals("$label: the service", service, code[9].getReference<TypeReference>()!!.type)
        assertEquals("$label: the create call", "create", code[10].getReference<MethodReference>()!!.name)
        assertEquals("$label: two scratch registers", 3, bridge.implementation!!.registerCount)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(bridge))
    }

    /** The liked list service's maker as 47.6.3 has it, cut down to the steps that make it. */
    private fun maker(
        host: ImmutableFieldReference = ImmutableFieldReference("LX/048i;", "LIZJ", "Ljava/lang/String;"),
        fromManager: Boolean = true,
        createRegisters: Pair<Int, Int> = 1 to 0,
    ): Method {
        val get = ImmutableMethodReference(MANAGER, "get", listOf(), MANAGER)
        val getService = ImmutableMethodReference(
            if (fromManager) MANAGER else "LX/0Other;", "getService", listOf("Ljava/lang/Class;"), "Ljava/lang/Object;")
        val factory = ImmutableMethodReference(FACTORY, "LIZ", listOf("Ljava/lang/String;"), CLIENT)
        val create = ImmutableMethodReference(CLIENT, "create", listOf("Ljava/lang/Class;"), "Ljava/lang/Object;")
        val instructions = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, get),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.CONST_CLASS, 0, ImmutableTypeReference(FACTORY)),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0, getService),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(FACTORY)),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, host),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 0, 0, 0, 0, factory),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.CONST_CLASS, 0, ImmutableTypeReference(SERVICE)),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, createRegisters.first, createRegisters.second, 0, 0, 0, create),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(SERVICE)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            "LX/1LC0;", "make", listOf(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, instructions, null, null),
        )
    }
}
