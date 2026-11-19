/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.BridgeTypeFlow
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #470 on a made-up menu with the declared build's shape: a rows lambda holding the sticker's
 * template and the menu, a shop row built by a static factory, and the row pieces that factory makes.
 */
class DmStickerMenuHookTest {
    @Test
    fun `the pieces resolve by shape and the hook lands at the return label`() {
        val rows = rows()
        val owner = owner(rows)
        assertTrue(DmStickerMenuRowsFingerprint.takes(rows, owner))
        val menu = dmStickerMenu(rows, owner, ::classOf)
        assertEquals(12, menu.returnIndex)
        assertEquals(listOf(1, 2), menu.scratch)
        assertEquals("$OWNER->template:$DM_STICKER_TEMPLATE", menu.template)
        assertEquals("$OWNER->menu:$MENU", menu.builder)
        assertEquals("$MENU->close($ACTION$FUNCTION0)V", menu.dismiss)
        assertEquals(ACTION, menu.action)
        assertEquals("$UNIT->INSTANCE:$UNIT", menu.unit)
        assertEquals(ROW, menu.row)
        assertEquals(listOf(ICON_BY_ID, TITLE_TEXT, STYLE, SIZE), listOf(menu.icon, menu.title, menu.style, menu.size))

        val stock = rows.implementation!!.instructions.toList()
        rows.addDmStickerMenuHook(menu)
        val code = rows.implementation!!.instructions.toList()
        assertEquals(stock.size + 3, code.size)
        stock.take(12).forEachIndexed { index, instruction -> assertSame(instruction, code[index]) }
        assertEquals(Opcode.RETURN_OBJECT, code.last().opcode)
        val template = code[12] as TwoRegisterInstruction
        val builder = code[13] as TwoRegisterInstruction
        assertEquals(listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC), code.subList(12, 15).map { it.opcode })
        assertEquals(listOf(1, 5), listOf(template.registerA, template.registerB))
        assertEquals(listOf(2, 5), listOf(builder.registerA, builder.registerB))
        val call = code[14] as FiveRegisterInstruction
        assertEquals(listOf(6, 1, 2), listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount))
        val target = code[14].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/download/StickerGallerySaver;->addDmStickerSaveRow" +
            "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V",
            "${target.definingClass}->${target.name}(${target.parameterTypes.joinToString("")})${target.returnType}")
        // The early exit branches straight onto the return, so it has to reach the hook as well.
        assertEquals(12, (code[3] as BuilderOffsetInstruction).target.location.index)
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(rows))
    }

    @Test
    fun `a menu without its shop row, or one that moved, is refused`() {
        val shop = rows(store = "Shop")
        assertFalse(DmStickerMenuRowsFingerprint.takes(shop, owner(shop)))
        assertRefused("rows lost their sticker shop row") { dmStickerMenu(shop, owner(shop), ::classOf) }
        val noTemplate = rows()
        assertFalse(DmStickerMenuRowsFingerprint.takes(noTemplate, owner(noTemplate, template = false)))
    }

    @Test
    fun `a return inside a try block is refused`() {
        val rows = rows().apply {
            implementation!!.run {
                addCatch("Ljava/lang/Exception;", newLabelForIndex(11), newLabelForIndex(13), newLabelForIndex(4))
            }
        }
        assertRefused("return sits inside a try block") { dmStickerMenu(rows, owner(rows), ::classOf) }
    }

    @Test
    fun `a write over the list after its cast is refused`() {
        val rows = rows(beforeReturn = "move-object p1, v4")
        assertRefused("rows write over their lambda or list at 10") { dmStickerMenu(rows, owner(rows), ::classOf) }
    }

    @Test
    fun `parameters past v15 are refused rather than dropped`() {
        val rows = rows(locals = 15)
        assertRefused("rows use 17 registers, past v15") { dmStickerMenu(rows, owner(rows), ::classOf) }
    }

    @Test
    fun `a row class or style that changed shape is refused`() {
        val rows = rows()
        assertRefused("row styles lost PRIMARY") {
            dmStickerMenu(rows, owner(rows)) { type -> if (type == STYLE) enumOf(STYLE, "DEBUG") else classOf(type) }
        }
        assertRefused("actions lost FAV_STICKER or DISMISS") {
            dmStickerMenu(rows, owner(rows)) { type -> if (type == ACTION) enumOf(ACTION, "STICKER_STORE", "FAV_STICKER") else classOf(type) }
        }
    }

    @Test
    fun `the bridges build the row, answer Unit and close the menu with the verifier's typing`() {
        val rows = rows()
        val menu = dmStickerMenu(rows, owner(rows), ::classOf)
        val bridges = dmStickerMenuBridges(menu).associateBy { it.name }
        assertEquals(setOf("dmStickerMenuRow", "dmStickerMenuDone", "dismissDmStickerMenu"), bridges.keys)
        bridges.values.forEach { assertEquals(it.name, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(it)) }
        val row = bridges.getValue("dmStickerMenuRow").implementation!!.instructions.toList()
        val init = row[row.size - 2].getReference<MethodReference>()!!
        assertEquals("$ROW-><init>", "${init.definingClass}->${init.name}")
        assertEquals(listOf("Ljava/lang/String;", "Ljava/lang/Enum;", ICON, TITLE, STYLE, SIZE, "Z", FUNCTION1),
            init.parameterTypes.map { it.toString() })
        assertTrue(row.any { it.getReference<FieldReference>()?.name == "FAV_STICKER" })
        val close = bridges.getValue("dismissDmStickerMenu").implementation!!.instructions.toList()
        assertEquals("DISMISS", close[1].getReference<FieldReference>()!!.name)
        assertEquals("close", close[3].getReference<MethodReference>()!!.name)
    }

    /** The shipped dex has R8's public static stubs, not the source's package-private ones. */
    @Test
    fun `the bridges replace the stubs with their flags kept and refuse an instance stub`() {
        val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val rows = rows()
        val menu = dmStickerMenu(rows, owner(rows), ::classOf)
        val stubs = dmStickerMenuBridges(menu) { publicStatic }
        val saver = MutableClass(saver(stubs))
        saver.swapDmStickerMenuStubs(menu)
        assertEquals(stubs.map { it.name }.toSet(), saver.methods.map { it.name }.toSet())
        for (bridge in saver.methods) {
            assertEquals(bridge.name, publicStatic, bridge.accessFlags)
            assertTrue(bridge.name, stubs.none { stub -> stub === bridge })
            assertTrue(bridge.name, bridge.implementation!!.instructions.count() > 0)
        }

        val instance = dmStickerMenuBridges(menu) { name -> if (name == "dismissDmStickerMenu") AccessFlags.PUBLIC.value else publicStatic }
        assertRefused("the dismissDmStickerMenu stub isn't static", prefix = "Downloads: ") {
            MutableClass(saver(instance)).swapDmStickerMenuStubs(menu)
        }
        assertRefused("StickerGallerySaver has no dmStickerMenuDone stub", prefix = "Downloads: ") {
            MutableClass(saver(stubs.filter { it.name != "dmStickerMenuDone" })).swapDmStickerMenuStubs(menu)
        }
    }

    private fun assertRefused(what: String, prefix: String = "Downloads: the DM sticker menu ", block: () -> Unit) {
        val refused = assertThrows(PatchException::class.java) { block() }
        assertEquals(prefix + what, refused.message)
    }

    /** The lambda's rows: the shop row after an early exit that branches straight onto the return. */
    private fun rows(store: String = "Store", locals: Int = 5, beforeReturn: String = "nop") = MutableMethod(
        ImmutableMethod(
            OWNER, "invoke", listOf(ImmutableMethodParameter(OBJECT, null, null)), OBJECT, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(locals + 2, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                check-cast p1, Ljava/util/List;
                sget-object v0, $UNIT->INSTANCE:$UNIT
                iget-object v1, p0, $OWNER->template:$DM_STICKER_TEMPLATE
                if-eqz v1, :done
                move-object/from16 v4, p1
                const-string v1, "$store"
                sget-object v2, $ACTION->STICKER_STORE:$ACTION
                invoke-static { v1, v2 }, $FACTORY->build(Ljava/lang/String;Ljava/lang/Enum;)$ROW
                move-result-object v3
                invoke-interface { v4, v3 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                $beforeReturn
                sget-object v0, $UNIT->INSTANCE:$UNIT
                :done
                return-object v0
            """.trimIndent(),
        )
    }

    private fun owner(rows: Method, template: Boolean = true) = classDef(
        OWNER, PUBLIC_FINAL, listOf(FUNCTION1),
        fields = listOfNotNull(
            field(OWNER, "menu", MENU),
            field(OWNER, "message", OBJECT),
            if (template) field(OWNER, "template", DM_STICKER_TEMPLATE) else null,
        ),
        methods = listOf(rows),
    )

    private fun saver(methods: List<Method>) = classDef(
        "Lapp/morphe/extension/tiktok/download/StickerGallerySaver;", PUBLIC_FINAL, methods = methods,
    )

    private fun classOf(type: String): ClassDef? = CLASSES[type]

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
        const val UNIT = "Lkotlin/Unit;"
        const val OWNER = "Lcom/example/StickerRows;"
        const val MENU = "Lcom/example/Menu;"
        const val ACTION = "Lcom/example/Action;"
        const val ROW = "Lcom/example/Row;"
        const val FACTORY = "Lcom/example/Rows;"
        const val ICON = "Lcom/example/Icon;"
        const val ICON_BY_ID = "Lcom/example/IconById;"
        const val TITLE = "Lcom/example/Title;"
        const val TITLE_BY_ID = "Lcom/example/TitleById;"
        const val TITLE_TEXT = "Lcom/example/TitleText;"
        const val STYLE = "Lcom/example/Style;"
        const val SIZE = "Lcom/example/Size;"
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val PUBLIC_INTERFACE = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value

        fun field(owner: String, name: String, type: String, flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) =
            ImmutableField(owner, name, type, flags, null, null, null)

        fun method(owner: String, name: String, parameters: List<String>, returnType: String, flags: Int): Method =
            ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null, null)

        fun classDef(
            type: String, flags: Int, interfaces: List<String> = emptyList(), superclass: String = OBJECT,
            fields: List<ImmutableField> = emptyList(), methods: List<Method> = emptyList(),
        ): ClassDef = ImmutableClassDef(type, flags, superclass, interfaces, null, emptySet(), fields, methods)

        fun init(owner: String, vararg parameters: String) = method(owner, "<init>", parameters.toList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)

        fun enumOf(type: String, vararg constants: String) = classDef(
            type, PUBLIC_FINAL or AccessFlags.ENUM.value, superclass = "Ljava/lang/Enum;",
            fields = constants.map { field(type, it, type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value) },
        )

        /** The shop row's factory: it makes every piece kind, so only one fits each wanted constructor. */
        val factory: Method = MutableMethod(
            ImmutableMethod(
                FACTORY, "build", listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null), ImmutableMethodParameter("Ljava/lang/Enum;", null, null)),
                ROW, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    new-instance v0, $ICON_BY_ID
                    new-instance v0, $TITLE_BY_ID
                    new-instance v0, $TITLE_TEXT
                    new-instance v0, $SIZE
                    new-instance v0, $ROW
                    return-object v0
                """.trimIndent(),
            )
        }

        val CLASSES: Map<String, ClassDef> = listOf(
            classDef(MENU, PUBLIC_INTERFACE, methods = listOf(
                method(MENU, "close", listOf(ACTION, FUNCTION0), "V", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value),
                method(MENU, "rows", listOf(OBJECT), "Ljava/util/List;", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value),
            )),
            enumOf(ACTION, "STICKER_STORE", "FAV_STICKER", "DISMISS"),
            classDef(ROW, PUBLIC_FINAL, methods = listOf(
                init(ROW, "Ljava/lang/String;", "Ljava/lang/Enum;", ICON, TITLE, STYLE, SIZE, "Z", FUNCTION1),
            )),
            classDef(FACTORY, PUBLIC_FINAL, methods = listOf(factory)),
            classDef(ICON, PUBLIC_INTERFACE),
            classDef(ICON_BY_ID, PUBLIC_FINAL, listOf(ICON), methods = listOf(init(ICON_BY_ID, "I"))),
            classDef(TITLE, PUBLIC_INTERFACE),
            classDef(TITLE_BY_ID, PUBLIC_FINAL, listOf(TITLE), methods = listOf(init(TITLE_BY_ID, "I"))),
            classDef(TITLE_TEXT, PUBLIC_FINAL, listOf(TITLE), methods = listOf(init(TITLE_TEXT, "Ljava/lang/String;"))),
            enumOf(STYLE, "PRIMARY", "DEBUG"),
            classDef(SIZE, PUBLIC_FINAL, methods = listOf(init(SIZE))),
        ).associateBy { it.type }
    }
}
