package engine

import (
	"context"
	"testing"
)

// Dion основной и отвечает: VK не трогаем вовсе (сеть в тесте недоступна,
// вызов VK дал бы ошибку или долгое ожидание).
func TestPrimaryDionUsedFirst(t *testing.T) {
	old := fetchDionCredsHook
	defer func() { fetchDionCredsHook = old; SetRelayPrimaryDion(false) }()
	called := 0
	fetchDionCredsHook = func(s *session, ctx context.Context, slug string, prot Protector) (turnCreds, func(), error) {
		called++
		return turnCreds{user: "u", pass: "p", addrs: []string{"1.2.3.4:3478"}}, nil, nil
	}
	SetRelayPrimaryDion(true)
	s := newSession(nil)
	c := candidate{hashes: []string{"vkhash"}, dionSlugs: []string{"room"}}
	cr, err := s.relayCreds(context.Background(), c, 0, nil)
	if err != nil || called != 1 || cr.user != "u" {
		t.Fatalf("ждали креды Dion с первого раза, err=%v called=%d", err, called)
	}
}
