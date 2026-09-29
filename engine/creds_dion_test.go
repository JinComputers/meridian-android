package engine

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"net"
	"reflect"
	"testing"
	"time"
)

func TestParseDionSlugs(t *testing.T) {
	got := parseDionSlugs(" https://dion.vc/event/aaa-bbb?x=1, dion:ccc ;\nddd  aaa-bbb dion.vc/event/eee/ ")
	want := []string{"aaa-bbb", "ccc", "ddd", "eee"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("получили %v, ждали %v", got, want)
	}
	if len(parseDionSlugs("  , ; ")) != 0 {
		t.Fatal("пустой ввод должен давать пустой список")
	}
}

// DION отдаёт и UDP-, и TCP-адреса TURN: годятся только UDP без turns.
func TestTurnUDPAddr(t *testing.T) {
	cases := map[string]string{
		"turn:p2-turn-1001.dion.vc:3478":              "p2-turn-1001.dion.vc:3478",
		"turn:p2-turn-1001.dion.vc:443?transport=tcp": "",
		"turn:p2-turn-1001.dion.vc:443?transport=TCP": "",
		"turns:p2-turn-1001.dion.vc:5349":             "",
		"turn:p2-turn-1001.dion.vc":                   "",
		"stun:p2-turn-1001.dion.vc:3478":              "",
		"turn:1.2.3.4:3478?transport=udp":             "1.2.3.4:3478",
	}
	for in, want := range cases {
		if got := turnUDPAddr(in); got != want {
			t.Errorf("%q: получили %q, ждали %q", in, got, want)
		}
	}
}

// Кадры WS клиента читаются через пару net.Pipe: маска, длины 126 и 127,
// фрагментация, ping отвечается pong'ом.
func TestDionWSFrames(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	cli := &dionWS{c: a, br: bufio.NewReader(a)}

	// «Сервер» шлёт: ping, потом текст в двух фрагментах.
	go func() {
		w := &dionWS{c: b}
		_ = w.writeFrame(0x9, []byte("p"), time.Second)
		// сервер не маскирует: пишем кадр руками
		b.Write([]byte{0x01, 0x03, 'a', 'b', 'c'}) // текст, не финальный
		b.Write([]byte{0x80, 0x02, 'd', 'e'})      // продолжение, финальный
	}()
	// читаем pong, который клиент отправит на ping (маскированный, от клиента)
	pong := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 64)
		n, _ := b.Read(buf)
		pong <- buf[:n]
	}()

	msg, err := cli.readText(2 * time.Second)
	if err != nil || string(msg) != "abcde" {
		t.Fatalf("сообщение %q, ошибка %v", msg, err)
	}
	if p := <-pong; len(p) == 0 || p[0] != 0x8A {
		t.Fatalf("pong не отправлен: %v", p)
	}
}

func TestDionWSLongFrame(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	cli := &dionWS{c: a, br: bufio.NewReader(a)}
	payload := bytes.Repeat([]byte("x"), 70000)
	go func() {
		hdr := []byte{0x81, 127, 0, 0, 0, 0, 0, 0x01, 0x11, 0x70}
		b.Write(append(hdr, payload...))
	}()
	msg, err := cli.readText(2 * time.Second)
	if err != nil || len(msg) != len(payload) {
		t.Fatalf("длина %d, ошибка %v", len(msg), err)
	}
}

// id heartbeat длиннее 2^53: через float64 он искажается. Ответ обязан нести
// то же число.
func TestHeartbeatIDKeepsDigits(t *testing.T) {
	raw := []byte(`{"jsonrpc":"2.0","method":"server:notify:main:heartbeat","id":8712345678901234567}`)
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var m map[string]interface{}
	if err := dec.Decode(&m); err != nil {
		t.Fatal(err)
	}
	reply, _ := json.Marshal(map[string]interface{}{"jsonrpc": "2.0", "id": m["id"], "result": map[string]interface{}{}})
	if !bytes.Contains(reply, []byte(`"id":8712345678901234567`)) {
		t.Fatalf("id искажён: %s", reply)
	}
}

func TestDionClassify(t *testing.T) {
	if !errors.Is(dionClassify(&dionErr{code: 404, body: "not found"}), errDionDead) {
		t.Fatal("404 = комната не найдена")
	}
	if !errors.Is(dionClassify(&dionErr{code: 400, body: `{"error":"call_unavailable"}`}), errDionDead) {
		t.Fatal("call_unavailable = мертва")
	}
	if errors.Is(dionClassify(&dionErr{code: 500, body: "x"}), errDionDead) {
		t.Fatal("500 не должна считаться смертью комнаты")
	}
}
