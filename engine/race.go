package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// raceTeardownLimit — сколько ждём, пока проигравшие ступени умрут.
//
// В норме это миллисекунды: победитель отменяет общий контекст, чужие
// рукопожатия обрываются, сокеты закрываются. Срок нужен только затем,
// чтобы не ждать вечно, если что-то застряло.
const raceTeardownLimit = 3 * time.Second

// noDtlsHandicap — фора, которую ступень без DTLS даёт остальным.
//
// Без неё честной гонки не выходит: рукопожатия у 56005 нет, она готова
// через миллисекунду после protect и побеждает всегда — на устройстве
// это и вышло, 1 мс против ступеней, которые даже не начали. А между тем
// именно 56005 давала "принято 0", то есть побеждала не лучшая, а самая
// быстрая на старте.
//
// Полторы секунды — это два первых ретрансмита pion (1 и 3 секунды,
// initialTickerInterval с удвоением): успевает то рукопожатие, которое
// прошло с первой попытки. Цена промаха — полторы секунды вместо
// прежних десяти на последовательный перебор.
const noDtlsHandicap = 1500 * time.Millisecond

// errRaceStuck — проигравшие не погасли за отведённый срок.
//
// Это НЕ повод отправить AUTH и понадеяться. Живой сокет проигравшей
// ступени, если он всё-таки дойдёт до AUTH, займёт слот по паролю и
// выбьет победителя. Поэтому исход один: отменить попытку целиком.
var errRaceStuck = errors.New("проигравшие ступени не погасли вовремя, попытка отменена")

// isNetworkGone — ошибка означает, что СЕТЬ ушла из-под ног, а не что
// ступень плоха.
//
// Различать обязательно. Наш UDP-сокет неявно привязан к адресу того
// интерфейса, что был маршрутом по умолчанию в момент первой отправки.
// Пропал интерфейс — все последующие sendto отдают ENETUNREACH, и это
// говорит о сети, а не о порте. Списывать ступень со счетов по такой
// ошибке значит забыть рабочий путь из-за того, что человек вошёл в
// зону Wi-Fi.
//
// EHOSTUNREACH ЗДЕСЬ БОЛЬШЕ НЕТ, и это исправление.
//
// Три оставшиеся ошибки говорят о СЕТИ ЦЕЛИКОМ: маршрута нет вовсе,
// интерфейс лёг, адрес-источник исчез. При любой из них следующая
// ступень упрётся ровно в то же самое, и обрывать попытку правильно.
//
// А EHOSTUNREACH говорит о КОНКРЕТНОМ адресе назначения: до этого
// хоста нет пути. Сеть при этом жива, и релей — он на другом адресе —
// вполне может ответить. Обрывая по ней всю попытку, мы отменяли бы
// автопереход на релей ровно в том случае, ради которого он и заведён:
// прямой путь закрыт, а окольный открыт.
func isNetworkGone(err error) bool {
	return errors.Is(err, syscall.ENETUNREACH) ||
		errors.Is(err, syscall.ENETDOWN) ||
		errors.Is(err, syscall.EADDRNOTAVAIL)
}

// rung — поднятый транспорт ступени, ещё БЕЗ AUTH.
//
// Разделение здесь не косметическое, оно и есть суть задачи: слот по
// паролю на шлюзе занимает AUTH, а не рукопожатие (путь к register() и
// assignClientIP() открывается только после завершённого AUTH). Значит
// транспорт можно поднимать всеми ступенями разом, а вот AUTH обязан
// уйти ровно один.
type rung struct {
	// retired — слот снят автоподбором планово.
	//
	// Насосы обязаны различать плановое снятие и обрыв: по обрыву
	// гасится вся сессия, а по снятию — только этот слот.
	retired atomic.Bool

	cand candidate
	link packetLink
	pc   net.PacketConn
	obfs *obfsPacketConn
}

func (r *rung) close() {
	if r == nil {
		return
	}
	if r.link != nil {
		_ = r.link.Close()
	}
	if r.pc != nil {
		_ = r.pc.Close()
	}
}

// droppedCount — сколько встречных пакетов отброшено проверкой
// целостности. Считает и UDP (obfsPacketConn), и поток (streamLink):
// у потоковой ступени obfs пуст, счётчик живёт в самом линке.
func (r *rung) droppedCount() uint64 {
	if r.obfs != nil {
		return r.obfs.dropped.Load()
	}
	if sl, ok := r.link.(*streamLink); ok {
		return sl.dropped.Load()
	}
	return 0
}

