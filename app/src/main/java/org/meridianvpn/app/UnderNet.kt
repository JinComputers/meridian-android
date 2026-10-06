package org.meridianvpn.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * НИЖНЯЯ СЕТЬ ВМЕСТО activeNetwork (04.10).
 *
 * С 0.1.240 само приложение внутри своего туннеля, и для нас activeNetwork
 * после establish() — это сам туннель (tun0). Все места, которые по нему
 * судили о «сети сессии», увидели смену сети, рвали только что поднятый
 * туннель и подключались заново (лог владельца 04.10 16:38:13).
 *
 * Здесь: активная, если она не VPN; иначе нижняя сеть с интернетом,
 * проверенная системой в первую очередь. Так же выбирает ApiClient.
 */
@Suppress("DEPRECATION") // allNetworks
fun ConnectivityManager.underlyingActive(): Network? {
    val act = activeNetwork
    val actCaps = act?.let { getNetworkCapabilities(it) }
    if (act != null && actCaps != null &&
        actCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    ) return act
    var fallback: Network? = null
    for (n in allNetworks) {
        val caps = getNetworkCapabilities(n) ?: continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n
        if (fallback == null) fallback = n
    }
    return fallback
}
