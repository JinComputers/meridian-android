package engine

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"testing"
	"time"
)

// selfSignedDER выпускает самоподписанный сертификат и отдаёт его
// DER-байты — ровно то, что шлюз положил бы в rawCerts[0].
func selfSignedDER(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("ключ: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "gw"},
		NotBefore:    time.Unix(1000, 0),
		NotAfter:     time.Unix(1<<31, 0),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("сертификат: %v", err)
	}
	return der
}

// Совпадающий отпечаток принимается.
func TestPinnedVerifyAcceptsMatching(t *testing.T) {
	der := selfSignedDER(t)
	want := certFingerprint(der)
	verify := pinnedGatewayVerify([]string{want})
	if err := verify([][]byte{der}, nil); err != nil {
		t.Fatalf("верный отпечаток отвергнут: %v", err)
	}
}

// Чужой отпечаток отвергается. Без этой пары к предыдущему тесту
// «принимает верный» было бы неотличимо от «принимает всё».
func TestPinnedVerifyRejectsWrong(t *testing.T) {
	der := selfSignedDER(t)
	// Ждём отпечаток ДРУГОГО сертификата — пришедший не тот.
	other := selfSignedDER(t)
	verify := pinnedGatewayVerify([]string{certFingerprint(other)})
	if err := verify([][]byte{der}, nil); err == nil {
		t.Fatal("чужой отпечаток принят — сверка не работает")
	}
}

// Список отпечатков: принимается ЛЮБОЙ из него — ради чего список и
// заведён (плавная ротация сертификата: текущий и следующий).
func TestPinnedVerifyAcceptsAnyInList(t *testing.T) {
	a := selfSignedDER(t)
	b := selfSignedDER(t)
	list := []string{certFingerprint(a), certFingerprint(b)}
	verify := pinnedGatewayVerify(list)
	if err := verify([][]byte{a}, nil); err != nil {
		t.Fatalf("первый из списка отвергнут: %v", err)
	}
	if err := verify([][]byte{b}, nil); err != nil {
		t.Fatalf("второй из списка отвергнут: %v", err)
	}
	// А не входящий в список — по-прежнему отвергается.
	if err := verify([][]byte{selfSignedDER(t)}, nil); err == nil {
		t.Fatal("сертификат вне списка принят")
	}
}

// Пустой список сертификатов — отказ, а не паника и не молчаливый
// пропуск.
func TestPinnedVerifyRejectsEmpty(t *testing.T) {
	verify := pinnedGatewayVerify(gwCertFingerprints)
	if err := verify(nil, nil); err == nil {
		t.Fatal("пустой список сертификатов принят")
	}
}

// Мутация: если проверку подменить на «всегда nil», тест на чужой
// отпечаток обязан покраснеть. Здесь проверяем сам факт — что различие
// отпечатков ловится, а не тонет в равенстве по случайности.
func TestPinnedVerifyDistinguishes(t *testing.T) {
	a := selfSignedDER(t)
	b := selfSignedDER(t)
	if certFingerprint(a) == certFingerprint(b) {
		t.Fatal("два разных сертификата дали один отпечаток — так быть не может")
	}
}

// ТРЕТЬЯ ПРОВЕРКА ПО ТРЕБОВАНИЮ КОТА 2: сверка реально стоит на боевом
// пути. Проверяем ровно тот конфиг, что уходит в дозвон (gatewayTLSConfig):
// снятие VerifyPeerCertificate отсюда компилируется и туннель бы
// поднялся — этот тест единственное, что это поймает.
func TestGatewayTLSConfigPins(t *testing.T) {
	cfg := gatewayTLSConfig(tlsGatewaySNI)
	if cfg.VerifyPeerCertificate == nil {
		t.Fatal("сверка отпечатка снята с боевого пути — VerifyPeerCertificate nil")
	}
	if !cfg.InsecureSkipVerify {
		t.Fatal("InsecureSkipVerify=false — своя сверка не сработает, а чужая отвергнет самоподписанный")
	}
	if cfg.ServerName != tlsGatewaySNI {
		t.Fatalf("SNI боевого конфига %q, ждали %q", cfg.ServerName, tlsGatewaySNI)
	}
	// И боевая функция сверки обязана отвергать чужой отпечаток — иначе
	// «сверка стоит» ничего не значит.
	if err := cfg.VerifyPeerCertificate([][]byte{selfSignedDER(t)}, nil); err == nil {
		t.Fatal("боевая сверка приняла чужой сертификат")
	}
}
