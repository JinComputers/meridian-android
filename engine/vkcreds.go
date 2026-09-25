package engine

import (
	"compress/gzip"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Анонимная цепочка vkcalls: приложение само добывает креды TURN.
//
// Пять шагов на ДВА разных хоста:
//
//	1 auth.getAnonymToken          api.vk.me      → anonymous_token
//	2 messages.getCallPreview      api.vk.me      → user_id, secret
//	3 messages.getAnonymCallToken  api.vk.me      → okAnonymToken
//	4 auth.anonymLogin             calls.okcdn.ru → session_key
//	5 vchat.joinConversationByLink calls.okcdn.ru → turn_server
//
// Шаги 4-5 физически исполняются на серверах Odnoklassniki — это не
// опечатка, а устройство VK Calls.
//
// ФОРМА ЗАПРОСОВ ВЫПИСАНА ИЗ РАБОЧЕГО КОДА, а не достроена. Это важно:
// четыре захода мы читали ошибку "Invalid name" как «неверное имя
// параметра» и искали, какое из наших имён не то. Означала она буквально
// «нет параметра name» — того самого, которого мы не слали вовсе.
//
// Общее для всех пяти шагов:
//   - метод POST всегда, ТЕЛО ПУСТОЕ;
//   - все параметры в СТРОКЕ ЗАПРОСА, в заданном порядке;
//   - Content-Type не ставится нигде;
//   - заголовков ровно четыре, см. setHeaders.
//
// ВХОД В VK-АККАУНТ НЕ ДЕЛАЕТСЯ, и вопрос закрыт:
//
//  1. Режим account кредов не добывает вовсе — он ждёт их от внешней
//     обвязки с живой пользовательской сессией.
//  2. Потолок там 4 воркера против 108 в анонимном режиме. То есть вход
//     в аккаунт делает соединение МЕДЛЕННЕЕ, а не быстрее.
//  3. Хеши он тоже не создаёт: calls.start требует авторизованного
//     пользователя.
const (
	vkClientID   = "8093730"
	vkAPIVersion = "5.276"
	vkAppKey     = "CGMMEJLGDIHBABABA"

	vkAPIHost = "https://api.vk.me"

	// vkCallsEndpoint — шаги 4 и 5 идут в ОДНУ точку, различаясь
	// параметром method.
	vkCallsEndpoint = "https://calls.okcdn.ru/fb.do"

	// vkJoinBase — основа ссылки на звонок.
	//
	// Именно vk.com. Сам VK в ответе шага 2 отдаёт vk_join_link на символ
	// короче, то есть с другим хостом, — но рабочий код строит vk.com и
	// передаёт ОДНУ И ТУ ЖЕ строку на шаги 2 и 3. Делаем как он.
	vkJoinBase = "https://vk.com/call/join/"

	// vkUserAgent — ВОЗМОЖНОЕ РАСХОЖДЕНИЕ С РАБОЧИМ КОДОМ.
	//
	// Там UA приходит от tls-клиента с профилем Chrome_146 — вместе с
	// ним совпадает и отпечаток TLS. У нас такого клиента нет: UA
	// написан литералом, а отпечаток остаётся го-шный.
	//
	// Если цепочка встанет на чём-то необъяснимом — особенно на капче
	// или на молчании — возвращаться надо сюда.
	vkUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

	// vkCredsTTL — сколько считаем комплект годным.
	//
	// Девять минут — НАША консервативная граница, а не срок из ответа
	// VK: настоящий TTL код не разбирает вовсе.
	vkCredsTTL = 9 * time.Minute

	// vkStepTimeout — срок ОДНОГО шага. Внутренний предохранитель.
	vkStepTimeout = 10 * time.Second

	// vkChainBudget — общий срок на добычу кредов ЦЕЛИКОМ.
	//
	// Пять шагов по десять секунд давали потолок в пятьдесят секунд, а с
	// аллокацией и AUTH релейная ступень доходила до шестидесяти четырёх.
	// Полсотни из них набираются, только если КАЖДЫЙ шаг висит до упора,
	// — а это уже не «релей поднимается», это VK не отвечает. Первое
	// обрывать нельзя, второе нужно.
	//
	// Пятнадцать, а не двенадцать: живой проход занимает секунды две-три,
	// но это прикидка, а не замер, и запас взят пятикратный. Разница в
	// потолке между 26 и 29 секундами не принципиальна, а вот ошибиться в
	// меньшую сторону значит обрывать рабочую добычу.
	//
	// Пересмотрим по числам холодного старта, когда они появятся.
	//
	// Срок покрывает И ТРОТТЛИНГ: пауза в 3-6 секунд перед вторым
	// комплектом входит в эти пятнадцать, а не добавляется сверху.
	// Поэтому отсчёт начинается ДО takeCredsGate.
	vkChainBudget = 15 * time.Second

	bodyBriefLen = 200

	// bodyFailLen — сколько тела показывать при НЕУДАЧЕ шага. Больше,
	// потому что настоящая причина стоит после request_params.
	bodyFailLen = 600
)

// Состояния хеша в пределах сессии.
const (
	hashOK      = ""
	hashCaptcha = "капча"
	hashDead    = "мёртвый"
)

var (
	errVKNoHashes = errors.New("хеши VK не заданы: добавь их на экране хешей")

	errVKCaptcha = errors.New("VK требует капчу")

	// errVKNoTurn — VK не дал адреса релея для этого звонка.
	//
	// ОТДЕЛЬНАЯ ПРИЧИНА, А НЕ ОТТЕНОК СМЕРТИ. Раньше «missing
	// turn_server» попадал в errVKDead, а «мёртвый» — это не надпись:
	// HashStore.usable() исключает такую ссылку НАВСЕГДА. То есть
	// рабочая ссылка хоронилась по признаку, который её мёртвой не
	// делает: без адреса релея звонок бывает и живым.
	//
	// Тот же дефект, что был с «verdict: ok» в задаче 51.2 — ошибка
	// понимания притворялась приговором.
	//
	// Ссылка при этом пропускается на эту попытку: кредов она не дала,
	// и следующую берём другую. Но не хоронится.
	errVKNoTurn = errors.New("VK не дал адреса релея для этого звонка")

	// errVKDead — хеш непригоден: звонка нет, ссылка недоступна.
	errVKDead = errors.New("хеш непригоден")

	// errVKServer — сбой НА СТОРОНЕ VK, не наш и не хеша. Хеш по такой
	// ошибке не помечается никак.
	errVKServer = errors.New("сбой на стороне VK")

	// errVKAllFailed — перебор хешей кончился без кредов, а годные в пуле
	// остались: VK не ответил за срок, не дал релея, сбоил. Итог перебора,
	// а не вердикт одному хешу.
	errVKAllFailed = errors.New("VK не дал кредов")
)

// vkCache — состояние хешей в этой сессии. Сами комплекты кредов живут
// дольше сессии — в credsShared ниже.
type vkCache struct {
	mu    sync.Mutex
	state map[string]string

	// deferred — хеши, отвалившиеся ПО СРОКУ. Не «мёртвые»: до них не
	// дошли, а не отказали. Политика и правило неопустошения пула — в
	// vkdefer.go. Только в памяти, на диск не пишется никогда.
	deferred map[string]hashDefer
}

type cachedCreds struct {
	creds turnCreds
	until time.Time
}

func newVKCache() *vkCache {
	return &vkCache{
		state: map[string]string{},
	}
}

// credsShared — комплекты кредов VK НА ВЕСЬ ПРОЦЕСС, а не на сессию.
//
// Было: кэш жил в сессии, и каждое переподключение через релей заново
// проходило цепочку VK из пяти шагов — секунды на подъём и главный повод
// для капчи. Комплект годен vkCredsTTL и от сессии не зависит, поэтому
// переживает её. Отметки «мёртвый»/«капча»/отсрочки остаются в vkCache,
// то есть в сессии, как и были: новая сессия судит хеши заново.
//
// Комплект, на котором релей отказал (TURN-аллокация), выбрасывается —
// forgetSharedCreds в raiseRelay, — иначе испорченный комплект держал бы
// релей до конца своего срока уже во многих сессиях подряд.
var credsShared = struct {
	mu sync.Mutex
	m  map[string]cachedCreds
}{m: map[string]cachedCreds{}}

func sharedCredsGet(hash string) (cachedCreds, bool) {
	credsShared.mu.Lock()
	defer credsShared.mu.Unlock()
	c, ok := credsShared.m[hash]
	return c, ok
}

func sharedCredsPut(hash string, c cachedCreds) {
	credsShared.mu.Lock()
	defer credsShared.mu.Unlock()
	credsShared.m[hash] = c
}

func forgetSharedCreds(hash string) {
	credsShared.mu.Lock()
	defer credsShared.mu.Unlock()
	delete(credsShared.m, hash)
}

// protectedHTTP — клиент, чьи сокеты исключены из туннеля.
//
// БЕЗ ЭТОГО ЦЕПОЧКА ЛОМАЕТ САМА СЕБЯ. Второй комплект кредов
// добывается, когда туннель УЖЕ поднят: запрос к VK ушёл бы в наш
// собственный туннель. Тот же инвариант, что и для сокета до шлюза,
// только для TCP.
func protectedHTTP(prot Protector) *http.Client {
	d := &net.Dialer{
		Timeout: vkStepTimeout,
		Control: func(network, address string, rc syscall.RawConn) error {
			if host, _, err := net.SplitHostPort(address); err == nil {
				excludeHost(prot, host) // адрес назначения уже разрешён Go
			}
			var perr error
			if err := rc.Control(func(fd uintptr) {
				if prot == nil {
					return
				}
				if !prot.Protect(int32(fd)) {
					perr = errors.New("VpnService.protect отказал на сокете к VK")
				}
			}); err != nil {
				return err
			}
			return perr
		},
	}
	return &http.Client{
		Timeout:   vkStepTimeout,
		Transport: &http.Transport{DialContext: resolvingDialContext(d, prot)},
	}
}

// CheckHash проверяет ОДИН хеш полной цепочкой и возвращает статус.
//
// Проверка НЕ автоматическая и не должна такой стать: каждый вызов —
// полный проход цепочки, то есть настоящий расход ссылки.
//
// Protector может быть nil: с экрана проверяют при погашенном туннеле.
func CheckHash(hash, deviceID string, prot Protector, captcha CaptchaSolver, log Logger) string {
	s := newSession(log)
	s.captcha = captcha
	ch, err := newVKChain(s, protectedHTTP(prot), hash)
	if err != nil {
		return "ошибка: " + err.Error()
	}

	// Тот же общий срок, что и на пути подключения: проверка с экрана
	// не должна вести себя иначе, чем настоящая добыча.
	ctx, cancel := context.WithTimeout(context.Background(), vkChainBudget)
	defer cancel()

	takeCredsGate(ctx, s.logf)
	if _, err := ch.run(ctx); err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			s.logf("VK: цепочка не уложилась в %s, добыто шагов %d из 5",
				vkChainBudget, ch.doneSteps)
		}
		// Текст ошибки в лог уходит в любом случае, даже когда наружу
		// возвращается один короткий вердикт.
		s.logf("VK: проверка хеша %s окончена неудачей: %v", shortHash(hash), err)
		switch {
		case errors.Is(err, errVKServer):
			return "сбой VK, попробуй позже"
		case errors.Is(err, errVKCaptcha):
			return "капча"
		case errors.Is(err, errVKNoTurn):
			return "релея нет"
		case errors.Is(err, errVKDead):
			return "мёртвый"
		}
		return "ошибка: " + err.Error()
	}
	return "живой"
}

