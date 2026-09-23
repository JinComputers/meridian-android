package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"

	"github.com/pion/turn/v4"
)

// ВАЖНО ПРО СБОРКУ.
//
// pion/turn безусловно импортирует transport/v4/stdnet (turn/client.go:17),
// а тот — github.com/wlynxg/anet, который через //go:linkname лезет во
// внутренний net.zoneCache (anet/interface_android.go:164). Go с версии
// 1.23 такие ссылки запрещает, и сборка падает с
//
//	link: github.com/wlynxg/anet: invalid reference to net.zoneCache
//
// Лечится ТОЛЬКО флагом компоновщика:
//
//	gomobile bind -ldflags=-checklinkname=0 ...
//
// Обойти выбором версии нельзя: stdnet импортируется безусловно и в v3,
// и в v4. Забыть флаг — значит получить непонятную ошибку сборки, ни
// словом не упоминающую TURN.

// relayAllocTimeout — сколько ждём TURN-аллокацию.
//
// Роутерный клиент даёт релейной ступени 50 секунд на рукопожатие
// (спецификация-2, C.2) — но там нет общего срока на лестницу, а у нас
// он есть, 25 секунд на всё. Берём 10: аллокация это два-три обмена со
// STUN-сервером, и если она не встала за десять секунд, дело не в
// медленной сети.
const relayAllocTimeout = 10 * time.Second

var (
	// errRelayNoCreds — креды TURN не заданы.
	errRelayNoCreds = errors.New("креды TURN не заданы: заполни блок TURN в Config.kt")

	// errRelayAlloc — не встала TURN-аллокация. Отличается от ошибки
	// AUTH намеренно: это разные места отказа, и в логе они должны
	// читаться по-разному.
	errRelayAlloc = errors.New("TURN-аллокация не встала")
)

// relayLink — транспорт через TURN-релей.
//
// Слои те же, что на прямом пути; меняется только то, через что идут
// пакеты до шлюза:
//
//	прямой:  UDP → obfs → [DTLS] → пакет
//	релей:   UDP → TURN → obfs → [DTLS] → пакет
//
// То есть TURN встаёт НИЖЕ обфускации: релею видны уже замаскированные
// байты, и для него это обычный UDP-поток к третьей стороне.
type relayLink struct {
	packetLink

	// client и alloc закрываются вместе с самой ссылкой. Порядок важен:
	// сначала аллокация, потом клиент, иначе клиент попробует слать
	// refresh по закрытому сокету.
	client *turn.Client
	alloc  net.PacketConn
}

func (l *relayLink) Close() error {
	err := l.packetLink.Close()
	if l.alloc != nil {
		_ = l.alloc.Close()
	}
	if l.client != nil {
		l.client.Close()
	}
	return err
}

