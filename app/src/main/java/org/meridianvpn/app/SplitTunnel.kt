package org.meridianvpn.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.compose.runtime.mutableStateOf

/**
 * Раздельное туннелирование: какие приложения идут через туннель, а
 * какие мимо.
 *
 * Делает это САМА СИСТЕМА. VpnService.Builder принимает список
 * пакетов, и дальше ядро само решает, чей сокет заворачивать. Движок
 * об этом не знает и знать не должен: он видит только дескриптор TUN, а
 * что в него попадает — вопрос настройки интерфейса, а не протокола.
 *
 * ГЛАВНОЕ ЗДЕСЬ — ПУНКТ 52.5: наше собственное приложение обязано
 * остаться ВНЕ туннеля при любом режиме и любом выборе. Разбор — у
 * apply() ниже, там же и три случая, в которых это могло сломаться.
 */
object SplitTunnel {

    /**
     * Режимы. Названы словами, сокращений здесь нет намеренно: человек
     * выбирает между двумя противоположностями, и ошибка в выборе
     * означает банк в туннеле вместо банка мимо него.
     */
    enum class Mode {
        /** Выбранные идут МИМО туннеля, остальные через него. */
        BYPASS,

        /** Только выбранные идут ЧЕРЕЗ туннель, остальные мимо. */
        ONLY,
    }

    // Коротко, без «Выбранные»: какие приложения выбраны, видно по
    // списку и галочкам над переключателем, повторять это в подписи
    // режима незачем (правка по просьбе владельца).
    fun title(m: Mode): String = when (m) {
        Mode.BYPASS -> "Мимо туннеля"
        Mode.ONLY -> "Через туннель"
    }

    fun hint(m: Mode): String = when (m) {
        Mode.BYPASS -> "остальные идут через туннель. Обычный случай: банк мимо VPN"
        Mode.ONLY -> "остальные идут мимо. Для тех, кому нужно одно-два приложения"
    }

    /** Для экрана. */
    val mode = mutableStateOf(Mode.BYPASS)

    /** Выбранные пакеты. Для экрана. */
    val chosen = mutableStateOf<Set<String>>(emptySet())

    private const val PREFS = "meridian_split"
    private const val K_MODE = "mode"
    private const val K_PACKAGES = "packages"

    private var appCtx: Context? = null
    private var loaded = false

    /**
     * Что было применено к последнему поднятому туннелю.
     *
     * Нужно ровно для одного: понять, разошлась ли настройка с тем, что
     * работает сейчас. Список применяется в establish(), и поменять его
     * у живого туннеля нельзя — см. changedSinceConnect().
     */
    @Volatile
    private var applied: String? = null

