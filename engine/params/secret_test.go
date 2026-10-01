package params

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Ключ подписчика уходит в строке запроса; при любом сбое запроса он не
// должен попадать в текст ошибки, который платформы пишут в журнал.
func TestFailureTextHasNoKey(t *testing.T) {
	const secret = "SECRETKEY0123456789abcdef"
	s := httptest.NewTLSServer(apiHandler(liveAnonymous))
	defer s.Close()

	c := New(Config{
		Addresses: []Address{addrOf(t, s, true)},
		Pins:      []string{PinMain, PinBackup}, // не ключ тестового сервера: запрос упадёт
	})
	_, err := c.Fetch(context.Background(), secret)
	if err == nil {
		t.Fatal("ждали отказ")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("ключ попал в текст ошибки: %v", err)
	}
}

// Версия и платформа из конверта уходят заголовками (просьба кота 1, 01.10).
func TestVersionHeadersSent(t *testing.T) {
	var gotV, gotP string
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotV, gotP = r.Header.Get("X-Meridian-Version"), r.Header.Get("X-Meridian-Platform")
		apiHandler(liveAnonymous).ServeHTTP(w, r)
	}))
	defer s.Close()
	c := New(Config{
		Addresses: []Address{addrOf(t, s, true)},
		Pins:      []string{SPKIPin(s.Certificate())},
		Envelope:  map[string]string{"app_version": "9.9.9", "platform": "windows"},
	})
	if _, err := c.Fetch(context.Background(), ""); err != nil {
		t.Fatal(err)
	}
	if gotV != "9.9.9" || gotP != "windows" {
		t.Fatalf("заголовки: version=%q platform=%q", gotV, gotP)
	}
}
