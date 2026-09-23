package engine

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func hexBytes(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("hex %q: %v", s, err)
	}
	return b
}

// TestNKAgainstCacophonyVector — тот же вектор и то же имя теста, что у
// Кота1 на сервере (github.com/haskell-cryptography/cacophony,
// vectors/cacophony.txt, Noise_NK_25519_AESGCM_SHA256). Здесь проверяется
// ТОЛЬКО роль инициатора (клиент): msg1 на отправку, msg2 на приём,
// handshake_hash после рукопожатия, и четыре транспортных сообщения через
// Split() — ровно то, что использует настоящий клиент (writeMsg1 для
// AUTH, readMsg2/чтение OK), без нужды реализовывать роль ответчика.
func TestNKAgainstCacophonyVector(t *testing.T) {
	// Пролог ЭТОГО вектора — "John Galt", НЕ пустой (пустой пролог — только
	// у боевого протокола с настоящим шлюзом, см. newNKInitiator).
	prologue := hexBytes(t, "4a6f686e2047616c74")
	initEphemeral := hexBytes(t, "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
	initRemoteStatic := hexBytes(t, "31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62")
	wantHandshakeHash := hexBytes(t, "f8a87aa8add4fea6e33365b89637486c2f6564546ce29d1df9ce9abf78c507d7")

	msg1Payload := hexBytes(t, "4c756477696720766f6e204d69736573")
	msg1Cipher := hexBytes(t, "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c794475ab4d66d222457dd414bc5f296bc7b4078cc7d72af5192628b68bca7d28844b")
	// Payload msg2 в ЭТОМ векторе НЕ пуст ("Murray Rothbard") — генерик-вектор
	// cacophony, не боевой протокол шлюза (там msg2 пуст, документ Кота1).
	msg2Payload := hexBytes(t, "4d757272617920526f746862617264")
	msg2Cipher := hexBytes(t, "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884303c7d89310502baa8299520ba451624c3c0492e2698f8d457c32400b91fd8a")

	msg3Payload := hexBytes(t, "462e20412e20486179656b")
	msg3Cipher := hexBytes(t, "304f70c37c93573099228016d54cb15213af94eb598d1b17df1153")
	msg4Payload := hexBytes(t, "4361726c204d656e676572")
	msg4Cipher := hexBytes(t, "a1bf6c954529f29b31d8ae9f67d2c18dbd332aa1a0918690c6d80b")

	msg5Payload := hexBytes(t, "4a65616e2d426170746973746520536179")
	msg5Cipher := hexBytes(t, "2e8f3e51888360b2b2d83a64dde9943c7dd3c5e84ac7c4b4e2d5cfc025b6c854d3")
	msg6Payload := hexBytes(t, "457567656e2042f6686d20766f6e2042617765726b")
	msg6Cipher := hexBytes(t, "8498bf41212a8b87c9eeb408274c75b3558fd0530865b5a7932d4b3af812d85b3df27e6f33")

	ni, err := newNKInitiatorWithEphemeral(initRemoteStatic, initEphemeral, prologue)
	if err != nil {
		t.Fatalf("newNKInitiatorWithEphemeral: %v", err)
	}

	msg1, err := ni.writeMsg1(msg1Payload)
	if err != nil {
		t.Fatalf("writeMsg1: %v", err)
	}
	// msg1 = e.pub(32) || ciphertext. Наш вектор даёт весь ciphertext
	// сообщения (включая обёрнутый e.pub, как отправляется на проводе).
	if !bytes.Equal(msg1, msg1Cipher) {
		t.Fatalf("msg1 разошёлся с вектором:\nполучено %x\nхочу     %x", msg1, msg1Cipher)
	}

	payload2, err := ni.readMsg2(msg2Cipher)
	if err != nil {
		t.Fatalf("readMsg2: %v", err)
	}
	if !bytes.Equal(payload2, msg2Payload) {
		t.Fatalf("payload msg2 разошёлся:\nполучено %x\nхочу     %x", payload2, msg2Payload)
	}

	gotHash := ni.handshakeHash()
	if !bytes.Equal(gotHash[:], wantHandshakeHash) {
		t.Fatalf("handshake_hash разошёлся:\nполучено %x\nхочу     %x", gotHash, wantHandshakeHash)
	}

	send, recv := ni.split()

	gotMsg3, err := send.encrypt(nil, msg3Payload)
	if err != nil {
		t.Fatalf("msg3 encrypt: %v", err)
	}
	if !bytes.Equal(gotMsg3, msg3Cipher) {
		t.Fatalf("msg3 разошёлся:\nполучено %x\nхочу     %x", gotMsg3, msg3Cipher)
	}

	gotPayload4, err := recv.decrypt(nil, msg4Cipher)
	if err != nil {
		t.Fatalf("msg4 decrypt: %v", err)
	}
	if !bytes.Equal(gotPayload4, msg4Payload) {
		t.Fatalf("payload msg4 разошёлся:\nполучено %x\nхочу     %x", gotPayload4, msg4Payload)
	}

	// Второй проход в каждую сторону — счётчик нонса обязан продолжиться
	// (0 уже израсходован msg3/msg4 выше), а не застрять на нуле.
	gotMsg5, err := send.encrypt(nil, msg5Payload)
	if err != nil {
		t.Fatalf("msg5 encrypt: %v", err)
	}
	if !bytes.Equal(gotMsg5, msg5Cipher) {
		t.Fatalf("msg5 разошёлся (счётчик нонса не продолжился?):\nполучено %x\nхочу     %x", gotMsg5, msg5Cipher)
	}

	gotPayload6, err := recv.decrypt(nil, msg6Cipher)
	if err != nil {
		t.Fatalf("msg6 decrypt: %v", err)
	}
	if !bytes.Equal(gotPayload6, msg6Payload) {
		t.Fatalf("payload msg6 разошёлся:\nполучено %x\nхочу     %x", gotPayload6, msg6Payload)
	}
}

func TestSymmetricStateProtocolNamePadding(t *testing.T) {
	// "Noise_NK_25519_AESGCM_SHA256" — 28 байт, <= 32: дополняется нулями,
	// НЕ хешируется. Ровно та ловушка, о которой предупредил Кот1.
	ss := newSymmetricState(nkProtocolName)
	var want [32]byte
	copy(want[:], nkProtocolName)
	if ss.h != want {
		t.Fatalf("h = %x, хочу дополненное нулями имя протокола %x", ss.h, want)
	}
	if ss.ck != ss.h {
		t.Fatalf("ck должен равняться h сразу после инициализации")
	}
}

func TestSymmetricStateLongNameIsHashed(t *testing.T) {
	long := "Noise_XXfallback+psk0_448_ChaChaPoly_BLAKE2b_ЭТО_ИМЯ_ТОЧНО_ДЛИННЕЕ_32_БАЙТ"
	if len(long) <= 32 {
		t.Fatalf("тестовое имя короче 32 байт, тест ничего не проверяет")
	}
	ss := newSymmetricState(long)
	wantSum := sha256.Sum256([]byte(long))
	if ss.h != wantSum {
		t.Fatalf("h = %x, хочу sha256(имя) = %x", ss.h, wantSum)
	}
}