// raise поднимает транспорт одной ступени: сокет, protect, рукопожатие
// там, где оно есть. AUTH НЕ отправляет.
func (s *session) raise(
	ctx context.Context, gateway string, c candidate, password string,
	prot Protector, failDirect bool, budget time.Duration,
) (*rung, error) {
	if c.isRelay {
		// Релейная ступень поднимается иначе — через TURN-аллокацию, —
		// но дальше идёт по тем же слоям и тем же AUTH.
		//
		// Креды добываются ЗДЕСЬ ЖЕ, цепочкой. Раньше тут стояло
		// c.credsFor(0), то есть статический комплект из настроек, и
		// цепочка на пути подключения не запускалась вовсе: она
		// отрабатывала только по кнопке с экрана. Отсюда и «401
		// Unauthorized» на вчерашних кредах.
		//
		// Основной линк — всегда пачка ноль.
		cr, err := s.relayCreds(ctx, c, 0, prot)
		if err != nil {
			return nil, err
		}
		return s.raiseRelay(ctx, gateway, c, cr, password, prot)
	}
	// ПОТОКОВЫЕ СТУПЕНИ (TCP/TLS) идут своим путём: транспорт — поток,
	// obfs лежит в кадрах, UDP-сокета нет вовсе. Всё, что ниже, — про
	// UDP, к ним неприменимо.
	//
	// ВЕТКА СТОИТ ВЫШЕ ИМИТАЦИИ НАМЕРЕННО. Раньше failDirect отрезал
	// вообще всё непрелейное, включая потоки, и включённая имитация
	// проверяла не то: сеть, где нет НИ UDP, НИ TCP, — а такой мы не
	// лечим, там остаётся один релей. Смысл имитации в другом: показать
	// сеть, режущую UDP, где потоковые ступени и должны выручать.
	//
	// Флаг появился до потоков (0.1.102), и слово «прямые» в его имени
	// значило именно UDP-прямые. Теперь оно значит то же и на деле.
	if c.transport == transTCP || c.transport == transTLS {
		return s.raiseStream(ctx, gateway, c, password, prot, budget)
	}

	if failDirect {
		return nil, errDirectSimulatedBlock
	}

	key, err := deriveWrapKey(password)
	if err != nil {
		return nil, fmt.Errorf("вывод ключа: %w", err)
	}
	// Состояние обфускации своё на каждую ступень: ssrc и стартовые
	// seq/ts обязаны быть новыми, иначе вторая попытка выглядела бы для
	// шлюза продолжением первой. В гонке это тем более обязательно —
	// ступени поднимаются одновременно.
	state, err := newObfsState(key)
	if err != nil {
		return nil, fmt.Errorf("инициализация обфускации: %w", err)
	}

	// Адрес шлюза разбирается ДО сокета: он нужен пробе адреса
	// источника внутри listenProtected.
	// Адрес СТУПЕНИ, если он у неё свой. Общий из Connect остаётся для
	// лестницы из зашитых настроек и для релея.
	host := c.host
	if host == "" {
		host = gateway
	}
	raddr, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(host, strconv.Itoa(int(c.port))))
	if err != nil {
		return nil, fmt.Errorf("разбор адреса шлюза: %w", err)
	}

	udp, err := s.listenProtected(ctx, prot, raddr.String(), c.name)
	if err != nil {
		return nil, err
	}

	obfs := newObfsPacketConn(udp, state, s.logf)

	var link packetLink
	if c.useDTLS {
		link, err = s.dialDTLS(ctx, c, obfs, raddr, udp, budget)
		if err != nil {
			udp.Close()
			return nil, err
		}
	} else {
		// Без DTLS рукопожатия нет вовсе: obfs — единственный слой,
		// и ступень готова к AUTH сразу после protect.
		s.logf("%s: без DTLS, рукопожатия нет — готов сразу", c.name)
		link = newRawLink(obfs, raddr, c.mtu, s.logf)
	}

	return &rung{cand: c, link: link, pc: udp, obfs: obfs}, nil
}

