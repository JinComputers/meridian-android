package engine

import (
	"context"
	"errors"
	"net"
	"time"
)

// ОТСРОЧКА ХЕША, ОТВАЛИВШЕГОСЯ ПО СРОКУ.
//
// Зачем. Мёртвым помечался только хеш, ответивший errVKDead; тайм-аут
// падал в общую ветку и забывался. Значит vkStepTimeout (10 с) платился
// ПРИ КАЖДОМ подключении заново, а не один раз: в логе владельца 03.09
// первый хеш умер по сроку, второй прошёл за три секунды — и так каждый
// раз, пока хеш в пуле.
//
// Политика согласована с котом 1, чья это область (VK). Все четыре
// условия обязательны, и три из них — про то, как НЕ навредить.
//
//  1. ОТКЛАДЫВАЕМ, А НЕ ХОРОНИМ. Тайм-аут это не errVKDead: хеш жив, до
//     него не дошли. Отдельное состояние с моментом возврата, и в поле
//     «мёртв» оно не сваливается.
//
//  2. СРОК КОРОТКИЙ И РАСТУЩИЙ: 2, 5, 15, потолок 30 минут. Не час:
//     плохая сеть проходит быстрее, чем портится хеш.
//
//  3. ПОЛ НЕ ВЫЖИГАЕТСЯ ЦЕЛИКОМ. Если отложены ВСЕ хеши — отсрочки
//     снимаются со всех разом, и перебор идёт как раньше. Пустой пул
//     недопустим ни при каких условиях: лучше десять секунд, чем
//     «звонить некуда». Это ровно та осторожность, ради которой автор
//     и оставлял тайм-аут непомеченным, и её надо сохранить, а не
//     отменить.
//
//  4. ТОЛЬКО В ПАМЯТИ, на диск не пишется. Иначе временно плохая сеть
//     отравила бы пул до переустановки, а причину через неделю никто
//     не найдёт.
var vkDeferSteps = []time.Duration{
	2 * time.Minute,
	5 * time.Minute,
	15 * time.Minute,
	30 * time.Minute,
}

// hashDefer — отложенный хеш: до какого мгновения и в который раз.
type hashDefer struct {
	until   time.Time
	strikes int
}

// deferStep — какой срок дать за очередной промах. Дальше потолка не
// растёт: отсрочка длиннее получаса перестаёт отличаться от смерти.
func deferStep(strikes int) time.Duration {
	if strikes < 1 {
		strikes = 1
	}
	if strikes > len(vkDeferSteps) {
		strikes = len(vkDeferSteps)
	}
	return vkDeferSteps[strikes-1]
}

// isVKTimeout — отвалился ли хеш ИМЕННО ПО СРОКУ.
//
// Проверяем два вида: срок контекста (общий бюджет цепочки) и срок
// самого http.Client, который отдаёт *url.Error с Timeout() = true и
// context.DeadlineExceeded внутри не несёт.
//
// Всё остальное — отказ VK, отсутствие релея, капча — сюда не относится
// и обрабатывается своими ветками, как и раньше.
func isVKTimeout(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	if errors.As(err, &ne) {
		return ne.Timeout()
	}
	return false
}

// deferHash откладывает хеш и возвращает, на сколько.
//
// Зовётся ПОД cache.mu снятым — блокировку берёт сам.
func (c *vkCache) deferHash(hash string) time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.deferred == nil {
		c.deferred = make(map[string]hashDefer)
	}
	d := c.deferred[hash]
	d.strikes++
	step := deferStep(d.strikes)
	d.until = time.Now().Add(step)
	c.deferred[hash] = d
	return step
}

// clearDefer снимает отсрочку с хеша, который сработал.
func (c *vkCache) clearDefer(hash string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.deferred, hash)
}

// deferredUntil — до какого мгновения хеш отложен. Ноль — не отложен.
func (c *vkCache) deferredUntil(hash string) time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.deferred[hash].until
}

// unfreezeIfAllDeferred — ГЛАВНОЕ ПРАВИЛО ПОЛИТИКИ.
//
// Если среди хешей, которые мы вообще стали бы пробовать (то есть не
// мёртвых и не отложенных капчей), НЕ ОСТАЛОСЬ НИ ОДНОГО доступного —
// снимаем отсрочки со всех разом и идём перебирать, как раньше.
//
// Счётчики промахов при этом СОХРАНЯЮТСЯ: следующая отсрочка будет
// длиннее, и хронически плохой хеш всё равно уступит место. Обнулять их
// значило бы забывать всё, что мы про хеши узнали, при каждом плохом
// часе.
//
// Возвращает true, если пришлось размораживать.
func (c *vkCache) unfreezeIfAllDeferred(hashes []string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	candidates := 0
	frozen := 0
	for _, h := range hashes {
		if c.state[h] != hashOK {
			continue // мёртвый или закапчёванный — он и так вне игры
		}
		candidates++
		if d, ok := c.deferred[h]; ok && now.Before(d.until) {
			frozen++
		}
	}
	if candidates == 0 || frozen < candidates {
		return false
	}
	for _, h := range hashes {
		if d, ok := c.deferred[h]; ok {
			// Момент возврата снимаем, счётчик промахов оставляем.
			d.until = time.Time{}
			c.deferred[h] = d
		}
	}
	return true
}

// poolSummary — из чего сейчас состоит пул: живых, мёртвых, в капче.
//
// ТРЕТЬЕ СОСТОЯНИЕ ОБЯЗАНО НАЗЫВАТЬСЯ ВСЛУХ — требование кота 1, и оно
// про тот же класс, что и «прибор, показывающий ноль».
//
// «Все отложены» и «годных не осталось вовсе» лечатся по-разному:
// первое снимается разморозкой, второе не лечится ничем, звонить
// действительно некуда. Без отдельной строки человек увидит тишину и
// решит, что клиент думает.
func (c *vkCache) poolSummary(hashes []string) (live, dead, captcha int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, h := range hashes {
		switch c.state[h] {
		case hashDead:
			dead++
		case hashCaptcha:
			captcha++
		default:
			live++
		}
	}
	return live, dead, captcha
}
