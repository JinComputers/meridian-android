// Package params — клиент API Meridian для настольных клиентов: поход за
// /v1/params и /v1/key, закрепление ключа сервера, обход запасных адресов.
//
// Портирован с Kotlin (ApiClient.kt, ApiPins.kt, Params.kt, часть Api.kt)
// БЕЗ ИЗМЕНЕНИЯ ПОВЕДЕНИЯ; Android пока остаётся на своём Kotlin. НЕ ПОПАДАЕТ
// под gomobile bind (подпакет, bind собирает только корень engine/).
package params

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sync"
	"syscall"
	"time"
)

// Contract — версия договора, которую понимает клиент.
const Contract = "v1"

// Пины API: base64(sha256(SPKI)) закреплённого ключа. Значения из
// ApiPins.kt. Основной — самоподписанный сертификат службы на порту 21947
// (замер БЕЗ /v1 на 443: там прокси с чужим ключом). Запасной нужен для
// немедленной смены при компрометации основного.
const (
	PinMain   = "bYxJBY7Uhz34CkyyssvHci+ihjsypwu//acXo1fsIbw="
	PinBackup = "Lsn4bXD0gvNi/FGV0N12f3Uj4i/iuu8iHmtzO2geYV0="
)

// Address — один адрес API. Pinned=true: доверие ТОЛЬКО по закреплённому
// ключу (самоподписанный сертификат, цепочки нет). Pinned=false: обычное
// системное доверие (настоящий Let's Encrypt, cdn.jincomputers.win) —
// закреплённый ключ к нему не применяется, там ключ свой и меняется.
type Address struct {
	Host   string
	Port   int
	Pinned bool
}

// DefaultAddresses — порядок как в ApiClient.kt: домен, IP, cdn-имя.
func DefaultAddresses() []Address {
	return []Address{
		{Host: "jinelectronics.ru", Port: 21947, Pinned: true},
		{Host: "138.249.246.89", Port: 21947, Pinned: true},
		{Host: "cdn.jincomputers.win", Port: 443, Pinned: false},
	}
}

// Protector — исключение сокета из туннеля. Структурно совпадает с
// engine.Protector: одна и та же платформенная реализация годится обоим.
type Protector interface {
	Protect(fd int32) bool
}

// Config — настройка клиента.
type Config struct {
	Addresses []Address // пусто — DefaultAddresses()
	Pins      []string  // пусто — PinMain, PinBackup

	// Envelope — общий конверт каждого запроса (contract добавляется сам).
	// Обычно: device_id, platform, app_version, app_code. Допустимые
	// значения platform для настольных клиентов у службы НЕ подтверждены.
	Envelope map[string]string

	// Protector — сокеты клиента идут мимо туннеля (привязка к физическому
	// интерфейсу). nil — без защиты (например, туннеля ещё нет).
	Protector Protector

	// Запоминание последнего удачного адреса между запусками (по образцу
	// prefs Kotlin). Оба nil — только в памяти клиента.
	LoadLastGood func() string
	SaveLastGood func(host string)

	// Сроки. 0 — значения из ApiClient.kt.
	ConnectTimeout time.Duration
	ReadTimeout    time.Duration
	WalkBudget     time.Duration

	// Log — необязательная диагностика (строки как TunnelLog.add).
	Log func(string)
}

const (
	defaultConnect = 2500 * time.Millisecond
	defaultRead    = 3500 * time.Millisecond
	defaultWalk    = 10 * time.Second
)

// Client — клиент API. Безопасен для параллельного использования.
type Client struct {
	cfg  Config
	mu   sync.Mutex
	good string
}

func New(cfg Config) *Client {
	if len(cfg.Addresses) == 0 {
		cfg.Addresses = DefaultAddresses()
	}
	if len(cfg.Pins) == 0 {
		cfg.Pins = []string{PinMain, PinBackup}
	}
	if cfg.ConnectTimeout == 0 {
		cfg.ConnectTimeout = defaultConnect
	}
	if cfg.ReadTimeout == 0 {
		cfg.ReadTimeout = defaultRead
	}
	if cfg.WalkBudget == 0 {
		cfg.WalkBudget = defaultWalk
	}
	return &Client{cfg: cfg}
}

func (c *Client) logf(format string, a ...interface{}) {
	if c.cfg.Log != nil {
		c.cfg.Log(fmt.Sprintf(format, a...))
	}
}

// --- итог обращения: четыре исхода, путать нельзя -------------------------

// Result — итог обращения к службе.
type Result interface{ isResult() }