    @Synchronized
    fun attach(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        val p = prefs(ctx) ?: return
        mode.value = if (p.getString(K_MODE, "") == Mode.ONLY.name) Mode.ONLY else Mode.BYPASS
        chosen.value = p.getStringSet(K_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setMode(m: Mode) {
        mode.value = m
        val ctx = appCtx ?: return
        prefs(ctx)?.edit()?.putString(K_MODE, m.name)?.apply()
    }

    fun toggle(pkg: String) {
        val now = chosen.value
        chosen.value = if (pkg in now) now - pkg else now + pkg
        save()
    }

    fun clear() {
        chosen.value = emptySet()
        save()
    }

    private fun save() {
        val ctx = appCtx ?: return
        // Копия множества обязательна: SharedPreferences хранит ссылку
        // на переданный Set и при последующей правке того же объекта
        // записывает не то, что ожидаешь. Известная ловушка putStringSet.
        prefs(ctx)?.edit()?.putStringSet(K_PACKAGES, HashSet(chosen.value))?.apply()
    }

    /**
     * Выбрасывает из списка пакеты, которых больше нет в системе.
     *
     * Молча, как и просили: человек удалил приложение, и напоминать ему
     * об этом незачем. Зовётся при открытии экрана.
     */
    fun prune(ctx: Context) {
        val gone = chosen.value.filterNot { installed(ctx, it) }
        if (gone.isEmpty()) return
        chosen.value = chosen.value - gone.toSet()
        save()
    }

    /** Сколько выбрано из тех, что ещё установлены. */
    fun countAlive(ctx: Context): Int = chosen.value.count { installed(ctx, it) }

    /**
     * Применяет настройку к строящемуся туннелю.
     *
     * НАШЕ ПРИЛОЖЕНИЕ ОСТАЁТСЯ ВНЕ ТУННЕЛЯ ВО ВСЕХ ТРЁХ ВЕТКАХ, и ни
     * одна из них не полагается на другую:
     *
     *  1. режим «мимо» — свой пакет добавляется в исключения ПЕРВЫМ,
     *     до всего пользовательского выбора;
     *  2. режим «только выбранные» — свой пакет в разрешённые не
     *     попадает никогда: экран его не показывает, а здесь он ещё раз
     *     отфильтровывается. Всё, чего нет в разрешённых, система и так
     *     оставляет снаружи;
     *  3. режим «только выбранные» с ПУСТЫМ выбором — отдельный случай,
     *     и самый опасный. Ни одного addAllowedApplication означает для
     *     системы «все приложения», то есть туннель заберёт и нас. Здесь
     *     мы уходим в ветку 1.
     *
     * Почему это важно: без исключения нашего пакета запросы к API и
     * цепочка VK пошли бы внутрь туннеля. Ровно этот дефект чинили в
     * задаче 50, и он не давал явного симптома — туннель работал, а
     * ключ не проверялся.
     *
     * Смешивать addAllowed и addDisallowed нельзя: система бросит
     * исключение. Поэтому ветки разделены жёстко.
     */
    fun apply(builder: VpnService.Builder, ctx: Context, ruDirect: Boolean = false) {
        val self = ctx.packageName
        val alive = chosen.value.filter { it != self && installed(ctx, it) }

        val useBypass = mode.value == Mode.BYPASS || alive.isEmpty()

        if (mode.value == Mode.ONLY && alive.isEmpty()) {
            // Не молчим: настройка человека не выполнена, и он должен
            // узнать почему. Туннель, который не несёт ничего, выглядит
            // сломанным, поэтому ведём себя как обычный VPN.
            TunnelLog.add(
                "раздельный туннель: режим «только выбранные», но не выбрано ни одного — " +
                    "веду весь трафик через туннель"
            )
        }

        if (useBypass) {
            // Свой пакет ПЕРВЫМ. Порядок не важен системе, но важен
            // читающему: видно, что исключение безусловное.
            addDisallowed(builder, self, ctx)
            for (p in alive) addDisallowed(builder, p, ctx)
            // RU-ПРИЛОЖЕНИЯ МИМО (переключатель «RU-адреса напрямую»): банки,
            // госуслуги, маркетплейсы. Список человека не трогаем — добавляем
            // поверх него, только в этот туннель. В режиме «через туннель»
            // они и так мимо, если человек не выбрал их сам.
            if (ruDirect) {
                val ru = RuDirect.installedApps(ctx).filter { it != self && it !in alive }
                for (p in ru) addDisallowed(builder, p, ctx)
                TunnelLog.add("RU-адреса напрямую: российских приложений мимо туннеля ${ru.size}")
            }
            TunnelLog.add(
                if (alive.isEmpty()) "раздельный туннель: мимо идёт только само приложение"
                else "раздельный туннель: мимо идут ${alive.size} приложений и само приложение"
            )
        } else {
            for (p in alive) addAllowed(builder, p, ctx)
            TunnelLog.add(
                "раздельный туннель: через туннель идут только ${alive.size} приложений, " +
                    "само приложение — мимо"
            )
        }

        applied = signature(ruDirect)
    }

    private fun addDisallowed(builder: VpnService.Builder, pkg: String, ctx: Context) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            // Пакет исчез между проверкой и применением. Молча.
        } catch (e: Throwable) {
            TunnelLog.add("раздельный туннель: $pkg не исключился (${e.message})")
        }
    }

    private fun addAllowed(builder: VpnService.Builder, pkg: String, ctx: Context) {
        try {
            builder.addAllowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            // Пакет исчез между проверкой и применением. Молча.
        } catch (e: Throwable) {
            TunnelLog.add("раздельный туннель: $pkg не пропустился (${e.message})")
        }
    }

    /**
     * Разошлась ли настройка с работающим туннелем.
     *
     * Список применяется в establish() и у живого туннеля не меняется —
     * это свойство Android, а не наше решение. Отсюда и вопрос 52.6:
     * что делать, если человек правит список при поднятом туннеле.
     */
    fun changedSinceConnect(): Boolean {
        val a = applied ?: return false
        return a != signature()
    }

    fun forgetApplied() {
        applied = null
    }

    private fun signature(ru: Boolean = RuDirect.enabled()): String =
        mode.value.name + ":" + chosen.value.sorted().joinToString(",") + ":ru=" + ru

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Throwable) {
        false
    }

    private fun prefs(ctx: Context) = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (e: Throwable) {
        null
    }
}
