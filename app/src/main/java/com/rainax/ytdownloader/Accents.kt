package com.rainax.ytdownloader

/**
 * The home-screen icons the old accent colours had (one activity-alias each in the manifest). The app is black
 * and white since v19.2 and always uses the first one; StyleSync still knows them all, so a phone that had
 * another one switches back to the normal icon.
 */
object Accents {

    class Accent(val id: Int, val alias: String)

    val ALL = listOf(
        "LauncherRed", "LauncherGold", "LauncherTeal", "LauncherPurple", "LauncherViolet", "LauncherGreen", "LauncherBlue",
        "LauncherPink", "LauncherOrange", "LauncherSlate", "LauncherBrown", "LauncherIndigo", "LauncherForest", "LauncherSteel",
        "LauncherPlum", "LauncherRose"
    ).mapIndexed { i, a -> Accent(i, a) }

    fun of(id: Int): Accent = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
