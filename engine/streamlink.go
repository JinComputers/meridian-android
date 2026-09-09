package engine

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// streamLink — packetLink поверх ПОТОКА (TCP или TLS), а не датаграмм.
//
// Ступени 3 (TCP 56004) и 4 (TLS 443) для сетей, где UDP к шлюзу мёртв,
// а TCP жив. Внутри кадра лежит РОВНО тот же obfs-пакет, что уходит
// датаграммой на UDP: 12-байтный RTP-заголовок, шифротекст с тегом,
// набивка. Схема WRAP не меняется ни на букву — меняется только обёртка
// снаружи (спецификация кота 2, 31.08; ключ ко всему — на TCP зовётся та
// же obfsWrapPacket, что на UDP).
//
// Кадр на проводе:
//
//	[2 байта длины, big-endian][ровно столько байт — весь obfs-пакет]
//
// Длина считает ТОЛЬКО тело; сами два байта в неё не входят. Нулевая
// длина и длина больше tcpFrameMax — структурная поломка потока, а не
// пустой кадр.
type streamLink struct {
	conn  net.Conn
	state *obfsState
	logf  func(string, ...interface{})

	// wmu держит упаковку и запись одним неделимым куском: номер, который
	// wrap присвоил кадру, и порядок его ухода в поток совпадают. Тот же
	// смысл, что у obfsPacketConn.wmu на UDP.
	wmu sync.Mutex

	// Буферы чтения. Насос чтения один (net→tun), поэтому их
	// переиспользование между вызовами Read безопасно.
	lenBuf [2]byte
	rbuf   []byte

	// dropped — кадры, не прошедшие проверку целостности. Наше
	// единственное слепое место на приёме, ровно как dropped у obfs на
	// UDP: снаружи «пришёл и отброшен» неотличимо от «не пришёл».
	dropped atomic.Uint64

	// maxFrame — самый крупный кадр, ушедший на провод, вместе с двумя
	// байтами длины. Двойник obfsPacketConn.maxWire.
	//
	// Нужен ровно для итоговой строки сессии. Без него потоковые сессии
	// писали в итог «максимум после обфускации 0 байт»: счётчик жил
	// только у UDP-упаковщика, которого у потока нет. Ноль в приборе
	// хуже отсутствия прибора — по нему легко решить, что на провод не
	// ушло ничего.
	maxFrame atomic.Uint64

	writeOnce sync.Once
}

// tcpFrameMax — потолок длины кадра, слово кота 2. Обфусцированный пакет
// больше этого — либо наша ошибка, либо поломка потока.
const tcpFrameMax = 2048

func newStreamLink(conn net.Conn, state *obfsState, logf func(string, ...interface{})) *streamLink {
	return &streamLink{conn: conn, state: state, logf: logf, rbuf: make([]byte, tcpFrameMax)}
}

// Read отдаёт один распакованный пакет.
//
// ЧИТАЕМ РОВНО СТОЛЬКО, через io.ReadFull — и для двух байт длины, и для
// тела. Обычный Read вернул бы «сколько есть», и на разорванном между
// сегментами кадре мы отдали бы AEAD обрезок и молча всё выбросили —
// ровно то, ради чего кадрирование и заведено.
func (l *streamLink) Read(p []byte) (int, error) {
	for {
		// Тайм-аут или EOF на длине — отдаём как есть: поток при этом
		// не разошёлся (тела мы ещё не начинали), и проверка меткой
		// прочтёт это как «эха нет».
		if _, err := io.ReadFull(l.conn, l.lenBuf[:]); err != nil {
			return 0, err
		}
		n := int(binary.BigEndian.Uint16(l.lenBuf[:]))

		// СТРУКТУРНАЯ ПОЛОМКА РВЁТ ПОТОК. Позицию в потоке после неё
		// восстановить нечем: где кончается «кадр» неверной длины,
		// неизвестно. Это НЕ то же, что битый кадр верной длины ниже.
		if n == 0 || n > tcpFrameMax {
			return 0, fmt.Errorf("кадр TCP: длина %d вне [1..%d] — поток разошёлся", n, tcpFrameMax)
		}

		// Тело кадра целиком. Прервало на середине — кадр разорван, и
		// восстановить его нечем: отдаём ошибку, соединение обрывается.
		if _, err := io.ReadFull(l.conn, l.rbuf[:n]); err != nil {
			return 0, err
		}

		out, ok := l.state.unwrap(l.rbuf[:n], p)
		if !ok {
			// БИТЫЙ ИЛИ ЧУЖОЙ КАДР СОЕДИНЕНИЕ НЕ РВЁТ. Считаем и читаем
			// дальше — как на UDP: один испорченный пакет не убивает
			// туннель. Длина была верной, позиция в потоке цела.
			l.dropped.Add(1)
			continue
		}
		return out, nil
	}
}

// Write упаковывает пакет и отправляет одним кадром.
//
// ОДИН Write НА КАДР: длина и тело одним вызовом conn.Write. Два вызова
// при выключенном Nagle дали бы два сегмента — вдвое больше пакетов на
// проводе, — а при ошибке на втором в потоке осталась бы половина кадра.
func (l *streamLink) Write(p []byte) (int, error) {
	l.wmu.Lock()
	defer l.wmu.Unlock()

	wire, err := l.state.wrap(p)
	if err != nil {
		return 0, err
	}
	if len(wire) > tcpFrameMax {
		return 0, fmt.Errorf("кадр TCP: пакет %d байт больше потолка %d", len(wire), tcpFrameMax)
	}

	frame := make([]byte, 2+len(wire))
	binary.BigEndian.PutUint16(frame[:2], uint16(len(wire)))
	copy(frame[2:], wire)

	// Замер ДО записи: кадр уже сформирован, и его размер от исхода
	// записи не зависит. Под wmu, поэтому обычного сравнения довольно.
	if n := uint64(len(frame)); n > l.maxFrame.Load() {
		l.maxFrame.Store(n)
	}

	nw, err := l.conn.Write(frame)
	if err != nil {
		return 0, err
	}
	if nw != len(frame) {
		l.writeOnce.Do(func() {
			if l.logf != nil {
				l.logf("кадр TCP: ушло %d из %d байт — запись неполная", nw, len(frame))
			}
		})
		return 0, fmt.Errorf("кадр TCP: неполная запись %d из %d байт", nw, len(frame))
	}
	return len(p), nil
}

func (l *streamLink) Close() error                       { return l.conn.Close() }
func (l *streamLink) SetReadDeadline(t time.Time) error  { return l.conn.SetReadDeadline(t) }
func (l *streamLink) SetWriteDeadline(t time.Time) error { return l.conn.SetWriteDeadline(t) }
