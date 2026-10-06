package engine

import (
	"context"
	"net"
	"testing"
)

type excludingProtector struct {
	protected int
	excluded  []string
}

func (p *excludingProtector) Protect(int32) bool { p.protected++; return true }
func (p *excludingProtector) ExcludeHost(h string) {
	p.excluded = append(p.excluded, h)
}

type plainProtector struct{ protected int }

func (p *plainProtector) Protect(int32) bool { p.protected++; return true }

func TestProtectDialControlExcludesDestination(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		if c, err := ln.Accept(); err == nil {
			c.Close()
		}
	}()

	p := &excludingProtector{}
	d := &net.Dialer{Control: protectDialControl(p, func(string, ...interface{}) {}, "t")}
	c, err := d.DialContext(context.Background(), "tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()

	if p.protected != 1 {
		t.Errorf("Protect вызван %d раз, хочу 1", p.protected)
	}
	if len(p.excluded) != 1 || p.excluded[0] != "127.0.0.1" {
		t.Errorf("ExcludeHost получил %v, хочу [127.0.0.1] (адрес назначения, не локальный)", p.excluded)
	}
}

func TestProtectDialControlWorksWithoutExcluder(t *testing.T) {
	// Protector без ExcludeHost (Android/iOS): вызов молча не происходит,
	// Protect работает как раньше.
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		if c, err := ln.Accept(); err == nil {
			c.Close()
		}
	}()
	p := &plainProtector{}
	d := &net.Dialer{Control: protectDialControl(p, func(string, ...interface{}) {}, "t")}
	c, err := d.DialContext(context.Background(), "tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if p.protected != 1 {
		t.Errorf("Protect вызван %d раз", p.protected)
	}
}

func TestExcludeHostFilters(t *testing.T) {
	p := &excludingProtector{}
	for _, h := range []string{"", "edge.example.org", "0.0.0.0", "62.76.231.231"} {
		excludeHost(p, h)
	}
	if len(p.excluded) != 1 || p.excluded[0] != "62.76.231.231" {
		t.Errorf("передаются только IP-адреса: %v", p.excluded)
	}
	excludeHost(nil, "1.2.3.4") // не паникует
}

type refusingProtector struct{ excluded []string }

func (p *refusingProtector) Protect(int32) bool { return false } // сокет не открывается, сеть не трогается
func (p *refusingProtector) ExcludeHost(h string) {
	p.excluded = append(p.excluded, h)
}

// TestDoHAddressesReachExcludeHost — какие адреса от DoH уходят в
// ExcludeHost: все четыре резолвера. Два из них (1.1.1.1, 8.8.8.8) совпадают
// с DNS туннеля Android, поэтому desktop.Start не пускает их дальше (см.
// desktop/guard.go). Protect возвращает false: до сети дело не доходит.
func TestDoHAddressesReachExcludeHost(t *testing.T) {
	p := &refusingProtector{}
	if _, ok := viaDoH("edge.example.org", p, func(string, ...interface{}) {}); ok {
		t.Fatal("DoH не должен был получить ответ")
	}
	got := map[string]bool{}
	for _, h := range p.excluded {
		got[h] = true
	}
	for _, want := range []string{"1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8"} {
		if !got[want] {
			t.Errorf("DoH не назвал %s в ExcludeHost: %v", want, p.excluded)
		}
	}
	if len(got) != 4 {
		t.Errorf("ожидал ровно 4 адреса DoH, получил %v", p.excluded)
	}
}

type lookupProtector struct {
	plainProtector
	table map[string][]string
	calls []string
}

func (p *lookupProtector) LookupHost(h string) ([]string, error) {
	p.calls = append(p.calls, h)
	if ips, ok := p.table[h]; ok {
		return ips, nil
	}
	return nil, net.UnknownNetworkError("нет в таблице")
}

func TestResolvingDialContextUsesPlatformResolver(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	_, port, _ := net.SplitHostPort(ln.Addr().String())

	p := &lookupProtector{table: map[string][]string{"api.vk.test": {"127.0.0.1"}}}
	d := &net.Dialer{}
	dial := resolvingDialContext(d, p)

	// Имя, которого система не знает: дозвон возможен только через платформу.
	c, err := dial(context.Background(), "tcp4", net.JoinHostPort("api.vk.test", port))
	if err != nil {
		t.Fatalf("платформа разрешила имя, а дозвон не прошёл: %v", err)
	}
	c.Close()
	if len(p.calls) != 1 || p.calls[0] != "api.vk.test" {
		t.Errorf("LookupHost вызывался %v", p.calls)
	}

	// IP-литерал платформу не спрашивает.
	c, err = dial(context.Background(), "tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if len(p.calls) != 1 {
		t.Errorf("IP-литерал ушёл в LookupHost: %v", p.calls)
	}

	// Ошибка платформы: откат на обычный Dial (localhost система знает).
	c, err = dial(context.Background(), "tcp4", net.JoinHostPort("localhost", port))
	if err != nil {
		t.Fatalf("откат на обычный резолв не сработал: %v", err)
	}
	c.Close()
}

func TestSystemDNSStepPrefersPlatformResolver(t *testing.T) {
	p := &lookupProtector{table: map[string][]string{"edge.example.org": {"62.76.231.231"}}}
	ip, ok := viaSystemDNS("edge.example.org", p, func(string, ...interface{}) {})
	if !ok || ip != "62.76.231.231" {
		t.Errorf("ip=%q ok=%v", ip, ok)
	}
	// Приватный ответ платформы отбрасывается тем же фильтром.
	p = &lookupProtector{table: map[string][]string{"edge.example.org": {"10.0.0.1"}}}
	if _, ok := viaSystemDNS("edge.example.org", p, func(string, ...interface{}) {}); ok {
		t.Error("приватный адрес от платформы принят")
	}
}
