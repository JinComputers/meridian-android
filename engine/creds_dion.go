package engine

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Транспорт DION (dion.vc, видеозвонки ВТБ): второй источник TURN-кредов
// рядом с VK. Анонимный гостевой вход, аккаунт не нужен. Нужна готовая
// комната (slug); анонимно её не создать.
//
// Цепочка: slug → id события → гость → билет WS → подписка на конференцию →
// server:you_joined с ice_servers. Дальше DION-протокол не говорим, берём
// только TURN-адрес, логин и пароль.
//
// КРИТИЧНО (замер кота 1 на роутере): WS комнаты нужно ДЕРЖАТЬ открытым и
// отвечать на heartbeat. Гость, вышедший сразу после выдачи кредов, получал
// рукопожатие через TURN, но трафик вставал (0,04 МБ за 11 минут).
//
// СЛАГ КОМНАТЫ — не для лога и не для репозитория: в строки лога он не
// попадает нигде в этом файле.
const (
	dionAPIHost = "api.dion.vc"
	dionWSHost  = "sockets-pool.dion.vc"
	dionUA      = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

	dionHoldMax     = 30 * time.Minute
	dionStepTimeout = 10 * time.Second
	dionWSDialMax   = 15 * time.Second
	dionJoinWait    = 15 * time.Second
	dionMaxFrame    = 1 << 20
)

// dionHeaders — заголовки браузера Chrome, обязательные для api.dion.vc.
func dionHeaders(h http.Header) {
	h.Set("User-Agent", dionUA)
	h.Set("Accept", "*/*")
	h.Set("Accept-Language", "en")
	h.Set("Referer", "https://dion.vc/")
	h.Set("D-Platform", "web")
	h.Set("D-Browser-Type", "Chrome")
	h.Set("D-Browser-Version", "146.0.0.0")
	h.Set("D-Device-Type", "pc")
	h.Set("D-OS", "Windows")
	h.Set("D-OS-Version", "10")
}

// dionSlugClean — голый slug из ссылки или из слага как есть.
func dionSlugClean(raw string) string {
	s := strings.TrimSpace(raw)
	for _, p := range []string{"https://dion.vc/event/", "http://dion.vc/event/", "dion.vc/event/"} {
		s = strings.TrimPrefix(s, p)
	}
	if i := strings.IndexAny(s, "?#/"); i >= 0 {
		s = s[:i]
	}
	return s
}

// parseDionSlugs — список комнат из строки ввода: разделители запятая,
// точка с запятой, пробел, перенос; пустые и дубли выбрасываются. Ответ —
// голые slug'и (без префикса).
func parseDionSlugs(raw string) []string {
	f := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\r' || r == '\t'
	})
	seen := map[string]bool{}
	var out []string
	for _, x := range f {
		x = strings.TrimPrefix(strings.TrimSpace(x), "dion:")
		x = dionSlugClean(x)
		if x == "" || seen[x] {
			continue
		}
		seen[x] = true
		out = append(out, x)
	}
	return out
}

// turnUDPAddr — "хост:порт" из ice-адреса, если это UDP-TURN; иначе "".
// Отбрасываются turns:, transport=tcp и адреса без порта: без этого каждая
// попытка через turn:…:443?transport=tcp падала за миллисекунды.
func turnUDPAddr(u string) string {
	if !strings.HasPrefix(u, "turn:") {
		return ""
	}
	rest := strings.TrimPrefix(u, "turn:")
	addr, query, _ := strings.Cut(rest, "?")
	if strings.Contains(strings.ToLower(query), "transport=tcp") {
		return ""
	}
	if _, _, err := net.SplitHostPort(addr); err != nil {
		return ""
	}
	return addr
}

// dionDialer — соединение мимо туннеля (protect до connect), как protectedHTTP.
func dionDialer(prot Protector) func(ctx context.Context, network, addr string) (net.Conn, error) {
	d := &net.Dialer{
		Timeout: dionStepTimeout,
		Control: func(network, address string, rc syscall.RawConn) error {
			if host, _, err := net.SplitHostPort(address); err == nil {
				excludeHost(prot, host)
			}
			var perr error
			if err := rc.Control(func(fd uintptr) {
				if prot == nil {
					return
				}
				if !prot.Protect(int32(fd)) {
					perr = errors.New("VpnService.protect отказал на сокете к DION")
				}
			}); err != nil {
				return err
			}
			return perr
		},
	}
	return resolvingDialContext(d, prot)
}

// dionErr — ошибка HTTP-шага; код нужен, чтобы отличить «комната не найдена».
type dionErr struct {
	code int
	body string
}

