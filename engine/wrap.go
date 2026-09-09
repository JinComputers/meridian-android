package engine

import (
	"crypto/sha256"
	"io"

	"golang.org/x/crypto/hkdf"
)

// wrapKeyLen — длина ключа ChaCha20-Poly1305.
const wrapKeyLen = 32

// deriveWrapKey повторяет вывод ключа роутерного клиента дословно.
// Соль и info — константы протокола, менять нельзя: обе стороны
// выводят ключ независимо и должны получить один и тот же.
func deriveWrapKey(password string) ([]byte, error) {
	key := make([]byte, wrapKeyLen)
	r := hkdf.New(
		sha256.New,
		[]byte(password),
		[]byte("WDTT-WRAP-v1"),
		[]byte("rtp-obfs/chacha20poly1305"),
	)
	if _, err := io.ReadFull(r, key); err != nil {
		return nil, err
	}
	return key, nil
}