// Ok — служба ответила по договору. Code — код HTTP: «ответила» и
// «согласилась» разные вещи (429 с телом по договору).
type Ok struct {
	Body map[string]interface{}
	Code int
	Raw  []byte // тело как пришло
}

// Refused — служба ответила отказом. Это ЗНАНИЕ, а не сбой.
type Refused struct {
	Err        string
	RetryAfter int
	Code       int
}

// Incompatible — договор чужой, тело не разобрано намеренно.
type Incompatible struct{ Theirs string }

// Unavailable — молчат ВСЕ адреса. Только здесь «API недоступен».
type Unavailable struct{ Why string }

func (Ok) isResult()           {}
func (Refused) isResult()      {}
func (Incompatible) isResult() {}
func (Unavailable) isResult()  {}

// Call — сходить по пути. Обход адресов: молчание одного — не «API
// недоступен», а «идём ко второму»; последний удачный пробуется первым.
func (c *Client) Call(ctx context.Context, path string, query map[string]string) Result {
	params := map[string]string{"contract": Contract}
	for k, v := range c.cfg.Envelope {
		params[k] = v
	}
	for k, v := range query {
		params[k] = v
	}

	ctx, cancel := context.WithTimeout(ctx, c.cfg.WalkBudget)
	defer cancel()

	lastWhy := "адресов нет"
	for _, a := range c.ordered() {
		if ctx.Err() != nil {
			c.logf("API: срок обхода исчерпан, оставшиеся адреса не пробовал")
			break
		}
		r := c.one(ctx, a, path, params)
		if u, ok := r.(Unavailable); ok {
			lastWhy = u.Why
			c.logf("API: %s не ответил (%s), пробую следующий", a.Host, lastWhy)
			continue
		}
		c.remember(a.Host)
		return r
	}
	c.logf("API недоступен: молчат все адреса (%s)", lastWhy)
	return Unavailable{Why: lastWhy}
}

func (c *Client) ordered() []Address {
	c.mu.Lock()
	good := c.good
	c.mu.Unlock()
	if good == "" && c.cfg.LoadLastGood != nil {
		good = c.cfg.LoadLastGood()
	}
	out := make([]Address, 0, len(c.cfg.Addresses))
	for _, a := range c.cfg.Addresses {
		if a.Host == good {
			out = append(out, a)
		}
	}
	for _, a := range c.cfg.Addresses {
		if a.Host != good {
			out = append(out, a)
		}
	}
	return out
}

func (c *Client) remember(host string) {
	c.mu.Lock()
	changed := c.good != host
	c.good = host
	c.mu.Unlock()
	if changed && c.cfg.SaveLastGood != nil {
		c.cfg.SaveLastGood(host)
	}
}

// --- TLS: закрепление -----------------------------------------------------

// SPKIPin — пин одного сертификата: base64(sha256(SubjectPublicKeyInfo)).
func SPKIPin(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return base64.StdEncoding.EncodeToString(sum[:])
}

// PinMatches — есть ли в цепочке хоть один закреплённый ключ (смотрим
// ВСЮ цепочку, не только лист).
func PinMatches(rawCerts [][]byte, pins []string) bool {
	for _, raw := range rawCerts {
		cert, err := x509.ParseCertificate(raw)
		if err != nil {
			continue
		}
		p := SPKIPin(cert)
		for _, want := range pins {
			if p == want {
				return true
			}
		}
	}
	return false
}

var errPinMismatch = errors.New("ключ сервера не совпал с закреплённым")

// pinnedTLS — доверие ТОЛЬКО по пину. Проверка цепочки и имени отключена
// намеренно (сертификат самоподписанный), решает VerifyPeerCertificate:
// несовпадение обрывает рукопожатие, ветки «принять любой» нет.
func pinnedTLS(pins []string) *tls.Config {
	return &tls.Config{
		InsecureSkipVerify: true, // ВСЯ проверка — в VerifyPeerCertificate ниже
		MinVersion:         tls.VersionTLS12,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("сервер не прислал сертификата")
			}
			if !PinMatches(raw, pins) {
				return errPinMismatch
			}
			return nil
		},
	}
}

