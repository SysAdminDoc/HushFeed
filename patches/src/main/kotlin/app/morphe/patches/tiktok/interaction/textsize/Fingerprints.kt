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
 * TikTok's mode under [TEXT_SIZE_MODE_KEY] and recreates every open activity.
 */
internal object TextSizeConfirmFingerprint : Fingerprint(
    strings = listOf("confirm_font_size", TEXT_SIZE_MODE_KEY),
)

/**
 * The text size manager's loader, which reads [TEXT_SIZE_MODE_KEY] at startup. Its class also
 * holds the mode field and the methods that turn the mode into a factor for the interface; its
 * name is obfuscated (X.04F8 on 47.7.1, X.03aC on 47.9.2).
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
