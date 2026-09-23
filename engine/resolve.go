package engine

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"strings"
	"time"
)

// resolveGateway превращает hostGateway ("edge.jincomputers.win" или уже
// готовый IP-литерал) в адрес, с которым дальше пойдёт climb().
//
// ОБРАТНАЯ СОВМЕСТИМОСТЬ — ПЕРВАЯ ВЕТКА. Если hostGateway уже IP (сегодняшний
// Config.GATEWAY, или клиент, ещё не перешедший на имя), резолв не
// запускается вовсе: via пустая, поведение не отличается от того, что было
// до этой правки.
//
// ПОРЯДОК — DoH, СИСТЕМНЫЙ DNS, КЭШ. Решение владельца, задача "edge":
// DoH тремя-четырьмя открытыми резолверами вперёд системного DNS, потому что
// системный резолвер — то, что проще всего подменить на уровне оператора или
// домашнего роутера; DoH идёт по IP резолвера напрямую, мимо любого чужого
// DNS. Кэш последнего рабочего адреса — только когда оба живых способа
// отказали, и то на риск платформы: сам resolveGateway не проверяет, жив ли
// кэш, только что он не приватный.
//
// НИЧЕГО НЕ НАШЛИ — ОШИБКА, НЕ ПУСТАЯ СТРОКА. Вызывающая сторона (Connect)
// поднимается этой же ошибкой до платформы, а та по решению владельца
// повторяет попытку со старым жёстким Config.GATEWAY — ровно так же, как
// клиент вёл себя до этой правки.
func resolveGateway(hostGateway, cachedFallback string, prot Protector, log Logger) (addr, via string, err error) {
	if net.ParseIP(hostGateway) != nil {
		return hostGateway, "", nil
	}

	logf := func(format string, args ...interface{}) {
		if log != nil {
			log.Log(fmt.Sprintf(format, args...))
		}
	}

	if ip, ok := viaDoH(hostGateway, prot, logf); ok {
		return ip, "DoH", nil
	}

	if ip, ok := viaSystemDNS(hostGateway, logf); ok {
		return ip, "системный DNS", nil
	}

	if cachedFallback != "" && !isPrivateOrSpecial(cachedFallback) {
		logf("резолв %s: DoH и системный DNS молчат, беру из кэша %s", hostGateway, cachedFallback)
		return cachedFallback, "кэш", nil
	}

	return "", "", fmt.Errorf("не удалось узнать адрес шлюза по имени %s", hostGateway)
}

// --- DoH ------------------------------------------------------------------

type dohProvider struct {
	// ip — сам резолвер, запрос идёт СЮДА, а не по доменному имени: имя
	// резолвера ещё предстояло бы разрешить, и мы вернулись бы к тому же
	// вопросу, который решаем.
	ip string
	// sni — имя, которое резолвер несёт в своём сертификате. Запрос идёт
	// на ip, а проверка TLS — по sni: без этого рукопожатие с любым
	// публичным DoH откажет само, сертификат на голый IP не выписывают.
	sni string
}

// ЧЕТЫРЕ ОТКРЫТЫХ РЕЗОЛВЕРА, порядок — решение владельца, задача "edge".
//
// ИМЯ ЯНДЕКСА ТРЕБУЕТ ПРОВЕРКИ ПЕРЕД РЕЛИЗОМ. Взято по памяти о публичной
// документации Яндекса, а не из спецификации протокола — в отличие от
// остальных трёх (это общеизвестные, годами стабильные адреса), для
// Яндекса стоит свериться с их актуальной страницей DNS over HTTPS перед
// сборкой релиза, а не полагаться на строку ниже как на факт.
var dohProviders = []dohProvider{
	{"1.1.1.1", "cloudflare-dns.com"},
	{"8.8.8.8", "dns.google"},
	{"9.9.9.9", "dns.quad9.net"},
	{"77.88.8.8", "common.dot.dns.yandex.net"},
}

// dohBudget — на ВЕСЬ обход резолверов, не на одного.
//
// Короче ladderBudget (25 с, session.go): это только имя шлюза, а не сама
// лестница транспорта, и тратить на него значимую часть общего срока
// подключения нельзя.
const dohBudget = 4 * time.Second

const dohDialTimeout = 1500 * time.Millisecond

func viaDoH(host string, prot Protector, logf func(string, ...interface{})) (string, bool) {
	order := append([]dohProvider(nil), dohProviders...)
	rand.Shuffle(len(order), func(i, j int) { order[i], order[j] = order[j], order[i] })

	ctx, cancel := context.WithTimeout(context.Background(), dohBudget)
	defer cancel()

	for _, p := range order {
		if ctx.Err() != nil {
			break
		}
		ip, err := dohQuery(ctx, p, host, prot)
		if err != nil {
			logf("DoH %s: %v", p.ip, err)
			continue
		}
		if isPrivateOrSpecial(ip) {
			logf("DoH %s вернул приватный адрес %s — не годится", p.ip, ip)
			continue
		}
		return ip, true
	}
	return "", false
}

