package engine

import (
	"encoding/binary"
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Замер скорости поднятого туннеля (просьба владельца 26.09).
//
// ЗАЧЕМ. Ступень лестницы выигрывает тем, что первой прошла рукопожатие, а
// не тем, что быстрее. У клиента 26.09 через прямые пути A и G было
// 0,06-0,1 Мбит/с, тогда как через релей VK на той же мобильной сети —
// 83. Приложение обязано само заметить такое и уйти на более быстрый
// путь, поэтому движок умеет померить, что реально несёт поднятый туннель.
//
// КАК. Пачка ICMP-эхо-запросов на адрес шлюза внутри туннеля,
// probePackets штук подряд, без пауз. Шлюз отвечает на ICMP (кот 1,
// 26.09); лимит частоты ядра на ответы эха не распространяется. Скорость
// считается по разбросу времени прихода ответов, а не по времени
// туда-обратно: разброс от задержки канала не зависит, и на 125 мс RTT
// быстрый канал не выглядит медленным.
//
// ВЫКЛЮЧЕНО ПО УМОЛЧАНИЮ. Платформа включает замер вызовом SetSpeedProbe(true):
// он шлёт около 40 КБ в каждую сторону один раз за сессию, и решать, нужен
// ли он, должна платформа, а не движок.
//
// Ответы на пробу поглощаются в насосе чтения и в TUN не попадают.
const (
	probePackets = 40
	// probePayload — полезная нагрузка эха. Пакет целиком 928 байт: с запасом
	// влезает и в MTU 1100 потоковой ступени.
	probePayload = 900
	// probeDelay — пауза после подъёма: не мешаем первой метке keepalive и
	// разгону слотов.
	probeDelay = 1500 * time.Millisecond
	// probeWait — сколько ждём ответы после отправки всей пачки.
	probeWait = 4 * time.Second
	// probeID — идентификатор ICMP этой пробы ("MR"). Чужие эхо-ответы
	// в туннеле не поглощаются.
	probeID uint16 = 0x4d52
)

// Состояния замера для платформы.
const (
	speedStateNone    int32 = 0 // не запускался или недоступен
	speedStateRunning int32 = 1
	speedStateDone    int32 = 2
	speedStateSilent  int32 = 3 // шлюз не ответил ни на один запрос
)

var speedProbeOn atomic.Bool

// SetSpeedProbe включает замер скорости для следующих сессий.
func SetSpeedProbe(on bool) { speedProbeOn.Store(on) }

// speedResult — итог замера.
type speedResult struct {
	state   int32
	kbps    int64
	lossPct int32
	rttMs   int32
	sent    int
	recv    int
}

type speedProbe struct {
	active atomic.Bool
	state  atomic.Int32
	res    atomic.Pointer[speedResult]

	mu       sync.Mutex
	startAt  time.Time
	seen     [probePackets]bool
	arrivals []time.Duration
}

// begin взводит замер; отсчёт времени идёт от этого момента.
func (p *speedProbe) begin() {
	p.mu.Lock()
	p.startAt = time.Now()
	p.arrivals = p.arrivals[:0]
	p.seen = [probePackets]bool{}
	p.mu.Unlock()
	p.state.Store(speedStateRunning)
	p.active.Store(true)
}

// take разбирает пакет из туннеля. true — это ответ нашей пробы, в TUN его
// отдавать не нужно.
func (p *speedProbe) take(pkt []byte) bool {
	seq, ok := parseEchoReply(pkt, probeID)
	if !ok || int(seq) >= probePackets {
		return false
	}
	p.mu.Lock()
	if !p.seen[seq] {
		p.seen[seq] = true
		p.arrivals = append(p.arrivals, time.Since(p.startAt))
	}
	p.mu.Unlock()
	return true
}

func (p *speedProbe) got() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.arrivals)
}

// runSpeedProbe — одна проба за сессию. Зовётся из startPumps отдельной
// горутиной; на остановке сессии тихо уходит.
func (s *session) runSpeedProbe(p *speedProbe) {
	select {
	case <-s.ctx.Done():
		return
	case <-time.After(probeDelay):
	}

	ipStr, _ := lastAuthIP.Load().(string)
	gwStr, _ := lastAuthGateway.Load().(string)
	src := net.ParseIP(ipStr).To4()
	dst := net.ParseIP(gwStr).To4()
	if src == nil || dst == nil {
		s.logf("замер скорости: адреса туннеля неизвестны — пропускаю")
		return
	}

	p.begin()
	defer p.active.Store(false)

	sent := 0
	for i := 0; i < probePackets; i++ {
		r := s.nextSlot()
		if r == nil {
			break
		}
		if _, err := r.link.Write(buildEchoRequest(src, dst, probeID, uint16(i), probePayload)); err != nil {
			break
		}
		sent++
	}

	deadline := time.After(probeWait)
	tick := time.NewTicker(25 * time.Millisecond)
	defer tick.Stop()
wait:
	for p.got() < sent {
		select {
		case <-s.ctx.Done():
			return
		case <-deadline:
			break wait
		case <-tick.C:
		}
	}

	p.mu.Lock()
	arr := append([]time.Duration(nil), p.arrivals...)
	p.mu.Unlock()
	res := computeSpeed(sent, probePayload+28, arr)
	p.res.Store(&res)
	p.state.Store(res.state)
	s.logf("замер скорости: отправлено %d, ответов %d, потери %d%%, %d кбит/с, отклик %d мс",
		res.sent, res.recv, res.lossPct, res.kbps, res.rttMs)
}

