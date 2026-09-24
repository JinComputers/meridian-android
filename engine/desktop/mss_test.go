package desktop

import (
	"bytes"
	"encoding/binary"
	"testing"
)

// tcpSum — контрольная сумма TCP с псевдозаголовком; у корректного пакета
// (вместе с полем контрольной суммы) итог сложения равен 0xffff.
func tcpSum(pkt []byte) uint16 {
	ihl := int(pkt[0]&0x0f) * 4
	total := int(binary.BigEndian.Uint16(pkt[2:4]))
	seg := pkt[ihl:total]
	var sum uint32
	add := func(b []byte) {
		for i := 0; i+1 < len(b); i += 2 {
			sum += uint32(binary.BigEndian.Uint16(b[i:]))
		}
		if len(b)%2 == 1 {
			sum += uint32(b[len(b)-1]) << 8
		}
	}
	add(pkt[12:20])
	sum += 6
	sum += uint32(len(seg))
	add(seg)
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return uint16(sum)
}

// mkTCP собирает IPv4+TCP пакет с ВЕРНОЙ контрольной суммой.
func mkTCP(flags byte, opts []byte, payload []byte) []byte {
	for len(opts)%4 != 0 {
		opts = append(opts, 0) // выравнивание концом списка
	}
	tcpLen := 20 + len(opts) + len(payload)
	p := make([]byte, 20+tcpLen)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
	p[8], p[9] = 64, 6
	copy(p[12:16], []byte{10, 77, 77, 5})
	copy(p[16:20], []byte{93, 184, 216, 34})
	t := p[20:]
	binary.BigEndian.PutUint16(t[0:], 51000)
	binary.BigEndian.PutUint16(t[2:], 443)
	t[12] = byte((20 + len(opts)) / 4 << 4)
	t[13] = flags
	binary.BigEndian.PutUint16(t[14:], 65535)
	copy(t[20:], opts)
	copy(t[20+len(opts):], payload)
	binary.BigEndian.PutUint16(t[16:], 0)
	binary.BigEndian.PutUint16(t[16:], ^tcpSum(p))
	return p
}

func mssOpt(v uint16) []byte { return []byte{2, 4, byte(v >> 8), byte(v)} }

func mssOf(p []byte) int {
	ihl := int(p[0]&0x0f) * 4
	t := p[ihl:]
	off := int(t[12]>>4) * 4
	for i := 20; i+3 < off; {
		switch t[i] {
		case 0:
			return -1
		case 1:
			i++
			continue
		}
		if t[i] == 2 {
			return int(binary.BigEndian.Uint16(t[i+2:]))
		}
		i += int(t[i+1])
	}
	return -1
}

const (
	synF    = 0x02
	synAckF = 0x12
	ackF    = 0x10
)

func TestClampMSS1460To1160AtMTU1200(t *testing.T) {
	p := mkTCP(synF, mssOpt(1460), nil)
	if tcpSum(p) != 0xffff {
		t.Fatal("сборщик пакета дал неверную контрольную сумму")
	}
	ipHdr := append([]byte(nil), p[:20]...)

	if !clampMSS(p, 1200-ipv4Overhead) {
		t.Fatal("SYN с MSS 1460 обязан быть изменён")
	}
	if got := mssOf(p); got != 1160 {
		t.Errorf("MSS = %d, хочу 1160", got)
	}
	if tcpSum(p) != 0xffff {
		t.Errorf("контрольная сумма TCP после кламп неверна: %#04x", tcpSum(p))
	}
	if !bytes.Equal(p[:20], ipHdr) {
		t.Error("заголовок IP тронут (контрольная сумма IP от TCP не зависит)")
	}
}

func TestClampMSSSynAckAndOtherOptions(t *testing.T) {
	// SYN-ACK; MSS не первой опцией, после NOP и window scale (3,3,7).
	opts := append([]byte{1, 1, 3, 3, 7}, mssOpt(1460)...)
	p := mkTCP(synAckF, opts, nil)
	if !clampMSS(p, 1060) || mssOf(p) != 1060 || tcpSum(p) != 0xffff {
		t.Errorf("SYN-ACK: mss=%d sum=%#04x", mssOf(p), tcpSum(p))
	}
}

