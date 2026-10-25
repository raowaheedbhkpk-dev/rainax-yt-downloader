package com.rainax.ytdownloader

/**
 * The sixteen accent colours of Settings › Accent (made by a script from one base colour each, tuned so text
 * in the accent stays readable on the light and the dark background). The number is what Settings stores,
 * so the first four keep the numbers they always had. Each has its own home-screen icon ([alias]).
 */
object Accents {

    /** [style]: the theme overlay that sets ?attr/rxAccent and friends (film red is the theme's own). */
    class Accent(val id: Int, val name: String, val style: Int, val color: Int, val alias: String)

    val ALL = listOf(
        Accent(0, "Film red", R.style.Theme_Rainax, R.color.rx_primary, "LauncherRed"),
        Accent(1, "Gold", R.style.ThemeOverlay_Rainax_Accent_Gold, R.color.rx_accent_gold, "LauncherGold"),
        Accent(2, "Teal", R.style.ThemeOverlay_Rainax_Accent_Teal, R.color.rx_accent_teal, "LauncherTeal"),
        Accent(3, "Purple", R.style.ThemeOverlay_Rainax_Accent_Purple, R.color.rx_accent_purple, "LauncherPurple"),
        Accent(4, "Violet", R.style.ThemeOverlay_Rainax_Accent_Violet, R.color.rx_accent_violet, "LauncherViolet"),
        Accent(5, "Green", R.style.ThemeOverlay_Rainax_Accent_Green, R.color.rx_accent_green, "LauncherGreen"),
        Accent(6, "Blue", R.style.ThemeOverlay_Rainax_Accent_Blue, R.color.rx_accent_blue, "LauncherBlue"),
        Accent(7, "Pink", R.style.ThemeOverlay_Rainax_Accent_Pink, R.color.rx_accent_pink, "LauncherPink"),
        Accent(8, "Orange", R.style.ThemeOverlay_Rainax_Accent_Orange, R.color.rx_accent_orange, "LauncherOrange"),
        Accent(9, "Slate", R.style.ThemeOverlay_Rainax_Accent_Slate, R.color.rx_accent_slate, "LauncherSlate"),
        Accent(10, "Brown", R.style.ThemeOverlay_Rainax_Accent_Brown, R.color.rx_accent_brown, "LauncherBrown"),
        Accent(11, "Indigo", R.style.ThemeOverlay_Rainax_Accent_Indigo, R.color.rx_accent_indigo, "LauncherIndigo"),
        Accent(12, "Forest", R.style.ThemeOverlay_Rainax_Accent_Forest, R.color.rx_accent_forest, "LauncherForest"),
        Accent(13, "Steel blue", R.style.ThemeOverlay_Rainax_Accent_Steel, R.color.rx_accent_steel, "LauncherSteel"),
        Accent(14, "Plum", R.style.ThemeOverlay_Rainax_Accent_Plum, R.color.rx_accent_plum, "LauncherPlum"),
        Accent(15, "Rose", R.style.ThemeOverlay_Rainax_Accent_Rose, R.color.rx_accent_rose, "LauncherRose"),
    )

    /** The order of the swatches in the picker (rows of four). */
    val GRID = listOf(4, 0, 5, 6, 1, 7, 8, 9, 10, 2, 11, 3, 12, 13, 14, 15)

    fun of(id: Int): Accent = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