// qparam — параметр запроса. Значение УЖЕ подготовлено вызывающим: где
// надо экранировано, где не надо — намеренно нет.
type qparam struct {
	k string
	v string
}

// vkChain — один проход цепочки для одного хеша.
type vkChain struct {
	s      *session
	client *http.Client
	hash   string

	// ДВА РАЗНЫХ идентификатора устройства, и путать их нельзя.
	//
	// deviceID идёт на шаги 1, 2 и 3 — один и тот же.
	// okDeviceID — ОТДЕЛЬНЫЙ, только внутри session_data на шаге 4.
	deviceID   string
	okDeviceID string

	// name — имя участника, генерируется один раз и идёт на шаг 3.
	// Его отсутствие и было той самой "Invalid name".
	name string

	// linkURL — ссылка на звонок. Строится ОДИН раз и уходит одной и той
	// же строкой на шаги 2 и 3; пересобирать нельзя.
	linkURL string

	lastStatus int
	lastBody   string

	// doneSteps — сколько шагов пройдено успешно. Нужно ровно для одной
	// строки: при исчерпании общего срока надо сказать, где встали.
	doneSteps int

	// solver — показ капчи человеку. nil означает «показать некому»:
	// тогда капча просто делает попытку неудачной.
	solver CaptchaSolver

	// captchaDone — капчу на этом проходе уже показывали.
	//
	// Ровно один показ на проход цепочки: если после решённой капчи VK
	// просит её снова, это не «человек ошибся», а что-то, чего мы не
	// понимаем, и крутить перед человеком экран по кругу нельзя.
	captchaDone bool
}

