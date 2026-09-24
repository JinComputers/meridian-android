package params

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// Реальный АНОНИМНЫЙ ответ /v1/params (снят живьём 24.09, без ключа):
// два входа, relay.target, настройка лестницы.
const liveAnonymous = `{"status":"answered","generation":3,"ttl":21600,"now":1790196629,"entries":[{"id":"entry-1","host":"62.76.231.231","port":443,"dtls":true},{"id":"entry-2","host":"62.76.231.231","port":56004,"dtls":true},{"id":"entry-3","host":"62.76.231.231","port":56005,"dtls":false},{"id":"entry-c1","host":"203.0.113.41","port":443,"dtls":true},{"id":"entry-c2","host":"203.0.113.41","port":56004,"dtls":true},{"id":"entry-c3","host":"203.0.113.41","port":56005,"dtls":false}],"relay":{"enabled":true,"target":"138.124.78.252"},"ladder":{"direct_probe_every_attempts":4,"direct_skip_after_fails":5,"second_round_ms":3000},"contract":"v1"}`

func TestParseLiveAnonymousAnswer(t *testing.T) {
	a, err := ParseAnswer([]byte(liveAnonymous))
	if err != nil {
		t.Fatal(err)
	}
	if len(a.Nodes) != 6 {
		t.Fatalf("узлов %d, хочу 6", len(a.Nodes))
	}
	n := a.Nodes[0]
	if n.ID != "entry-1" || n.Host != "62.76.231.231" || n.Port != 443 || !n.DTLS || n.Transport != "udp" {
		t.Errorf("первый узел разобран неверно: %+v", n)
	}
	if a.Nodes[2].DTLS {
		t.Error("entry-3 без dtls")
	}
	if !a.Relay.Enabled || a.Relay.Target != "138.124.78.252" {
		t.Errorf("relay: %+v", a.Relay)
	}
	if a.TTLSeconds != 21600 || a.Generation != 3 {
		t.Errorf("ttl=%d generation=%d", a.TTLSeconds, a.Generation)
	}
	if a.Ladder.SecondRoundMs != 3000 || a.Ladder.DirectSkipAfterFails != 5 || a.Ladder.DirectProbeEveryAttempt != 4 {
		t.Errorf("ladder: %+v", a.Ladder)
	}
	if string(a.Raw) != liveAnonymous {
		t.Error("Raw должен быть нетронутым телом ответа")
	}
}

