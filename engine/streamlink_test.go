package engine

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

// chunkConn — net.Conn, отдающий на чтение заранее заданные КУСКИ, по
// одному на вызов Read, и записывающий всё, что в него пишут отдельными
// вызовами. Так проверяется и «два кадра в одном сегменте» (оба в одном
// куске), и «кадр разорван между сегментами» (один кадр в двух кусках).
type chunkConn struct {
	readChunks [][]byte
	writes     [][]byte
}

func (c *chunkConn) Read(p []byte) (int, error) {
	if len(c.readChunks) == 0 {
		return 0, net.ErrClosed
	}
	ch := c.readChunks[0]
	n := copy(p, ch)
	if n < len(ch) {
		c.readChunks[0] = ch[n:]
	} else {
		c.readChunks = c.readChunks[1:]
	}
	return n, nil
}

func (c *chunkConn) Write(p []byte) (int, error) {
	cp := make([]byte, len(p))
	copy(cp, p)
	c.writes = append(c.writes, cp)
	return len(p), nil
}

func (c *chunkConn) Close() error                       { return nil }
func (c *chunkConn) LocalAddr() net.Addr                { return nil }
func (c *chunkConn) RemoteAddr() net.Addr               { return nil }
func (c *chunkConn) SetDeadline(t time.Time) error      { return nil }
func (c *chunkConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *chunkConn) SetWriteDeadline(t time.Time) error { return nil }

// testState — obfsState с известным ключом. Для приёма и передачи можно
// брать один: unwrap выводит нонс из заголовка кадра, а не из своего
// счётчика, поэтому не зависит от того, кто паковал.
func testState(t *testing.T) *obfsState {
	t.Helper()
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i + 1)
	}
	st, err := newObfsState(key)
	if err != nil {
		t.Fatalf("obfsState: %v", err)
	}
	return st
}

// frameOf упаковывает payload тем же wrap и оборачивает в кадр — то, что
// прислал бы шлюз.
func frameOf(t *testing.T, st *obfsState, payload []byte) []byte {
	t.Helper()
	wire, err := st.wrap(payload)
	if err != nil {
		t.Fatalf("wrap: %v", err)
	}
	frame := make([]byte, 2+len(wire))
	binary.BigEndian.PutUint16(frame[:2], uint16(len(wire)))
	copy(frame[2:], wire)
	return frame
}

// Круговой проход: Write кладёт в поток кадр, Read его оттуда достаёт.
func TestStreamRoundtrip(t *testing.T) {
	st := testState(t)
	conn := &chunkConn{}
	link := newStreamLink(conn, st, nil)

	payload := []byte("привет, шлюз")
	if _, err := link.Write(payload); err != nil {
		t.Fatalf("Write: %v", err)
	}
	// Один Write на кадр — ровно один вызов conn.Write.
	if len(conn.writes) != 1 {
		t.Fatalf("ожидал 1 вызов conn.Write, вышло %d", len(conn.writes))
	}
	// Прочитаем то, что записали.
	conn.readChunks = [][]byte{conn.writes[0]}
	buf := make([]byte, 2048)
	n, err := link.Read(buf)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if string(buf[:n]) != string(payload) {
		t.Fatalf("получили %q, ждали %q", buf[:n], payload)
	}
}

// Два кадра пришли ОДНИМ сегментом. Два Read обязаны вернуть каждую
// нагрузку по отдельности.
func TestStreamTwoFramesOneSegment(t *testing.T) {
	st := testState(t)
	a := frameOf(t, st, []byte("первый"))
	b := frameOf(t, st, []byte("второй"))
	one := append(append([]byte{}, a...), b...)

	conn := &chunkConn{readChunks: [][]byte{one}}
	link := newStreamLink(conn, st, nil)
	buf := make([]byte, 2048)

	n, err := link.Read(buf)
	if err != nil || string(buf[:n]) != "первый" {
		t.Fatalf("первый кадр: n=%d err=%v got=%q", n, err, buf[:n])
	}
	n, err = link.Read(buf)
	if err != nil || string(buf[:n]) != "второй" {
		t.Fatalf("второй кадр: n=%d err=%v got=%q", n, err, buf[:n])
	}
}

// Один кадр РАЗОРВАН между двумя сегментами. Read обязан его собрать.
func TestStreamFrameSplitAcrossSegments(t *testing.T) {
	st := testState(t)
	frame := frameOf(t, st, []byte("разорванный кадр"))
	// Рвём в трёх местах: посреди длины, на границе длины/тела, посреди
	// тела — каждое проверяет свой io.ReadFull.
	for _, cut := range []int{1, 2, 2 + 5, len(frame) - 1} {
		conn := &chunkConn{readChunks: [][]byte{frame[:cut], frame[cut:]}}
		link := newStreamLink(conn, st, nil)
		buf := make([]byte, 2048)
		n, err := link.Read(buf)
		if err != nil || string(buf[:n]) != "разорванный кадр" {
			t.Fatalf("разрыв в %d: n=%d err=%v got=%q", cut, n, err, buf[:n])
		}
	}
}

// Битый кадр (верной длины, но не проходит AEAD) СОЕДИНЕНИЕ НЕ РВЁТ:
// Read его пропускает, считает и возвращает следующий, годный.
func TestStreamBadFrameContinues(t *testing.T) {
	st := testState(t)
	good := frameOf(t, st, []byte("годный"))

	// Битый кадр: верный префикс длины, но тело — мусор той же длины.
	bad := make([]byte, len(good))
	copy(bad, good)
	for i := 2; i < len(bad); i++ {
		bad[i] ^= 0xFF
	}

	conn := &chunkConn{readChunks: [][]byte{append(append([]byte{}, bad...), good...)}}
	link := newStreamLink(conn, st, nil)
	buf := make([]byte, 2048)

	n, err := link.Read(buf)
	if err != nil || string(buf[:n]) != "годный" {
		t.Fatalf("после битого ждал годный: n=%d err=%v got=%q", n, err, buf[:n])
	}
	if link.dropped.Load() != 1 {
		t.Fatalf("битый кадр не сосчитан: dropped=%d", link.dropped.Load())
	}
}

// Структурная поломка — нулевая длина и длина больше потолка — рвёт
// поток ошибкой, а не молчанием.
func TestStreamStructuralErrors(t *testing.T) {
	st := testState(t)

	zero := []byte{0x00, 0x00}
	conn := &chunkConn{readChunks: [][]byte{zero}}
	if _, err := newStreamLink(conn, st, nil).Read(make([]byte, 64)); err == nil {
		t.Fatal("нулевая длина принята как кадр")
	}

	tooBig := []byte{0xFF, 0xFF} // 65535 > tcpFrameMax
	conn = &chunkConn{readChunks: [][]byte{tooBig}}
	if _, err := newStreamLink(conn, st, nil).Read(make([]byte, 64)); err == nil {
		t.Fatal("длина больше потолка принята")
	}
}
