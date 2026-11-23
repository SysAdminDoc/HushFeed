/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import java.lang.reflect.Constructor

/**
 * Lets the resource table the patcher dropped be collected. Call it as the last thing a patch
 * does after reading or writing resources (#48).
 *
 * On a heap under 1.5 GB, which is what Morphe Manager gives its 32-bit patcher, the patcher
 * drops the parsed resource table after each use rather than hold it through the rest of the
 * patches and the dex write, and parses it again when a patch next asks for a resource. ARSCLib
 * keeps the dropped table alive anyway: every style in a string pool ends with one shared footer
 * block (StyleItem's static END_BLOCK), and each StyleItem ARSCLib builds makes itself that
 * block's parent. The static then reaches the last style parsed, and through its string pool the
 * whole table and the APK archive it came from: 139 MB on a 64-bit desktop JVM for TikTok
 * 47.9.3's universal APK, which carries every density and language, kept to the end of the run.
 *
 * A style built on its own, in no table, takes the footer over, and the dropped table can go.
 * A table the patcher still holds is untouched, and the next table ARSCLib parses takes the
 * footer back. ARSCLib comes with the patcher rather than on the patches' compile classpath, so
 * it is reached by name; a patcher without it has nothing to let go of.
 */
fun releaseResourceTable() {
    StyleFooter.takeOver?.newInstance()
}

private object StyleFooter {
    /** StyleItem's constructor, which makes the new style the shared footer's parent. */
    val takeOver: Constructor<*>? = try {
        Class.forName("com.reandroid.arsc.item.StyleItem").getConstructor()
    } catch (_: ReflectiveOperationException) {
        null
    }
}
