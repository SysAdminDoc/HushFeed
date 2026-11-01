/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PUSH_REPORT = "Lapp/morphe/extension/tiktok/inbox/PushDeliveryReport;"
internal const val PUSH_SETUP_BODY = "Lcom/ss/android/ugc/aweme/legoImp/task/InitPushTask;->LIZJ(Landroid/content/Context;)V"
internal const val TOKEN_REQUEST = "LX/0FdW;->LIZ(Landroid/content/Context;)V"
internal const val TOKEN_FORWARD = "LX/0FdW;->LIZIZ(Landroid/content/Context;Ljava/lang/String;)V"
internal const val TOKEN_RESULT = "LX/0Fdc;->LIZ(LX/07dk;)V"
internal const val TOKEN_UPLOAD = "Lcom/bytedance/push/task/SendTokenTask;->LIZIZ(LX/0FeM;)V"
internal const val FCM_RECEIVE = "Lcom/fcm/service/SSGcmListenerService;->onMessageReceived(Lcom/google/firebase/messaging/RemoteMessage;)V"
internal const val NATIVE_MESSAGE = "Lcom/ss/android/ugc/awemepushlib/di/ies/MessageHandler;->onHandleMessage(Landroid/content/Context;ILjava/lang/String;ILjava/lang/String;)V"
internal val PUSH_NATIVE_METHODS = setOf(PUSH_SETUP_BODY, TOKEN_REQUEST, TOKEN_FORWARD, TOKEN_RESULT, TOKEN_UPLOAD, FCM_RECEIVE, NATIVE_MESSAGE)

internal fun supportsNativePushProfile(packageName: String?, version: String?) =
    packageName == "com.zhiliaoapp.musically" && version == "47.6.3"

internal data class PushObservation(val index: Int, val event: String, val atLabel: Boolean = true)
internal fun Method.pushDescriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Instruction.call() = getReference<MethodReference>()?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
}

/** All anchors are checked before any source write. */
internal fun planNativePushDiagnostics(classes: Iterable<ClassDef>): Map<Method, List<PushObservation>> {
    val methods = classes.flatMap { it.methods }.filter { it.pushDescriptor() in PUSH_NATIVE_METHODS }
    if (methods.map { it.pushDescriptor() }.toSet() != PUSH_NATIVE_METHODS || methods.size != PUSH_NATIVE_METHODS.size) {
        throw PatchException("Push diagnostics: missing or duplicate global 47.6.3 native method.")
    }
    return methods.associateWith(::planPushObservations)
}