func newVKChain(s *session, client *http.Client, hash string) (*vkChain, error) {
	dev, err := uuid4()
	if err != nil {
		return nil, fmt.Errorf("генерация device_id: %w", err)
	}
	okDev, err := uuid4()
	if err != nil {
		return nil, fmt.Errorf("генерация ok device_id: %w", err)
	}
	name, err := genName()
	if err != nil {
		return nil, fmt.Errorf("генерация имени: %w", err)
	}
	return &vkChain{
		s:          s,
		client:     client,
		hash:       hash,
		deviceID:   dev,
		okDeviceID: okDev,
		name:       name,
		linkURL:    vkJoinBase + hash,
		solver:     s.captcha,
	}, nil
}

// fail — единственный способ вернуть неудачу шага. Строка в лог уходит
// ЗДЕСЬ, а не у вызывающего: иначе текст теряется, если вызывающий
// свёл ошибку к короткому вердикту.
func (c *vkChain) fail(step int, name string, err error) error {
	c.s.logf("VK: шаг %d/5 %s НЕ ПРОШЁЛ: %v | HTTP %d, ответ: %s",
		step, name, err, c.lastStatus, c.lastBody)
	return fmt.Errorf("шаг %d/5 %s: %w", step, name, err)
}

// fetchCreds добывает комплект кредов, перебирая хеши.
func (s *session) fetchCreds(ctx context.Context, c candidate, batch int, prot Protector) (turnCreds, error) {
	if len(c.hashes) == 0 {
		return turnCreds{}, errVKNoHashes
	}
	cache := s.vk
	client := protectedHTTP(prot)

	// ГОДНЫХ НЕ ОСТАЛОСЬ — это ТРЕТЬЕ состояние, и оно не то же самое,
	// что «все отложены». Разморозка тут не поможет: мёртвый и
	// закапчёванный от неё не оживают. Говорим прямо, иначе человек
	// увидит тишину и решит, что клиент думает.
	if live, dead, captcha := cache.poolSummary(c.hashes); live == 0 {
		s.logf("VK: годных ссылок не осталось — мёртвых %d, в капче %d. "+
			"Добавьте новые на экране обхода белых списков", dead, captcha)
	}

	// ПУСТОЙ ПУЛ НЕДОПУСТИМ. Если отложены все — снимаем отсрочки разом
	// и идём как раньше. Лучше десять секунд, чем «звонить некуда».
	if cache.unfreezeIfAllDeferred(c.hashes) {
		s.logf("VK: отложены были ВСЕ хеши — снимаю отсрочки со всех, " +
			"перебираю как обычно")
	}

	simDone := false
	start := batch % len(c.hashes)
	for i := 0; i < len(c.hashes); i++ {
		hash := c.hashes[(start+i)%len(c.hashes)]

		cache.mu.Lock()
		st := cache.state[hash]
		got, ok := sharedCredsGet(hash)
		cache.mu.Unlock()

		if st != hashOK {
			s.logf("VK: хеш %s пропущен (%s)", shortHash(hash), st)
			continue
		}

		// ОТЛОЖЕННЫЙ ПРОПУСКАЕМ, НО МЕСТА В ОЧЕРЕДИ НЕ ТЕРЯЕТ: порядок
		// перебора задан пачкой и не переставляется. Отсрочка кончится —
		// хеш вернётся туда же, где стоял.
		if until := cache.deferredUntil(hash); time.Now().Before(until) {
			s.logf("VK: хеш %s отложен ещё на %s (отваливался по сроку)",
				shortHash(hash), time.Until(until).Round(time.Second))
			continue
		}
		if ok && time.Now().Before(got.until) {
			s.logf("VK: комплект для хеша %s взят из кэша, годен ещё %s",
				shortHash(hash), time.Until(got.until).Round(time.Second))
			return got.creds, nil
		}

		// Общий срок на добычу — ОТСЧЁТ НАЧИНАЕТСЯ ЗДЕСЬ, до троттлинга.
		// Так пауза перед новым комплектом входит в те же пятнадцать
		// секунд, а не добавляется к ним.
		chainCtx, cancelChain := context.WithTimeout(ctx, vkChainBudget)

		// Троттлинг общий на всё приложение: капча считается на источник
		// обращений, а не на нашу внутреннюю раскладку.
		takeCredsGate(chainCtx, s.logf)

		// ИМИТАЦИЯ МЁРТВОГО ХЕША — только в отладочной сборке.
		//
		// На исправной сети настоящего тайм-аута не добиться, а
		// отсрочку хеша проверить надо. Притворяемся, что ПЕРВЫЙ
		// пробуемый хеш не ответил за срок: дальше отрабатывает та же
		// ветка isVKTimeout, что и на живом промахе.
		//
		// Срабатывает ОДИН РАЗ за перебор: иначе легли бы все хеши
		// разом, сработала бы разморозка, и проверялось бы не то.
		if s.simVKTimeout && !simDone {
			simDone = true
			cancelChain()
			step := cache.deferHash(hash)
			s.logf("ИМИТАЦИЯ: хеш %s «не ответил за срок» — откладываю на %s",
				shortHash(hash), step)
			continue
		}

		ch, err := newVKChain(s, client, hash)
		if err != nil {
			cancelChain()
			return turnCreds{}, err
		}
		creds, err := ch.run(chainCtx)

		// Исчерпание ОБЩЕГО срока отличаем от отказа шага: у шага свой
		// предохранитель, и по его срабатыванию видно другое.
		chainExpired := chainCtx.Err() == context.DeadlineExceeded
		cancelChain()
		if chainExpired {
			s.logf("VK: цепочка не уложилась в %s, добыто шагов %d из 5",
				vkChainBudget, ch.doneSteps)
		}

		if err == nil {
			cache.mu.Lock()
			creds.hash = hash
			sharedCredsPut(hash, cachedCreds{creds: creds, until: time.Now().Add(vkCredsTTL)})
			// Сработал — отсрочку и счётчик промахов забываем.
			delete(cache.deferred, hash)
			cache.mu.Unlock()
			s.logf("VK: комплект добыт по хешу %s, считаем годным %s",
				shortHash(hash), vkCredsTTL)
			return creds, nil
		}

		switch {
		case errors.Is(err, errVKServer):
			s.logf("VK: сбой на стороне VK по хешу %s — хеш не помечаю, беру следующий",
				shortHash(hash))
		case errors.Is(err, errVKCaptcha):
			cache.mu.Lock()
			cache.state[hash] = hashCaptcha
			cache.mu.Unlock()
			s.logf("VK: КАПЧА на хеше %s осталась нерешённой — "+
				"хеш отложен до конца сессии, МЁРТВЫМ не помечен", shortHash(hash))
		case errors.Is(err, errVKNoTurn):
			// НЕ помечаем никак: ни мёртвым, ни отложенным. Звонок
			// может быть жив, а релея не оказалось на этот раз.
			s.logf("VK: по хешу %s релея нет (%v) — хеш НЕ помечаю, беру следующий",
				shortHash(hash), err)
		case errors.Is(err, errVKDead):
			cache.mu.Lock()
			cache.state[hash] = hashDead
			cache.mu.Unlock()
			s.logf("VK: хеш %s мёртв (%v), больше к нему не возвращаюсь", shortHash(hash), err)
		case isVKTimeout(err):
			// ОТВАЛИЛСЯ ПО СРОКУ — откладываем, а не хороним. Разбор
			// политики и почему нельзя помечать мёртвым — в vkdefer.go.
			step := cache.deferHash(hash)
			s.logf("VK: хеш %s не ответил за срок — откладываю на %s, "+
				"мёртвым НЕ помечаю", shortHash(hash), step)
		default:
			s.logf("VK: хеш %s не дал кредов: %v", shortHash(hash), err)
		}
	}
	// ИТОГ ПО ПУЛУ — В ОШИБКУ, чтобы причина отказа (failure.go) сказала
	// человеку, что делать: решить капчу или добавить свежие ссылки.
	live, dead, captcha := cache.poolSummary(c.hashes)
	switch {
	case live == 0 && captcha > 0:
		return turnCreds{}, fmt.Errorf("%w: ни один из %d хешей не дал кредов (мёртвых %d, в капче %d)",
			errVKCaptcha, len(c.hashes), dead, captcha)
	case live == 0:
		return turnCreds{}, fmt.Errorf("%w: ни один из %d хешей не дал кредов, все мёртвые",
			errVKDead, len(c.hashes))
	}
	return turnCreds{}, fmt.Errorf("%w: ни один из %d хешей не дал кредов", errVKAllFailed, len(c.hashes))
}