func (e *dionErr) Error() string { return fmt.Sprintf("HTTP %d: %s", e.code, e.body) }

func dionJSON(ctx context.Context, c *http.Client, method, u, token string, body interface{}) (map[string]interface{}, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, rd)
	if err != nil {
		return nil, err
	}
	dionHeaders(req.Header)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := c.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errDionNetwork, dionScrub(err))
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		b := string(raw)
		if len(b) > 200 {
			b = b[:200]
		}
		return nil, &dionErr{code: resp.StatusCode, body: b}
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var m map[string]interface{}
	if err := dec.Decode(&m); err != nil {
		return nil, fmt.Errorf("DION: ответ не JSON: %w", err)
	}
	return m, nil
}

// --- минимальный WebSocket-клиент (RFC 6455) --------------------------------

type dionWS struct {
	c  net.Conn
	br *bufio.Reader
	wm sync.Mutex
}

func dialDionWS(ctx context.Context, prot Protector, host, path string) (*dionWS, error) {
	dctx, cancel := context.WithTimeout(ctx, dionWSDialMax)
	defer cancel()
	raw, err := dionDialer(prot)(dctx, "tcp", net.JoinHostPort(host, "443"))
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errDionNetwork, dionScrub(err))
	}
	tc := tls.Client(raw, &tls.Config{ServerName: host, NextProtos: []string{"http/1.1"}})
	_ = tc.SetDeadline(time.Now().Add(dionWSDialMax))
	if err := tc.HandshakeContext(dctx); err != nil {
		raw.Close()
		return nil, fmt.Errorf("%w: TLS: %v", errDionNetwork, err)
	}
	var kb [16]byte
	if _, err := rand.Read(kb[:]); err != nil {
		tc.Close()
		return nil, err
	}
	key := base64.StdEncoding.EncodeToString(kb[:])
	req := "GET " + path + " HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n" +
		"User-Agent: " + dionUA + "\r\nOrigin: https://dion.vc\r\n\r\n"
	if _, err := tc.Write([]byte(req)); err != nil {
		tc.Close()
		return nil, fmt.Errorf("%w: %v", errDionNetwork, err)
	}
	br := bufio.NewReader(tc)
	resp, err := http.ReadResponse(br, &http.Request{Method: "GET"})
	if err != nil {
		tc.Close()
		return nil, fmt.Errorf("%w: %v", errDionNetwork, err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		tc.Close()
		return nil, &dionErr{code: resp.StatusCode, body: "WS отказ"}
	}
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	if resp.Header.Get("Sec-WebSocket-Accept") != base64.StdEncoding.EncodeToString(sum[:]) {
		tc.Close()
		return nil, errors.New("DION: WS Accept не совпал")
	}
	_ = tc.SetDeadline(time.Time{})
	return &dionWS{c: tc, br: br}, nil
}

func (w *dionWS) close() { _ = w.c.Close() }

// writeFrame — клиентский кадр (всегда маскируется).
func (w *dionWS) writeFrame(op byte, payload []byte, deadline time.Duration) error {
	w.wm.Lock()
	defer w.wm.Unlock()
	hdr := []byte{0x80 | op}
	n := len(payload)
	switch {
	case n < 126:
		hdr = append(hdr, 0x80|byte(n))
	case n <= 0xffff:
		hdr = append(hdr, 0x80|126, byte(n>>8), byte(n))
	default:
		hdr = append(hdr, 0x80|127)
		var l [8]byte
		binary.BigEndian.PutUint64(l[:], uint64(n))
		hdr = append(hdr, l[:]...)
	}
	var mask [4]byte
	if _, err := rand.Read(mask[:]); err != nil {
		return err
	}
	hdr = append(hdr, mask[:]...)
	body := make([]byte, n)
	for i := range payload {
		body[i] = payload[i] ^ mask[i%4]
	}
	_ = w.c.SetWriteDeadline(time.Now().Add(deadline))
	_, err := w.c.Write(append(hdr, body...))
	return err
}