internal fun planPushObservations(method: Method): List<PushObservation> {
    val instructions = method.implementation?.instructions?.toList()
        ?: throw PatchException("Push diagnostics: ${method.name} has no body.")
    val plan = mutableListOf<PushObservation>()
    fun fail(what: String): Nothing = throw PatchException("Push diagnostics: ${method.name} has no unique $what anchor.")
    fun one(what: String, predicate: (Instruction) -> Boolean): Int {
        val matches = instructions.indices.filter { predicate(instructions[it]) }
        return matches.singleOrNull() ?: fail(what)
    }
    fun literal(value: String) = one(value) { it.getReference<StringReference>()?.string == value }
    fun call(value: String) = one(value) { it.call() == value }
    fun target(index: Int): Int {
        val addresses = instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val offset = instructions[index] as? OffsetInstruction ?: fail("branch target")
        return addresses.indexOf(addresses[index] + offset.codeOffset).takeIf { it >= 0 } ?: fail("branch destination")
    }
    fun record(index: Int, event: String, atLabel: Boolean = true) { plan += PushObservation(index, event, atLabel) }
    fun errorAfter(value: String): Int {
        val start = literal(value)
        return (start + 1 until minOf(instructions.size, start + 8)).singleOrNull {
            instructions[it].call() == "LX/03N1;->LJIIIIZZ(IILjava/lang/String;Ljava/lang/String;)V"
        } ?: fail("$value error")
    }
    when (method.pushDescriptor()) {
        PUSH_SETUP_BODY -> {
            call("Lcom/ss/android/ugc/awemepushapi/IPushApi;->init(Landroid/content/Context;LX/03LL;)V")
            call("Lcom/ss/android/ugc/awemepushapi/IPushApi;->setupNotificationChannelRestore(Landroid/content/Context;)V")
            record(0, "setupBody")
        }
        TOKEN_REQUEST -> {
            call("LX/07dk;->LIZIZ(LX/0Fdb;)LX/07dk;")
            if (instructions.none { it.call() == "LX/0Fdc;-><init>(Landroid/content/Context;)V" }) fail("FCM completion owner")
            record(0, "tokenRequest")
        }
        TOKEN_RESULT -> {
            call("LX/07dk;->LJIILL()Z")
            if (instructions.count { it.opcode == Opcode.IF_EQZ } != 3) fail("null/success/result branches")
            record(call(TOKEN_FORWARD), "tokenResultPresent")
            record(errorAfter("token is empty"), "tokenResultRejectedOrEmpty")
        }
        TOKEN_FORWARD -> {
            call("Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z")
            if (instructions.none { it.opcode == Opcode.IF_NEZ }) fail("empty-token branch")
            record(call("LX/0FeI;->LIZJ(ILandroid/content/Context;Ljava/lang/String;)V"), "tokenForwarded")
            record(errorAfter("token is empty"), "tokenEmpty")
        }
        TOKEN_UPLOAD -> {
            val request = literal("/service/1/update_token/")
            call("LX/0Fd2;->LJI(I)Z")
            call("Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z")
            literal("success")
            literal("message")
            val parsed = call("Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;")
            val equals = call("Ljava/lang/String;->equals(Ljava/lang/Object;)Z")
            val accepted = call("LX/0FeP;->LIZJ(Landroid/content/Context;LX/0FeO;)V")
            val rejected = errorAfter("return error")
            if (parsed >= equals || instructions.getOrNull(equals + 1)?.opcode != Opcode.MOVE_RESULT ||
                instructions.getOrNull(equals + 2)?.opcode != Opcode.IF_EQZ ||
                (instructions[equals + 1] as OneRegisterInstruction).registerA != (instructions[equals + 2] as OneRegisterInstruction).registerA ||
                accepted !in equals + 3 until target(equals + 2) || rejected !in target(equals + 2) until target(equals + 2) + 8
            ) fail("success/rejection control flow")
            val callbacks = instructions.indices.filter { instructions[it].call() == "LX/03N1;->LJ(I)V" }
            val cached = callbacks.filter { it < request }.singleOrNull() ?: fail("cached registration")
            val ignoredReturn = instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
            if (ignoredReturn !in 1 until cached || instructions.drop(cached + 1).take(request - cached - 1).none { it.opcode == Opcode.RETURN_VOID }) fail("ignored/cache returns")
            record(0, "uploadEntered")
            record(ignoredReturn, "uploadIgnored")
            record(cached, "uploadCached")
            record(call("LX/05DO;->LIZJ(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;"), "uploadAttempted")
            record(accepted, "uploadAccepted")
            record(rejected, "uploadRejected")
            record(errorAfter("token is empty"), "uploadEmpty")
            val handlers = instructions.indices.filter { instructions[it].opcode == Opcode.MOVE_EXCEPTION }
            if (handlers.size != 8) fail("exception handlers")
            // move-exception stays first. Normal edges to the next instruction skip this observation.
            handlers.forEach { record(it + 1, "uploadException", false) }
        }
        FCM_RECEIVE -> {
            call("Lcom/fcm/service/SSGcmListenerService;->com_fcm_service_SSGcmListenerService_com_ss_android_ugc_aweme_lancet_PushColdBootLancet_onMessageReceived(Lcom/fcm/service/SSGcmListenerService;Lcom/google/firebase/messaging/RemoteMessage;)V")
            record(0, "fcmReceived")
        }
        NATIVE_MESSAGE -> {
            call("Lcom/ss/android/ugc/awemepushlib/manager/MessageShowHandler;->LIZLLL(Landroid/content/Context;ILjava/lang/String;I)V")
            record(0, "nativeHandlerEntered")
        }
        else -> fail("supported method")
    }
    return plan
}

internal fun MutableMethod.applyPushObservations(plan: List<PushObservation>) {
    plan.sortedByDescending { it.index }.forEach { observation ->
        val call = "invoke-static {}, $PUSH_REPORT->${observation.event}()V"
        if (observation.atLabel) addInstructionsAtControlFlowLabel(observation.index, call)
        else addInstruction(observation.index, call)
    }
}

internal val pushDeliveryDiagnosticsPatch = bytecodePatch {
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())
    execute {
        val supported = supportsNativePushProfile(packageMetadata.packageName, packageMetadata.versionName)
        if (supported) {
            val classes = PUSH_NATIVE_METHODS.map { it.substringBefore("->") }.distinct().map(::classDefBy)
            val plans = planNativePushDiagnostics(classes)
            plans.forEach { (method, plan) ->
                mutableClassDefBy(method.definingClass).findMutableMethodOf(method).applyPushObservations(plan)
            }
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, $PUSH_REPORT->${if (supported) "nativeProfileInstalled" else "nativeProfileUnsupported"}()V")
    }
}
