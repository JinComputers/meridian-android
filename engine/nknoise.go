package engine

// Noise NK — проверка подлинности шлюза ДО отправки пароля, для путей БЕЗ
// DTLS (прямой UDP, релей через VK, TCP-поток). Формат — из документа
// Кота1 (MOBILNYJ-KLIENT-PROTOKOL.md, meridian-backup, коммит e6afeb4):
// Noise_NK_25519_AESGCM_SHA256, пролог пустой, версия протокола 0x01 первым
// байтом payload msg1. Здесь — ТОЛЬКО роль инициатора (клиент); роль
// ответчика (шлюз) — на сервере, не наша территория.
//
// БЕЗ СТОРОННЕЙ БИБЛИОТЕКИ NOISE. Нужен один паттерн из полутора десятков —
// тянуть библиотеку ради него дороже, чем полторы сотни строк, сверенных с
// тестовым вектором байт в байт (resolve.go рассуждает так же про DNS).
// Все примитивы — из уже имеющихся зависимостей: X25519 (crypto/ecdh,
// стандартная библиотека Go 1.27), AES-256-GCM (crypto/aes+crypto/cipher),
// SHA-256 (crypto/sha256). Новых пакетов не добавлено ни одного.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
)

const nkProtocolName = "Noise_NK_25519_AESGCM_SHA256"

// nkProtocolVersion — версия протокола, первый байт payload msg1.
const nkProtocolVersion byte = 0x01

// errGatewayNotAuthentic — ОБЩИЙ текст для обоих путей проверки (Noise NK
// без DTLS, отпечаток сертификата с DTLS): по документу это логически одно
// условие, разный текст ввёл бы разницу там, где её нет.
var errGatewayNotAuthentic = errors.New("сервер не подтвердил подлинность, пароль не отправлен")

// gatewayStaticPublicKey — открытый статический ключ шлюза (X25519).
// Публикуется намеренно: приватная половина только на сервере, и она одна
// решает подлинность. Запасного ключа НЕТ — решение владельца (документ):
// утечка рабочего чинится только новым выпуском клиента.
var gatewayStaticPublicKey *ecdh.PublicKey

// gatewayDTLSFingerprint — отпечаток (SHA-256 от DER) постоянного
// самоподписанного сертификата шлюза на DTLS-пути.
var gatewayDTLSFingerprint string

func init() {
	raw, err := base64.StdEncoding.DecodeString("ig5OlH1O6fxjUQoV894l2KZbDGhlB48yUl+8dIPAtBg=")
	if err != nil || len(raw) != 32 {
		panic("nknoise.go: неверный публичный ключ шлюза")
	}
	pub, err := ecdh.X25519().NewPublicKey(raw)
	if err != nil {
		panic("nknoise.go: публичный ключ шлюза не ложится на кривую: " + err.Error())
	}
	gatewayStaticPublicKey = pub

	const fp = "804897c03285a14294d9fa6cfd62df6554bd175ed84ea2e6d0777821f67c35a0"
	if len(fp) != 64 {
		panic("nknoise.go: неверная длина отпечатка сертификата шлюза")
	}
	if _, err := hex.DecodeString(fp); err != nil {
		panic("nknoise.go: отпечаток сертификата шлюза не hex: " + err.Error())
	}
	gatewayDTLSFingerprint = fp
}

// --- CipherState -------------------------------------------------------

type cipherState struct {
	key    [32]byte
	hasKey bool
	n      uint64
}

// nonceBytes — 4 нулевых байта + 8 байт big-endian счётчика, отдельно по
// документу (не общее правило Noise по умолчанию, а именно то, что
// проверено вектором cacophony у Кота1).
func nonceBytes(n uint64) [12]byte {
	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], n)
	return nonce
}