// run проходит все пять шагов.
func (c *vkChain) run(ctx context.Context) (turnCreds, error) {
	s := c.s
	s.logf("VK: цепочка по хешу %s, шаг 1/5 auth.getAnonymToken", shortHash(c.hash))
	tok, err := c.step1(ctx)
	if err != nil {
		return turnCreds{}, c.fail(1, "auth.getAnonymToken", err)
	}
	c.doneSteps = 1

	s.logf("VK: шаг 2/5 messages.getCallPreview")
	userID, secret, err := c.step2(ctx, tok)
	if err != nil {
		return turnCreds{}, c.fail(2, "messages.getCallPreview", err)
	}
	c.doneSteps = 2

	s.logf("VK: шаг 3/5 messages.getAnonymCallToken")
	okTok, err := c.step3(ctx, tok, userID, secret)
	if err != nil {
		return turnCreds{}, c.fail(3, "messages.getAnonymCallToken", err)
	}
	c.doneSteps = 3

	s.logf("VK: шаг 4/5 auth.anonymLogin (хост calls.okcdn.ru)")
	sessionKey, err := c.step4(ctx)
	if err != nil {
		return turnCreds{}, c.fail(4, "auth.anonymLogin", err)
	}
	c.doneSteps = 4

	s.logf("VK: шаг 5/5 vchat.joinConversationByLink")
	creds, err := c.step5(ctx, okTok, sessionKey)
	if err != nil {
		return turnCreds{}, c.fail(5, "vchat.joinConversationByLink", err)
	}
	c.doneSteps = 5
	return creds, nil
}

