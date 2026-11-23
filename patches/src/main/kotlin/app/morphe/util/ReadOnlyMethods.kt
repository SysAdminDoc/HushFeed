/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.HiddenApiRestriction
import com.android.tools.smali.dexlib2.base.reference.BaseMethodReference
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.MethodParameter

/**
 * This method for reading only: its body is read without the patcher building an editable copy.
 *
 * Once any patch makes a class mutable, the patcher hands out that MutableClass from every lookup
 * (classDefByOrNull, classDefForEach, a match's originalClassDef, a fingerprint's custom block).
 * The first read of one of its methods' `implementation` builds an editable copy of the whole body,
 * and the patcher keeps every copy until it writes the dex, whether anything changed it or not.
 * Scans that only look were building and keeping hundreds of megabytes that way: with every patch
 * selected, UI scale's pass over every method TikTok has built the body of each method of 2,500
 * mutable classes, and Morphe Manager ran out of heap on 4 GB phones (#497, #460, #310).
 *
 * The view reads the original body while nothing has built the copy, and the copy once something
 * has, so it always reads what the patched dex will hold, at every read, not only the first.
 * Signature, flags and annotations come from the mutable method. Edit through
 * `mutableClassDefBy(...).findMutableMethodOf(view)`, never through the view. Any other method,
 * and every method on a patcher whose internals this can't find, is returned as it is.
 */
fun Method.readOnly(): Method =
    if (this is MutableMethod && PatcherBodies.available) ReadOnlyMethod(this) else this

/**
 * Whether the patcher has built [method]'s editable body (it then keeps it until the dex is
 * written), or null when this patcher's internals can't say.
 */
internal fun bodyIsBuilt(method: MutableMethod): Boolean? =
    if (PatcherBodies.available) PatcherBodies.isBuilt(method) else null

/**
 * The two internal members of the patcher's MutableMethod the view needs, found once. Patchers
 * 2.2.0 to 2.5.0 have both. Manager runs the bundle in whatever patcher it ships, so a missing one
 * turns the view off rather than failing the patch.
 */
private object PatcherBodies {
    private val built = member("isImplementationCreated")
    private val source = member("getSourceMethod")
    val available = built != null && source != null

    fun isBuilt(method: MutableMethod) = built!!.invoke(method) as Boolean

    fun sourceOf(method: MutableMethod) = source!!.invoke(method) as Method

    private fun member(name: String) = try {
        MutableMethod::class.java.getMethod("$name\$morphe_patcher")
    } catch (_: NoSuchMethodException) {
        null
    }
}

private class ReadOnlyMethod(private val method: MutableMethod) : BaseMethodReference(), Method {
    override fun getDefiningClass(): String = method.definingClass

    override fun getName(): String = method.name

    override fun getParameterTypes(): List<CharSequence> = method.parameterTypes

    override fun getReturnType(): String = method.returnType

    override fun getParameters(): List<MethodParameter> = method.parameters

    override fun getAccessFlags(): Int = method.accessFlags

    override fun getAnnotations(): Set<Annotation> = method.annotations

    override fun getHiddenApiRestrictions(): Set<HiddenApiRestriction> = method.hiddenApiRestrictions

    override fun getImplementation(): MethodImplementation? =
        if (PatcherBodies.isBuilt(method)) method.implementation else PatcherBodies.sourceOf(method).implementation
}
