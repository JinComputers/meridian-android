package engine

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func newTestCache(hashes []string) *vkCache {
	c := &vkCache{
		creds:    map[string]cachedCreds{},
		state:    map[string]string{},
		deferred: map[string]hashDefer{},
	}
	for _, h := range hashes {
		c.state[h] = hashOK
	}
	return c
}

// Отсрочка растёт по заданным ступеням и упирается в потолок: 2, 5, 15,
// 30 и дальше 30. Отсрочка длиннее получаса перестаёт отличаться от
// смерти, а хоронить по сроку мы как раз и не хотим.
func TestDeferStepsGrowAndCap(t *testing.T) {
	want := []time.Duration{
		2 * time.Minute, 5 * time.Minute, 15 * time.Minute,
		30 * time.Minute, 30 * time.Minute, 30 * time.Minute,
	}
	for i, w := range want {
		if got := deferStep(i + 1); got != w {
			t.Fatalf("промах %d: ждали %s, вышло %s", i+1, w, got)
		}
	}
	if got := deferStep(0); got != 2*time.Minute {
		t.Fatalf("нулевой промах обязан дать первую ступень, вышло %s", got)
	}
}

// Один отложен, остальные живы — отложенный пропускается, разморозки НЕ
// происходит.
func TestDeferredHashIsSkippedWhileOthersLive(t *testing.T) {
	hs := []string{"a", "b", "c"}
	c := newTestCache(hs)
	c.deferHash("a")

	if c.unfreezeIfAllDeferred(hs) {
		t.Fatal("разморозка при одном отложенном — пул не пуст, размораживать нечего")
	}
	if !time.Now().Before(c.deferredUntil("a")) {
		t.Fatal("«a» обязан остаться отложенным")
	}
	for _, h := range []string{"b", "c"} {
		if !c.deferredUntil(h).IsZero() {
			t.Fatalf("«%s» никто не откладывал, а он отложен", h)
		}
	}
}

// ГЛАВНЫЙ ТЕСТ ПОЛИТИКИ: отложены все — отсрочки снимаются со всех
// разом. Пустой пул недопустим ни при каких условиях.
//
// ЭТОТ ТЕСТ ОБЯЗАН КРАСНЕТЬ, если убрать вызов unfreezeIfAllDeferred из
// перебора или сделать его пустышкой. Проверка мутацией — требование
// кота 1, чья это область.
func TestAllDeferredUnfreezesAtOnce(t *testing.T) {
	hs := []string{"a", "b", "c"}
	c := newTestCache(hs)
	for _, h := range hs {
		c.deferHash(h)
	}
	for _, h := range hs {
		if !time.Now().Before(c.deferredUntil(h)) {
			t.Fatalf("подготовка: «%s» обязан быть отложен", h)
		}
	}

	if !c.unfreezeIfAllDeferred(hs) {
		t.Fatal("отложены ВСЕ — разморозка обязана была случиться")
	}
	for _, h := range hs {
		if until := c.deferredUntil(h); time.Now().Before(until) {
			t.Fatalf("после разморозки «%s» всё ещё отложен до %s", h, until)
		}
	}
}

// Разморозка снимает МОМЕНТ ВОЗВРАТА, но не счётчик промахов: следующая
// отсрочка обязана быть длиннее. Иначе плохой час стирал бы всё, что мы
// узнали про хеши, и хронически мёртвый хеш вечно ходил бы по первой
// ступени.
func TestUnfreezeKeepsStrikes(t *testing.T) {
	hs := []string{"a"}
	c := newTestCache(hs)

	first := c.deferHash("a")
	if !c.unfreezeIfAllDeferred(hs) {
		t.Fatal("единственный хеш отложен — обязана быть разморозка")
	}
	second := c.deferHash("a")
	if second <= first {
		t.Fatalf("после разморозки отсрочка обязана расти: было %s, стало %s", first, second)
	}
}

// Отложенный не теряет места в очереди: как только срок вышел, он снова
// годен, и порядок перебора его не наказывает.
func TestDeferredReturnsAfterExpiry(t *testing.T) {
	c := newTestCache([]string{"a"})
	c.deferHash("a")

	// Двигаем момент возврата в прошлое — то же, что дождаться срока.
	c.mu.Lock()
	d := c.deferred["a"]
	d.until = time.Now().Add(-time.Second)
	c.deferred["a"] = d
	c.mu.Unlock()

	if time.Now().Before(c.deferredUntil("a")) {
		t.Fatal("срок вышел, а хеш всё ещё считается отложенным")
	}
}

// Мёртвые и закапчёванные в счёт кандидатов НЕ идут: они и так вне
// игры. Если все ЖИВЫЕ отложены, размораживать надо, даже когда рядом
// лежит труп.
func TestDeadHashesDoNotBlockUnfreeze(t *testing.T) {
	hs := []string{"живой", "труп", "капча"}
	c := newTestCache(hs)
	c.state["труп"] = hashDead
	c.state["капча"] = hashCaptcha
	c.deferHash("живой")

	if !c.unfreezeIfAllDeferred(hs) {
		t.Fatal("единственный живой отложен — обязана быть разморозка")
	}
}

// Хешей нет вовсе — размораживать нечего, и падать тоже незачем.
func TestUnfreezeWithNoCandidates(t *testing.T) {
	c := newTestCache(nil)
	if c.unfreezeIfAllDeferred(nil) {
		t.Fatal("пустой список не может требовать разморозки")
	}
}

// Откладываем ТОЛЬКО по сроку. Отказ VK, отсутствие релея и капча имеют
// свои ветки, и путать их с тайм-аутом нельзя: у них другая цена.
func TestIsVKTimeout(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"срок контекста", context.DeadlineExceeded, true},
		{"обёрнутый срок", fmt.Errorf("шаг 1/5: %w", context.DeadlineExceeded), true},
		{"сеть по сроку", fakeNetErr{timeout: true}, true},
		{"сеть не по сроку", fakeNetErr{timeout: false}, false},
		{"обычная ошибка", errors.New("VK сказал нет"), false},
		{"капча", errVKCaptcha, false},
		{"мёртвый хеш", errVKDead, false},
		{"пусто", nil, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isVKTimeout(tc.err); got != tc.want {
				t.Fatalf("ждали %v, вышло %v", tc.want, got)
			}
		})
	}
}

type fakeNetErr struct{ timeout bool }

func (e fakeNetErr) Error() string { return "сетевая ошибка" }
func (e fakeNetErr) Timeout() bool { return e.timeout }
func (e fakeNetErr) Temporary() bool {
	return false
}