// step1 — анонимный токен.
//
// Точный состав параметров этого шага в выписке не приводился: он и так
// проходил. Форму запроса привели к общей, набор параметров не трогали.
func (c *vkChain) step1(ctx context.Context) (string, error) {
	ps := []qparam{
		{"v", vkAPIVersion},
		{"client_id", vkClientID},
		{"device_id", url.QueryEscape(c.deviceID)},
		{"lang", "en"},
	}
	m, err := c.post(ctx, ps, vkAPIHost+"/method/auth.getAnonymToken")
	if err != nil {
		return "", err
	}
	c.logFields(1, m)

	tok := digStr(m, "response", "token")
	if tok == "" {
		tok = digStr(m, "response", "anonymous_token")
	}
	if tok == "" {
		return "", errors.New("не нашёл anonymous_token в ответе")
	}
	return tok, nil
}

// step2 — сведения о звонке. Порядок параметров как в рабочем коде.
func (c *vkChain) step2(ctx context.Context, tok string) (string, string, error) {
	ps := []qparam{
		{"v", vkAPIVersion},
		{"anonymous_token", url.QueryEscape(tok)},
		{"device_id", url.QueryEscape(c.deviceID)},
		{"extended", "1"},
		{"fields", "first_name,last_name,photo_200"},
		{"lang", "en"},
		{"link", url.QueryEscape(c.linkURL)},
	}
	m, err := c.post(ctx, ps, vkAPIHost+"/method/messages.getCallPreview")
	if err != nil {
		return "", "", err
	}
	c.logFields(2, m)

	userID := digStr(m, "response", "user_id")
	secret := digStr(m, "response", "secret")
	if userID == "" || secret == "" {
		// НЕ errVKDead: отсутствие полей означает лишь, что мы не нашли
		// их там, где ищем. Вердикт "мёртвый" ставит только classify.
		return "", "", errors.New("не нашёл user_id и secret в ответе")
	}
	return userID, secret, nil
}

// step3 — тот самый шаг, что падал четыре захода.
//
// Причина была буквальной: VK ждёт параметр name, а мы его не слали
// вовсе. "Invalid name" означало «нет параметра name», а не «неверное
// имя параметра».
func (c *vkChain) step3(ctx context.Context, tok, userID, secret string) (string, error) {
	ps := []qparam{
		{"v", vkAPIVersion},
		// Тот же токен, что на шагах 1-2: своего шаг 2 не выдаёт.
		{"anonymous_token", url.QueryEscape(tok)},
		{"device_id", url.QueryEscape(c.deviceID)},
		// ТА ЖЕ строка, что на шаге 2. Пересобирать нельзя.
		{"link", url.QueryEscape(c.linkURL)},
		{"name", url.QueryEscape(c.name)},
		// Десятичная строка без экспоненты — этим занимается digStr.
		{"user_id", userID},
		{"secret", url.QueryEscape(secret)},
		{"lang", "en"},
	}
	c.logValueLens(3, ps)

	m, err := c.post(ctx, ps, vkAPIHost+"/method/messages.getAnonymCallToken")
	if err != nil {
		return "", err
	}
	c.logFields(3, m)

	// Это okAnonymToken, НЕ путать с токеном шага 1.
	t := digStr(m, "response", "token")
	if t == "" {
		return "", errors.New("не нашёл okAnonymToken в ответе")
	}
	return t, nil
}

