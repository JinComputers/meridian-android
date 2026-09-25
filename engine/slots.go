package engine

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

const (
	// slotChunk — размер пачки при раздаче вверх.
	//
	// Стартовый слот меняется раз в slotChunk пакетов по формуле
	// (n/slotChunk) % len(conns) — дословно как в роутерном клиенте
	// (session_raw_slots.go:296-320; сама константа — там же, :41).
	// Шлюз раздаёт вниз симметрично, тем же размером пачки
	// (спецификация 3.5).
	slotChunk = 8

	// slotsPerBatch — квота релея на ОДИН комплект кредов.
	//
	// Девять аллокаций. Считается именно на комплект, а не на ссылку:
	// новый GetCreds на том же хеше даёт независимую девятку.
	slotsPerBatch = 9

	// slotsMax — ЖЁСТКИЙ потолок числа слотов. Шестнадцать, и ни одним
	// больше.
	//
	// Это не наш выбор и не запас: на шлюзе maxSlotsPerIP = 16, а адрес
	// в туннеле выдаётся по хешу пароля — значит ВСЕ наши слоты приходят
	// на один и тот же IP. Семнадцатый шлюз отвергнет.
	//
	// Отсюда разбивка на пачки 9 + 7, а НЕ 9 + 9. Второй комплект
	// намеренно неполный: девять плюс девять это восемнадцать, то есть
	// на два слота выше шлюзового предела. Если кто-то соберётся
	// "исправить" на 9+9 — вот причина, по которой этого делать нельзя.
	slotsMax = 16

	// credsThrottleMin/Max — интервал между ЛЮБЫМИ двумя запросами
	// кредов, как у роутерного клиента.
	//
	// Второй запрос вплотную за первым — верный способ напроситься на
	// капчу. Разброс, а не постоянная величина, чтобы обращения не
	// ложились ровной сеткой.
	credsThrottleMin = 3 * time.Second
	credsThrottleMax = 6 * time.Second

	// slotDialBudget — срок на подъём ОДНОГО дополнительного слота.
	//
	// Слоты поднимаются последовательно и уже после того, как туннель
	// встал, поэтому общий срок лестницы на них не распространяется:
	// человек в этот момент уже пользуется связью.
	slotDialBudget = 8 * time.Second
)

// slotDialer — всё, что нужно, чтобы поднять ещё один слот.
//
// Слоты открываются ПОСЛЕ подъёма туннеля, в фоне, поэтому исходные
// данные приходится держать при себе: к тому времени climb давно
// вернулся.
type slotDialer struct {
	gateway  string
	password string
	deviceID string
	prot     Protector
	cand     candidate
}

// turnCreds — один комплект кредов TURN.
//
// Добывается ТОЛЬКО цепочкой vkcalls. Статических кредов в настройках
// больше нет: они были ступенькой первого захода, и пока лежали рядом,
// путь подключения молча уходил по ним мимо цепочки.
type turnCreds struct {
	user string
	pass string

	// addrs — адреса релеев из ответа шага 5 цепочки, в виде "хост:порт".
	//
	// Когда цепочка их отдала, брать надо именно их: это релеи, которые
	// VK назначил ИМЕННО под эти креды. Адрес из настроек остаётся
	// запасным на случай статических кредов, где выбирать не из чего.
	addrs []string

	// hash — по какому хешу VK добыт комплект (пусто у статических
	// кредов). Нужен, чтобы выбросить комплект из общего кэша, если релей
	// на нём отказал.
	hash string
}

// batchOf — в какую пачку попадает слот с этим номером (номер с нуля).
func batchOf(slotIdx int) int {
	return slotIdx / slotsPerBatch
}

var (
	credsGateMu   sync.Mutex
	credsLastTake time.Time
)

// takeCredsGate — глобальный троттлинг между запросами кредов.
//
// У роутерного клиента интервал 3-6 секунд между ЛЮБЫМИ двумя вызовами
// GetCreds. Теперь и у нас: цепочка vkcalls зовётся отсюда, и задержка
// сторожит настоящие походы в VK, а не пустое место.
//
// Глобальная, а не на сессию: капча считается на источник обращений, а
// не на нашу внутреннюю раскладку.
func takeCredsGate(ctx context.Context, logf func(string, ...interface{})) {
	credsGateMu.Lock()
	defer credsGateMu.Unlock()

	if credsLastTake.IsZero() {
		credsLastTake = time.Now()
		return
	}
	wait := credsThrottleMin + randDuration(credsThrottleMax-credsThrottleMin)
	if since := time.Since(credsLastTake); since < wait {
		pause := wait - since
		if logf != nil {
			logf("троттлинг кредов: жду %s перед новым комплектом", pause.Round(time.Millisecond))
		}
		// Спим, поглядывая на общий срок цепочки: он покрывает и эту
		// паузу, и досыпать сверх него бессмысленно — всё равно
		// добывать будет уже некогда.
		t := time.NewTimer(pause)
		select {
		case <-t.C:
		case <-ctx.Done():
			t.Stop()
		}
	}
	credsLastTake = time.Now()
}

