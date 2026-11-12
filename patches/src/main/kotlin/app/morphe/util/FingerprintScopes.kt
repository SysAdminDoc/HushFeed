/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
/**
 * The types of the classes holding a call to some method of one of [owners], each an exact type
 * (`Lpkg/Type;`).
 *
 * <p>The patcher keeps an index of which classes reference which types, and a fingerprint whose
 * one instruction filter is a call on such a type is matched against those classes alone. A scan
 * that has to read every call in TikTok can skip every other class: these classes are a superset
 * of the callers of any one method, so the scan that follows still reads each instruction and
 * finds what it found before, over a few hundred classes instead of every class of the app (#54).
 * Each lookup also walks the class list once, so this takes types, not type and name pairs,
 * which cost the browser guard about eighty lookups. Hushfeed's own injected calls go to the
 * extension, so a class that calls one of these only after an earlier patch ran is not a case
 * this has to cover.
 */
internal fun classesCalling(owners: Collection<String>): Set<String> {
    val types = HashSet<String>()
    for (owner in owners.toSet()) {
        require(owner.startsWith("L") && owner.endsWith(";")) { "classesCalling needs an exact type, not $owner" }
        Fingerprint(filters = listOf(methodCall(definingClass = owner)))
            .matchAllOrNull().orEmpty()
            .mapTo(types) { it.originalClassDef.type }
    }
    return types
}

/**
 * The types of the classes holding a call to one of [targets], each a full method descriptor
 * (`Lpkg/Type;->name(params)ret`), looked up by the owner and the method's name together.
 *
 * <p>A lookup by owner alone makes the patcher build a match for every method that calls any
 * method of that owner, then a set of all of them to drop repeats. For `View` that is most of
 * TikTok, and Turn off haptics ran a desktop patcher out of heap right there (#220). With the name
 * in the filter the patcher's index narrows to the classes that name both, so each lookup holds a
 * few hundred matches instead.
 */
internal fun classesCallingMethods(targets: Collection<String>): Set<String> {
    val pairs = targets.mapTo(LinkedHashSet()) { target ->
        require(target.startsWith("L") && ";->" in target && "(" in target) {
            "classesCallingMethods needs a full method descriptor, not $target"
        }
        target.substringBefore("->") to target.substringAfter("->").substringBefore('(')
    }
    val types = HashSet<String>()
    for ((owner, name) in pairs) {
        Fingerprint(filters = listOf(methodCall(definingClass = owner, name = name)))
            .matchAllOrNull().orEmpty()
            .mapTo(types) { it.originalClassDef.type }
    }
    return types
}
