package com.rainax.ytdownloader

import android.webkit.CookieManager

/** The app has no accounts: nothing is ever signed in, and any old Google session is wiped. */
object CookieHelper {
    fun clearAccount() {
        try {
            val cm = CookieManager.getInstance()
            val names = setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "SIDCC",
                "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
                "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC")
            for (site in listOf("https://www.youtube.com", "https://m.youtube.com", "https://accounts.google.com", "https://www.google.com")) {
                val old = cm.getCookie(site).orEmpty().split(";").map { it.trim().substringBefore("=") }
                old.filter { it in names }.forEach { n ->
                    cm.setCookie(site, "$n=; Max-Age=0; Path=/")
                    val dom = if (site.contains("youtube")) ".youtube.com" else ".google.com"
                    cm.setCookie(site, "$n=; Max-Age=0; Path=/; Domain=$dom")
                }
            }
            cm.flush()
        } catch (e: Exception) { }
    }
}
