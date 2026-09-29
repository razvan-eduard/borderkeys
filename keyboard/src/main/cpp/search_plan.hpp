// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_SEARCH_PLAN_HPP
#define BORDERKEYS_SEARCH_PLAN_HPP

#include <cstdint>

namespace borderkeys {

/** The searches one request runs, in order. */
enum class Pass : uint8_t {
    /** The packs the request is restricted to. */
    Primary,
    /** Every pack, when Primary found nothing and the language is not locked. */
    AllPacks,
    /** The personal dictionary. */
    UserModel,
    /** Every pack again at kFallbackEditCost, when nothing else found anything. */
    Wide,
    /** Successors and phrases, when nothing has been typed. */
    NextWord,
};

/** Whether a pass's candidates may be committed. Wide fills the strip only. */
constexpr bool commits(Pass pass) {
    return pass != Pass::Wide;
}

/** What a pass searches. */
enum class PassSource : uint8_t {
    /** The packs the request is restricted to; every active pack when it is not restricted. */
    RestrictedPacks,
    /** Every active pack. */
    AllPacks,
    /** The personal words. */
    PersonalWords,
    /** The personal successors and phrases. */
    PersonalNextWords,
};

/** The edit-cost ceiling a pass walks the packs under. */
enum class PassCeiling : uint8_t {
    /** maxEditCostFor the typed length. */
    ByLength,
    /** kFallbackEditCost. */
    Fallback,
    /** None: the pass does not walk the packs. */
    None,
};

/** What a pass's condition reads. */
struct PlanState {
    /** The typed word's folded length; zero when nothing is typed. */
    int typedLength;
    /** Whether the request is restricted to some packs. */
    bool restricted;
    /** Whether the language lock is strict. */
    bool strict;
    /** How many candidates the passes before this one found. */
    int found;
};

/** One pass: when it runs, what it searches, and under what ceiling. */
struct PassSpec {
    Pass pass;
    bool (*runs)(const PlanState& state);
    PassSource source;
    PassCeiling ceiling;
};

namespace plan {

constexpr bool always(const PlanState& /*state*/) {
    return true;
}

constexpr bool restrictedPrimaryFoundNothing(const PlanState& state) {
    return state.found == 0 && state.restricted && !state.strict;
}

constexpr bool somethingTyped(const PlanState& state) {
    return state.typedLength > 0;
}

constexpr bool somethingTypedAndNothingFound(const PlanState& state) {
    return state.typedLength > 0 && state.found == 0;
}

constexpr bool nothingTyped(const PlanState& state) {
    return state.typedLength == 0;
}

}  // namespace plan

/** The passes of one request, in the order they run. */
inline constexpr PassSpec kSearchPlan[] = {
    {Pass::Primary, plan::always, PassSource::RestrictedPacks, PassCeiling::ByLength},
    {Pass::AllPacks, plan::restrictedPrimaryFoundNothing, PassSource::AllPacks,
     PassCeiling::ByLength},
    {Pass::UserModel, plan::somethingTyped, PassSource::PersonalWords, PassCeiling::None},
    {Pass::Wide, plan::somethingTypedAndNothingFound, PassSource::AllPacks,
     PassCeiling::Fallback},
    {Pass::NextWord, plan::nothingTyped, PassSource::PersonalNextWords, PassCeiling::None},
};

}  // namespace borderkeys

#endif  // BORDERKEYS_SEARCH_PLAN_HPP