// computeSpeed — скорость по разбросу времени прихода ответов. Чистая
// функция, чтобы проверяться без сети.
func computeSpeed(sent, pktBytes int, arrivals []time.Duration) speedResult {
	res := speedResult{sent: sent, recv: len(arrivals)}
	if sent <= 0 || len(arrivals) == 0 {
		res.state = speedStateSilent
		res.lossPct = 100
		return res
	}
	sorted := append([]time.Duration(nil), arrivals...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	res.state = speedStateDone
	res.lossPct = int32((sent - len(sorted)) * 100 / sent)
	res.rttMs = int32(sorted[0] / time.Millisecond)

	// Меньше трёх ответов разброса не дают. Оцениваем сверху тем, что дошло
	// за всё окно ожидания: это заведомо низкое число, и его достаточно,
	// чтобы признать путь медленным.
	if len(sorted) < 3 {
		res.kbps = int64(len(sorted)*pktBytes*8) / int64(probeWait/time.Millisecond)
		return res
	}
	span := sorted[len(sorted)-1] - sorted[0]
	if span < time.Millisecond {
		span = time.Millisecond
	}
	bits := int64(len(sorted)-1) * int64(pktBytes) * 8
	res.kbps = bits * int64(time.Millisecond) / int64(span)
	return res
}

// buildEchoRequest собирает IPv4-пакет с ICMP-эхо-запросом.
func buildEchoRequest(src, dst net.IP, id, seq uint16, payload int) []byte {
	total := 20 + 8 + payload
	pkt := make([]byte, total)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	binary.BigEndian.PutUint16(pkt[4:6], seq)
	binary.BigEndian.PutUint16(pkt[6:8], 0x4000)
	pkt[8] = 64
	pkt[9] = 1
	copy(pkt[12:16], src)
	copy(pkt[16:20], dst)
	binary.BigEndian.PutUint16(pkt[10:12], inetChecksum(pkt[:20]))

	pkt[20] = 8
	binary.BigEndian.PutUint16(pkt[24:26], id)
	binary.BigEndian.PutUint16(pkt[26:28], seq)
	for i := 28; i < total; i++ {
		pkt[i] = byte(i)
	}
	binary.BigEndian.PutUint16(pkt[22:24], inetChecksum(pkt[20:]))
	return pkt
}

// parseEchoReply — номер запроса, если pkt это ICMP-эхо-ответ с нашим id.
func parseEchoReply(pkt []byte, id uint16) (uint16, bool) {
	if len(pkt) < 28 || pkt[0] != 0x45 || pkt[9] != 1 {
		return 0, false
	}
	if pkt[20] != 0 || pkt[21] != 0 {
		return 0, false
	}
	if binary.BigEndian.Uint16(pkt[24:26]) != id {
		return 0, false
	}
	return binary.BigEndian.Uint16(pkt[26:28]), true
}

// inetChecksum — контрольная сумма Интернета (RFC 1071).
func inetChecksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = sum&0xffff + sum>>16
	}
	return ^uint16(sum)
}

func currentProbe() *speedProbe {
	mu.Lock()
	defer mu.Unlock()
	if current == nil {
		return nil
	}
	return current.probe
}

// SpeedProbeState — 0 не запускался, 1 идёт, 2 готов, 3 шлюз молчит.
func SpeedProbeState() int32 {
	if p := currentProbe(); p != nil {
		return p.state.Load()
	}
	return speedStateNone
}

// SpeedProbeKbps — скорость по замеру, кбит/с; 0 пока замер не готов.
func SpeedProbeKbps() int64 {
	if p := currentProbe(); p != nil {
		if r := p.res.Load(); r != nil {
			return r.kbps
		}
	}
	return 0
}

// SpeedProbeLossPct — потери пакетов в замере, проценты.
func SpeedProbeLossPct() int32 {
	if p := currentProbe(); p != nil {
		if r := p.res.Load(); r != nil {
			return r.lossPct
		}
	}
	return 0
}

// SpeedProbeRttMs — время до первого ответа, мс.
func SpeedProbeRttMs() int32 {
	if p := currentProbe(); p != nil {
		if r := p.res.Load(); r != nil {
			return r.rttMs
		}
	}
	return 0
}
