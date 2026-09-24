package desktop

import (
	"context"
	"testing"
	"time"

	"meridian/engine"
)

// fakeFlow — минимальный PacketFlow: сразу отдаёт nil (поток пуст/закрыт),
// насосу движка нечего читать, ждать не приходится.
type fakeFlow struct{}

func (fakeFlow) ReadBatch() []byte      { return nil }
func (fakeFlow) WriteBatch([]byte) bool { return false }
func (fakeFlow) Close()                 {}

type fakeProtector struct{}

func (fakeProtector) Protect(int32) bool { return true }

// TestStartClosesEventsOnConnectFailure — пустая лестница Connect() бракует
// сразу (engine.go, "лестница пуста: ни одной ступени"), без сети и без
// сроков. Проверяет саму сцепку: ошибка долетает наружу, канал событий
// закрывается ровно один раз, второй Start не виснет на панике "закрыто
// дважды".
//
// ПОЛНЫЙ live-путь (реальный Connect до шлюза, затем Stop/отмена ctx) этим
// тестом не покрыт: climb/race не подменяются фиктивной сетью нигде в
// движке, а поднимать настоящую сессию в юнит-тесте — значит зависеть от
// живого шлюза. Живой прогон — на стороне кота 5/кота 6 с настоящим TUN.
func TestStartClosesEventsOnConnectFailure(t *testing.T) {
	cfg := Config{
		Gateway:   "127.0.0.1",
		Ladder:    engine.NewLadder(), // пустая — Connect бракует сразу
		Password:  "test",
		DeviceID:  "test",
		Protector: fakeProtector{},
	}

	h, events, err := Start(context.Background(), cfg, fakeFlow{})
	if err == nil {
		t.Fatal("ожидал ошибку на пустой лестнице")
	}
	if h != nil {
		t.Error("Handle не должен возвращаться при ошибке Connect")
	}

	select {
	case _, ok := <-events:
		if ok {
			t.Error("канал событий не должен отдавать значений на этом пути")
		}
	case <-time.After(time.Second):
		t.Fatal("канал событий не закрылся за секунду")
	}
}

func TestCurrentStatusWithoutSession(t *testing.T) {
	// Без сессии — нулевые значения, без паники. Не гарантирует, что
	// СОВСЕМ нет активной сессии (Active — общий пакетный флаг), но саму
	// сцепку геттеров проверяет.
	s := CurrentStatus()
	if s.Winner != "" && s.Active {
		t.Skip("в процессе уже есть активная сессия — не изолированный запуск")
	}
}

func TestParseAssigned(t *testing.T) {
	info, err := parseAssigned("10.77.77.5/16")
	if err != nil {
		t.Fatal(err)
	}
	if info.IP != "10.77.77.5" || info.PrefixLen != 16 {
		t.Errorf("разбор: %+v", info)
	}
	for _, bad := range []string{"", "10.77.77.5", "10.77.77.5/x", "/16"} {
		if _, err := parseAssigned(bad); err == nil {
			t.Errorf("%q: ожидал ошибку", bad)
		}
	}
}

type recProtector struct{ excluded []string }

func (p *recProtector) Protect(int32) bool   { return true }
func (p *recProtector) ExcludeHost(h string) { p.excluded = append(p.excluded, h) }

func TestGuardedProtectorNeverExcludesTunnelDNS(t *testing.T) {
	inner := &recProtector{}
	g := newGuardedProtector(inner, DefaultTunnelDNS).(*guardedProtector)
	for _, h := range []string{"1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8", "62.76.231.231"} {
		g.ExcludeHost(h)
	}
	want := []string{"9.9.9.9", "77.88.8.8", "62.76.231.231"}
	if len(inner.excluded) != len(want) {
		t.Fatalf("дошло до платформы %v, хочу %v", inner.excluded, want)
	}
	for i := range want {
		if inner.excluded[i] != want[i] {
			t.Errorf("[%d] %s, хочу %s", i, inner.excluded[i], want[i])
		}
	}
	if !g.Protect(3) {
		t.Error("Protect обязан делегироваться")
	}
}

func TestGuardedProtectorCustomListReplacesDefault(t *testing.T) {
	inner := &recProtector{}
	g := newGuardedProtector(inner, []string{"10.77.77.1"}).(*guardedProtector)
	g.ExcludeHost("1.1.1.1") // не в списке клиента: доходит
	g.ExcludeHost("10.77.77.1")
	if len(inner.excluded) != 1 || inner.excluded[0] != "1.1.1.1" {
		t.Errorf("%v", inner.excluded)
	}
}

func TestGuardedProtectorNilAndMissingHooks(t *testing.T) {
	if newGuardedProtector(nil, nil) != nil {
		t.Error("nil Protector обязан остаться nil (движок сам обработает)")
	}
	g := newGuardedProtector(fakeProtector{}, nil).(*guardedProtector)
	g.ExcludeHost("1.2.3.4") // у fakeProtector метода нет: молча
	if _, err := g.LookupHost("x.example"); err == nil {
		t.Error("без LookupHost у клиента обёртка обязана вернуть ошибку (движок откатится на обычный резолв)")
	}
}
