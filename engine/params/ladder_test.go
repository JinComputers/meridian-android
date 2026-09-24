package params

import "testing"

func liveAnswer(t *testing.T) *Answer {
	t.Helper()
	a, err := ParseAnswer([]byte(liveAnonymous))
	if err != nil {
		t.Fatal(err)
	}
	return a
}

// Живой ответ по ключу (24.09) с потоковыми ступенями.
const liveKeyed = `{"status":"answered","generation":3,"ttl":21600,"entries":[{"id":"entry-1","host":"62.76.231.231","port":443,"dtls":true},{"id":"entry-2","host":"62.76.231.231","port":56004,"dtls":true},{"id":"entry-3","host":"62.76.231.231","port":56005,"dtls":false},{"id":"entry-c1","host":"203.0.113.41","port":443,"dtls":true},{"id":"entry-c2","host":"203.0.113.41","port":56004,"dtls":true},{"id":"entry-c3","host":"203.0.113.41","port":56005,"dtls":false},{"id":"entry-tcp","host":"62.76.231.231","port":56004,"dtls":false,"transport":"tcp"},{"id":"entry-tls","host":"62.76.231.231","port":443,"dtls":false,"transport":"tls","sni":"gw.62-76-231-231.sslip.io"}],"relay":{"enabled":true,"target":"138.124.78.252"},"ladder":{"direct_probe_every_attempts":4,"direct_skip_after_fails":5,"second_round_ms":3000},"contract":"v1"}`

func TestBuildLadderLiveAnonymous(t *testing.T) {
	l, info, err := BuildLadder(liveAnswer(t), LadderOptions{Hashes: []string{"h1", "h2"}})
	if err != nil {
		t.Fatal(err)
	}
	// 6 прямых + релей на relay.target + релей-откат на адрес из edge.
	if l.Len() != 8 {
		t.Errorf("ступеней %d, хочу 8", l.Len())
	}
	if info.RelayTarget != "ok" || info.NoHashes {
		t.Errorf("info: %+v", info)
	}
	if info.Letters["entry-1"] != "A" || info.Letters["entry-c3"] != "F" || info.IDs["C"] != "entry-3" {
		t.Errorf("буквы: %v / %v", info.Letters, info.IDs)
	}
	if len(info.Streams) != 0 {
		t.Errorf("в анонимном ответе потоков нет: %v", info.Streams)
	}
	if info.Slots < 1 || info.Slots > 4 {
		t.Errorf("слоты по умолчанию min(4, ядер): %d", info.Slots)
	}
}

func TestBuildLadderKeyedStreamsAndKnown(t *testing.T) {
	a, err := ParseAnswer([]byte(liveKeyed))
	if err != nil {
		t.Fatal(err)
	}
	l, info, err := BuildLadder(a, LadderOptions{Known: "entry-3", NumCPU: 8})
	if err != nil {
		t.Fatal(err)
	}
	if l.Len() != 10 { // 8 узлов + 2 релейных
		t.Errorf("ступеней %d, хочу 10", l.Len())
	}
	if !info.Streams["G"] || !info.Streams["H"] || info.IDs["G"] != "entry-tcp" || info.IDs["H"] != "entry-tls" {
		t.Errorf("потоки: %v %v", info.Streams, info.IDs)
	}
	// Буква — свойство узла, а не места: запомненный entry-3 встаёт
	// первым, но остаётся C.
	if info.Letters["entry-3"] != "C" {
		t.Errorf("буква запомненного сменилась: %v", info.Letters)
	}
	if info.Slots != 4 {
		t.Errorf("слоты: %d, хочу min(4, 8 ядер) = 4", info.Slots)
	}
	if !info.NoHashes {
		t.Error("хешей не дали — NoHashes обязан быть true")
	}
}

func TestBuildLadderSkipFlags(t *testing.T) {
	a, _ := ParseAnswer([]byte(liveKeyed))

	l, _, err := BuildLadder(a, LadderOptions{SkipDirect: true, Hashes: []string{"h"}})
	if err != nil {
		t.Fatal(err)
	}
	if l.Len() != 4 { // tcp + tls + 2 релейных
		t.Errorf("SkipDirect: %d, хочу 4", l.Len())
	}

	l, _, _ = BuildLadder(a, LadderOptions{SkipDirect: true, SkipStreams: true, Hashes: []string{"h"}})
	if l.Len() != 2 { // остаётся один релей (с откатом)
		t.Errorf("оба пропуска: %d, хочу 2", l.Len())
	}
}

