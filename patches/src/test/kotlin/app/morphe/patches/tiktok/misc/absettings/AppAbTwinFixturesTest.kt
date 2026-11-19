/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.absettings

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed getter hooks have to reach the class TikTok reads its gates through.
 *
 * <p>47.x carries the app AB getters twice, and the class with all seven (the one the hooks were
 * resolved from) takes a handful of reads while the twin without the raw getter takes thousands.
 * With the main class hooked alone, the Feature Gate Lab never saw `basic_vv_batch_size` (#448),
 * and the gates the Favorites tab, search, seekbar and video surface patches force were never
 * asked through a hooked getter.
 */
class AppAbTwinFixturesTest {
    /** The gates the patches force through the typed getters, and the Lab presets' keys. */
    private val forcedGates = setOf(
        "basic_vv_batch_size",
        "studio_publish_hashtag_max_limit",
        "tt_cmt_new_emoji",
        "favorite_reverse",
        "new_user_favorite_full_reversal_exp",
        "player_enable_surface_surfaceview",
        "search_player_surface_view",
        "seekbar_show_thumbnail_when_drag",
        "enable_non_personalized_search",
    )

    @Test
    fun `each build has one main class and a twin, each with one of every typed getter`() = Fixtures.forEachDeclared { apk ->
        val roles = rolesIn(classesOf(apk))
        val version = Fixtures.versionOf(apk)
        assertEquals("$version main classes", 1, roles.values.count { (role, _) -> role == AppAbRole.MAIN })
        assertTrue("$version has no twin", roles.values.any { (role, _) -> role == AppAbRole.TWIN })
        roles.forEach { (type, entry) ->
            for (shape in APP_AB_TYPED_SHAPES) {
                assertEquals("$version $type ${shape.returnType}${shape.parameters}", 1, entry.second.methods.count { it.shape() == shape })
            }
        }
    }

    @Test
    fun `every forced gate is read through a class the typed hooks cover`() = Fixtures.forEachDeclared { apk ->
        val classes = classesOf(apk)
        val roles = rolesIn(classes)
        val version = Fixtures.versionOf(apk)
        val readers = mutableMapOf<String, MutableSet<String>>()
        for (classDef in classes) for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            code.forEachIndexed { index, instruction ->
                val key = instruction.getReference<StringReference>()?.string ?: return@forEachIndexed
                if (key !in forcedGates) return@forEachIndexed
                // The key goes into the getter within a few instructions of its load.
                val read = code.subList(index + 1, minOf(code.size, index + 13))
                    .mapNotNull { it.getReference<MethodReference>() }
                    .firstOrNull { MethodShape(it.returnType, it.parameterTypes.map(CharSequence::toString)) in APP_AB_TYPED_SHAPES }
                    ?: return@forEachIndexed
                readers.getOrPut(key) { mutableSetOf() } += read.definingClass
            }
        }
        assertEquals("$version gates with a typed read", forcedGates, readers.keys)
        readers.forEach { (key, owners) ->
            owners.forEach { owner -> assertNotNull("$version reads $key through $owner, which no hook covers", roles[owner]) }
        }
    }

    private fun classesOf(apk: File): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
    }

    private fun rolesIn(classes: List<ClassDef>): Map<String, Pair<AppAbRole, ClassDef>> =
        classes.mapNotNull { classDef -> appAbRole(classDef)?.let { classDef.type to (it to classDef) } }.toMap()
}