// readText — следующее текстовое сообщение целиком; ping отвечается pong,
// close возвращает io.EOF.
func (w *dionWS) readText(readTimeout time.Duration) ([]byte, error) {
	var msg []byte
	for {
		_ = w.c.SetReadDeadline(time.Now().Add(readTimeout))
		var h [2]byte
		if _, err := io.ReadFull(w.br, h[:]); err != nil {
			return nil, err
		}
		fin, op := h[0]&0x80 != 0, h[0]&0x0f
		ln := uint64(h[1] & 0x7f)
		switch ln {
		case 126:
			var b [2]byte
			if _, err := io.ReadFull(w.br, b[:]); err != nil {
				return nil, err
			}
			ln = uint64(binary.BigEndian.Uint16(b[:]))
		case 127:
			var b [8]byte
			if _, err := io.ReadFull(w.br, b[:]); err != nil {
				return nil, err
			}
			ln = binary.BigEndian.Uint64(b[:])
		}
		if ln > dionMaxFrame {
			return nil, errors.New("DION: кадр слишком большой")
		}
		if h[1]&0x80 != 0 { // сервер не маскирует, но допускаем
			var m [4]byte
			if _, err := io.ReadFull(w.br, m[:]); err != nil {
				return nil, err
			}
		}
		p := make([]byte, ln)
		if _, err := io.ReadFull(w.br, p); err != nil {
			return nil, err
		}
		switch op {
		case 0x8:
			return nil, io.EOF
		case 0x9:
			_ = w.writeFrame(0xA, p, 10*time.Second)
			continue
		case 0xA:
			continue
		}
		msg = append(msg, p...)
		if len(msg) > dionMaxFrame {
			return nil, errors.New("DION: сообщение слишком большое")
		}
		if fin {
			return msg, nil
		}
	}
}

// --- креды --------------------------------------------------------------

// fetchDionCreds добывает TURN-креды комнаты DION. release закрывает WS
// комнаты: она остаётся открытой до dionHoldMax или до вызова release.
func (s *session) fetchDionCreds(ctx context.Context, slug string, prot Protector) (turnCreds, func(), error) {
	slug = dionSlugClean(slug)
	if slug == "" {
		return turnCreds{}, nil, errDionDead
	}
	client := &http.Client{
		Timeout:   dionStepTimeout,
		Transport: &http.Transport{DialContext: dionDialer(prot), ForceAttemptHTTP2: false},
	}

	ev, err := dionJSON(ctx, client, http.MethodGet,
		"https://"+dionAPIHost+"/conference/v2/events/slug/"+url.PathEscape(slug), "", nil)
	if err != nil {
		return turnCreds{}, nil, dionClassify(err)
	}
	eventID, ok := ev["id"]
	if !ok {
		return turnCreds{}, nil, fmt.Errorf("%w: в ответе нет id комнаты", errDionDead)
	}

	name, err := genName()
	if err != nil {
		return turnCreds{}, nil, err
	}
	g, err := dionJSON(ctx, client, http.MethodPost,
		"https://"+dionAPIHost+"/platform/v1/users/register/guest", "",
		map[string]interface{}{"name": name, "event_id": eventID})
	if err != nil {
		return turnCreds{}, nil, dionClassify(err)
	}
	token, _ := g["access_token"].(string)
	if token == "" {
		return turnCreds{}, nil, errors.New("DION: гостевой вход не дал токена")
	}

	sessionID, err := uuid4()
	if err != nil {
		return turnCreds{}, nil, err
	}
	t, err := dionJSON(ctx, client, http.MethodPost,
		"https://"+dionAPIHost+"/conference/v2/connect/wss", token,
		map[string]interface{}{"session_id": sessionID})
	if err != nil {
		return turnCreds{}, nil, dionClassify(err)
	}
	ticket, _ := t["ticket"].(string)
	if ticket == "" {
		return turnCreds{}, nil, errors.New("DION: нет билета WS")
	}

	path := "/conference/sockets-pool/v2/wss?session_id=" + url.QueryEscape(sessionID) +
		"&ticket=" + url.QueryEscape(ticket)
	ws, err := dialDionWS(ctx, prot, dionWSHost, path)
	if err != nil {
		return turnCreds{}, nil, dionClassify(err)
	}

	sub, _ := json.Marshal(map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  "client:main:request:subscribe:conference",
		"params": map[string]interface{}{
			"event_id":                eventID,
			"conf_user_session_id":    sessionID,
			"main_user_session_id":    nil,
			"product_version":         "6.24.0",
			"subscription_version":    "2.0",
			"is_ignore_screensharing": false,
		},
	})
	if err := ws.writeFrame(0x1, sub, 10*time.Second); err != nil {
		ws.close()
		return turnCreds{}, nil, fmt.Errorf("%w: %v", errDionNetwork, err)
	}

	cr, err := dionAwaitJoin(ctx, ws)
	if err != nil {
		ws.close()
		return turnCreds{}, nil, dionClassify(err)
	}

	hctx, cancel := context.WithTimeout(context.Background(), dionHoldMax)
	go dionHold(hctx, ws)
	var once sync.Once
	release := func() { once.Do(func() { cancel(); ws.close() }) }
	return cr, release, nil
}

