/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val LIVE_ROOM_INFO_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveRoomInfo;"

/** TikTok's own LIVE debug panel, real-named on 47.9.3, which lists the player's numbers. */
internal const val LIVE_DEBUG_INFO_WIDGET = "Lcom/bytedance/android/livesdk/widget/LiveDebugInfoWidget;"

private const val NATIVE_STATS_RETURN = "Ljava/lang/Object;"

/**
 * The debug panel's text (#487): it asks the pull stream service for its player pool, takes the
 * current player and reads its stats JSON, then writes out rtt:, delay:, download_Speed: and the
 * rest under their labels. Its first lines are the one route from the room to those numbers.
 */
internal object LiveDebugInfoTextFingerprint : Fingerprint(
    definingClass = LIVE_DEBUG_INFO_WIDGET,
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("rtt:", "delay:", "download_Speed:"),
)

/**
 * The calls the debug panel makes to reach the current player's stats, read from the panel
 * itself: the service class it looks up, the lookup, the service's player pool getter, the pool's
 * current player field and the player's stats call. Each call keeps the opcode the panel uses.
 */
internal data class LivePlayerStatsReads(
    val service: String,
    val lookup: String,
    val poolGetter: String,
    val poolInvoke: String,
    val player: String,
    val stats: String,
    val statsInvoke: String,
)

private fun Opcode.invokeName(): String? = when (this) {
    Opcode.INVOKE_INTERFACE -> "invoke-interface"
    Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
    else -> null
}

/**
 * The route [panelText] takes from the pull stream service to the player's stats JSON, in order,
 * or a PatchException that says which step is missing.
 */
internal fun livePlayerStatsReads(panelText: Method): LivePlayerStatsReads {
    fun fail(step: String): Nothing = throw PatchException("LIVE controls: the debug panel's $step wasn't found.")
    val code = panelText.implementation?.instructions?.toList() ?: fail("code")
    fun <T> after(from: Int, read: (Int) -> T?): Pair<Int, T> {
        for (index in from until code.size) read(index)?.let { return index to it }
        fail("route to the player's stats")
    }
    val (classAt, service) = after(0) { index ->
        val instruction = code[index]
        if (instruction.opcode != Opcode.CONST_CLASS) null
        else ((instruction as ReferenceInstruction).reference as TypeReference).type
    }
    val (lookupAt, lookup) = after(classAt + 1) { index ->
        val instruction = code[index]
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (instruction.opcode != Opcode.INVOKE_STATIC || method == null) null
        else if (method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Class;")) null
        else method
    }
    val (poolAt, pool) = after(lookupAt + 1) { index ->
        val instruction = code[index]
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (method == null || method.definingClass != service || method.parameterTypes.isNotEmpty()) null
        else instruction.opcode.invokeName()?.let { method to it }
    }
    val (playerAt, player) = after(poolAt + 1) { index ->
        val instruction = code[index]
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode != Opcode.IGET_OBJECT || field == null || field.definingClass != pool.first.returnType) null
        else field
    }
    val (_, stats) = after(playerAt + 1) { index ->
        val instruction = code[index]
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (method == null || method.definingClass != player.type || method.parameterTypes.isNotEmpty()) null
        else if (method.returnType != "Lorg/json/JSONObject;") null
        else instruction.opcode.invokeName()?.let { method to it }
    }
    if (!lookup.returnType.startsWith("L")) fail("service lookup")
    return LivePlayerStatsReads(
        service = service,
        lookup = lookup.toString(),
        poolGetter = pool.first.toString(),
        poolInvoke = pool.second,
        player = player.toString(),
        stats = stats.first.toString(),
        statsInvoke = stats.second,
    )
}

/**
 * Rewrites LiveRoomInfo.nativeStats to take the debug panel's route to the current player's
 * stats, with a null check at each step the panel skips, so a room with no player answers null.
 */
internal fun installLiveRoomInfoBridge(reads: LivePlayerStatsReads) {
    val extension = mutableClassDefBy(LIVE_ROOM_INFO_EXTENSION)
    fun bridge(name: String) = extension.methods.singleOrNull { it.name == name }
        ?: throw PatchException("LIVE controls: missing room line bridge $name")
    val stub: MutableMethod = bridge("nativeStats")
    if (stub.returnType != NATIVE_STATS_RETURN || stub.parameterTypes.isNotEmpty()) {
        throw PatchException("LIVE controls: the room line bridge nativeStats has the wrong shape")
    }
    val method = stub.cloneMutable(additionalRegisters = 2)
    method.addInstructions(
        0,
        """
            const-class v0, ${reads.service}
            invoke-static { v0 }, ${reads.lookup}
            move-result-object v0
            instance-of v1, v0, ${reads.service}
            if-eqz v1, :none
            check-cast v0, ${reads.service}
            ${reads.poolInvoke} { v0 }, ${reads.poolGetter}
            move-result-object v0
            if-eqz v0, :none
            iget-object v0, v0, ${reads.player}
            if-eqz v0, :none
            ${reads.statsInvoke} { v0 }, ${reads.stats}
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    extension.methods.remove(stub)
    extension.methods.add(method)
}