// step4 — вход на стороне Odnoklassniki. Параметра v тут НЕТ.
func (c *vkChain) step4(ctx context.Context) (string, error) {
	sessionData := fmt.Sprintf(
		`{"version":2,"device_id":"%s","client_version":"1.0.1"}`, c.okDeviceID)

	ps := []qparam{
		{"session_data", url.QueryEscape(sessionData)},
		{"method", "auth.anonymLogin"},
		{"format", "JSON"},
		{"application_key", vkAppKey},
	}
	c.logValueLens(4, ps)

	m, err := c.post(ctx, ps, vkCallsEndpoint)
	if err != nil {
		return "", err
	}
	c.logFields(4, m)

	// На ВЕРХНЕМ уровне, не под response.
	k := digStr(m, "session_key")
	if k == "" {
		return "", errors.New("не нашёл session_key на верхнем уровне ответа")
	}
	return k, nil
}

// step5 — присоединение к звонку и выдача кредов TURN.
func (c *vkChain) step5(ctx context.Context, okTok, sessionKey string) (turnCreds, error) {
	ps := []qparam{
		// СЫРОЙ хеш, без URL и без экранирования — единственный шаг, где
		// идёт голый хеш. Открытый вопрос из задачи 29 закрыт: здесь он
		// верен, а на шагах 2-3 нужна полная ссылка.
		{"joinLink", c.hash},
		{"isVideo", "false"},
		{"protocolVersion", "5"},
		// Токен ШАГА 3, не шага 1.
		{"anonymToken", url.QueryEscape(okTok)},
		{"method", "vchat.joinConversationByLink"},
		{"format", "JSON"},
		{"application_key", vkAppKey},
		{"session_key", url.QueryEscape(sessionKey)},
	}
	c.logValueLens(5, ps)

	m, err := c.post(ctx, ps, vkCallsEndpoint)
	if err != nil {
		return turnCreds{}, err
	}
	c.logFields(5, m)

	user := digStr(m, "turn_server", "username")
	pass := digStr(m, "turn_server", "credential")
	if user == "" || pass == "" {
		// Не errVKDead: отсутствие turn_server не делает ссылку
		// мёртвой, см. объявление errVKNoTurn.
		return turnCreds{}, fmt.Errorf("%w: в ответе нет turn_server", errVKNoTurn)
	}

	addrs := turnAddresses(m)
	if len(addrs) > 0 {
		c.s.logf("VK: релеи из ответа: %s", strings.Join(addrs, ", "))
	} else {
		c.s.logf("VK: адресов релея в ответе нет, останется адрес из настроек")
	}
	return turnCreds{user: user, pass: pass, addrs: addrs}, nil
}

// turnAddresses вытаскивает адреса релеев из turn_server.urls.
//
// Префикс turn:/turns: снимается, хвост после "?" отбрасывается:
// pion/turn ждёт голое "хост:порт".
func turnAddresses(m map[string]any) []string {
	node, ok := m["turn_server"].(map[string]any)
	if !ok {
		return nil
	}
	list, ok := node["urls"].([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(list))
	for _, v := range list {
		s, ok := v.(string)
		if !ok {
			continue
		}
		s = strings.TrimPrefix(s, "turns:")
		s = strings.TrimPrefix(s, "turn:")
		if q := strings.IndexByte(s, '?'); q >= 0 {
			s = s[:q]
		}
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

// setHeaders ставит РОВНО четыре заголовка — как в рабочем коде.
// Content-Type не ставится: тела нет вовсе.
func setHeaders(req *http.Request) {
	req.Header.Set("User-Agent", vkUserAgent)
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Encoding", "gzip, deflate, br, zstd")
	req.Header.Set("Accept-Language", "en-GB,en;q=0.9")
}

// post отправляет POST с ПУСТЫМ телом и параметрами в строке запроса.
func (c *vkChain) post(ctx context.Context, ps []qparam, endpoint string) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(ctx, vkStepTimeout)
	defer cancel()

	parts := make([]string, 0, len(ps))
	names := make([]string, 0, len(ps))
	for _, p := range ps {
		parts = append(parts, p.k+"="+p.v)
		names = append(names, p.k)
	}
	full := endpoint + "?" + strings.Join(parts, "&")

	// Имена параметров — всегда, значения — никогда. По ним видно, чего
	// не хватает: ровно так и нашлась пропажа параметра name.
	c.s.logf("VK: → %s, параметры: %s",
		strings.TrimPrefix(endpoint, "https://"), strings.Join(names, ", "))

	// Тело ПУСТОЕ, и именно nil, а не пустой Reader: с телом Go выставил
	// бы Content-Length, которого в рабочей форме нет.
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, full, nil)
	if err != nil {
		return nil, err
	}
	setHeaders(req)

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := readBody(resp)
	if err != nil {
		return nil, err
	}

	c.lastStatus = resp.StatusCode
	c.lastBody = cut(body, bodyFailLen)

	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, fmt.Errorf("ответ не JSON: %v", err)
	}
	// Разобралось — показываем затёртую версию: в сыром теле бывают и
	// токены, и полная ссылка на звонок.
	c.lastBody = c.maskHash(briefLen(m, bodyFailLen))

	if err := classify(m); err != nil {
		// Капча — единственный отказ, который можно исправить, не
		// меняя запрос: его исправляет человек.
		if errors.Is(err, errVKCaptcha) && !c.captchaDone {
			c.captchaDone = true
			// Исход разбирается типом, а не пустой строкой. Сегодня
			// все неудачные исходы ведут себя одинаково — попытка
			// провалена, — но каждый из них теперь назван, и добавить
			// ветку можно, не переделывая разбор.
			if token, out := c.solveCaptcha(m); out == captchaSolved {
				return c.retryWithCaptcha(ctx, ps, endpoint, token)
			}
		}
		return nil, err
	}
	return m, nil
}

