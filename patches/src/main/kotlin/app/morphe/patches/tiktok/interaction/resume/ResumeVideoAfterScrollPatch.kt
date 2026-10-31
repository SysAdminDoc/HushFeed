/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.numberOfParameterRegisters
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/interaction/ResumeVideoAfterScrollPatch;"

/** What a call's answer is taken with. Nothing may be put between the call and one of these. */
private val MOVE_RESULTS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT)

@Suppress("unused")
val resumeVideoAfterScrollPatch = bytecodePatch(
    name = "Resume videos after scrolling",
    description = "When you scroll back to a video, it picks up where you left off instead of " +
        "starting over. On by default. Turn it off in Hushfeed settings > Playback.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Everything the writes below need is read off the host and checked before the first of
        // them, because the patcher does not take a failed patch's writes back out.
        val settingsStatus = SettingsStatusLoadFingerprint.method

        val continueGate = FeedProgressContinueGateFingerprint.method
        // v0 is scratch and the branch falls back into the host's own first instruction, so
        // the register has to be a local rather than one of the parameters.
        continueGate.requireLocals("Resume videos after scrolling", 1)

        val completed = FeedPlayCompletedFingerprint.method
        // v0 and v1 are written before the host's own first instruction runs.
        completed.requireLocals("Resume videos after scrolling", 2)

        // TikTok records a position for every feed it plays, but it only hands one back when the
        // feed's event type is in FeedPlayProgressContinueConfig.event_type_list. On 46.2.3 that
        // list is built in the config's own constructor and holds homepage_hot, others_homepage,
        // personal_homepage, landscape_mode, account_history and collection_video. The Following
        // and Friends tabs are not in it, which is why the switch appeared to do nothing there
        // while the position was being stored all along.
        val resumePosition = FeedProgressResumePositionFingerprint.method
        val eventTypeListIndex = resumePosition.indexOfFirstInstructionOrThrow {
            getReference<FieldReference>()?.name == "event_type_list"
        }
        val containsIndex = resumePosition.indexOfFirstInstructionOrThrow(eventTypeListIndex) {
            val reference = getReference<MethodReference>()
            reference?.definingClass == "Ljava/util/List;" && reference.name == "contains"
        }
        val containsResult = resumePosition.getInstruction(containsIndex + 1)
        if (containsResult.opcode != Opcode.MOVE_RESULT) {
            throw PatchException(
                "Resume video after scroll: the event type check does not keep its answer.",
            )
        }
        val eventTypeRegister = (containsResult as OneRegisterInstruction).registerA
        check(eventTypeRegister <= 15) {
            "Resume video after scroll: the event type answer is above v15, which the " +
                "override cannot name."
        }

        val progress = FeedPlayProgressFingerprint.method
        val cachePutIndex = progress.indexOfFirstInstructionReversedOrThrow {
            val reference = getReference<MethodReference>()
            reference?.definingClass == "Landroid/util/LruCache;" &&
                reference.name == "put" &&
                reference.parameterTypes.size == 2
        }
        val (cacheRegister, keyRegister, scratchRegister) =
            progressClearRegistersAt(progress, cachePutIndex)
        val continueInstruction = progress.getInstruction(cachePutIndex + 1)

        // Where the positions live, read off that same put because every name in it is
        // renamed per build.
        val store = progressStoreAt(progress, cachePutIndex) { classDefByOrNull(it) }

        // PineDrama alone asks its drama app-diff store where an episode starts, and an unpause got
        // the series' start point back, so tapping a paused episode played it over (#156). That
        // happens with the switch off too, so the hook doesn't read it.
        val seriesStart = if (packageMetadata.packageName == AppCompatibilities.PINEDRAMA_PACKAGE) {
            val start = SeriesResumeStartFingerprint.method
            val controller = classDefByOrNull(start.definingClass) ?: throw PatchException(
                "Resume video after scroll: ${start.definingClass} isn't in this build.",
            )
            Triple(start, seriesResumeStartSiteIn(start), playerPausedCheckIn(controller) { classDefByOrNull(it) })
        } else {
            null
        }

        settingsStatus.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableResumeVideoAfterScroll()V",
        )

        continueGate.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                move-result v0
                if-eqz v0, :continue_gate
                const/4 v0, 0x1
                invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("continue_gate", continueGate.getInstruction(0)),
        )

        // A video watched to the end starts from the top next time: its position leaves the
        // cache, and so does the record the store would otherwise answer from first.
        completed.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                move-result v0
                if-eqz v0, :continue_completion
                sget-object v0, ${store.cache.smali()}
                ${store.cacheValueInvoke} {v0}, ${store.cacheValue.smali()}
                move-result-object v0
                check-cast v0, Landroid/util/LruCache;
                move-object/from16 v1, p1
                invoke-virtual {v0, v1}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                const/4 v0, 0x0
                sput-object v0, ${store.lastRecord.smali()}
                sput-object v0, ${store.lastAid.smali()}
            """,
            ExternalLabel("continue_completion", completed.getInstruction(0)),
        )

        resumePosition.addInstructions(
            containsIndex + 2,
            """
                invoke-static {v$eventTypeRegister}, $EXTENSION_DESCRIPTOR->allowResumeInThisFeed(Z)Z
                move-result v$eventTypeRegister
            """,
        )

        progress.addInstructionsWithLabels(
            cachePutIndex + 1,
            """
                invoke-static/range {p2 .. p5}, $EXTENSION_DESCRIPTOR->shouldClearCompletedProgress(JJ)Z
                move-result v$scratchRegister
                if-eqz v$scratchRegister, :continue_progress
                invoke-virtual {v$cacheRegister, v$keyRegister}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                const/4 v$scratchRegister, 0x0
                sput-object v$scratchRegister, ${store.lastRecord.smali()}
                sput-object v$scratchRegister, ${store.lastAid.smali()}
            """,
            ExternalLabel("continue_progress", continueInstruction),
        )

        seriesStart?.let { (start, site, paused) -> start.installSeriesResumeStart(site, paused) }
    }
}

private const val APP_DIFF_PROTOCOL = "Lcom/ss/android/ugc/aweme/drama/IDramaOfflineFeedAppDiffProtocol;"

/**
 * Where the series start hook goes: the line after PineDrama takes the app-diff store's answer.
 * [requested] and [tag] are the caller's start and its name for the play, and [manager] and
 * [paused] are two locals nothing reads from there on.
 */
internal data class SeriesResumeStartSite(
    val index: Int,
    val appDiff: Int,
    val requested: Int,
    val tag: Int,
    val manager: Int,
    val paused: Int,
)

/** The site in PineDrama's `(I, Aweme, String)I` start method, and every reason there isn't one. */
internal fun seriesResumeStartSiteIn(start: Method): SeriesResumeStartSite {
    // p1 and p3 are the caller's start and tag only when p0 is the controller.
    check(!AccessFlags.STATIC.isSet(start.accessFlags)) {
        "Resume video after scroll: PineDrama's series start method is static, so p1 and p3 " +
            "are not the caller's start and tag."
    }
    val instructions = start.implementation?.instructions?.toList()
        ?: throw PatchException("Resume video after scroll: PineDrama's series start method has no body.")
    val call = instructions.indices.filter { index ->
        val reference = instructions[index].getReference<MethodReference>()
        reference?.definingClass == APP_DIFF_PROTOCOL && reference.returnType == "I"
    }.singleOrNull() ?: throw PatchException(
        "Resume video after scroll: PineDrama's series start method doesn't ask the drama " +
            "app-diff store exactly once.",
    )
    val answer = instructions.getOrNull(call + 1)
    if (answer?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException(
            "Resume video after scroll: PineDrama's series start method doesn't keep the " +
                "app-diff store's answer.",
        )
    }
    val appDiff = (answer as OneRegisterInstruction).registerA
    val index = call + 2
    if (index >= instructions.size) {
        throw PatchException("Resume video after scroll: the app-diff answer ends the series start method.")
    }

    val p0 = start.p0Register
    val requested = p0 + 1
    val tag = p0 + 3
    check(maxOf(appDiff, tag) <= 15) {
        "Resume video after scroll: the series start method reaches above v15, which the hook " +
            "cannot name."
    }

    // A jump onto that line would go around the hook, since a branch keeps its old target.
    val flow = ControlFlow.of(start)
    val reachedOtherwise = flow.normal.indices.any { from -> from != index - 1 && index in flow.normal[from] } ||
        flow.exceptional.any { index in it }
    if (reachedOtherwise) {
        throw PatchException(
            "Resume video after scroll: something in the series start method jumps onto the line " +
                "after the app-diff answer, so the hook would be skipped there.",
        )
    }

    val live = RegisterLiveness.of(start).liveInto(index) + setOf(appDiff, requested, tag)
    val free = (0 until minOf(16, p0)).filter { it !in live }
    if (free.size < 2) {
        throw PatchException(
            "Resume video after scroll: the series start method has no two free locals after the " +
                "app-diff answer.",
        )
    }
    return SeriesResumeStartSite(index, appDiff, requested, tag, free[0], free[1])
}

/** The player manager field the hook reads, its type, and whether that type is an interface. */
internal data class PlayerPausedCheck(val field: String, val managerType: String, val throughInterface: Boolean)

/**
 * How the hook asks PineDrama's player whether it's paused. The field is read rather than the
 * controller's getter, which builds a manager when there is none.
 */
internal fun playerPausedCheckIn(controller: ClassDef, classDefOf: (String) -> ClassDef?): PlayerPausedCheck {
    val field = controller.fields.singleOrNull {
        it.name == "mPlayerManager" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException(
        "Resume video after scroll: ${controller.type} has no mPlayerManager field, so the series " +
            "start can't ask the player whether it's paused.",
    )
    val type = field.type
    val manager = classDefOf(type) ?: throw PatchException(
        "Resume video after scroll: $type, the player manager, isn't in this build.",
    )
    if (!answersIsPaused(manager, classDefOf)) {
        throw PatchException("Resume video after scroll: the player manager $type can't answer isPaused().")
    }
    return PlayerPausedCheck(
        field = "${controller.type}->mPlayerManager:$type",
        managerType = type,
        throughInterface = AccessFlags.INTERFACE.isSet(manager.accessFlags),
    )
}

/** Whether [start] or anything it extends or implements declares `isPaused()Z`. */
private fun answersIsPaused(start: ClassDef, classDefOf: (String) -> ClassDef?): Boolean {
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val classDef = pending.removeFirst()
        if (!seen.add(classDef.type)) continue
        if (classDef.methods.any { it.name == "isPaused" && it.parameterTypes.isEmpty() && it.returnType == "Z" }) {
            return true
        }
        (classDef.interfaces + listOfNotNull(classDef.superclass)).mapNotNull(classDefOf).forEach(pending::addLast)
    }
    return false
}

/**
 * Hands the app-diff answer to the extension with the caller's start, its tag and whether the
 * player is paused, and keeps what comes back. A missing manager counts as not paused.
 */
internal fun MutableMethod.installSeriesResumeStart(site: SeriesResumeStartSite, paused: PlayerPausedCheck) {
    val invoke = if (paused.throughInterface) "invoke-interface" else "invoke-virtual"
    addInstructionsWithLabels(
        site.index,
        """
            iget-object v${site.manager}, p0, ${paused.field}
            const/4 v${site.paused}, 0x0
            if-eqz v${site.manager}, :series_start_answer
            $invoke {v${site.manager}}, ${paused.managerType}->isPaused()Z
            move-result v${site.paused}
            :series_start_answer
            invoke-static {v${site.appDiff}, v${site.requested}, v${site.tag}, v${site.paused}}, $EXTENSION_DESCRIPTOR->seriesResumeStart(IILjava/lang/String;I)I
            move-result v${site.appDiff}
        """,
    )
}

/** The three registers the progress clear writes through, read off the host's own put. */
internal data class ProgressClearRegisters(val cache: Int, val key: Int, val flag: Int)

/**
 * The registers the clear may use at [cachePutIndex] + 1, and every reason it may not.
 *
 * <p>The cache, the key and a register to work in all come out of the put itself: it holds the
 * cache and the key this has to remove, and the value it was given is spent the moment it
 * returns. Naming them by number was right on 46.2.3 and says nothing about the next build.
 *
 * <p>"Spent the moment it returns" was a comment rather than a question put to the method. The
 * value register is an ordinary host register, and a build that reads it again after the put
 * would have had the flag written over the top of it, with nothing here to say so and the
 * damage showing up as wrong playback rather than as a failed patch. `GhostModeCallSites`
 * asks `RegisterLiveness` for exactly this before it writes its own flag mid-method, and the
 * working notes say every mid-method write needs the same answer.
 */
internal fun progressClearRegistersAt(progress: Method, cachePutIndex: Int): ProgressClearRegisters {
    // p2 to p5 are the two timestamps only when p0 is the receiver. A static callback of the
    // same signature would hand the clear the aid and half a timestamp.
    check(!AccessFlags.STATIC.isSet(progress.accessFlags)) {
        "Resume video after scroll: the progress callback is static, so p2 to p5 are not the " +
            "two timestamps the clear is given."
    }

    val instructions = progress.implementation?.instructions?.toList()
        ?: throw PatchException("Resume video after scroll: the progress method has no body.")
    val put = instructions.getOrNull(cachePutIndex) as? FiveRegisterInstruction
        ?: throw PatchException(
            "Resume video after scroll: the progress cache put is not a plain invoke.",
        )
    val registers = ProgressClearRegisters(put.registerC, put.registerD, put.registerE)
    check(maxOf(registers.cache, registers.key, registers.flag) <= 15) {
        "Resume video after scroll: the progress cache put reaches above v15, which the " +
            "removal cannot name."
    }

    // The value is only spent once the put returns if nothing takes the put's own answer,
    // and a line wedged between a call and its move-result is one the verifier refuses.
    val continueInstruction = instructions.getOrNull(cachePutIndex + 1)
        ?: throw PatchException(
            "Resume video after scroll: the progress cache put ends the method, so nothing " +
                "can go after it.",
        )
    if (continueInstruction.opcode in MOVE_RESULTS) {
        throw PatchException(
            "Resume video after scroll: the progress cache put keeps its answer, so nothing " +
                "can go straight after it.",
        )
    }

    // Asked of the whole method, branches and handlers included, rather than of the lines that
    // happen to follow the put.
    if (registers.flag in RegisterLiveness.of(progress).liveInto(cachePutIndex + 1)) {
        throw PatchException(
            "Resume video after scroll: v${registers.flag}, the value handed to the progress " +
                "cache put, is read again after it, so the clear cannot answer in it.",
        )
    }

    return registers
}
