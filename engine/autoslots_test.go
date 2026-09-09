package engine

import (
	"testing"
	"time"
)

// Развилка стоит четырёх чужих TURN-аллокаций на релее VK, поэтому она
// и вынесена отдельно от остального автоподбора: тот завязан на
// состояние и таймеры, а это — чистая функция.
func TestIdleShrinkAllowed(t *testing.T) {
	cases := []struct {
		name    string
		sawLoad bool
		slots   int
		upFor   time.Duration
		want    bool
	}{
		{
			// Ради чего вето и заводилось: подключились, трафик ещё не
			// пошёл. Снять слот здесь — убить разгон.
			name: "молчит, но только что подняли", sawLoad: false, slots: 4,
			upFor: 20 * time.Second, want: false,
		},
		{
			name: "молчит ровно на границе", sawLoad: false, slots: 4,
			upFor: idleNoLoadGrace, want: true,
		},
		{
			name: "молчит дольше предела", sawLoad: false, slots: 4,
			upFor: 5 * time.Minute, want: true,
		},
		{
			// Нагрузка была — решает обычная ветка, с подтверждением и
			// с запретом роста. Сюда лезть нельзя.
			name: "нагрузка была", sawLoad: true, slots: 4,
			upFor: 5 * time.Minute, want: false,
		},
		{
			// Последний слот несёт туннель. Снять его — оборвать связь.
			name: "остался один слот", sawLoad: false, slots: 1,
			upFor: 5 * time.Minute, want: false,
		},
		{
			name: "слотов ноль", sawLoad: false, slots: 0,
			upFor: time.Hour, want: false,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := idleShrinkAllowed(tc.sawLoad, tc.slots, tc.upFor)
			if got != tc.want {
				t.Fatalf("ждали %v, вышло %v", tc.want, got)
			}
		})
	}
}

// Предел обязан быть заметно больше того замера, ради которого вето
// заводилось: слот сняли через ДВАДЦАТЬ секунд после подъёма, и трафик
// просто не успел пойти. Если однажды кто-то урежет предел до тех же
// двадцати секунд, тест обязан это поймать.
func TestIdleGraceIsWellPastTheKnownFailure(t *testing.T) {
	const knownFailure = 20 * time.Second
	if idleNoLoadGrace < 4*knownFailure {
		t.Fatalf("предел %s слишком близок к замеру, на котором обожглись (%s): "+
			"нужен запас не меньше четырёхкратного", idleNoLoadGrace, knownFailure)
	}
}