// randDuration — равномерное значение от нуля до max.
func randDuration(max time.Duration) time.Duration {
	if max <= 0 {
		return 0
	}
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return max / 2
	}
	var v uint64
	for _, x := range b {
		v = v<<8 | uint64(x)
	}
	return time.Duration(v % uint64(max))
}

// newSessionGeneration — 8 случайных байт в hex, одно на сессию.
//
// Дословно как у роутера (спецификация 3.2, session_raw_slots.go:126-135).
// Шлюз проверяет алфавит и длину до 32 символов; наши 16 проходят.
func newSessionGeneration() (string, error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// authCaps — четвёртое поле AUTH.
//
// При одном слоте — ровно "echo", как было всегда. Поколение при этом
// не шлётся вовсе: шлюз и так вытесняет прежнее соединение по адресу
// (спецификация 3.2).
//
// Многослот включается ТОЛЬКО на релейной ступени. На прямом пути он у
// роутера неприменим вовсе — там нет шейпа на аллокацию, наращивать
// нечего (спецификация-2, C.5). Значит AUTH прямых ступеней не меняется
// ни на байт, и работающий путь мы не трогаем.
func authCaps(slots int, gen string) string {
	if slots > 1 && gen != "" {
		return "echo,multi,gen=" + gen
	}
	return "echo"
}

// slotList — текущий состав слотов. Никогда не nil после adoptFirst.
func (s *session) slotList() []*rung {
	p := s.slots.Load()
	if p == nil {
		return nil
	}
	return *p
}

// nextSlot — слот для очередного пакета вверх.
//
// Round-robin ПАЧКАМИ: стартовый индекс меняется раз в slotChunk
// пакетов, а не на каждом. Так соседние пакеты одного потока чаще
// уходят одной дорогой, и получатель реже видит перестановку.
func (s *session) nextSlot() *rung {
	list := s.slotList()
	switch len(list) {
	case 0:
		return nil
	case 1:
		return list[0]
	}
	n := s.slotRR.Add(1) - 1
	return list[(n/slotChunk)%uint64(len(list))]
}

// adoptFirst делает первый слот сессионным.
//
// Возвращает false, если сессию успели остановить, пока ступень
// поднималась, — тогда ресурсы закрывает вызывающий.
func (s *session) adoptFirst(r *rung) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return false
	}
	list := []*rung{r}
	s.slots.Store(&list)
	s.obfs = r.obfs
	s.mtu = r.cand.mtu
	return true
}

// addSlot добавляет поднявшийся дополнительный слот и запускает его
// насос чтения.
//
// wg.Add происходит ЗДЕСЬ, под тем же мьютексом, что и проверка
// stopped. Это не украшение: reap ждёт ту же группу, и добавление
// счётчика после начала ожидания было бы гонкой. Порядок строгий —
// stop() выставляет stopped под s.mu раньше, чем порождает reap,
// поэтому опоздавший слот получит false и насос не запустит.
func (s *session) addSlot(r *rung) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return false
	}
	old := s.slotList()
	list := make([]*rung, len(old), len(old)+1)
	copy(list, old)
	list = append(list, r)
	s.slots.Store(&list)

	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		s.netToTun(r)
	}()
	return true
}

// openExtraSlots поднимает слоты со второго по последний.
//
// Последовательно и без явной паузы: задержку даёт сам дозвон, как у
// роутера (спецификация 3.1). Первая ошибка обрывает цикл — продолжаем
// с тем, что успело подняться.
//
// Каждый слот берёт СВОЮ TURN-аллокацию, но на ТЕХ ЖЕ кредах: креды
// запрашиваются один раз на сессию, поэтому многослот не умножает их
// износ.
func (s *session) openExtraSlots() {
	d := s.dialer
	if d == nil {
		return
	}
	want := s.wantSlots
	if want > slotsMax {
		want = slotsMax
	}

	for i := 1; i < want; i++ {
		select {
		case <-s.ctx.Done():
			return
		default:
		}
		if !s.dialOneSlot(i+1, want) {
			return
		}
	}
	s.logf("многослот собран: слотов в работе %d из %d", len(s.slotList()), want)
}

// growOneSlot добавляет ОДИН слот по решению автоподбора.
func (s *session) growOneSlot() {
	if s.dialer == nil {
		return
	}
	n := len(s.slotList()) + 1
	s.dialOneSlot(n, s.slotsMax)
}