func (c *Client) httpClient(a Address) *http.Client {
	d := &net.Dialer{Timeout: c.cfg.ConnectTimeout}
	if p := c.cfg.Protector; p != nil {
		d.Control = func(network, address string, rc syscall.RawConn) error {
			// Необязательное исключение маршрута (см. engine/exclude.go): тот же
			// метод ExcludeHost, если платформенный Protector его умеет.
			if ex, ok := p.(interface{ ExcludeHost(string) }); ok {
				if host, _, err := net.SplitHostPort(address); err == nil {
					ex.ExcludeHost(host)
				}
			}
			var perr error
			if err := rc.Control(func(fd uintptr) {
				if !p.Protect(int32(fd)) {
					perr = errors.New("Protector отказал на сокете к API")
				}
			}); err != nil {
				return err
			}
			return perr
		}
	}
	tr := &http.Transport{
		DialContext:         c.resolvingDial(d),
		TLSHandshakeTimeout: c.cfg.ConnectTimeout,
		DisableKeepAlives:   true,
		Proxy:               nil,
	}
	if a.Pinned {
		tr.TLSClientConfig = pinnedTLS(c.cfg.Pins)
	}
	return &http.Client{Transport: tr, Timeout: c.cfg.ConnectTimeout + c.cfg.ReadTimeout}
}

// one — один заход к одному адресу.
func (c *Client) one(ctx context.Context, a Address, path string, params map[string]string) Result {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	// ПАРАМЕТРЫ ТОЛЬКО В СТРОКЕ ЗАПРОСА, тело не отправляется никогда.
	u := fmt.Sprintf("https://%s:%d%s?%s", a.Host, a.Port, path, q.Encode())

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return Unavailable{Why: scrubErr(err)}
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient(a).Do(req)
	if err != nil {
		// Сюда же приходит и провал пина: для человека это «не
		// дозвонились», сервер «не тот» всё равно что сервера нет.
		return Unavailable{Why: scrubErr(err)}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return Unavailable{Why: scrubErr(err)}
	}
	return c.interpret(a.Host, resp.StatusCode, body)
}

// interpret — общий разбор ответа. Живёт здесь и только здесь.
func (c *Client) interpret(host string, code int, body []byte) Result {
	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		// ОТВЕТ ЕСТЬ, НО НЕ JSON (405/400 простым текстом). Это НЕ «адрес
		// не ответил»: рукопожатие с закреплённым ключом уже прошло, на
		// том конце наш сервер, следующий адрес ответит тем же.
		c.logf("API %s: ответ не по договору (код %d)", host, code)
		return Refused{Err: "служба ответила не по договору", Code: code}
	}
	if theirs, _ := m["contract"].(string); theirs != Contract {
		// Тело НЕ разбираем дальше: одно имя в другом договоре может
		// значить другое.
		c.logf("API: договор «%s», а я понимаю «%s» — нужно обновить приложение", theirs, Contract)
		return Incompatible{Theirs: theirs}
	}
	if status, _ := m["status"].(string); status != "answered" {
		errText, _ := m["error"].(string)
		if errText == "" {
			errText, _ = m["message"].(string)
		}
		if errText == "" {
			errText = "без причины"
		}
		retry, _ := m["retry_after"].(float64)
		return Refused{Err: errText, RetryAfter: int(retry), Code: code}
	}
	return Ok{Body: m, Code: code, Raw: body}
}

// resolvingDial — имена API (jinelectronics.ru, cdn.jincomputers.win)
// разрешает платформа, если её Protector умеет LookupHost (то же
// необязательное расширение, что и в engine/exclude.go): системный резолвер
// при живом туннеле может уйти в туннель. Нет метода или ошибка — обычный
// Dial.
func (c *Client) resolvingDial(d *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	hr, _ := c.cfg.Protector.(interface {
		LookupHost(string) ([]string, error)
	})
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil || hr == nil || net.ParseIP(host) != nil {
			return d.DialContext(ctx, network, addr)
		}
		ips, err := hr.LookupHost(host)
		if err != nil || len(ips) == 0 {
			return d.DialContext(ctx, network, addr)
		}
		var last error
		for _, ip := range ips {
			conn, err := d.DialContext(ctx, network, net.JoinHostPort(ip, port))
			if err == nil {
				return conn, nil
			}
			last = err
			if ctx.Err() != nil {
				break
			}
		}
		return nil, last
	}
}

// scrubErr — текст ошибки запроса БЕЗ адреса запроса. *url.Error всегда несёт
// полный URL, а параметры (в том числе key) у нас идут в строке запроса:
// сырой err.Error() клал ключ подписчика в текст, который платформы пишут в
// журнал как есть (найдено котом 5 при замере скорости, 26.09).
func scrubErr(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Op + ": " + ue.Err.Error()
	}
	return err.Error()
}
