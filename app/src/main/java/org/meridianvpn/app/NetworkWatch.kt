package org.meridianvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Наблюдение за сетями ПОД туннелем.
 *
 * Следим за сетями с NET_CAPABILITY_NOT_VPN, а не за сетью по
 * умолчанию. Причина: как только наш туннель поднимется, сетью по
 * умолчанию для приложения станет он сам, и наблюдать мы будем за
 * собственным туннелем вместо канала оператора.
 *
 * @param onUnderlyingLost вызывается при пропаже сети. НЕ с главного потока.
 */
class NetworkWatch(
    private val ctx: Context,
    // ИМЯ ЗДЕСЬ НЕ СЛУЧАЙНО. Раньше параметр звался onLost — как и метод
    // NetworkCallback. Внутри object : NetworkCallback() голый вызов
    // onLost(network) разрешался в метод самого объекта, а не в эту
    // лямбду: член перекрывает захваченную переменную, и компилятор об
    // этом молчит. Получалась бесконечная рекурсия и StackOverflowError
    // на ConnectivityThread, то есть падение всего процесса.
    //
    // Любой будущий параметр-обработчик обязан называться иначе, чем
    // метод NetworkCallback: не onAvailable, не onLost, не
    // onCapabilitiesChanged, не onLinkPropertiesChanged.
    private val onUnderlyingLost: (Network) -> Unit,
    /**
     * Адрес нижней сети сменился, а сама сеть выжила.
     *
     * Самый частый вид обрыва на сотовой: перерегистрация меняет IPv4,
     * объект Network и имя интерфейса при этом те же, и onLost не
     * приходит НИКОГДА. Наш UDP-сокет держит прежний адрес-источник,
     * все sendto по нему отдают ENETUNREACH, и до этой правки такое
     * замечал только сторож тишины — через полторы минуты.
     */
    private val onUnderlyingAddrChanged: (Network) -> Unit,
    /**
     * Android перестал видеть связь в нижней сети (снял VALIDATED).
     *
     * Сигнал шумный: проверка связи мигает и на исправных сетях. Рвать
     * по нему сессию нельзя, поэтому получатель обязан лишь ускорить
     * проверку, а не выносить приговор.
     */
    private val onUnderlyingSuspect: (Network) -> Unit,
) {

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val lock = Any()

    /**
     * Имя интерфейса запоминаем, пока сеть жива: в onLost
     * getLinkProperties уже отдаёт null, и в самом интересном событии
     * мы остались бы без имени.
     */
    private val names = HashMap<Network, String>()

    /**
     * Последняя записанная в лог сводка возможностей на сеть.
     *
     * onCapabilitiesChanged срабатывает часто, в том числе на изменение
     * уровня сигнала. Без этого сравнения получился бы ровно тот
     * неограниченный поток строк, что запрещён инвариантами.
     */
    private val lastCaps = HashMap<Network, String>()

    /**
     * Последние известные IPv4-адреса сети.
     *
     * Только v4 и намеренно: наш сокет до релея стоит на v4, а v6 в
     * туннель не идёт вовсе. Приватные адреса IPv6 на мобильных сетях
     * перевыпускаются сами по себе, и считать это обрывом значило бы
     * рвать исправную сессию по расписанию оператора.
     */
    private val lastAddrs = HashMap<Network, Set<String>>()

    /** Была ли сеть проверенной в прошлый раз — для перехода да→нет. */
    private val lastValid = HashMap<Network, Boolean>()

    /**
     * Повторный вызов ничего не делает.
     *
     * Важно: startTunnel() зовётся заново при каждом переподключении, и
     * без этой проверки на каждой смене сети добавлялся бы ещё один
     * обработчик, а с ним ещё одна копия каждой строки в логе.
     */
    fun start() {
        if (callback != null) return
        try {
            val manager = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            cm = manager

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val name = ifaceOf(network)
                    synchronized(lock) { names[network] = name }
                    // Только сообщаем. Живую сессию на появление сети,
                    // пусть даже лучшего типа, НЕ трогаем: рвать рабочий
                    // туннель ради потенциально лучшего пути — это
                    // заметный провал связи там, где его могло не быть.
                    // Переезд на Wi-Fi будет отдельной, осознанной
                    // задачей.
                    TunnelLog.add("СЕТЬ появилась: $name, ${transportOf(network)}")
                }

                override fun onLost(network: Network) {
                    val name = synchronized(lock) { names.remove(network) } ?: ifaceOf(network)
                    synchronized(lock) {
                        lastCaps.remove(network)
                        lastAddrs.remove(network)
                        lastValid.remove(network)
                    }
                    TunnelLog.add("СЕТЬ пропала: $name")
                    // Явная квалификация this@NetworkWatch — второй,
                    // независимый от имени заслон: она делает адресата
                    // однозначным, даже если параметр когда-нибудь
                    // переименуют обратно в onLost.
                    this@NetworkWatch.onUnderlyingLost(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    val name = synchronized(lock) { names[network] } ?: ifaceOf(network)
                    val summary = buildString {
                        append(transportName(caps))
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            append(", проверена")
                        }
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                            append(", безлимит")
                        }
                    }
                    val changed = synchronized(lock) {
                        if (lastCaps[network] == summary) {
                            false
                        } else {
                            lastCaps[network] = summary
                            true
                        }
                    }
                    if (changed) {
                        TunnelLog.add("СЕТЬ изменилась: $name, $summary")
                    }

                    // ПЕРЕХОД да→нет, а не само отсутствие проверки.
                    // Сеть без VALIDATED бывает и в норме — например,
                    // пока проверка ещё идёт после подъёма. Значение
                    // имеет именно утрата: связь БЫЛА и пропала.
                    val valid = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val lostValidation = synchronized(lock) {
                        val was = lastValid[network]
                        lastValid[network] = valid
                        was == true && !valid
                    }
                    if (lostValidation) {
                        TunnelLog.add("СЕТЬ $name перестала проходить проверку связи")
                        this@NetworkWatch.onUnderlyingSuspect(network)
                    }
                }

                /**
                 * Смена адресов на ВЫЖИВШЕЙ сети.
                 *
                 * Ради этого метода правка и делалась. На сотовой
                 * перерегистрации Android оставляет тот же объект
                 * Network и то же имя интерфейса, onLost не приходит
                 * вовсе, а onCapabilitiesChanged в логе владельца 09.09
                 * дал лишь мигание "проверена". Смена IPv4 —
                 * единственный однозначный признак того, что сокет
                 * мёртв.
                 */
                override fun onLinkPropertiesChanged(
                    network: Network,
                    props: LinkProperties,
                ) {
                    val name = props.interfaceName ?: synchronized(lock) { names[network] }
                        ?: "интерфейс?"
                    synchronized(lock) { names[network] = name }

                    val now = props.linkAddresses
                        .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                        .toSet()

                    // Пропажу считаем обрывом, появление — нет.
                    // Добавленный адрес прежнему сокету не мешает, а
                    // исчезнувший означает, что его адрес-источник
                    // больше не существует.
                    val gone = synchronized(lock) {
                        val was = lastAddrs[network]
                        lastAddrs[network] = now
                        lostAddrs(was, now)
                    }
                    if (gone.isEmpty()) return

                    TunnelLog.add("СЕТЬ $name сменила адрес: пропал ${gone.joinToString(", ")}")
                    this@NetworkWatch.onUnderlyingAddrChanged(network)
                }
            }

            manager.registerNetworkCallback(request, cb)
            callback = cb
            TunnelLog.add("наблюдение за сетями включено")
        } catch (e: Throwable) {
            // Наблюдение — не то, ради чего стоит ронять подключение.
            TunnelLog.add("наблюдение за сетями не включилось: ${e.message}")
        }
    }

    fun stop() {
        try {
            callback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (e: Throwable) {
            // Снятие незарегистрированного обработчика бросает
            // IllegalArgumentException — нам это безразлично.
        }
        callback = null
        cm = null
        synchronized(lock) {
            names.clear()
            lastCaps.clear()
            lastAddrs.clear()
            lastValid.clear()
        }
    }

    /**
     * Активная сеть НЕ считая туннеля — та, на которой встанет наш
     * защищённый сокет.
     *
     * Спрашивать имеет смысл только ДО establish(): после него активной
     * станет сам туннель, и проверка на NOT_VPN вернёт null.
     */
    fun activeUnderlying(): Network? = try {
        val manager = cm ?: ctx.getSystemService(ConnectivityManager::class.java)
        val net = manager?.activeNetwork
        val caps = net?.let { manager.getNetworkCapabilities(it) }
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            net
        } else {
            null
        }
    } catch (e: Throwable) {
        null
    }

    /** Имя интерфейса сети, для лога. */
    fun ifaceOf(network: Network): String =
        try {
            val manager = cm ?: ctx.getSystemService(ConnectivityManager::class.java)
            manager?.getLinkProperties(network)?.interfaceName ?: "интерфейс?"
        } catch (e: Throwable) {
            "интерфейс?"
        }

    private fun transportOf(network: Network): String =
        try {
            cm?.getNetworkCapabilities(network)?.let { transportName(it) } ?: "транспорт?"
        } catch (e: Throwable) {
            "транспорт?"
        }

    companion object {
        /**
         * Какие адреса ПРОПАЛИ — единственный признак, по которому мы
         * считаем сокет мёртвым.
         *
         * Три решения, и каждое стоит того, чтобы его было видно:
         *
         *  1. ПОЯВЛЕНИЕ адреса обрывом НЕ считается. Прежнему сокету
         *     новый адрес не мешает, а рвать по нему сессию значило бы
         *     переподключаться каждый раз, когда оператор выдаёт
         *     вторую подсеть.
         *
         *  2. ПЕРВЫЙ замер обрывом НЕ считается. При регистрации
         *     обработчика onLinkPropertiesChanged приходит сразу, и
         *     прошлого набора ещё нет; принять пустое прошлое за
         *     пропажу значило бы рвать сессию на каждом подъёме.
         *
         *  3. Сравнение по СТРОКАМ адресов, а не по объектам
         *     LinkAddress: у последних в равенство входят время жизни
         *     и флаги, которые оператор меняет сам по себе.
         */
        fun lostAddrs(was: Set<String>?, now: Set<String>): Set<String> {
            if (was.isNullOrEmpty()) return emptySet()
            return was - now
        }
    }

    private fun transportName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "иной"
    }
}
