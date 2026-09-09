package org.meridianvpn.app

import androidx.compose.runtime.Composable

/**
 * Отладочных настроек в релизе НЕТ. Пустая заглушка на ту же подпись.
 *
 * Не флаг и не недостижимая ветка: в собранном релизе этого кода нет
 * физически, и проверить это можно обычным grep по dex.
 */
@Composable
fun DebugRows() = Unit
