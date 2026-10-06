package engine

import (
	"net"
	"testing"
	"time"
)

func TestEchoRequestIsValidIPv4(t *testing.T) {
	src, dst := net.ParseIP("10.77.77.5").To4(), net.ParseIP("10.77.77.1").To4()
	pkt := buildEchoRequest(src, dst, probeID, 7, probePayload)

	if len(pkt) != 20+8+probePayload {
		t.Fatalf("длина %d", len(pkt))
	}
	// Сумма по заголовку вместе с его собственной суммой даёт ноль.
	if inetChecksum(pkt[:20]) != 0 {
		t.Fatal("контрольная сумма IP неверна")
	}
	if inetChecksum(pkt[20:]) != 0 {
		t.Fatal("контрольная сумма ICMP неверна")
	}
	if pkt[9] != 1 || pkt[20] != 8 {
		t.Fatal("это не ICMP-эхо-запрос")
	}
}

func TestParseEchoReply(t *testing.T) {
	src, dst := net.ParseIP("10.77.77.5").To4(), net.ParseIP("10.77.77.1").To4()
	pkt := buildEchoRequest(src, dst, probeID, 9, 16)

	if _, ok := parseEchoReply(pkt, probeID); ok {
		t.Fatal("запрос принят за ответ")
	}
	pkt[20] = 0 // тип 0 — эхо-ответ
	seq, ok := parseEchoReply(pkt, probeID)
	if !ok || seq != 9 {
		t.Fatalf("ответ не распознан: seq=%d ok=%v", seq, ok)
	}
	if _, ok := parseEchoReply(pkt, probeID+1); ok {
		t.Fatal("чужой идентификатор принят")
	}
	if _, ok := parseEchoReply(pkt[:20], probeID); ok {
		t.Fatal("обрезанный пакет принят")
	}
}

func spaced(n int, first, step time.Duration) []time.Duration {
	out := make([]time.Duration, n)
	for i := range out {
		out[i] = first + time.Duration(i)*step
	}
	return out
}

func TestComputeSpeed(t *testing.T) {
	const pkt = probePayload + 28

	t.Run("быстрый канал: ответы идут вплотную", func(t *testing.T) {
		r := computeSpeed(40, pkt, spaced(40, 125*time.Millisecond, time.Millisecond))
		// 928 байт за миллисекунду ≈ 7,4 Мбит/с.
		if r.state != speedStateDone || r.kbps < 7000 || r.kbps > 8000 {
			t.Fatalf("ждали ~7424 кбит/с, вышло %+v", r)
		}
		if r.lossPct != 0 || r.rttMs != 125 {
			t.Fatalf("потери/отклик: %+v", r)
		}
	})

	t.Run("медленный канал: ответы с растяжкой", func(t *testing.T) {
		r := computeSpeed(40, pkt, spaced(40, 150*time.Millisecond, 80*time.Millisecond))
		// 928 байт за 80 мс ≈ 93 кбит/с.
		if r.kbps < 80 || r.kbps > 110 {
			t.Fatalf("ждали ~93 кбит/с, вышло %+v", r)
		}
	})

	t.Run("потери считаются от отправленного", func(t *testing.T) {
		r := computeSpeed(40, pkt, spaced(30, 100*time.Millisecond, 2*time.Millisecond))
		if r.lossPct != 25 {
			t.Fatalf("ждали 25%% потерь, вышло %d", r.lossPct)
		}
	})

	t.Run("шлюз молчит", func(t *testing.T) {
		r := computeSpeed(40, pkt, nil)
		if r.state != speedStateSilent || r.lossPct != 100 {
			t.Fatalf("вышло %+v", r)
		}
	})

	t.Run("дошло меньше трёх — оценка сверху по всему окну", func(t *testing.T) {
		r := computeSpeed(40, pkt, spaced(2, 200*time.Millisecond, 10*time.Millisecond))
		if r.state != speedStateDone || r.kbps <= 0 || r.kbps > 10 {
			t.Fatalf("вышло %+v", r)
		}
	})
}

// take обязан поглощать только свои ответы и не считать один и тот же дважды.
func TestSpeedProbeTake(t *testing.T) {
	p := &speedProbe{}
	p.begin()
	src, dst := net.ParseIP("10.77.77.5").To4(), net.ParseIP("10.77.77.1").To4()
	rep := buildEchoRequest(src, dst, probeID, 3, 16)
	rep[20] = 0

	if !p.take(rep) {
		t.Fatal("свой ответ не принят")
	}
	if !p.take(rep) {
		t.Fatal("повтор должен поглощаться, а не уходить в TUN")
	}
	if p.got() != 1 {
		t.Fatalf("повтор посчитан дважды: %d", p.got())
	}
	if p.take([]byte{0x45, 0, 0, 20}) {
		t.Fatal("чужой пакет поглощён")
	}
}