func dohQuery(ctx context.Context, p dohProvider, host string, prot Protector) (string, error) {
	q := buildDNSQuery(host)
	encoded := base64.RawURLEncoding.EncodeToString(q)
	url := fmt.Sprintf("https://%s/dns-query?dns=%s", p.ip, encoded)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/dns-message")

	client := &http.Client{
		Timeout: dohDialTimeout,
		Transport: &http.Transport{
			DialContext: (&net.Dialer{
				Timeout: dohDialTimeout,
				// Тот же protectControl, что и у прямых ступеней
				// (race.go) — сокет до резолвера обязан быть исключён
				// из туннеля тем же порядком, что и сокет до шлюза.
				Control: protectControl(prot, func(string, ...interface{}) {}, "DoH"),
			}).DialContext,
			TLSClientConfig: &tls.Config{ServerName: p.sni},
		},
	}
	defer client.CloseIdleConnections()

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("код ответа %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	return parseDNSResponse(body)
}

// --- системный DNS ----------------------------------------------------------

const systemDNSTimeout = 2 * time.Second

func viaSystemDNS(host string, logf func(string, ...interface{})) (string, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), systemDNSTimeout)
	defer cancel()

	addrs, err := net.DefaultResolver.LookupHost(ctx, host)
	if err != nil {
		logf("системный DNS: %v", err)
		return "", false
	}
	for _, a := range addrs {
		if !isPrivateOrSpecial(a) {
			return a, true
		}
	}
	logf("системный DNS вернул только приватные адреса")
	return "", false
}

// --- общий разбор DNS (RFC 1035), без стороннего пакета --------------------
//
// РУЧНОЙ РАЗБОР, А НЕ БИБЛИОТЕКА. Нужен один тип запроса (A) и один вопрос —
// тянуть зависимость ради тридцати строк бинарного формата дороже, чем
// написать и один раз проверить тестом их самих (resolve_test.go).

func buildDNSQuery(host string) []byte {
	buf := make([]byte, 0, 32+len(host))

	var id [2]byte
	// ID не обязан быть криптографически случайным — это не защита, а
	// сопоставление ответа запросу, которого тут всего один за раз.
	binary.BigEndian.PutUint16(id[:], uint16(rand.Intn(1<<16)))
	buf = append(buf, id[:]...)
	buf = append(buf, 0x01, 0x00) // flags: RD=1, остальное по умолчанию
	buf = append(buf, 0x00, 0x01) // QDCOUNT=1
	buf = append(buf, 0x00, 0x00) // ANCOUNT
	buf = append(buf, 0x00, 0x00) // NSCOUNT
	buf = append(buf, 0x00, 0x00) // ARCOUNT

	for _, label := range strings.Split(host, ".") {
		buf = append(buf, byte(len(label)))
		buf = append(buf, label...)
	}
	buf = append(buf, 0x00)       // конец имени
	buf = append(buf, 0x00, 0x01) // QTYPE=A
	buf = append(buf, 0x00, 0x01) // QCLASS=IN
	return buf
}

// skipName пропускает закодированное имя (обычные метки ИЛИ указатель
// сжатия 0xC0xx) и возвращает смещение сразу за ним. Содержимое имени нам
// не нужно — только для запроса, где имя уже известно нам самим.
func skipName(data []byte, offset int) (int, error) {
	for {
		if offset >= len(data) {
			return 0, errors.New("имя обрезано")
		}
		b := data[offset]
		if b&0xC0 == 0xC0 { // указатель сжатия — ровно два байта
			return offset + 2, nil
		}
		if b == 0 {
			return offset + 1, nil
		}
		offset += int(b) + 1
	}
}

func parseDNSResponse(data []byte) (string, error) {
	if len(data) < 12 {
		return "", errors.New("ответ короче заголовка DNS")
	}
	qdcount := int(binary.BigEndian.Uint16(data[4:6]))
	ancount := int(binary.BigEndian.Uint16(data[6:8]))

	offset := 12
	for i := 0; i < qdcount; i++ {
		var err error
		offset, err = skipName(data, offset)
		if err != nil {
			return "", err
		}
		offset += 4 // QTYPE(2) + QCLASS(2)
	}

	for i := 0; i < ancount; i++ {
		var err error
		offset, err = skipName(data, offset)
		if err != nil {
			return "", err
		}
		if offset+10 > len(data) {
			return "", errors.New("запись ответа обрезана")
		}
		rtype := binary.BigEndian.Uint16(data[offset : offset+2])
		rdlength := int(binary.BigEndian.Uint16(data[offset+8 : offset+10]))
		offset += 10
		if offset+rdlength > len(data) {
			return "", errors.New("данные записи обрезаны")
		}
		if rtype == 1 && rdlength == 4 { // TYPE A
			ip := net.IPv4(data[offset], data[offset+1], data[offset+2], data[offset+3])
			return ip.String(), nil
		}
		offset += rdlength
	}
	return "", errors.New("A-записи в ответе нет")
}

// --- фильтр приватных/особых адресов ---------------------------------------
//
// ОБЩИЙ ДЛЯ DoH, СИСТЕМНОГО DNS И КЭША. Задача явно требует его хотя бы для
// системного DNS (тот проще всего подменить перехватом на уровне оператора
// или домашнего роутера) — применяю ко всем трём источникам разом, это
// дешёвая защита и от испорченного кэша тоже.
var privateBlocks = mustParseCIDRs(
	"10.0.0.0/8",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"0.0.0.0/32",
)

func mustParseCIDRs(cidrs ...string) []*net.IPNet {
	nets := make([]*net.IPNet, 0, len(cidrs))
	for _, c := range cidrs {
		_, n, err := net.ParseCIDR(c)
		if err != nil {
			panic("resolve.go: неверный CIDR " + c)
		}
		nets = append(nets, n)
	}
	return nets
}

func isPrivateOrSpecial(addr string) bool {
	ip := net.ParseIP(addr)
	if ip == nil {
		return true // не адрес вовсе — доверия ему тоже нет
	}
	for _, n := range privateBlocks {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}
