package org.meridianvpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор адресов нижней сети.
 *
 * Ради этой развилки правка и делалась: на сотовой перерегистрации
 * Android оставляет тот же объект Network, onLost не приходит вовсе, и
 * смена IPv4 — единственный однозначный признак того, что наш сокет
 * мёртв. Ошибка здесь стоит либо полутора минут без связи (не заметили),
 * либо обрыва на ровном месте (заметили лишнее).
 */
class NetworkWatchTest {

    @Test
    fun `адрес пропал — это обрыв`() {
        val gone = NetworkWatch.lostAddrs(setOf("10.1.2.3"), setOf("10.9.9.9"))
        assertEquals(setOf("10.1.2.3"), gone)
    }

    @Test
    fun `адрес добавился — это не обрыв`() {
        val gone = NetworkWatch.lostAddrs(setOf("10.1.2.3"), setOf("10.1.2.3", "10.4.5.6"))
        assertTrue("появление адреса рвать сессию не должно", gone.isEmpty())
    }

    @Test
    fun `набор не менялся — тишина`() {
        val gone = NetworkWatch.lostAddrs(setOf("10.1.2.3"), setOf("10.1.2.3"))
        assertTrue(gone.isEmpty())
    }

    /**
     * Первый замер. onLinkPropertiesChanged приходит сразу при
     * регистрации обработчика, и прошлого набора ещё нет. Принять это
     * за пропажу значило бы рвать сессию на каждом подъёме.
     */
    @Test
    fun `прошлого набора нет — не обрыв`() {
        assertTrue(NetworkWatch.lostAddrs(null, setOf("10.1.2.3")).isEmpty())
        assertTrue(NetworkWatch.lostAddrs(emptySet(), setOf("10.1.2.3")).isEmpty())
    }

    /**
     * Адреса исчезли все. Обрыв безусловный: адреса-источника для
     * сокета не осталось вовсе.
     */
    @Test
    fun `адреса исчезли все`() {
        val gone = NetworkWatch.lostAddrs(setOf("10.1.2.3", "10.4.5.6"), emptySet())
        assertEquals(setOf("10.1.2.3", "10.4.5.6"), gone)
    }

    /**
     * Смена одного из двух. Именно так выглядит перерегистрация, когда
     * оператор держит вторую подсеть: один адрес уехал, другой остался.
     * Пропажа обязана быть замечена, несмотря на выжившего соседа.
     */
    @Test
    fun `один из двух уехал — всё равно обрыв`() {
        val gone = NetworkWatch.lostAddrs(
            setOf("10.1.2.3", "10.4.5.6"),
            setOf("10.4.5.6", "10.7.8.9"),
        )
        assertEquals(setOf("10.1.2.3"), gone)
    }
}
