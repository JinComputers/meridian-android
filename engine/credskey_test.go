package engine

import "testing"

// Пачек больше, чем ссылок: следующий круг обязан получить свой ключ кэша,
// иначе отдастся исчерпанный комплект (квота релея девять на комплект).
func TestCredsKey(t *testing.T) {
	if got := credsKey("h", 0, 4); got != "h" {
		t.Fatalf("первый круг: %q", got)
	}
	if got := credsKey("h", 3, 4); got != "h" {
		t.Fatalf("последняя пачка первого круга: %q", got)
	}
	a, b := credsKey("h", 4, 4), credsKey("h", 8, 4)
	if a == "h" || b == "h" || a == b {
		t.Fatalf("круги должны различаться: %q %q", a, b)
	}
	if got := credsKey("h", 5, 0); got != "h" {
		t.Fatalf("без хешей: %q", got)
	}
}