func TestBuildLadderModes(t *testing.T) {
	a := liveAnswer(t)
	l, _, err := BuildLadder(a, LadderOptions{Mode: ModeDirectOnly})
	if err != nil || l.Len() != 6 {
		t.Errorf("DirectOnly: len=%d err=%v", l.Len(), err)
	}
	l, _, err = BuildLadder(a, LadderOptions{Mode: ModeRelayOnly, Hashes: []string{"h"}})
	if err != nil || l.Len() != 2 {
		t.Errorf("RelayOnly: len=%d err=%v", l.Len(), err)
	}
}

func TestBuildLadderRelayDisabledAndEmpty(t *testing.T) {
	a, _ := ParseAnswer([]byte(`{"entries":[{"host":"1.2.3.4","port":443,"dtls":true}],"relay":{"enabled":false}}`))
	l, _, err := BuildLadder(a, LadderOptions{})
	if err != nil || l.Len() != 1 {
		t.Errorf("relay.enabled=false: len=%d err=%v", l.Len(), err)
	}
	// Всё выключено — пустая лестница обязана быть ошибкой, не тишиной.
	if _, _, err := BuildLadder(a, LadderOptions{Mode: ModeRelayOnly}); err != ErrEmptyLadder {
		t.Errorf("err=%v, хочу ErrEmptyLadder", err)
	}
	if _, _, err := BuildLadder(nil, LadderOptions{}); err == nil {
		t.Error("nil-ответ обязан давать ошибку")
	}
}

func TestBuildLadderRelayTargetInvalidAndUnknownTransport(t *testing.T) {
	a, _ := ParseAnswer([]byte(`{"entries":[{"host":"1.2.3.4","port":443,"transport":"quic"},{"id":"u","host":"1.2.3.4","port":443}],"relay":{"enabled":true,"target":"edge.example.org"}}`))
	l, info, err := BuildLadder(a, LadderOptions{Hashes: []string{"h"}})
	if err != nil {
		t.Fatal(err)
	}
	if info.RelayTarget != "invalid" {
		t.Errorf("RelayTarget=%q, хочу invalid", info.RelayTarget)
	}
	if len(info.Skipped) != 1 {
		t.Errorf("незнакомый транспорт обязан пропускаться вслух: %v", info.Skipped)
	}
	if l.Len() != 2 { // один udp + один релей без отката (target негоден)
		t.Errorf("ступеней %d, хочу 2", l.Len())
	}
}

func TestParseHashes(t *testing.T) {
	got := ParseHashes("https://vk.com/call/join/AbC123?x=1#y, xyz;\nAbC123\thttps://vk.ru/call/join/Q9/extra https://vk.com/other")
	want := []string{"AbC123", "xyz", "Q9"}
	if len(got) != len(want) {
		t.Fatalf("%v, хочу %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("[%d] %q, хочу %q", i, got[i], want[i])
		}
	}
	if NormalizeHash("") != "" || NormalizeHash("http://x.y/nope") != "" {
		t.Error("пустое и чужая ссылка не хеш")
	}
}

func TestBuildLadderTURNBackupOptions(t *testing.T) {
	a := liveAnswer(t)
	for _, o := range []LadderOptions{
		{Hashes: []string{"h"}},                             // умолчание DefaultTURNBackup
		{Hashes: []string{"h"}, TURNBackup: "1.2.3.4:3478"}, // свой
		{Hashes: []string{"h"}, NoTURNBackup: true},         // без запасного
	} {
		l, _, err := BuildLadder(a, o)
		if err != nil || l.Len() != 8 {
			t.Errorf("%+v: len=%d err=%v", o, l.Len(), err)
		}
	}
	if DefaultTURNBackup != "95.163.34.180:19302" {
		t.Errorf("DefaultTURNBackup разошёлся с TURN_ADDR_BACKUP Android: %s", DefaultTURNBackup)
	}
}