// dionAwaitJoin ждёт server:you_joined и берёт из него TURN-креды.
func dionAwaitJoin(ctx context.Context, ws *dionWS) (turnCreds, error) {
	deadline := time.Now().Add(dionJoinWait)
	for time.Now().Before(deadline) {
		if ctx.Err() != nil {
			return turnCreds{}, ctx.Err()
		}
		raw, err := ws.readText(time.Until(deadline))
		if err != nil {
			return turnCreds{}, fmt.Errorf("%w: %v", errDionNetwork, err)
		}
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var m map[string]interface{}
		if dec.Decode(&m) != nil {
			continue
		}
		if msg, _ := json.Marshal(m); bytes.Contains(bytes.ToLower(msg), []byte("call_unavailable")) {
			return turnCreds{}, errDionDead
		}
		if method, _ := m["method"].(string); method != "server:you_joined" {
			continue
		}
		params, _ := m["params"].(map[string]interface{})
		ice, _ := params["ice_servers"].([]interface{})
		var cr turnCreds
		for _, e := range ice {
			em, _ := e.(map[string]interface{})
			user, _ := em["username"].(string)
			pass, _ := em["credential"].(string)
			var urls []interface{}
			switch v := em["urls"].(type) {
			case []interface{}:
				urls = v
			case string:
				urls = []interface{}{v}
			}
			for _, u := range urls {
				us, _ := u.(string)
				if a := turnUDPAddr(us); a != "" && user != "" && pass != "" {
					cr.user, cr.pass = user, pass
					cr.addrs = append(cr.addrs, a)
				}
			}
		}
		if len(cr.addrs) == 0 {
			return turnCreds{}, errors.New("DION: в ответе нет UDP-TURN")
		}
		return cr, nil
	}
	return turnCreds{}, fmt.Errorf("%w: комната не выдала TURN за %s", errDionNetwork, dionJoinWait)
}

// dionHold держит WS комнаты открытым и отвечает на heartbeat. id длиннее
// 2^53, поэтому его нельзя пропускать через float64: json.Number сохраняет
// исходную запись.
func dionHold(ctx context.Context, ws *dionWS) {
	defer ws.close()
	for ctx.Err() == nil {
		raw, err := ws.readText(30 * time.Second)
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue // тишина: heartbeat приходит не чаще раза в интервал
			}
			return
		}
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var m map[string]interface{}
		if dec.Decode(&m) != nil {
			continue
		}
		if method, _ := m["method"].(string); method != "server:notify:main:heartbeat" {
			continue
		}
		id, ok := m["id"]
		if !ok {
			continue
		}
		reply, err := json.Marshal(map[string]interface{}{
			"jsonrpc": "2.0", "id": id, "result": map[string]interface{}{},
		})
		if err != nil {
			continue
		}
		if err := ws.writeFrame(0x1, reply, 10*time.Second); err != nil {
			return
		}
	}
}

// dionClassify переводит сбой HTTP/WS в «комната мертва» или «сеть».
func dionClassify(err error) error {
	var de *dionErr
	if errors.As(err, &de) {
		if de.code == http.StatusNotFound || strings.Contains(strings.ToLower(de.body), "call_unavailable") {
			return fmt.Errorf("%w: %v", errDionDead, err)
		}
	}
	return err
}

// CheckDionRoom проверяет комнату DION полным проходом до выдачи TURN и
// сразу закрывает её. Статус для экрана, как у CheckHash. Protector может
// быть nil (проверка при погашенном туннеле).
func CheckDionRoom(slug string, prot Protector, log Logger) string {
	s := newSession(log)
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	_, release, err := s.fetchDionCreds(ctx, slug, prot)
	if err != nil {
		switch {
		case errors.Is(err, errDionDead):
			return "мёртвая"
		case errors.Is(err, errDionNetwork):
			return "сеть"
		}
		return "ошибка: " + err.Error()
	}
	release()
	return "живая"
}

func init() {
	fetchDionCredsHook = func(s *session, ctx context.Context, slug string, prot Protector) (turnCreds, func(), error) {
		return s.fetchDionCreds(ctx, slug, prot)
	}
}

// dionScrub — текст ошибки без адреса запроса: *url.Error несёт полный URL,
// а в нём slug комнаты, которого в логе быть не должно.
func dionScrub(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Op + ": " + ue.Err.Error()
	}
	return err.Error()
}