func (cs *cipherState) aead() (cipher.AEAD, error) {
	block, err := aes.NewCipher(cs.key[:])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// encrypt — EncryptWithAd. Без ключа отдаёт plaintext как есть (спецификация
// Noise, §5.1) — в NK-обмене к моменту первого вызова ключ уже есть всегда,
// ветка сохранена для точного соответствия спецификации, а не потому что
// нужна в этом конкретном паттерне.
func (cs *cipherState) encrypt(ad, plaintext []byte) ([]byte, error) {
	if !cs.hasKey {
		return append([]byte{}, plaintext...), nil
	}
	aead, err := cs.aead()
	if err != nil {
		return nil, err
	}
	nonce := nonceBytes(cs.n)
	cs.n++
	return aead.Seal(nil, nonce[:], plaintext, ad), nil
}

func (cs *cipherState) decrypt(ad, ciphertext []byte) ([]byte, error) {
	if !cs.hasKey {
		return append([]byte{}, ciphertext...), nil
	}
	aead, err := cs.aead()
	if err != nil {
		return nil, err
	}
	nonce := nonceBytes(cs.n)
	cs.n++
	return aead.Open(nil, nonce[:], ciphertext, ad)
}

// --- HKDF (Noise §4.3, НЕ RFC 5869 Expand с info) -----------------------

func hmacHash(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(data)
	return m.Sum(nil)
}

func hkdf2(chainingKey, ikm []byte) (out1, out2 [32]byte) {
	tempKey := hmacHash(chainingKey, ikm)
	o1 := hmacHash(tempKey, []byte{0x01})
	o2 := hmacHash(tempKey, append(append([]byte{}, o1...), 0x02))
	copy(out1[:], o1)
	copy(out2[:], o2)
	return
}

// --- SymmetricState ------------------------------------------------------

type symmetricState struct {
	cs cipherState
	ck [32]byte
	h  [32]byte
}

// newSymmetricState — InitializeSymmetric.
//
// ЛОВУШКА, НА КОТОРОЙ УЖЕ ОБОЖГЛИСЬ (документ Кота1): имя протокола длиной
// <= 32 байт НЕ хешируется, а дополняется нулями до 32 (спецификация Noise,
// §5.2). "Noise_NK_25519_AESGCM_SHA256" — 28 байт, попадает сюда. Длиннее
// 32 — было бы SHA-256 от имени; ветка держится для полноты, в этом
// протоколе не используется.
func newSymmetricState(protocolName string) *symmetricState {
	ss := &symmetricState{}
	name := []byte(protocolName)
	if len(name) <= 32 {
		copy(ss.h[:], name)
	} else {
		ss.h = sha256.Sum256(name)
	}
	ss.ck = ss.h
	return ss
}

func (ss *symmetricState) mixKey(ikm []byte) {
	ck, tempK := hkdf2(ss.ck[:], ikm)
	ss.ck = ck
	ss.cs = cipherState{key: tempK, hasKey: true, n: 0}
}

func (ss *symmetricState) mixHash(data []byte) {
	sum := sha256.Sum256(append(append([]byte{}, ss.h[:]...), data...))
	ss.h = sum
}

func (ss *symmetricState) encryptAndHash(plaintext []byte) ([]byte, error) {
	ct, err := ss.cs.encrypt(ss.h[:], plaintext)
	if err != nil {
		return nil, err
	}
	ss.mixHash(ct)
	return ct, nil
}

func (ss *symmetricState) decryptAndHash(ciphertext []byte) ([]byte, error) {
	pt, err := ss.cs.decrypt(ss.h[:], ciphertext)
	if err != nil {
		return nil, err
	}
	ss.mixHash(ciphertext)
	return pt, nil
}

func (ss *symmetricState) split() (send, recv cipherState) {
	k1, k2 := hkdf2(ss.ck[:], nil)
	return cipherState{key: k1, hasKey: true}, cipherState{key: k2, hasKey: true}
}

// --- NK, роль инициатора ------------------------------------------------

// nkInitiator — клиентская половина Noise_NK_25519_AESGCM_SHA256.
//
// Предсообщение NK — "<- s": инициатор уже знает статический ключ
// ответчика (шлюза) заранее, зашитым. Схема сообщений:
//
//	-> e, es   (msg1, наш)
//	<- e, ee   (msg2, шлюза)
type nkInitiator struct {
	ss    *symmetricState
	ePriv *ecdh.PrivateKey
	rs    *ecdh.PublicKey
}

func newNKInitiatorFrom(rs *ecdh.PublicKey, ePriv *ecdh.PrivateKey, prologue []byte) *nkInitiator {
	ss := newSymmetricState(nkProtocolName)
	ss.mixHash(prologue)   // пролог: пустой в бою (документ Кота1), непустой в тестовом векторе cacophony
	ss.mixHash(rs.Bytes()) // предсообщение "<- s"
	return &nkInitiator{ss: ss, ePriv: ePriv, rs: rs}
}

// newNKInitiator — рабочий конструктор для подключения к шлюзу: эфемерный
// ключ случайный, пролог пустой (документ Кота1).
func newNKInitiator(rs *ecdh.PublicKey) (*nkInitiator, error) {
	ePriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("генерация эфемерного ключа: %w", err)
	}
	return newNKInitiatorFrom(rs, ePriv, nil), nil
}

