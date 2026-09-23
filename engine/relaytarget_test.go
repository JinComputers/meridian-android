package engine

import "testing"

func relayLadder() *Ladder {
	l := NewLadder()
	l.AddDirect("A", "62.76.231.231", 443, true)
	l.AddRelay("R", "1.2.3.4:19302", "realm", 56005, false)
	l.AddRelayHash("hash1")
	return l
}

func TestSetRelayTargetSplitsRelayWithFallback(t *testing.T) {
	l := relayLadder()
	if !l.SetRelayTarget("138.124.78.252") {
		t.Fatal("верный IPv4 не принят")
	}
	if len(l.items) != 3 {
		t.Fatalf("ступеней %d, хочу 3 (прямая, релей на target, релей-откат)", len(l.items))
	}
	via, fb := l.items[1], l.items[2]
	if !via.isRelay || via.host != "138.124.78.252" {
		t.Errorf("первая релейная: isRelay=%v host=%q", via.isRelay, via.host)
	}
	if !fb.isRelay || fb.host != "" {
		t.Errorf("откат: isRelay=%v host=%q, хочу пустой host (адрес из edge)", fb.isRelay, fb.host)
	}
	if via.port != 56005 || fb.port != 56005 {
		t.Errorf("порт релея изменился: %d / %d", via.port, fb.port)
	}
	if len(via.hashes) != 1 || len(fb.hashes) != 1 {
		t.Errorf("хеши потеряны при раздвоении: %d / %d", len(via.hashes), len(fb.hashes))
	}
	if l.items[0].host != "62.76.231.231" {
		t.Error("прямая ступень тронута")
	}
}

func TestSetRelayTargetRejectsBadValues(t *testing.T) {
	for _, v := range []string{"", "edge.example.org", "138.124.78.252:56005", "10.0.0.1", "127.0.0.1", "::1", "мусор"} {
		l := relayLadder()
		if l.SetRelayTarget(v) {
			t.Errorf("значение %q принято", v)
		}
		if len(l.items) != 2 {
			t.Errorf("значение %q изменило лестницу: %d ступеней", v, len(l.items))
		}
	}
}