// raiseRelay поднимает релейную ступень: TURN-аллокация на кредах из
// настроек, дальше та же обфускация и тот же AUTH, что на прямом пути.
//
// AUTH здесь НЕ отправляется — этим занимается authRung, как и для
// прямых ступеней. Слот по паролю на шлюзе занимает именно AUTH.
// Креды приходят ПАРАМЕТРОМ, а не берутся из кандидата: у разных
// пачек слотов они разные, и захват из внешней области здесь означал бы
// молча взять комплект первой пачки для всех.
func (s *session) raiseRelay(
	ctx context.Context, gateway string, c candidate, cr turnCreds, password string,
	prot Protector,
) (*rung, error) {
	// Адрес может прийти и от цепочки, поэтому здесь проверяем только
	// креды: без них идти некуда в любом случае.
	if cr.user == "" || cr.pass == "" {
		return nil, errRelayNoCreds
	}

	key, err := deriveWrapKey(password)
	if err != nil {
		return nil, fmt.Errorf("вывод ключа: %w", err)
	}
	// СОСТОЯНИЕ ОБФУСКАЦИИ — СВОЁ НА КАЖДЫЙ ВЫЗОВ, И ЭТО ИНВАРИАНТ.
	//
	// Ключ общий, он выведен из пароля. А вот SSRC, initSeq и initTs
	// newObfsState берёт из crypto/rand заново, и это ровно то, что
	// делает нонсы разных слотов непересекающимися: нонс собирается из
	// (SSRC, seq, ts), и при общем ключе совпадение тройки означает
	// повторное использование нонса ChaCha20-Poly1305.
	//
	// Разделить одно состояние между слотами или переиспользовать SSRC
	// НЕЛЬЗЯ. Симптома у такой поломки нет никакого — тот же класс, что
	// слагаемое c>>16 в obfs.go, только между слотами, а не внутри
	// одного.
	state, err := newObfsState(key)
	if err != nil {
		return nil, fmt.Errorf("инициализация обфускации: %w", err)
	}

	// Сокет до РЕЛЕЯ. protect() ему нужен ровно так же, как сокету до
	// шлюза на прямом пути: без него трафик до TURN уйдёт в наш же
	// туннель.
	// Адрес источника здесь не прибивается: адрес релея на этот момент
	// ещё не известен, его отдаёт цепочка VK ниже. Прямой путь от этого
	// не страдает — там прибивается, см. listenProtected.
	udp, err := s.listenProtected(ctx, prot, "", c.name)
	if err != nil {
		return nil, err
	}

	// Адрес релея: тот, что назначил VK под ЭТИ креды, если цепочка его
	// отдала. Адрес из настроек — запасной, для статических кредов, где
	// выбирать не из чего.
	addr := c.turnAddr
	whence := "ЗАПАСНОЙ из настроек"
	if len(cr.addrs) > 0 {
		addr = cr.addrs[0]
		whence = "из ответа VK"
	}
	if addr == "" {
		udp.Close()
		return nil, errRelayNoCreds
	}

	s.logf("%s: беру TURN-аллокацию на %s (%s)", c.name, addr, whence)
	client, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: addr,
		TURNServerAddr: addr,
		Conn:           udp,
		Username:       cr.user,
		Password:       cr.pass,
		Realm:          c.turnRealm,
		RTO:            time.Second,
	})
	if err != nil {
		udp.Close()
		return nil, fmt.Errorf("%w: создание клиента: %v", errRelayAlloc, err)
	}
	if err := client.Listen(); err != nil {
		client.Close()
		udp.Close()
		return nil, fmt.Errorf("%w: Listen: %v", errRelayAlloc, err)
	}

	// Аллокация под своим сроком: без него неотвечающий релей подвесил
	// бы попытку молча — та же болезнь, что была с рукопожатием DTLS.
	type allocResult struct {
		pc  net.PacketConn
		err error
	}
	done := make(chan allocResult, 1)
	go func() {
		pc, aerr := client.Allocate()
		done <- allocResult{pc, aerr}
	}()

	var alloc net.PacketConn
	select {
	case r := <-done:
		if r.err != nil {
			client.Close()
			udp.Close()
			return nil, fmt.Errorf("%w: %v", errRelayAlloc, r.err)
		}
		alloc = r.pc
	case <-time.After(relayAllocTimeout):
		client.Close()
		udp.Close()
		return nil, fmt.Errorf("%w: ответа нет за %s", errRelayAlloc, relayAllocTimeout)
	case <-ctx.Done():
		client.Close()
		udp.Close()
		return nil, ctx.Err()
	}

	s.logf("%s: аллокация получена, адрес на релее %s", c.name, alloc.LocalAddr())

	// Конечный адрес релея: свой у ступени (relay.target из /v1/params,
	// Ladder.SetRelayTarget), иначе общий gateway — как у прямых ступеней.
	host, hostFrom := c.host, "relay.target"
	if host == "" {
		host, hostFrom = gateway, "адрес шлюза"
	}
	s.logf("%s: конечный адрес релея %s (%s)", c.name, host, hostFrom)
	raddr, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(host, strconv.Itoa(int(c.port))))
	if err != nil {
		alloc.Close()
		client.Close()
		udp.Close()
		return nil, fmt.Errorf("разбор адреса шлюза: %w", err)
	}

	// Разрешение на приём от шлюза. Без него релей выбросит встречные
	// пакеты, и получится ровно тот исход, который труднее всего
	// отличить от неверного порта: аллокация есть, ответа нет.
	if err := client.CreatePermission(raddr); err != nil {
		alloc.Close()
		client.Close()
		udp.Close()
		return nil, fmt.Errorf("%w: разрешение на %s: %v", errRelayAlloc, raddr, err)
	}

	// Дальше всё как на прямом пути: obfs поверх того, что дал релей.
	obfs := newObfsPacketConn(alloc, state, s.logf)

	var link packetLink
	if c.useDTLS {
		link, err = s.dialDTLS(ctx, c, obfs, raddr, alloc, relayHandshakeBudget)
		if err != nil {
			alloc.Close()
			client.Close()
			udp.Close()
			return nil, err
		}
	} else {
		s.logf("%s: без DTLS, obfs единственный слой", c.name)
		link = newRawLink(obfs, raddr, c.mtu, s.logf)
	}

	return &rung{
		cand: c,
		link: &relayLink{packetLink: link, client: client, alloc: alloc},
		pc:   udp,
		obfs: obfs,
	}, nil
}
