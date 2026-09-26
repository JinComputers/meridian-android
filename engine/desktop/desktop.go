// Package desktop — идиоматичный Go-фасад движка для настольных клиентов
// (Mac/Windows), НЕ проходящих через gomobile.
//
// НЕ ПОПАДАЕТ ПОД gomobile bind. Тот вызывается только с корневым пакетом
// движка (`gomobile bind ... .` из engine/build-aar.sh — точка, не `./...`,
// подпакеты не участвуют). Поэтому здесь можно использовать то, что
// gomobile не умеет выразить в Java/ObjC — настоящий context.Context,
// каналы, — не рискуя случайно расширить экспортированную поверхность,
// которую видят Android и iOS.
//
// Движок (package engine) ради этого фасада почти не тронут: единственное
// добавление — desktopinfo.go (шлюз и маска из ответа AUTH), исключённый
// тегом сборки для android/ios, так что gomobile его не видит. TUN
// уже платформенно-нейтрален (PacketFlow, заведён для iOS), события и
// статус — те же самые Logger/StateListener/геттеры, что и у Android/iOS,
// просто собраны здесь в одно место.
package desktop

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"meridian/engine"
)

// Config — всё, что нужно для одной попытки подключения.
//
// Ladder собирается вызывающим тем же способом, что и Kotlin/Swift:
// engine.NewLadder() + AddDirect/AddTCP/AddTLS/AddRelay/AddRelayHash/
// SetRelayTarget — по данным из /v1/params. Поход за /v1/params остаётся
// на стороне настольного клиента (см. план части 4, "за скобками") — тот
// же Go, что и здесь, так что это в разы дешевле, чем порт на Kotlin/Swift
// был для Android/iOS.
type Config struct {
	// Gateway — имя или IP шлюза, как в Engine.connect() у Android/iOS
	// (edge.jincomputers.win или готовый литерал).
	Gateway        string
	CachedFallback string
	Ladder         *engine.Ladder
	Password       string
	DeviceID       string

	// Protector — привязка сокетов движка к физическому интерфейсу
	// (аналог VpnService.protect() у Android). Реализуется платформой:
	// на Windows — привязка к интерфейсу через IP_UNICAST_IF, на Mac —
	// через IP_BOUND_IF/setsockopt.
	Protector engine.Protector

	// TunnelDNS — адреса DNS, которые клиент ставит на интерфейс туннеля.
	// Их host-маршрут в обход туннеля НИКОГДА не создаётся (см.
	// guardedProtector): иначе весь DNS компьютера утёк бы мимо туннеля.
	// nil — DefaultTunnelDNS (1.1.1.1, 8.8.8.8); свой список ЗАМЕНЯЕТ
	// умолчание, а не дополняет. ВАЖНО: DoH движка ходит на 1.1.1.1, 8.8.8.8,
	// 9.9.9.9, 77.88.8.8; если ставите на туннель другой DNS, а эти адреса
	// не исключены, DoH при живом туннеле пойдёт по правилам маршрутов.
	TunnelDNS []string

	// ClampMSS включает опускание MSS в TCP SYN/SYN-ACK, идущих через
	// туннель, до MTU текущей ступени минус 40 (только IPv4). Страховка для
	// НОВЫХ соединений; не заменяет MTU интерфейса туннеля = Info().MTU,
	// которое клиент обязан выставлять заново при каждом подключении. Подробно
	// и о границах: mss.go. Выключен по умолчанию, рекомендуется включать.
	ClampMSS bool

	Guard   engine.NetworkGuard
	Captcha engine.CaptchaSolver
}

// Event — одно сообщение о ходе сессии. Kind — "ready" (сессия поднята,
// Reason = "ip/префикс gw шлюз"; полные данные — Handle.Info()), "log" (обычная строка
// движка, как TunnelLog.add/.event у Android), "stopping" (сессия
// начала останавливаться, TUN ещё не закрыт — см. StateListener.OnStopping
// в engine.go про Kill Switch) или "stopped" (сессия мертва целиком).
//
// Канал закрывается ПОСЛЕ "stopped" — на этом можно ждать range-ом.
type Event struct {
	Kind   string
	Reason string
}

// Status — статус сессии одним снимком. Значения геттеров engine.go,
// собранные вместе — Android/Kotlin и iOS/Swift зовут их по одному, здесь
// зовутся все разом ради удобства.
type Status struct {
	Winner       string
	ResolvedVia  string
	ResolvedAddr string
	Active       bool
	LastTx       int64
	LastRx       int64
	MTU          int32
}

// CurrentStatus — статус ТЕКУЩЕЙ (последней) сессии движка.
func CurrentStatus() Status {
	return Status{
		Winner:       engine.Winner(),
		ResolvedVia:  engine.ResolvedVia(),
		ResolvedAddr: engine.ResolvedAddr(),
		Active:       engine.Active(),
		LastTx:       engine.LastTx(),
		LastRx:       engine.LastRx(),
		MTU:          engine.MTU(),
	}
}

// TunnelInfo — то, что выдал шлюз в ответе на AUTH: всё нужное, чтобы
// настроить интерфейс TUN (`ifconfig utunN inet <IP> <Gateway> netmask ...`).
type TunnelInfo struct {
	IP        string // свой адрес в туннеле, например "10.77.77.5"
	PrefixLen int    // длина префикса, например 16
	MaskHex   string // маска как прислал шлюз, хексом ("ffff0000")
	Gateway   string // адрес шлюза внутри туннеля, например "10.77.77.1"
	MTU       int32  // MTU туннеля для победившей ступени
}

