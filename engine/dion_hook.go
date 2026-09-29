package engine

import (
	"context"
	"errors"
	"sync"
)

var (
	// errDionDead — комната DION мертва (не найдена, call_unavailable).
	errDionDead = errors.New("комната DION недоступна")
	// errDionNetwork — сбой сети на пути к DION.
	errDionNetwork = errors.New("DION: сеть")
	// errDionNoRooms — комнат DION нет.
	errDionNoRooms = errors.New("комнат DION нет")
)

// fetchDionCredsHook — получение кредов DION; его ставит creds_dion.go. Пока
// реализации нет, звено DION недоступно. release закрывает WS комнаты.
var fetchDionCredsHook func(s *session, ctx context.Context, slug string, prot Protector) (turnCreds, func(), error)

// relayPrimary — основное звено релея: VK (умолчание) или Dion.
var relayPrimary struct {
	sync.Mutex
	dion bool
}

// SetRelayPrimaryDion ставит Dion основным звеном (RELAY_PRIMARY="dion"): его
// креды берутся первыми, VK — запасное. Действует, только когда у релейной
// ступени есть комнаты Dion.
func SetRelayPrimaryDion(on bool) {
	relayPrimary.Lock()
	relayPrimary.dion = on
	relayPrimary.Unlock()
}

func primaryIsDion() bool {
	relayPrimary.Lock()
	defer relayPrimary.Unlock()
	return relayPrimary.dion
}
