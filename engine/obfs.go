package engine

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	obfsHeaderLen   = 12
	obfsPayloadType = 111 // audio; video было бы 96
	obfsPaddingMax  = 24  // audio; video было бы 60
	obfsTagLen      = 16
)

// obfsState — состояние обфускации на одно направление одной сессии.
//
// ВНИМАНИЕ. Формулы seq и ts переносятся из роутерного клиента ДОСЛОВНО.
// Слагаемое (c >> 16) в ts выглядит косметикой, но именно оно отодвигает
// повтор нонса с ~2^26 пакетов до ~2^48. Убрать его — значит получить
// повторное использование нонса ChaCha20-Poly1305 примерно через сотню
// гигабайт трафика в одной сессии, без единого внешнего симптома.
type obfsState struct {
	mu      sync.Mutex
	aead    cipher.AEAD
	ssrc    uint32
	initSeq uint16
	initTs  uint32
	count   uint64
}

func newObfsState(key []byte) (*obfsState, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	var b [10]byte
	if _, err := rand.Read(b[:]); err != nil {
		return nil, err
	}
	return &obfsState{
		aead:    aead,
		ssrc:    binary.BigEndian.Uint32(b[0:4]),
		initSeq: binary.BigEndian.Uint16(b[4:6]),
		initTs:  binary.BigEndian.Uint32(b[6:10]),
	}, nil
}

// obfsNonce собирает 12-байтный нонс.
// Порядок полей ОТЛИЧАЕТСЯ от порядка в самом RTP-заголовке:
// в заголовке seq, ts, ssrc — в нонсе ssrc, seq, два нуля, ts.
func obfsNonce(n *[12]byte, ssrc uint32, seq uint16, ts uint32) {
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	n[6], n[7] = 0, 0
	binary.BigEndian.PutUint32(n[8:12], ts)
}

// wrap упаковывает полезную нагрузку в вид, неотличимый от RTP-потока.
//
// Итоговый пакет: [12Б заголовок][шифротекст+тег][набивка][1Б длина набивки]
// Мьютекс держится на ВСЮ работу, а не только на инкремент счётчика:
// иначе два вызова, взяв соседние номера, дальше идут вперемешку.
// Порядок ухода пакетов в сокет это всё равно не гарантирует — за него
// отвечает отдельный мьютекс записи в obfsPacketConn.WriteTo.
func (s *obfsState) wrap(payload []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	c := s.count
	s.count++

	seq := s.initSeq + uint16(c)
	ts := s.initTs + uint32(c)*960 + uint32(c>>16)

	// Длина набивки берётся как остаток от деления случайного байта.
	// Распределение неравномерное — и это НАМЕРЕННО перенесено как есть:
	// два клиента к одному шлюзу с разными распределениями набивки дают
	// два различимых профиля вместо одного.
	var padByte [1]byte
	if _, err := rand.Read(padByte[:]); err != nil {
		return nil, err
	}
	padTotal := int(padByte[0])%obfsPaddingMax + 1

	hdr := make([]byte, obfsHeaderLen)
	hdr[0] = 0x80 | 0x20 // V=2, P=1 (набивка есть), X=0, CC=0
	hdr[1] = obfsPayloadType & 0x7F
	binary.BigEndian.PutUint16(hdr[2:4], seq)
	binary.BigEndian.PutUint32(hdr[4:8], ts)
	binary.BigEndian.PutUint32(hdr[8:12], s.ssrc)

	var nonce [12]byte
	obfsNonce(&nonce, s.ssrc, seq, ts)

	out := make([]byte, 0, obfsHeaderLen+len(payload)+obfsTagLen+padTotal)
	out = append(out, hdr...)
	// AAD — сам заголовок: он аутентифицируется, но не шифруется,
	// чтобы DPI видел снаружи обычный RTP.
	out = s.aead.Seal(out, nonce[:], payload, hdr)

	pad := make([]byte, padTotal)
	if padTotal > 1 {
		if _, err := rand.Read(pad[:padTotal-1]); err != nil {
			return nil, err
		}
	}
	pad[padTotal-1] = byte(padTotal)
	out = append(out, pad...)
	return out, nil
}

// unwrap разбирает встречный пакет. Возвращает длину и признак успеха;
// чужой или битый пакет — это не ошибка, а повод молча его выбросить.
func (s *obfsState) unwrap(wire []byte, dst []byte) (int, bool) {
	if len(wire) < obfsHeaderLen+1 {
		return 0, false
	}
	if wire[0]>>6 != 2 { // версия RTP
		return 0, false
	}

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 { // бит P — есть набивка
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > len(wire)-obfsHeaderLen {
			return 0, false
		}
		payloadEnd = len(wire) - padLen
	}
	if payloadEnd < obfsHeaderLen+obfsTagLen {
		return 0, false
	}

	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	var nonce [12]byte
	obfsNonce(&nonce, ssrc, seq, ts)

	plain, err := s.aead.Open(nil, nonce[:], wire[obfsHeaderLen:payloadEnd], wire[:obfsHeaderLen])
	if err != nil {
		return 0, false
	}
	if len(plain) > len(dst) {
		return 0, false
	}
	copy(dst, plain)
	return len(plain), true
}