// newNKInitiatorWithEphemeral — ТОЛЬКО ДЛЯ ТЕСТА: фиксированный (не
// случайный) эфемерный ключ и явный пролог, нужны для сверки с вектором
// cacophony байт в байт (у него свой, непустой пролог, "John Galt" — не
// путать с боевым протоколом шлюза, там пролог пустой).
func newNKInitiatorWithEphemeral(rs, fixedEphemeralPriv, prologue []byte) (*nkInitiator, error) {
	rsPub, err := ecdh.X25519().NewPublicKey(rs)
	if err != nil {
		return nil, fmt.Errorf("публичный ключ rs: %w", err)
	}
	ePriv, err := ecdh.X25519().NewPrivateKey(fixedEphemeralPriv)
	if err != nil {
		return nil, fmt.Errorf("эфемерный ключ: %w", err)
	}
	return newNKInitiatorFrom(rsPub, ePriv, prologue), nil
}

// writeMsg1 — "-> e, es": сгенерировать/взять e, mixHash(e.pub), DH(e, rs)
// как "es", mixKey, зашифровать payload. Возвращает e.pub || ciphertext.
func (n *nkInitiator) writeMsg1(payload []byte) ([]byte, error) {
	ePub := n.ePriv.PublicKey().Bytes()
	n.ss.mixHash(ePub)

	es, err := n.ePriv.ECDH(n.rs)
	if err != nil {
		return nil, fmt.Errorf("es: %w", err)
	}
	n.ss.mixKey(es)

	ct, err := n.ss.encryptAndHash(payload)
	if err != nil {
		return nil, err
	}
	return append(append([]byte{}, ePub...), ct...), nil
}

// readMsg2 — "<- e, ee": re := msg[:32], mixHash(re), DH(e, re) как "ee",
// mixKey, расшифровать остаток как payload.
func (n *nkInitiator) readMsg2(msg []byte) ([]byte, error) {
	if len(msg) < 32 {
		return nil, errors.New("msg2 короче эфемерного ключа")
	}
	rePub := msg[:32]
	ct := msg[32:]

	re, err := ecdh.X25519().NewPublicKey(rePub)
	if err != nil {
		return nil, fmt.Errorf("эфемерный ключ шлюза: %w", err)
	}
	n.ss.mixHash(rePub)

	ee, err := n.ePriv.ECDH(re)
	if err != nil {
		return nil, fmt.Errorf("ee: %w", err)
	}
	n.ss.mixKey(ee)

	return n.ss.decryptAndHash(ct)
}

// split — завершение рукопожатия: пара шифров для транспортных сообщений.
// Первый (send) шифрует то, что посылает ИНИЦИАТОР (мы — msg3/AUTH);
// второй (recv) — то, что посылает ОТВЕТЧИК (шлюз — msg4/OK), см. документ
// и спецификацию Noise §5.2.
func (n *nkInitiator) split() (send, recv cipherState) {
	return n.ss.split()
}

// handshakeHash — итоговый h после msg2, для сверки с вектором cacophony.
func (n *nkInitiator) handshakeHash() [32]byte {
	return n.ss.h
}