// protectControl — общий Control для сокетов, которые обязаны идти мимо
// туннеля.
//
// Control выполняется ДО bind — это единственный момент, когда
// дескриптор уже есть, а сокет ещё не используется.
func protectControl(prot Protector, logf func(string, ...interface{}), name string) func(string, string, syscall.RawConn) error {
	return func(network, address string, rc syscall.RawConn) error {
		var perr error
		if err := rc.Control(func(fd uintptr) {
			if prot == nil {
				logf("%s: protect(fd) пропущен, Protector не задан", name)
				return
			}
			// Явно, потому что "не вернул false" и "подействовал" —
			// разные вещи, а видно нам было только первое.
			ok := prot.Protect(int32(fd))
			logf("%s: protect(fd) вернул %v", name, ok)
			if !ok {
				perr = errors.New("VpnService.protect отказал")
			}
		}); err != nil {
			return err
		}
		return perr
	}
}

// sourceFor — адрес источника, который ядро выберет для пути к peer
// МИМО туннеля.
//
// Пробный сокет ничего не отправляет: connect на UDP — это поиск
// маршрута и запись выбранного адреса в сокет, а не обмен с той
// стороной. Ни одного байта в сеть не уходит.
//
// protect() пробе нужен ровно так же, как боевому сокету: без него
// поиск маршрута пойдёт по туннелю и вернёт туннельный адрес — то
// самое, что мы и лечим.
func (s *session) sourceFor(ctx context.Context, prot Protector, peer, name string) string {
	d := &net.Dialer{Control: protectControl(prot, s.logf, name+" (проба)")}
	c, err := d.DialContext(ctx, "udp4", peer)
	if err != nil {
		s.logf("%s: пробу адреса источника сделать не вышло: %v", name, err)
		return ""
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok || ua.IP == nil || ua.IP.IsUnspecified() {
		return ""
	}
	return ua.IP.String()
}

// listenProtected открывает UDP-сокет и исключает его из туннеля.
//
// Сокет ОБЯЗАН быть исключён через VpnService.protect(), иначе трафик
// уйдёт сам в себя.
//
// АДРЕС ИСТОЧНИКА ПРИБИВАЕТСЯ ПРИ BIND, и это не украшение.
//
// Раньше сокет открывался на 0.0.0.0. У несвязанного UDP-сокета ядро
// выбирает адрес источника ЗАНОВО на каждый sendto, по текущей таблице
// маршрутов. protect() уводит мимо туннеля МАРШРУТ, но адрес источника
// не прибивает. Пока туннеля нет — выбор один; после establish() в
// системе появляется tun0 со своим адресом, и выбор может стать другим.
//
// Шлюз различает клиентов ПО АДРЕСУ ИСТОЧНИКА (спецификация-2,
// udp/conn.go:277, raddr.String()). Пакет, ушедший с туннельного
// адреса, для него не наш клиент, а чужой, и он его не принимает.
// Признак ровно тот, что был в логе 26.08: AUTH проходит всегда — он
// уходит ДО establish(), — а сразу после "туннель поднят" отправка
// перестаёт доходить: у нас 127 отправлено, у шлюза 3 принято.
//
// Bind на конкретный адрес закрывает это навсегда: выбор делается один
// раз, до establish(), и ядро к нему больше не возвращается.
//
// Почему не connect на боевом сокете, чего просилось само: у связанного
// UDP-сокета ядро отдаёт ICMP-ошибки как ECONNREFUSED, и случайный
// "port unreachable" от промежуточного узла убивал бы живую сессию.
// Bind даёт тот же выигрыш и не трогает поведение при ICMP.
//
// peer — куда сокет будет смотреть, "host:port". Пусто (релейная
// ступень, где адрес релея на этот момент ещё не известен) — остаётся
// прежнее поведение, 0.0.0.0.
//
// Одна на всех: релейной ступени protect нужен ровно так же, как
// прямой, только сокет у неё смотрит на релей, а не на шлюз.
func (s *session) listenProtected(ctx context.Context, prot Protector, peer, name string) (net.PacketConn, error) {
	lc := &net.ListenConfig{Control: protectControl(prot, s.logf, name)}

	s.logf("%s: открываю сокет", name)

	laddr := ":0"
	if peer != "" {
		if src := s.sourceFor(ctx, prot, peer, name); src != "" {
			laddr = net.JoinHostPort(src, "0")
			s.logf("%s: адрес источника %s — прибиваю сокет к нему", name, src)
		} else {
			s.logf("%s: адрес источника не определён — сокет на 0.0.0.0", name)
		}
	}

	udp, err := lc.ListenPacket(ctx, "udp4", laddr)
	if err != nil && laddr != ":0" {
		// Адрес мог уйти между пробой и bind — ровно в это мгновение
		// сменилась сеть. Это не повод не подняться вовсе.
		s.logf("%s: bind на %s не удался (%v) — беру 0.0.0.0", name, laddr, err)
		udp, err = lc.ListenPacket(ctx, "udp4", ":0")
	}
	if err != nil {
		return nil, fmt.Errorf("открытие UDP-сокета: %w", err)
	}
	// Печатаем то, что вернуло ядро, а не то, что мы просили: по этой
	// строке видно настоящий адрес источника, а не наше намерение.
	s.logf("%s: сокет на %s", name, udp.LocalAddr())
	return udp, nil
}

// race поднимает транспорт всех переданных ступеней ОДНОВРЕМЕННО и
// возвращает ту, что первой дошла до готовности отправить AUTH.
//
// ГЛАВНОЕ СВОЙСТВО: к моменту возврата все проигравшие ступени закрыты
// по факту, а не по таймеру. Механика — WaitGroup: каждый участник
// закрывает свои ресурсы ДО того, как отметится завершённым, а race
// дожидается всех до единого, прежде чем отдать победителя наверх.
// Только после этого вызывающий отправляет AUTH.
//
// Если дождаться не удалось, возвращается errRaceStuck и победитель
// тоже закрывается: отправить AUTH при живом чужом сокете нельзя — он
// займёт слот по паролю и выбьет нас же.
func (s *session) race(
	ctx context.Context, gateway string, cands []candidate, password string,
	prot Protector, failDirect bool, budget time.Duration,
) (*rung, error) {
	raceCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	var wg sync.WaitGroup
	var claimed atomic.Bool
	winner := make(chan *rung, 1)
	errs := make(chan error, len(cands))

	// Фора имеет смысл, только если есть кому её отдавать. Если в гонке
	// одни ступени без DTLS, ждать полторы секунды не за чем.
	var dtlsLeft atomic.Int32
	for _, c := range cands {
		if c.useDTLS {
			dtlsLeft.Add(1)
		}
	}
	hasDTLS := dtlsLeft.Load() > 0

	// dtlsGone закрывается, когда УПАЛА последняя ступень с DTLS.
	// Тогда фору держать не перед кем: ждать оставшееся время значило бы
	// подарить полторы секунды впустую. Ступень с DTLS, которая победила,
	// счётчик не трогает — там сработает отмена контекста.
	dtlsGone := make(chan struct{})
	var goneOnce sync.Once

	// netGone — под нами сменилась сеть. Тогда гонка бессмысленна вся
	// целиком: ступени идут через один маршрут по умолчанию.
	var netGone atomic.Bool

	s.logf("гонка транспорта: ступеней %d, срок %s, фора без DTLS %v",
		len(cands), budget.Round(time.Millisecond), hasDTLS)
	started := time.Now()

	for _, c := range cands {
		wg.Add(1)
		go func(c candidate) {
			defer wg.Done()

			r, err := s.raise(raceCtx, gateway, c, password, prot, failDirect, budget)
			if err != nil {
				if isNetworkGone(err) {
					// Не отказ ступени, а уход сети. Остальным ловить
					// нечего — обрываем гонку целиком.
					s.logf("%s: сеть ушла из-под ног: %v", c.name, err)
					netGone.Store(true)
					cancel()
					return
				}
				s.logf("%s: транспорт не поднялся: %v", c.name, err)
				errs <- fmt.Errorf("%s: %w", c.name, err)
				if c.useDTLS && dtlsLeft.Add(-1) == 0 {
					goneOnce.Do(func() { close(dtlsGone) })
				}
				return
			}

			// Фора. Ступень без DTLS готова сразу после protect, и без
			// этой паузы она забирает первенство всегда, даже когда 443
			// доступен и лучше.
			//
			// Кроме уже пробованной: ей эту фору уже отдали, когда шли
			// к ней первой и без гонки. См. candidate.spent.
			if !c.useDTLS && hasDTLS && !c.spent {
				// Запоминаем ДО сна: если сейчас никто ещё не победил, а
				// после сна победил — значит первенство отдала именно
				// фора, а не чужая расторопность.
				readyFirst := !claimed.Load()
				s.logf("%s: транспорт готов за %s, уступаю фору %s ступеням с DTLS",
					c.name, time.Since(started).Round(time.Millisecond), noDtlsHandicap)

				// ЗАМЕР СНА И ПРОВЕРКИ СЕТИ ПОРОЗНЬ.
				//
				// Повод: 27.08 в 14:27:54 фора в 1,5 секунды заняла
				// 6,199 — а проверка метки в той же сессии отработала
				// ровно за свои три. Значит задержка не постоянная
				// добавка, а что-то плавающее, и надо знать, ЧТО
				// именно: сон в Go или обращение в Kotlin за состоянием
				// сети. Второе идёт через JNI, и сколько оно стоит, мы
				// не измеряли ни разу.
				//
				// Молчим, пока укладываемся: строка на каждую ступень
				// при каждом подключении — это шум. Говорим только про
				// заметный перебор.
				sleptFrom := time.Now()
				timer := time.NewTimer(noDtlsHandicap)
				select {
				case <-timer.C:
				case <-raceCtx.Done():
					// Гонка кончилась или сессию остановили — досыпать
					// оставшееся время незачем.
					timer.Stop()
				case <-dtlsGone:
					// Все ступени с DTLS уже упали. Держать фору не перед
					// кем, и дарить остаток впустую тоже незачем.
					timer.Stop()
					s.logf("%s: ступеней с DTLS не осталось, фора снята досрочно", c.name)
				}
				if slept := time.Since(sleptFrom); slept > noDtlsHandicap+timingSlack {
					s.logf("%s: ЗАМЕР — фора просили %s, вышло %s",
						c.name, noDtlsHandicap, slept.Round(time.Millisecond))
				}

				if readyFirst && claimed.Load() {
					s.logf("ФОРА СРАБОТАЛА: %s был готов первым, но первенство ушло ступени с DTLS",
						c.name)
				}

				// Полторы секунды ожидания — ровно то окно, в которое
				// человек успевает войти в зону Wi-Fi. Проверяем сразу
				// после форы: слать AUTH с сокета, привязанного к
				// исчезнувшему адресу, значит потерять всю попытку.
				askedAt := time.Now()
				changed := s.networkChanged()
				if took := time.Since(askedAt); took > timingSlack {
					s.logf("%s: ЗАМЕР — вопрос о сети занял %s",
						c.name, took.Round(time.Millisecond))
				}
				if changed {
					s.logf("%s: за время форы сменилась сеть — гонку прекращаю", c.name)
					netGone.Store(true)
					r.close()
					cancel()
					return
				}
			}

			// Победитель ровно один: кто первым переставил флаг.
			if !claimed.CompareAndSwap(false, true) {
				s.logf("%s: опоздала, транспорт закрыт", c.name)
				r.close()
				return
			}

			s.logf("%s: транспорт готов ПЕРВЫМ за %s",
				c.name, time.Since(started).Round(time.Millisecond))
			winner <- r
			// Остальным больше незачем: отмена оборвёт их рукопожатия,
			// и они закроются сами, каждый за собой.
			cancel()
		}(c)
	}

	// ВОТ ОНА, ГАРАНТИЯ. Ждём завершения ВСЕХ участников, включая
	// проигравших. Каждый закрывает ресурсы до wg.Done(), значит после
	// этой строки чужих живых сокетов не осталось ни одного.
	if !waitTimeout(&wg, budget+raceTeardownLimit) {
		cancel()
		// Победителя тоже гасим: отправлять AUTH, не зная состояния
		// остальных, нельзя ни при каких обстоятельствах.
		select {
		case r := <-winner:
			r.close()
		default:
		}
		return nil, errRaceStuck
	}

	// Уход сети проверяем ПЕРЕД победителем: если маршрут сменился, его
	// транспорт всё равно привязан к исчезнувшему адресу.
	if netGone.Load() {
		select {
		case r := <-winner:
			r.close()
		default:
		}
		return nil, errNetworkGone
	}

	select {
	case r := <-winner:
		s.logf("гонка окончена за %s, победитель %s, чужих сокетов не осталось",
			time.Since(started).Round(time.Millisecond), r.cand.name)
		return r, nil
	default:
	}

	// Никто не поднялся. Собираем причины — их немного, по числу ступеней.
	close(errs)
	var last error
	for e := range errs {
		last = e
	}
	if last == nil {
		last = errors.New("ни одной ступени в гонке")
	}
	return nil, last
}
