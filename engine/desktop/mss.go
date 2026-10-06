package desktop

import (
	"encoding/binary"

	"meridian/engine"
)

// MSS clamp для настольного пути (PacketFlow).
//
// ЗАЧЕМ. Туннель несёт пакеты не длиннее MTU текущей ступени (1200 с DTLS,
// 1100 без; engine.MTU() после Connect). TCP выбирает размер сегмента по
// MSS из SYN/SYN-ACK. Если MSS взят с интерфейса крупнее туннеля, сегменты
// не влезают, и без ICMP «fragmentation needed» (движок не роутер и его не
// шлёт) соединение ретраит впустую. Поэтому в SYN и SYN-ACK, идущих
// через туннель в обе стороны, MSS опускается до MTU−40.
//
// ГРАНИЦЫ, чтобы не ждать лишнего.
//   - Чинит НОВЫЕ соединения. Уже установленное соединение с MSS от
//     физического интерфейса (случай 64 повторов по 1360 байт у кота 6)
//     этим не лечится: его SYN уже прошёл. Для него остаётся MTU
//     интерфейса туннеля = Info().MTU, тогда ОС сама ограничит сегменты
//     через PMTUD/повтор.
//   - Если MTU интерфейса туннеля выставлен верно, ОС и так объявляет
//     MSS = MTU−40 в своих SYN, и клампу почти нечего делать; он нужен как
//     страховка (другие стеки, форвардинг, MTU выставили выше).
//   - Только IPv4: IPv6 движок в туннель не пускает.
//
// НЕ ДЛЯ ANDROID/iOS, решение: там MTU интерфейса задаёт сама платформа при
// построении туннеля (VpnService.Builder.setMtu / NEPacketTunnelNetworkSettings),
// новые соединения получают MSS из него, а поднимается туннель до приложений.
// Правка живёт в подпакете desktop и в общий код движка не заходит.

const (
	ipv4Overhead = 20 + 20 // заголовок IPv4 без опций + заголовок TCP без опций
	minMSS       = 536     // ниже не опускаем: минимум по RFC 879 для IPv4 без опций
)

// clampMSS уменьшает MSS в SYN/SYN-ACK до limit, in place, и пересчитывает
// контрольную сумму TCP (RFC 1624, инкрементально: меняется одно 16-битное
// слово). Возвращает true, если пакет изменён.
//
// Не трогает: не IPv4, фрагменты, не TCP, сегменты без флага SYN, SYN без
// опции MSS, MSS уже не больше limit, повреждённые заголовки.
func clampMSS(pkt []byte, limit int) bool {
	if limit < minMSS || len(pkt) < 20 {
		return false
	}
	if pkt[0]>>4 != 4 {
		return false
	}
	ihl := int(pkt[0]&0x0f) * 4
	if ihl < 20 || len(pkt) < ihl+20 {
		return false
	}
	if pkt[9] != 6 { // не TCP
		return false
	}
	// Фрагмент (смещение не ноль или MF): заголовка TCP здесь может не быть.
	if binary.BigEndian.Uint16(pkt[6:8])&0x3fff != 0 {
		return false
	}
	// Длина по заголовку IP не должна выходить за пакет.
	total := int(binary.BigEndian.Uint16(pkt[2:4]))
	if total < ihl+20 || total > len(pkt) {
		return false
	}
	tcp := pkt[ihl:total]
	if tcp[13]&0x02 == 0 { // нет SYN
		return false
	}
	dataOff := int(tcp[12]>>4) * 4
	if dataOff < 20 || dataOff > len(tcp) {
		return false
	}

	opts := tcp[20:dataOff]
	for i := 0; i < len(opts); {
		switch opts[i] {
		case 0: // конец списка
			return false
		case 1: // NOP
			i++
			continue
		}
		if i+1 >= len(opts) {
			return false
		}
		l := int(opts[i+1])
		if l < 2 || i+l > len(opts) {
			return false
		}
		if opts[i] == 2 && l == 4 { // MSS
			old := int(binary.BigEndian.Uint16(opts[i+2 : i+4]))
			if old <= limit {
				return false
			}
			// Смещение значения MSS от начала TCP-сегмента. Оно может быть
			// НЕЧЁТНЫМ (перед MSS стоят NOP/wscale), и тогда значение
			// расползается по двум 16-битным словам суммы: обновлять его
			// как одно слово нельзя. Вклад каждого байта считается по его
			// позиции: чётная позиция — старший байт слова, нечётная —
			// младший.
			pos := 20 + i + 2
			oldB, newB := opts[i+2:i+4], [2]byte{byte(limit >> 8), byte(limit)}
			contrib := func(p int, b byte) uint32 {
				if p%2 == 0 {
					return uint32(b) << 8
				}
				return uint32(b)
			}
			oldSum := contrib(pos, oldB[0]) + contrib(pos+1, oldB[1])
			newSum := contrib(pos, newB[0]) + contrib(pos+1, newB[1])
			copy(opts[i+2:i+4], newB[:])
			// RFC 1624: HC' = ~(~HC + ~m + m'), где m и m' — вклады
			// старых и новых байт в сумму.
			sum := uint32(^binary.BigEndian.Uint16(tcp[16:18])) +
				uint32(^uint16(oldSum)) + newSum
			sum = (sum & 0xffff) + (sum >> 16)
			sum = (sum & 0xffff) + (sum >> 16)
			binary.BigEndian.PutUint16(tcp[16:18], ^uint16(sum))
			return true
		}
		i += l
	}
	return false
}

// clampFlow — PacketFlow, который клампит MSS в обоих направлениях и
// делегирует остальное. Предел считается по engine.MTU() КАЖДЫЙ раз: ступень
// (а с ней и MTU) может смениться при переподключении.
type clampFlow struct {
	inner engine.PacketFlow
}

func currentMSSLimit() int { return int(engine.MTU()) - ipv4Overhead }

// ReadBatch: пакеты от ОС в туннель (SYN клиента).
func (c clampFlow) ReadBatch() []byte {
	b := c.inner.ReadBatch()
	limit := currentMSSLimit()
	for off := 0; off+2 <= len(b); {
		n := int(b[off])<<8 | int(b[off+1])
		if n == 0 || off+2+n > len(b) {
			break // разметка негодна: не наше дело, движок сам её забракует
		}
		clampMSS(b[off+2:off+2+n], limit)
		off += 2 + n
	}
	return b
}

// WriteBatch: пакет из туннеля к ОС, ровно один кадр (SYN-ACK от сервера).
func (c clampFlow) WriteBatch(b []byte) bool {
	if len(b) > 2 {
		n := int(b[0])<<8 | int(b[1])
		if n > 0 && 2+n <= len(b) {
			clampMSS(b[2:2+n], currentMSSLimit())
		}
	}
	return c.inner.WriteBatch(b)
}

func (c clampFlow) Close() { c.inner.Close() }
