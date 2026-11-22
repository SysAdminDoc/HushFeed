/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.textsize

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal const val TEXT_SIZE_PAGE =
    "Lcom/ss/android/ugc/aweme/setting/page/accessibility/DynamicFontSizeFragment;"
internal const val TEXT_SIZE_MODE_KEY = "dynamic_font_mode"

/**
 * The Text size page's confirm callback, a lambda that saves the picked slider position as
 * TikTok's mode under [TEXT_SIZE_MODE_KEY], copies it into the manager's mode field and
 * recreates every open activity. On 47.9.3 it's `AwS583S0100000_39_I1.invoke$260`.
 */
internal object TextSizeConfirmFingerprint : Fingerprint(
    strings = listOf("confirm_font_size", TEXT_SIZE_MODE_KEY),
)

/**
 * TikTok's text size manager's loader, which reads [TEXT_SIZE_MODE_KEY] into the mode field at
 * startup. Its class also holds the factor methods and the two density rewrites that call them
 * (X.03aF on 47.9.3: loader LJ(Context), factors LIZ()F and LIZJ()F, rewrites LIZLLL(Context)
 * and LJFF(Activity)).
 */
internal object TextSizeModeLoadFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf(TEXT_SIZE_MODE_KEY),
)

/** The Text size page's onCreate, which copies the saved mode into the slider's start position. */
internal object TextSizePageCreateFingerprint : Fingerprint(
    definingClass = TEXT_SIZE_PAGE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The Text size page's onViewCreated, the one place the slider is wired to the page. */
internal object TextSizePageViewCreatedFingerprint : Fingerprint(
    definingClass = TEXT_SIZE_PAGE,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)
