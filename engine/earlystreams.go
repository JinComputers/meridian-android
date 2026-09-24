package engine

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

// streamEarlyStart — через сколько после начала гонки UDP стартуют потоки.
//
// Не ноль: на рабочей сети UDP встаёт за секунду-полторы (DTLS с первого
// ретрансмита, фора без DTLS 1,5 с), и поток тогда поднимался бы впустую
// на каждом подключении — лишнее TCP-соединение к шлюзу на проводе. Две с
// половиной секунды: UDP, который встаёт, обычно уже встал; тот, что не
// встал, дальше только тратит свои восемь.
const streamEarlyStart = 2500 * time.Millisecond

// earlyStreams — потоковая гонка, запущенная параллельно гонке UDP.
//
// Владение транспортом: поднятый поток принадлежит этому объекту, пока
// его не забрали take(). Всё, что не забрали, закрывает discard() — он
// стоит в defer у climb и отрабатывает на любом выходе из лестницы.
type earlyStreams struct {
	cancel context.CancelFunc
	done   chan struct{}

	// kick — начать сейчас, не дожидаясь streamEarlyStart: UDP кончился
	// раньше (все ступени отказали сразу), ждать срока незачем.
	kick     chan struct{}
	kickOnce sync.Once

	// held — у UDP есть победитель транспорта, идёт его AUTH и проверка.
	// Потоки в это время не стартуют: лог 24.09 20:33:52 — F уже прошла
	// AUTH и первую метку, а поток G открылся впустую. AUTH не прошёл —
	// resume, и потоки стартуют (срок к тому времени обычно вышел).
	held atomic.Bool
	wake chan struct{}

	readyCh chan struct{}

	mu    sync.Mutex
	on    bool // гонка запущена (или ждёт своего срока)
	won   *rung
	err   error
	taken bool
}

type earlyResult struct {
	r   *rung
	err error
}

// startEarlyStreams запускает потоки через streamEarlyStart, если есть и
// UDP-ступени, и потоковые. Без UDP заранее запускать не перед чем — шаг 3
// сам начнёт сразу; без потоков — нечего.
func (s *session) startEarlyStreams(
	gateway string, streams, directs []candidate, password string, prot Protector,
	failDirect bool, ladderLeft func() time.Duration,
) *earlyStreams {
	e := &earlyStreams{
		done: make(chan struct{}),
		kick: make(chan struct{}),
		wake:    make(chan struct{}, 1),
		readyCh: make(chan struct{}),
	}
	if len(streams) == 0 || len(directs) == 0 {
		close(e.done)
		return e
	}
	ctx, cancel := context.WithCancel(s.ctx)
	e.cancel = cancel
	e.on = true
	cands := append([]candidate(nil), streams...)

	// Срок считаем ЗДЕСЬ, в горутине лестницы: ladderLeft не для чужих
	// горутин (пишет свою отметку без замка). Остаток — на момент старта
	// потоков, то есть за вычетом ожидания.
	hs := minDuration(streamHandshakeBudget, ladderLeft()-streamEarlyStart-firstRungAuth)
	if hs < minCandidateBudget {
		hs = minCandidateBudget
	}

	go func() {
		defer close(e.done)
		t := time.NewTimer(streamEarlyStart)
		select {
		case <-t.C:
		case <-e.kick:
			t.Stop()
		case <-ctx.Done():
			t.Stop()
			e.finish(nil, ctx.Err())
			return
		}
		for e.held.Load() {
			select {
			case <-e.wake:
			case <-e.kick:
				e.held.Store(false)
			case <-ctx.Done():
				e.finish(nil, ctx.Err())
				return
			}
		}
		s.logf("потоковые ступени стартуют заранее, параллельно гонке UDP (через %s)",
			streamEarlyStart)
		r, err := s.race(ctx, gateway, cands, password, prot, failDirect, hs)
		e.finish(r, err)
	}()
	return e
}

// now — стартовать сейчас, не дожидаясь streamEarlyStart и снимая hold.
// Повод: UDP-победитель провалил AUTH или метку — сеть UDP недолюбливает,
// и каждая секунда ожидания потоков — впустую (лог 24.09 20:40:30: F и C
// прошли AUTH и оглохли на метке, поток встал позже, чем мог).
func (e *earlyStreams) now() {
	e.held.Store(false)
	e.kickOnce.Do(func() { close(e.kick) })
}

// ready — поток уже поднят и ждёт, его можно забрать без ожидания.
func (e *earlyStreams) ready() bool {
	select {
	case <-e.done:
	default:
		return false
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	return !e.taken && e.won != nil
}

// hold — не стартовать, пока не скажут resume. Уже идущую гонку не трогает.
func (e *earlyStreams) hold() { e.held.Store(true) }

// resume — снять hold; если срок старта уже вышел, потоки стартуют сейчас.
func (e *earlyStreams) resume() {
	e.held.Store(false)
	select {
	case e.wake <- struct{}{}:
	default:
	}
}

func (e *earlyStreams) finish(r *rung, err error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.won, e.err = r, err
	if r != nil {
		close(e.readyCh)
	}
}

// readyC закрывается, когда поток поднят и ждёт (см. ready). Гонка UDP
// по нему прерывается: ждать UDP, когда готовый транспорт уже есть,
// незачем.
func (e *earlyStreams) readyC() <-chan struct{} { return e.readyCh }

func (e *earlyStreams) started() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.on && !e.taken
}

// take ждёт итога и отдаёт победителя вызывающему, вместе с владением.
func (e *earlyStreams) take() (*rung, error) {
	e.kickOnce.Do(func() { close(e.kick) })
	<-e.done
	e.mu.Lock()
	defer e.mu.Unlock()
	e.taken = true
	r, err := e.won, e.err
	e.won = nil
	if r == nil && err == nil {
		err = errSessionStopped
	}
	return r, err
}

// discard гасит гонку и закрывает не забранный транспорт. Идемпотентен.
//
// ЖДЁТ КОНЦА ГОНКИ: race сам дожидается гибели своих проигравших, и после
// этого вызова чужих живых сокетов не остаётся — то же свойство, что у
// любой гонки в лестнице.
func (e *earlyStreams) discard() {
	if e.cancel != nil {
		e.cancel()
	}
	<-e.done
	e.mu.Lock()
	r := e.won
	e.won = nil
	e.mu.Unlock()
	if r != nil {
		r.close()
	}
}
