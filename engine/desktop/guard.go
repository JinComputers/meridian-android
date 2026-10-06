package desktop

import (
	"errors"
	"net"

	"meridian/engine"
)

// DefaultTunnelDNS — DNS внутри туннеля у Android (MeridianVpnService.DNS_SERVERS).
// Тот же набор по умолчанию и здесь: настольный клиент ставит их на интерфейс
// туннеля, и запросы на них обязаны идти В туннель.
var DefaultTunnelDNS = []string{"1.1.1.1", "8.8.8.8"}

// errNoPlatformResolver — у платформенного Protector нет LookupHost: движок
// разрешает имя обычным способом (см. engine/exclude.go).
var errNoPlatformResolver = errors.New("платформа не разрешает имена сама")

// guardedProtector — обёртка над Protector клиента, ставится desktop.Start.
//
// ГЛАВНОЕ: не пускает в ExcludeHost адреса DNS туннеля. DoH движка ходит на
// 1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8 (engine/resolve.go, dohProviders), и
// два из них совпадают с DNS туннеля. Host-маршрут /32 в обход туннеля к
// такому адресу увёл бы мимо туннеля ВЕСЬ DNS компьютера — утечка. Пока
// адрес в списке «никогда не исключать», ExcludeHost по нему молча не
// доходит до платформы. Protect (привязка сокета) от этого не страдает.
//
// Остальное делегируется: Protect всегда, ExcludeHost и LookupHost — если
// клиентский Protector их имеет (структурно, необязательные).
type guardedProtector struct {
	inner engine.Protector
	never map[string]bool
}

func newGuardedProtector(inner engine.Protector, tunnelDNS []string) engine.Protector {
	if inner == nil {
		return nil
	}
	never := map[string]bool{}
	for _, a := range tunnelDNS {
		if ip := net.ParseIP(a); ip != nil {
			never[ip.String()] = true
		}
	}
	return &guardedProtector{inner: inner, never: never}
}

func (g *guardedProtector) Protect(fd int32) bool { return g.inner.Protect(fd) }

func (g *guardedProtector) ExcludeHost(host string) {
	ex, ok := g.inner.(interface{ ExcludeHost(string) })
	if !ok {
		return
	}
	if ip := net.ParseIP(host); ip != nil && g.never[ip.String()] {
		return
	}
	ex.ExcludeHost(host)
}

func (g *guardedProtector) LookupHost(host string) ([]string, error) {
	hr, ok := g.inner.(interface {
		LookupHost(string) ([]string, error)
	})
	if !ok {
		return nil, errNoPlatformResolver
	}
	return hr.LookupHost(host)
}