// retryWithCaptcha повторяет тот же запрос, добавив решённую капчу.
//
// ФОРМА ПЕРЕДАЧИ ТОКЕНА НЕ ПОДТВЕРЖДЕНА. Капчи мы не видели ни разу, в
// разведке имени параметра нет, и captchaTokenParam — обоснованная
// догадка, а не выписка. Первый живой случай её поправит: и запрос, и
// ответ логируются целиком.
//
// Повтор ровно один: captchaDone уже взведён, и второй капчи на этом
// проходе не будет.
func (c *vkChain) retryWithCaptcha(
	ctx context.Context, ps []qparam, endpoint, token string,
) (map[string]any, error) {
	c.s.logf("VK: повтор запроса с решённой капчей, параметр %s", captchaTokenParam)
	return c.post(ctx, append(ps, qparam{captchaTokenParam, url.QueryEscape(token)}), endpoint)
}

// readBody читает тело, распаковывая при необходимости.
//
// РАСПАКОВЫВАТЬ ПРИХОДИТСЯ САМИМ, и это прямое следствие требования
// ставить свой Accept-Encoding. Go добавляет gzip и распаковывает
// прозрачно ТОЛЬКО когда заголовок пуст — условие
// `req.Header.Get("Accept-Encoding") == ""` в net/http/transport.go.
// Мы его ставим, значит авто-распаковка выключена, и сырой gzip дошёл
// бы до json.Unmarshal.
//
// br и zstd в стандартной библиотеке отсутствуют. Если сервер ответит
// ими, честно скажем об этом, а не отдадим наверх мусор.
func readBody(resp *http.Response) ([]byte, error) {
	r := io.LimitReader(resp.Body, 1<<20)

	switch enc := strings.ToLower(resp.Header.Get("Content-Encoding")); enc {
	case "", "identity":
		return io.ReadAll(r)
	case "gzip":
		zr, err := gzip.NewReader(r)
		if err != nil {
			return nil, fmt.Errorf("распаковка gzip: %w", err)
		}
		defer zr.Close()
		return io.ReadAll(io.LimitReader(zr, 1<<20))
	default:
		return nil, fmt.Errorf(
			"ответ сжат как %q, а распаковать этим мы не умеем — "+
				"в стандартной библиотеке нет ни br, ни zstd", enc)
	}
}

// classify — капча, мёртвый хеш и сбой VK по признакам разведки.
//
// По ПОЛНОМУ телу, а не по обрезку: признак легко уезжает за границу
// выжимки, потому что стоит после request_params.
func classify(m map[string]any) error {
	raw, err := json.Marshal(m)
	if err != nil {
		return nil
	}
	blob := strings.ToLower(string(raw))
	switch {
	case strings.Contains(blob, "internal server error"),
		strings.Contains(blob, `"error_code":10,`),
		strings.Contains(blob, `"error_code":10}`):
		return errVKServer
	case strings.Contains(blob, "captcha_required"),
		strings.Contains(blob, "captcha_wait_required"):
		return errVKCaptcha
	// Мёртвая ссылка: звонка нет вовсе.
	case strings.Contains(blob, "call not found"),
		strings.Contains(blob, "callunavailable"):
		return errVKDead
	// Звонок есть, релея к нему нет. Это НЕ смерть ссылки.
	case strings.Contains(blob, "missing turn_server"):
		return errVKNoTurn
	}
	return nil
}

// logFields выводит ДЕРЕВО ИМЁН полей ответа, без единого значения.
// Рядом с именем — тип и, для строк, длина: она безопасна и сразу
// отличает короткий код от токена.
func (c *vkChain) logFields(step int, m map[string]any) {
	var out []string
	collectFields(m, "", 0, &out)
	c.s.logf("VK: шаг %d/5, поля ответа: %s", step, strings.Join(out, ", "))
}

