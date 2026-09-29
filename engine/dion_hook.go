package engine

import (
	"context"
	"errors"
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