func TestParseTLSNodeAndDefaults(t *testing.T) {
	raw := `{"status":"answered","entries":[{"id":"entry-tls","host":"62.76.231.231","port":443,"dtls":false,"transport":"tls","sni":"gw.62-76-231-231.sslip.io"},{"host":"1.2.3.4","port":56004}],"relay":{"enabled":false,"unknown":42},"ladder":{"second_round_ms":999999},"contract":"v1"}`
	a, err := ParseAnswer([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	if a.Nodes[0].Transport != "tls" || a.Nodes[0].SNI != "gw.62-76-231-231.sslip.io" {
		t.Errorf("tls-узел: %+v", a.Nodes[0])
	}
	if a.Nodes[1].ID != "1.2.3.4:56004" || a.Nodes[1].Transport != "udp" {
		t.Errorf("запись без id/transport: %+v", a.Nodes[1])
	}
	if a.Relay.Enabled || a.Relay.Target != "" {
		t.Errorf("relay.enabled=false обязан читаться, неизвестный ключ — игнорироваться: %+v", a.Relay)
	}
	if a.Ladder.SecondRoundMs != 3000 {
		t.Errorf("значение вне пределов обязано уйти к умолчанию: %d", a.Ladder.SecondRoundMs)
	}
}

func TestParseRelayDefaultsToEnabled(t *testing.T) {
	a, err := ParseAnswer([]byte(`{"entries":[{"host":"1.2.3.4","port":443}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if !a.Relay.Enabled {
		t.Error("ответ без relay не должен отнимать запасной путь")
	}
}

func TestParseNoNodes(t *testing.T) {
	for _, raw := range []string{`{"status":"unavailable"}`, `{"entries":[]}`, `{"entries":[{"host":"","port":0}]}`} {
		if _, err := ParseAnswer([]byte(raw)); err != ErrNoNodes {
			t.Errorf("%s: err=%v, хочу ErrNoNodes", raw, err)
		}
	}
}

func TestParseKey(t *testing.T) {
	cases := map[string]struct {
		state KeyState
		dead  bool
		noV   bool
	}{
		`{"verdict":"ok"}`:          {KeyActive, false, false},
		`{"verdict":"expired"}`:     {KeyExpired, true, false},
		`{"verdict":"deactivated"}`: {KeyDeactivated, true, false},
		`{"verdict":"rotated"}`:     {KeyRotated, true, false},
		`{"verdict":"unknown"}`:     {KeyUnknown, false, false},
		`{"verdict":"новое"}`:       {KeyUnrecognised, false, false},
		`{"status":"unavailable"}`:  {0, false, true},
	}
	for body, want := range cases {
		m := mustMap(t, body)
		got := parseKey(m)
		if got.NoVerdict != want.noV {
			t.Errorf("%s: NoVerdict=%v", body, got.NoVerdict)
			continue
		}
		if want.noV {
			continue
		}
		if got.State != want.state || got.State.Dead() != want.dead {
			t.Errorf("%s: state=%v dead=%v", body, got.State, got.State.Dead())
		}
	}
}

// --- клиент: закрепление и обход адресов ----------------------------------

func apiHandler(body string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(body))
	})
}

func addrOf(t *testing.T, s *httptest.Server, pinned bool) Address {
	t.Helper()
	host, port, err := net.SplitHostPort(s.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	p, _ := strconv.Atoi(port)
	return Address{Host: host, Port: p, Pinned: pinned}
}

func TestPinnedClientAcceptsMatchingKey(t *testing.T) {
	s := httptest.NewTLSServer(apiHandler(liveAnonymous))
	defer s.Close()

	c := New(Config{
		Addresses: []Address{addrOf(t, s, true)},
		Pins:      []string{SPKIPin(s.Certificate())},
	})
	a, err := c.Fetch(context.Background(), "")
	if err != nil {
		t.Fatalf("пин совпал, а Fetch отказал: %v", err)
	}
	if len(a.Nodes) != 6 {
		t.Errorf("узлов %d", len(a.Nodes))
	}
}

func TestPinnedClientRejectsWrongKey(t *testing.T) {
	s := httptest.NewTLSServer(apiHandler(liveAnonymous))
	defer s.Close()

	c := New(Config{
		Addresses: []Address{addrOf(t, s, true)},
		Pins:      []string{PinMain, PinBackup}, // не ключ тестового сервера
	})
	_, err := c.Fetch(context.Background(), "")
	e, ok := err.(*Error)
	if !ok || e.Kind != KindUnavailable {
		t.Fatalf("несовпавший пин обязан выглядеть как «молчат все адреса», получил %v", err)
	}
}

func TestUnpinnedAddressUsesSystemTrust(t *testing.T) {
	// Тестовый сертификат системой НЕ доверен: адрес с Pinned=false обязан
	// отвергнуть его, а не принять «любой» (нет ветки «принять всё»).
	s := httptest.NewTLSServer(apiHandler(liveAnonymous))
	defer s.Close()

	c := New(Config{Addresses: []Address{addrOf(t, s, false)}, Pins: []string{SPKIPin(s.Certificate())}})
	if _, err := c.Fetch(context.Background(), ""); err == nil {
		t.Fatal("адрес без пина принял недоверенный сертификат")
	}
}

func TestWalkSkipsSilentAddressAndRemembersGood(t *testing.T) {
	s := httptest.NewTLSServer(apiHandler(liveAnonymous))
	defer s.Close()

	// Первый адрес — закрытый порт (молчит), второй — живой.
	dead := Address{Host: "localhost", Port: 1, Pinned: true}
	var saved string
	c := New(Config{
		Addresses:      []Address{dead, addrOf(t, s, true)},
		Pins:           []string{SPKIPin(s.Certificate())},
		SaveLastGood:   func(h string) { saved = h },
		ConnectTimeout: 500 * time.Millisecond,
	})
	if _, err := c.Fetch(context.Background(), ""); err != nil {
		t.Fatalf("обход должен дойти до второго адреса: %v", err)
	}
	if saved != "127.0.0.1" {
		t.Errorf("последний удачный не запомнен: %q", saved)
	}
	if got := c.ordered()[0]; got.Host == "localhost" {
		t.Error("удачный адрес обязан идти первым в следующий раз")
	}
}

func TestInterpretOutcomes(t *testing.T) {
	c := New(Config{})
	if r, ok := c.interpret("h", 405, []byte("только GET")).(Refused); !ok || r.Code != 405 {
		t.Errorf("не-JSON — это отказ, не «адрес молчит»: %#v", r)
	}
	if _, ok := c.interpret("h", 200, []byte(`{"contract":"v2"}`)).(Incompatible); !ok {
		t.Error("чужой договор — Incompatible")
	}
	r, ok := c.interpret("h", 429, []byte(`{"contract":"v1","status":"refused","error":"limit","retry_after":30}`)).(Refused)
	if !ok || r.Err != "limit" || r.RetryAfter != 30 {
		t.Errorf("отказ по договору: %#v", r)
	}
	if _, ok := c.interpret("h", 200, []byte(`{"contract":"v1","status":"answered"}`)).(Ok); !ok {
		t.Error("answered — Ok")
	}
}

func mustMap(t *testing.T, s string) map[string]interface{} {
	t.Helper()
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatal(err)
	}
	return m
}