// Handle — управление одной поднятой сессией.
type Handle struct {
	cancel context.CancelFunc
	info   TunnelInfo
}

// Info — параметры туннеля от шлюза. Известны сразу после Start.
func (h *Handle) Info() TunnelInfo { return h.info }

// Stop останавливает сессию. НЕ ЖДЁТ полной остановки (как и
// engine.Stop(), который зовётся отсюда) — если нужно дождаться, читать
// канал событий из Start до "stopped" либо звать engine.AwaitStopped
// напрямую.
func (h *Handle) Stop() {
	h.cancel()
}

type logFunc func(string)

func (f logFunc) Log(line string) { f(line) }

type stateAdapter struct {
	events chan<- Event
}

func (a stateAdapter) OnStopping(reason string) {
	trySend(a.events, Event{Kind: "stopping", Reason: reason})
}

func (a stateAdapter) OnStopped(reason string) {
	trySend(a.events, Event{Kind: "stopped", Reason: reason})
	close(a.events)
}

// trySend — не блокировать движок на медленном читателе. Событий немного
// и они не критичны для протокола (в отличие от TunnelLog у Android,
// здесь нет ограничения MAX_LINES — переполнение буфера канала просто
// роняет самые старые/новые сообщения по логике select, а не память).
func trySend(events chan<- Event, e Event) {
	select {
	case events <- e:
	default:
	}
}

// Start поднимает сессию: проходит лестницу (Connect), затем поднимает
// насос TUN (StartFlow). Возвращает Handle для управления и канал
// событий — закрывается после "stopped".
//
// ctx отменяется (в том числе через Handle.Stop, который просто зовёт
// отменяющую функцию этого же ctx) — сторожевая горутина зовёт
// engine.Stop().
//
// ВАЖНО: сторож заведён ДО engine.Connect, а не после. engine.Connect —
// блокирующий вызов без своего ctx (gomobile-граница, отсюда и обычные
// аргументы вместо context.Context); лестница внутри него (session.go,
// climb) живёт до ladderBudget = 25с. Раньше сторож ставился уже ПОСЛЕ
// возврата engine.Connect — отмена ctx во время самой лестницы (второе
// нажатие «стоп» на клиенте, пока подключение ещё не встало) никем не
// принималась, и клиент реально ждал до 25с вместо немедленной отмены.
// engine.Connect кладёт сессию в current ДО начала лазания (engine.go,
// с явным комментарием про этот же побочный эффект: "Stop() из onDestroy
// ... во время лазания теперь действительно обрывает его"), так что
// engine.Stop() отсюда безопасно звать в любой момент — до, во время и
// после Connect (current == nil — попросту no-op).
//
// ОДНА СЕССИЯ НА ПРОЦЕСС, как и у Android/iOS: движок хранит её в
// собственном пакетном состоянии (engine.go, current *session), а не
// per-instance — Start второй раз до Stop первой вернёт ошибку "сессия
// уже поднята" тем же путём, что и у мобильных клиентов.
func Start(ctx context.Context, cfg Config, tun engine.PacketFlow) (*Handle, <-chan Event, error) {
	events := make(chan Event, 64)
	logger := logFunc(func(line string) { trySend(events, Event{Kind: "log", Reason: line}) })
	listener := stateAdapter{events: events}

	tunnelDNS := cfg.TunnelDNS
	if tunnelDNS == nil {
		tunnelDNS = DefaultTunnelDNS
	}
	prot := newGuardedProtector(cfg.Protector, tunnelDNS)

	runCtx, cancel := context.WithCancel(ctx)
	go func() {
		<-runCtx.Done()
		engine.Stop()
	}()

	assigned, err := engine.Connect(
		cfg.Gateway, cfg.CachedFallback, cfg.Ladder, cfg.Password, cfg.DeviceID,
		prot, cfg.Guard, cfg.Captcha, logger, listener,
	)
	if err != nil {
		cancel()
		close(events)
		return nil, events, err
	}

	info, err := parseAssigned(assigned)
	if err != nil {
		cancel()
		return nil, events, err
	}
	info.MTU = engine.MTU()

	if cfg.ClampMSS {
		tun = clampFlow{inner: tun}
	}
	if err := engine.StartFlow(tun); err != nil {
		cancel()
		return nil, events, err
	}

	trySend(events, Event{Kind: "ready", Reason: fmt.Sprintf("%s/%d gw %s", info.IP, info.PrefixLen, info.Gateway)})
	return &Handle{cancel: cancel, info: info}, events, nil
}

// parseAssigned разбирает "10.77.77.5/16" от engine.Connect и добавляет шлюз
// и маску из ответа AUTH (engine.AssignedInfo, только настольные сборки).
func parseAssigned(assigned string) (TunnelInfo, error) {
	ip, pfx, ok := strings.Cut(assigned, "/")
	n, err := strconv.Atoi(pfx)
	if !ok || err != nil || ip == "" {
		return TunnelInfo{}, fmt.Errorf("непонятный адрес от движка: %q", assigned)
	}
	gw, mask := engine.AssignedInfo()
	return TunnelInfo{IP: ip, PrefixLen: n, MaskHex: mask, Gateway: gw}, nil
}
