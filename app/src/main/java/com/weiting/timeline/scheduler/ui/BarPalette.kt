package com.weiting.timeline.scheduler.ui

import androidx.compose.ui.graphics.Color

/**
 * Bar colours are a fixed palette rather than theme colours: a Gantt chart needs stable,
 * mutually distinguishable hues, and Material dynamic colour would otherwise re-tint them
 * from the reviewer's wallpaper.
 */
internal val BarPalette: List<Color> = listOf(
    Color(0xFF3F6FE4),
    Color(0xFF0E9F8A),
    Color(0xFFE08A2E),
    Color(0xFF8E5BD4),
    Color(0xFFD2495F),
    Color(0xFF2A9FB8),
)

internal fun barColor(colorIndex: Int): Color = BarPalette[colorIndex.mod(BarPalette.size)]