func collectFields(v any, prefix string, depth int, out *[]string) {
	if depth > 3 {
		return
	}
	switch t := v.(type) {
	case map[string]any:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			path := prefix + k
			*out = append(*out, path+fieldTag(t[k]))
			collectFields(t[k], path+".", depth+1, out)
		}
	case []any:
		if len(t) > 0 {
			collectFields(t[0], prefix+"[].", depth+1, out)
		}
	}
}

func fieldTag(v any) string {
	switch t := v.(type) {
	case string:
		return fmt.Sprintf("(строка,%d)", len(t))
	case float64:
		return "(число)"
	case bool:
		return "(да/нет)"
	case map[string]any:
		return "(объект)"
	case []any:
		return fmt.Sprintf("(список,%d)", len(t))
	case nil:
		return "(пусто)"
	}
	return "(?)"
}

// logValueLens печатает ДЛИНЫ отправляемых значений, не сами значения.
// Отвечает на один вопрос: не подставилось ли что-то пустым.
func (c *vkChain) logValueLens(step int, ps []qparam) {
	parts := make([]string, 0, len(ps))
	for _, p := range ps {
		parts = append(parts, fmt.Sprintf("%s=%d", p.k, len(p.v)))
	}
	c.s.logf("VK: шаг %d/5, длины значений: %s", step, strings.Join(parts, ", "))
}

// maskHash прячет наш хеш, если он всё-таки оказался в теле ответа.
func (c *vkChain) maskHash(s string) string {
	if len(c.hash) <= 8 {
		return s
	}
	return strings.ReplaceAll(s, c.hash, shortHash(c.hash))
}

// digStr достаёт строку по пути в разобранном JSON.
//
// Числа приводятся к ДЕСЯТИЧНОЙ строке без экспоненты и дробной части:
// VK отдаёт user_id числом, а шагу 3 нужна именно такая строка.
func digStr(m map[string]any, path ...string) string {
	var cur any = m
	for _, p := range path {
		node, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = node[p]
	}
	switch v := cur.(type) {
	case string:
		return v
	case float64:
		return fmt.Sprintf("%.0f", v)
	}
	return ""
}

func brief(m map[string]any) string { return briefLen(m, bodyBriefLen) }

// briefLen — выжимка ответа заданной длины, с затиранием чувствительных
// значений.
func briefLen(m map[string]any, limit int) string {
	b, err := json.Marshal(redact(m))
	if err != nil {
		return "?"
	}
	return cut(b, limit)
}

func cut(b []byte, limit int) string {
	if len(b) > limit {
		return string(b[:limit]) + "…"
	}
	return string(b)
}

// redact затирает значения, которые не должны попасть в лог, оставляя
// имена полей.
//
// В ответе об ошибке VK возвращает request_params — список присланного,
// а там оказываются и токены, и параметр link с ПОЛНОЙ ссылкой на
// звонок. Без затирания хеш утёк бы в лог целиком.
func redact(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		// Пара вида {"key":"secret","value":"…"}: имя поля там всегда
		// "value", и решать надо по соседнему "key".
		keyName, _ := t["key"].(string)
		for k, val := range t {
			if k == "value" && sensitiveName(keyName) {
				out[k] = "…затёрто…"
				continue
			}
			if sensitiveName(k) {
				out[k] = "…затёрто…"
				continue
			}
			out[k] = redact(val)
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, val := range t {
			out[i] = redact(val)
		}
		return out
	case string:
		if looksSecret(t) {
			return "…затёрто…"
		}
		return t
	}
	return v
}

func sensitiveName(k string) bool {
	k = strings.ToLower(k)
	for _, bad := range []string{
		"token", "secret", "credential", "password", "sig", "session", "link",
	} {
		if strings.Contains(k, bad) {
			return true
		}
	}
	return false
}

// looksSecret — длинная строка из алфавита токенов. Порог высокий:
// короткие коды и версии затирать незачем.
func looksSecret(s string) bool {
	if len(s) < 32 {
		return false
	}
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		case r == '_', r == '-', r == '+', r == '/', r == '=', r == '.', r == ':':
		default:
			return false
		}
	}
	return true
}

// shortHash — хеш в логе показывается обрезанным: это ссылка на звонок,
// по ней можно присоединиться.
func shortHash(h string) string {
	if len(h) <= 8 {
		return "…"
	}
	return h[:4] + "…" + h[len(h)-4:]
}

// joinLink собирает полную ссылку на звонок из хранимого хеша.
//
// ХРАНИМ ХЕШ, ШЛЁМ ССЫЛКУ: при добавлении ссылка обрезается до хеша,
// а VK голый хеш на шагах 2-3 не принимает — отвечает 954, "Invalid
// join link: link param contains unsupported URL".
func joinLink(hash string) string { return vkJoinBase + hash }

// uuid4 — случайный UUID версии 4.
func uuid4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40 // версия 4
	b[8] = (b[8] & 0x3f) | 0x80 // вариант RFC 4122
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}

// genName — имя участника для шага 3.
//
// ДОСТРОЕНО: как именно его составляет рабочий код, в выписке не
// сказано. Важно, что параметр вообще есть и не пуст — его отсутствие и
// давало "Invalid name". Латиница намеренно: имя уходит в чужой сервис.
func genName() (string, error) {
	var b [3]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return fmt.Sprintf("Guest%02x%02x%02x", b[0], b[1], b[2]), nil
}
