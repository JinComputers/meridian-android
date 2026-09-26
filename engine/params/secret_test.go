package params

import (
	"context"
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