// dialOneSlot поднимает один дополнительный слот: своя TURN-аллокация,
// свой AUTH, своё состояние обфускации. Креды — те же самые.
func (s *session) dialOneSlot(num, want int) bool {
	d := s.dialer
	if d == nil {
		return false
	}
	started := time.Now()

	// Номер пачки: слоты с 1 по 9 — пачка ноль, с 10 по 16 — пачка один.
	// Новая пачка означает НОВЫЙ комплект кредов, и это единственное
	// место, где он берётся.
	batch := batchOf(num - 1)
	cr, err := s.credsForBatch(batch, d)
	if err != nil {
		s.logf("слот %d/%d: комплекта кредов нет: %v — продолжаю с %d",
			num, want, err, len(s.slotList()))
		return false
	}

	r, err := s.raiseRelay(s.ctx, d.gateway, d.cand, cr, d.password, d.prot)
	if err != nil {
		s.logf("слот %d/%d не поднялся: %v — продолжаю с %d",
			num, want, err, len(s.slotList()))
		return false
	}
	// caps считаются от ЖЕЛАЕМОГО числа слотов, а не от текущего: в
	// многослотовой сессии поколение уходит в каждый слот, включая те,
	// что поднимаются позже (спецификация 3.2).
	needsNK := d.cand.transport != transTLS && !d.cand.useDTLS
	if _, err := s.auth(r.link, r.pc, d.password, d.deviceID,
		slotDialBudget, authCaps(2, s.sessionGen), needsNK); err != nil {
		r.close()
		s.logf("слот %d/%d: AUTH не прошёл: %v — продолжаю с %d",
			num, want, err, len(s.slotList()))
		return false
	}
	if !s.addSlot(r) {
		// Сессию остановили, пока слот поднимался.
		r.close()
		return false
	}
	s.logf("слот %d/%d поднят за %s, слотов в работе: %d",
		num, want, time.Since(started).Round(time.Millisecond), len(s.slotList()))
	return true
}

// dropSlot снимает ПОСЛЕДНИЙ добавленный слот и возвращает его для
// закрытия. Последний, а не случайный, — как у роутера.
//
// Слот помечается retired ДО того, как исчезнет из состава: насосы,
// наткнувшись на закрытое соединение, должны понять, что это плановое
// снятие, а не обрыв, и не гасить всю сессию.
func (s *session) dropSlot() *rung {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return nil
	}
	old := s.slotList()
	if len(old) <= 1 {
		return nil
	}
	victim := old[len(old)-1]
	victim.retired.Store(true)
	if victim.obfs != nil {
		// Тот же признак ниже по слоям: иначе закрытие сокета уедет в
		// лог как ошибка чтения.
		victim.obfs.retired.Store(true)
	}

	list := make([]*rung, len(old)-1)
	copy(list, old[:len(old)-1])
	s.slots.Store(&list)
	return victim
}

// credsForBatch — комплект кредов для пачки слотов.
//
// Каждая пачка — свой комплект: квота релея в девять аллокаций
// считается на КОМПЛЕКТ, а не на ссылку. Отсюда и износ: две пачки
// стоят двух походов за кредами.
//
// Два источника, в порядке предпочтения:
//
//  1. Хеши VK — комплект добывается цепочкой на лету. Рабочий путь.
//  2. Статические креды из настроек — остаток первого захода. Нужны,
//     пока цепочка отлаживается: с ними релей проверяется отдельно от
//     VK, а не вместе с ним.
func (s *session) credsForBatch(batch int, d *slotDialer) (turnCreds, error) {
	return s.relayCreds(s.ctx, d.cand, batch, d.prot)
}

// relayCreds — комплект кредов для пачки слотов.
//
// ЕДИНСТВЕННЫЙ путь к кредам, общий для главной ссылки и для
// дополнительных слотов. Раньше путей было два, и они расходились:
// главная ссылка брала статический комплект из настроек, а цепочка
// работала только по кнопке с экрана.
//
// Кэш живёт внутри fetchCreds: комплект на хеш, девять минут. Значит
// слоты 2..9 одной пачки в VK повторно не ходят — они берут добытое
// первым слотом. А вот пачка номер два берёт ДРУГОЙ хеш, и это
// правильно: квота релея считается на комплект.
func (s *session) relayCreds(ctx context.Context, c candidate, batch int, prot Protector) (turnCreds, error) {
	if len(c.hashes) == 0 {
		return turnCreds{}, errVKNoHashes
	}
	if batch > s.credsBatch {
		s.credsBatch = batch
		s.credsTaken++
		s.logf("пачка %d (слоты с %d): нужен новый комплект — походов за кредами за сессию: %d",
			batch+1, batch*slotsPerBatch+1, s.credsTaken)
	}
	return s.fetchCreds(ctx, c, batch, prot)
}
