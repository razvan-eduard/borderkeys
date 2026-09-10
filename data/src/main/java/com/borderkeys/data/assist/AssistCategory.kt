// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * The kinds of task that want different models.
 *
 * Two, not one per task: the split that matters in practice is translation against everything
 * else. A model trained for European-language translation and a model trained to follow a
 * chat-style instruction are good at opposite halves of this feature, and asking one to do the
 * other is where the answers come back narrated or unchanged. Correcting, shortening,
 * summarising and register changes all sit on the instruction-following side with the custom
 * prompt; only the translations sit apart.
 *
 * When one model is imported it runs everything and this makes no difference. With two or more,
 * each category can be pointed at one -- see [com.borderkeys.data.theme.KeyboardPreferences].
 */
enum class AssistCategory {
    WRITE,
    TRANSLATE,
}