func TestClampMSSLeavesAlone(t *testing.T) {
	limit := 1160
	cases := map[string][]byte{
		"MSS уже меньше":          mkTCP(synF, mssOpt(1000), nil),
		"MSS ровно равен пределу": mkTCP(synF, mssOpt(1160), nil),
		"SYN без опции MSS":       mkTCP(synF, []byte{3, 3, 7}, nil),
		"не SYN (данные)":         mkTCP(ackF, mssOpt(1460), []byte("hello")),
		"MSS длиной не 4":         mkTCP(synF, []byte{2, 3, 5, 180}, nil),
	}
	udp := mkTCP(synF, mssOpt(1460), nil)
	udp[9] = 17
	cases["не TCP (UDP)"] = udp
	icmp := mkTCP(synF, mssOpt(1460), nil)
	icmp[9] = 1
	cases["не TCP (ICMP)"] = icmp
	v6 := mkTCP(synF, mssOpt(1460), nil)
	v6[0] = 0x65
	cases["не IPv4"] = v6
	frag := mkTCP(synF, mssOpt(1460), nil)
	binary.BigEndian.PutUint16(frag[6:], 0x0010) // смещение фрагмента не ноль
	cases["фрагмент"] = frag
	mf := mkTCP(synF, mssOpt(1460), nil)
	binary.BigEndian.PutUint16(mf[6:], 0x2000) // MF
	cases["первый фрагмент (MF)"] = mf

	for name, p := range cases {
		orig := append([]byte(nil), p...)
		if clampMSS(p, limit) {
			t.Errorf("%s: пакет изменён", name)
		}
		if !bytes.Equal(p, orig) {
			t.Errorf("%s: байты изменились", name)
		}
	}
}

func TestClampMSSNeverPanicsOnGarbage(t *testing.T) {
	good := mkTCP(synF, append([]byte{1, 3, 3, 7}, mssOpt(1460)...), nil)
	// Все усечения и порча каждого байта: ни паники, ни выхода за границы.
	for n := 0; n <= len(good); n++ {
		clampMSS(append([]byte(nil), good[:n]...), 1160)
	}
	for i := range good {
		for _, v := range []byte{0x00, 0x01, 0x0f, 0x7f, 0xff} {
			p := append([]byte(nil), good...)
			p[i] = v
			clampMSS(p, 1160)
		}
	}
	if clampMSS(good, 100) {
		t.Error("предел ниже минимального MSS обязан отключать кламп")
	}
}

func TestClampFlowBothDirections(t *testing.T) {
	limit := currentMSSLimit() // без сессии MTU = rawTunMTU, предел 1060

	syn := mkTCP(synF, mssOpt(1460), nil)
	data := mkTCP(ackF, nil, []byte("x"))
	frame := func(p []byte) []byte {
		return append([]byte{byte(len(p) >> 8), byte(len(p))}, p...)
	}
	batch := append(frame(syn), frame(data)...)
	dataBefore := append([]byte(nil), data...)

	f := &recFlow{batches: [][]byte{batch}}
	cf := clampFlow{inner: f}
	got := cf.ReadBatch()

	a := got[2 : 2+len(syn)]
	b := got[2+len(syn)+2:]
	if mssOf(a) != limit || tcpSum(a) != 0xffff {
		t.Errorf("SYN из батча: mss=%d, хочу %d", mssOf(a), limit)
	}
	if !bytes.Equal(b, dataBefore) {
		t.Error("обычный пакет в батче тронут")
	}

	synack := mkTCP(synAckF, mssOpt(1460), nil)
	if !cf.WriteBatch(frame(synack)) {
		t.Fatal("WriteBatch вернул false")
	}
	w := f.written[0][2:]
	if mssOf(w) != limit || tcpSum(w) != 0xffff {
		t.Errorf("SYN-ACK вниз: mss=%d, хочу %d", mssOf(w), limit)
	}

	// Негодная разметка не трогается и не паникует.
	cf2 := clampFlow{inner: &recFlow{batches: [][]byte{{0, 9, 1, 2}}}}
	if out := cf2.ReadBatch(); len(out) != 4 {
		t.Errorf("негодный батч изменён: %v", out)
	}
	cf.Close()
	if !f.closed {
		t.Error("Close не делегируется")
	}
}

type recFlow struct {
	batches [][]byte
	written [][]byte
	closed  bool
}

func (f *recFlow) ReadBatch() []byte {
	if len(f.batches) == 0 {
		return nil
	}
	b := f.batches[0]
	f.batches = f.batches[1:]
	return b
}
func (f *recFlow) WriteBatch(b []byte) bool {
	f.written = append(f.written, append([]byte(nil), b...))
	return true
}
func (f *recFlow) Close() { f.closed = true }

// MSS по любому выравниванию (чётному и нечётному смещению в сегменте):
// контрольная сумма обязана остаться верной.
func TestClampMSSAllAlignments(t *testing.T) {
	for nops := 0; nops < 8; nops++ {
		opts := bytes.Repeat([]byte{1}, nops)
		opts = append(opts, mssOpt(1460)...)
		for _, flags := range []byte{synF, synAckF} {
			for _, payload := range [][]byte{nil, []byte("abc")} {
				p := mkTCP(flags, append([]byte(nil), opts...), payload)
				if !clampMSS(p, 1160) || mssOf(p) != 1160 || tcpSum(p) != 0xffff {
					t.Errorf("nops=%d flags=%#x payload=%d: mss=%d sum=%#04x",
						nops, flags, len(payload), mssOf(p), tcpSum(p))
				}
			}
		}
	}
}
