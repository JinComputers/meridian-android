package engine

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"time"
)

// raiseStream поднимает потоковую ступень (TCP 56004 или TLS 443).
//
// Отличие от UDP-пути в raise: транспорт — поток, крипто-слой obfs лежит
// в кадрах (streamLink), отдельного obfsPacketConn и UDP-сокета нет.
// Схема WRAP та же: тот же deriveWrapKey, тот же newObfsState.
func (s *session) raiseStream(
	ctx context.Context, gateway string, c candidate, password string,
	prot Protector, budget time.Duration,
) (*rung, error) {
	key, err := deriveWrapKey(password)
	if err != nil {
		return nil, fmt.Errorf("вывод ключа: %w", err)
	}
	// Состояние обфускации своё на ступень: новые ssrc/seq/ts, иначе
	// вторая попытка выглядела бы для шлюза продолжением первой.
	state, err := newObfsState(key)
	if err != nil {
		return nil, fmt.Errorf("инициализация обфускации: %w", err)
	}

	host := c.host
	if host == "" {
		host = gateway
	}

	conn, err := s.dialStream(ctx, c, prot, host, budget)
	if err != nil {
		return nil, err
	}

	link := newStreamLink(conn, state, s.logf)
	// pc и obfs пусты намеренно: у потоковой ступени UDP-сокета и
	// obfsPacketConn нет. rung.close() и счётчик отброшенных это
	// учитывают, см. rung.droppedCount.
	return &rung{cand: c, link: link}, nil
}

// dialStream открывает поток до шлюза: TCP с protect и TCP_NODELAY, а
// для TLS-ступени — ещё TLS-обёртку с SNI и сверкой отпечатка.
func (s *session) dialStream(
	ctx context.Context, c candidate, prot Protector, host string, budget time.Duration,
) (net.Conn, error) {
	s.logf("%s: открываю поток", c.name)

	// protect в Control исключает сокет из туннеля — как у UDP-сокета в
	// listenProtected. Без него запрос ушёл бы ВНУТРЬ туннеля, который
	// мы ещё и поднимаем.
	d := &net.Dialer{
		Control: protectDialControl(prot, s.logf, c.name),
		Timeout: budget,
	}
	addr := net.JoinHostPort(host, strconv.Itoa(int(c.port)))
	raw, err := d.DialContext(ctx, "tcp4", addr)
	if err != nil {
		return nil, fmt.Errorf("%s: дозвон TCP: %w", c.name, err)
	}

	// TCP_NODELAY обязателен: без него Nagle склеит мелкие кадры и
	// добавит десятки миллисекунд на каждый обмен (слово кота 2).
	if tc, ok := raw.(*net.TCPConn); ok {
		if err := tc.SetNoDelay(true); err != nil {
			raw.Close()
			return nil, fmt.Errorf("%s: TCP_NODELAY: %w", c.name, err)
		}
	}

	if c.transport != transTLS {
		s.logf("%s: TCP поднят, кадрирование поверх потока", c.name)
		return raw, nil
	}

	// TLS-ступень. SNI — из кандидата (из /v1/params); пусто — запаска.
	// Конфиг берём из одного места (gatewayTLSConfig), тем же, что
	// проверяет тест: там выключенная чужая проверка и сверка отпечатка.
	sni := c.sni
	if sni == "" {
		sni = tlsGatewaySNI
	}
	tlsConn := tls.Client(raw, gatewayTLSConfig(sni))
	hsCtx, cancel := context.WithTimeout(ctx, budget)
	defer cancel()
	if err := tlsConn.HandshakeContext(hsCtx); err != nil {
		raw.Close()
		// Сюда же приходит и провал сверки отпечатка: VerifyPeerCertificate
		// вернул ошибку — рукопожатие оборвалось. Это ровно то, чего мы
		// хотим: чужой сертификат ступень не поднимает.
		return nil, fmt.Errorf("%s: рукопожатие TLS: %w", c.name, err)
	}
	s.logf("%s: TLS поднят (SNI %s, отпечаток сверен), кадрирование внутри", c.name, sni)
	return tlsConn, nil
}
