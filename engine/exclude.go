package engine

import (
	"context"
	"net"
	"sync"
	"syscall"
)

// hostExcluder — НЕОБЯЗАТЕЛЬНАЯ возможность платформенного Protector: если у
// его реализации есть метод ExcludeHost(host string), движок зовёт его с
// адресом каждого удалённого узла, к которому сейчас пойдёт сокетом (вход,
// TURN-релей VK, HTTPS к VK, DoH), ДО отправки первого байта, синхронно.
//
// Нужно там, где одной привязки сокета к интерфейсу мало: на macOS
// IP_BOUND_IF не переживает маршрут через utun (замер кота 5: и защищённый, и
// незащищённый сокет ловят «no route to host», пока живы 0/1 и 128/1, а
// /32-исключение к адресу сразу лечит). Платформа в этом методе добавляет
// host-маршрут /32 к адресу через физический шлюз; идемпотентность и уборку
// при остановке держит она сама.
//
// Интерфейс НЕ экспортирован и не входит в подпись экспортированных функций,
// поэтому gomobile о нём не знает: Android/iOS не задеты (их Protector этого
// метода не имеет, вызов молча не происходит).
type hostExcluder interface {
	ExcludeHost(host string)
}

// excludeHost вызывает ExcludeHost, если Protector его умеет. Не-IP
// (пусто, имя, неопределённый адрес) не передаются.
func excludeHost(prot Protector, host string) {
	if prot == nil {
		return
	}
	he, ok := prot.(hostExcluder)
	if !ok {
		return
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.IsUnspecified() {
		return
	}
	// ПО ОДНОМУ ЗА РАЗ. Движок зовёт ExcludeHost из нескольких горутин
	// сразу (DoH опрашивает резолверы параллельно, гонка ступеней,
	// потоки), а платформа в нём правит таблицу маршрутов и о потоках
	// ничего не обещала. Замок здесь снимает с неё эту заботу: вызовы
	// приходят последовательно, как до параллельного DoH.
	excludeMu.Lock()
	defer excludeMu.Unlock()
	he.ExcludeHost(host)
}

var excludeMu sync.Mutex

// protectDialControl — protectControl для ИСХОДЯЩЕГО соединения (net.Dialer):
// перед защитой сокета отдаёт платформе адрес назначения, который Go к этому
// моменту уже разрешил. Для ListenConfig не годится: там address — свой
// локальный адрес, исключать его нечего.
func protectDialControl(prot Protector, logf func(string, ...interface{}), name string) func(string, string, syscall.RawConn) error {
	inner := protectControl(prot, logf, name)
	return func(network, address string, rc syscall.RawConn) error {
		if host, _, err := net.SplitHostPort(address); err == nil {
			excludeHost(prot, host)
		}
		return inner(network, address, rc)
	}
}

// hostResolver — ВТОРАЯ необязательная возможность платформенного Protector:
// метод LookupHost(host string) ([]string, error). Если он есть, имена (не
// IP-литералы), которые движок иначе разрешил бы системным резолвером, —
// имя шлюза (edge), api.vk.me, calls.okcdn.ru, имена API в engine/params —
// разрешает платформа, в обход туннеля.
//
// Зачем: на macOS маршруты 0/1 и 128/1 через utun забирают и сам DNS-запрос
// системного резолвера при переподключении с живым туннелем, а он уходит в
// мёртвый туннель. Защита сокета (Protect/ExcludeHost) резолвер ОС не
// касается: тот открывает свои сокеты внутри libc/Go, не через Control.
//
// Ошибка или пустой ответ — движок продолжает обычным способом, как без
// метода. Интерфейс не экспортирован, gomobile о нём не знает.
type hostResolver interface {
	LookupHost(host string) ([]string, error)
}

// lookupViaPlatform разрешает имя средствами платформы. ok=false — платформа
// метода не имеет или не смогла: разрешать обычным способом.
func lookupViaPlatform(prot Protector, host string) ([]string, bool) {
	if prot == nil || net.ParseIP(host) != nil {
		return nil, false
	}
	hr, ok := prot.(hostResolver)
	if !ok {
		return nil, false
	}
	ips, err := hr.LookupHost(host)
	if err != nil || len(ips) == 0 {
		return nil, false
	}
	return ips, true
}

// resolvingDialContext — DialContext, который для имени сначала спрашивает
// платформу (lookupViaPlatform) и стучится по полученным адресам по очереди;
// для IP-литерала и при отсутствии ответа платформы — обычный d.DialContext.
// Control у d остаётся: сокет по-прежнему защищается и адрес исключается.
func resolvingDialContext(d *net.Dialer, prot Protector) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return d.DialContext(ctx, network, addr)
		}
		ips, ok := lookupViaPlatform(prot, host)
		if !ok {
			return d.DialContext(ctx, network, addr)
		}
		var lastErr error
		for _, ip := range ips {
			c, err := d.DialContext(ctx, network, net.JoinHostPort(ip, port))
			if err == nil {
				return c, nil
			}
			lastErr = err
			if ctx.Err() != nil {
				break
			}
		}
		return nil, lastErr
	}
}
