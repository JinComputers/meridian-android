package engine

import (
	"fmt"
	"net"
	"sync"
	"time"
)

// packetLink — то, чем сессия говорит со шлюзом после того, как ступень
// поднялась. Прячет единственное различие между ступенями: есть над
// obfs слой DTLS или его нет.
//
// *dtls.Conn удовлетворяет этому набору как есть.
type packetLink interface {
	Read(p []byte) (int, error)
	Write(p []byte) (int, error)
	Close() error
	SetReadDeadline(t time.Time) error
	SetWriteDeadline(t time.Time) error
}

// obfsOverheadMax — обвязка obfs в худшем случае: 12 байт заголовка,
// 16 байт AEAD-тега, до obfsPaddingMax байт набивки.
//
// Считается из тех же констант, что и упаковка, чтобы не разъехалось,
// если набивку когда-нибудь поменяют.
const obfsOverheadMax = obfsHeaderLen + obfsTagLen + obfsPaddingMax

// rawLink — ступень без DTLS (порт 56005): obfs лежит прямо на UDP и
// является ЕДИНСТВЕННЫМ крипто-слоем (спецификация 1.1, листенер
// -listen-notls; A2 — serveNoTLS зовёт wrapPacketListener напрямую,
// без dtls.NewListenerWithOptions сверху).
//
// Собственных буферов у rawLink НЕТ и быть не должно: и чтение, и
// запись отдаются obfsPacketConn, который читает в свой rbuf на 2048
// байт, а на запись выделяет выход точно по размеру пакета. Проверки
// ниже сторожат не свои буферы, а границы, за которые нельзя выходить.
type rawLink struct {
	pc    *obfsPacketConn
	raddr net.Addr

	// mtu — MTU туннеля ИМЕННО этой ступени. Верхняя граница пакета:
	// над rawLink нет DTLS, который резал бы поток по своему MTU,
	// значит следить больше некому.
	mtu int

	logf func(string, ...interface{})

	// Каждая жалоба — ровно один раз за жизнь ступени: ошибка размера
	// повторится на КАЖДОМ пакете, и без защёлки это тот самый
	// неограниченный поток строк из инвариантов.
	bigOnce   sync.Once
	shortOnce sync.Once
}

func newRawLink(pc *obfsPacketConn, raddr net.Addr, mtu int32, logf func(string, ...interface{})) *rawLink {
	return &rawLink{pc: pc, raddr: raddr, mtu: int(mtu), logf: logf}
}

// Read отдаёт распакованный пакет. Адрес отправителя не сверяется
// намеренно: чужой пакет не пройдёт проверку AEAD-тега внутри unwrap и
// будет отброшен там же, а сверка адреса сломала бы нас на смене NAT.
func (l *rawLink) Read(p []byte) (int, error) {
	n, _, err := l.pc.ReadFrom(p)
	return n, err
}

// Write упаковывает и отправляет пакет.
//
// Две проверки, и обе ГРОМКИЕ. Раньше на этом пути не было ни одной, и
// расхождение «у нас отправлено 534, у шлюза принято 3» пришлось ловить
// сверкой с чужим логом вместо своего.
func (l *rawLink) Write(p []byte) (int, error) {
	// Над rawLink нет DTLS, который бы нарезал поток по своему MTU.
	// Значит единственный, кто следит за верхней границей, — мы сами.
	if len(p) > l.mtu {
		var err error
		l.bigOnce.Do(func() {
			if l.logf != nil {
				l.logf("пакет %d байт больше MTU ступени %d — на проводе вышло бы %d байт",
					len(p), l.mtu, len(p)+obfsOverheadMax)
			}
		})
		err = fmt.Errorf("пакет %d байт превышает MTU ступени %d", len(p), l.mtu)
		return 0, err
	}

	n, err := l.pc.WriteTo(p, l.raddr)
	if err != nil {
		return n, err
	}
	if n != len(p) {
		l.shortOnce.Do(func() {
			if l.logf != nil {
				l.logf("в туннель ушло %d из %d байт — запись неполная", n, len(p))
			}
		})
		return n, fmt.Errorf("неполная запись в туннель: %d из %d байт", n, len(p))
	}
	return n, nil
}

func (l *rawLink) Close() error                       { return l.pc.Close() }
func (l *rawLink) SetReadDeadline(t time.Time) error  { return l.pc.SetReadDeadline(t) }
func (l *rawLink) SetWriteDeadline(t time.Time) error { return l.pc.SetWriteDeadline(t) }
