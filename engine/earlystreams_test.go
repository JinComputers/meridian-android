package engine

import (
	"context"
	"net"
	"strconv"
	"testing"
	"time"
)

// Слушатель, который принимает TCP и сообщает, когда соединение закрыли.
func tcpSink(t *testing.T) (port int, accepted chan net.Conn, closed chan struct{}) {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	accepted = make(chan net.Conn, 4)
	closed = make(chan struct{}, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			accepted <- c
			go func() {
				buf := make([]byte, 64)
				for {
					if _, err := c.Read(buf); err != nil {
						closed <- struct{}{}
						return
					}
				}
			}()
		}
	}()
	_, p, _ := net.SplitHostPort(ln.Addr().String())
	port, _ = strconv.Atoi(p)
	return port, accepted, closed
}

func testSession() *session {
	s := newSession(nil)
	s.ctx, s.cancel = context.WithCancel(context.Background())
	return s
}

func budget25() func() time.Duration { return func() time.Duration { return 25 * time.Second } }

// UDP успел: поток, поднятый заранее, закрывается discard и в AUTH не идёт.
func TestEarlyStreamsDiscardClosesWinner(t *testing.T) {
	port, accepted, closed := tcpSink(t)
	s := testSession()
	defer s.cancel()
	streams := []candidate{{name: "T", transport: transTCP, host: "127.0.0.1", port: int32(port)}}
	directs := []candidate{{name: "A"}}

	e := s.startEarlyStreams("127.0.0.1", streams, directs, "пароль", nil, false, budget25())
	e.kickOnce.Do(func() { close(e.kick) }) // не ждать 2,5 с в тесте
	select {
	case <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("поток не поднялся")
	}
	// Дать гонке записать победителя.
	time.Sleep(100 * time.Millisecond)
	e.discard()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("discard не закрыл поднятый поток")
	}
	e.discard() // идемпотентен
}

// UDP не встал: шаг 3 забирает поток, discard его уже не трогает.
func TestEarlyStreamsTakeTransfersOwnership(t *testing.T) {
	port, accepted, closed := tcpSink(t)
	s := testSession()
	defer s.cancel()
	streams := []candidate{{name: "T", transport: transTCP, host: "127.0.0.1", port: int32(port)}}
	directs := []candidate{{name: "A"}}

	e := s.startEarlyStreams("127.0.0.1", streams, directs, "пароль", nil, false, budget25())
	if !e.started() {
		t.Fatal("гонка потоков должна быть запущена")
	}
	start := time.Now()
	r, err := e.take() // take сам торопит старт
	if err != nil || r == nil {
		t.Fatalf("take: %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("take не поторопил старт: %s", time.Since(start))
	}
	<-accepted
	e.discard()
	select {
	case <-closed:
		t.Fatal("discard закрыл поток, который уже забрали")
	case <-time.After(200 * time.Millisecond):
	}
	r.close()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("поток не закрылся владельцем")
	}
}

// Без UDP-ступеней или без потоков заранее ничего не запускается.
func TestEarlyStreamsNotStarted(t *testing.T) {
	s := testSession()
	defer s.cancel()
	st := []candidate{{name: "T", transport: transTCP}}
	for _, c := range []struct{ streams, directs []candidate }{
		{st, nil}, {nil, []candidate{{name: "A"}}},
	} {
		e := s.startEarlyStreams("127.0.0.1", c.streams, c.directs, "пароль", nil, false, budget25())
		if e.started() {
			t.Fatal("не должна запускаться")
		}
		e.discard()
	}
}

func closedPort(t *testing.T) int32 {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	_, p, _ := net.SplitHostPort(ln.Addr().String())
	ln.Close()
	n, _ := strconv.Atoi(p)
	return int32(n)
}

// Фора запомненной: ведущий поднялся — отложенный не стартует вовсе.
func TestRaceDelayedNotStartedWhenLeadWins(t *testing.T) {
	leadPort, leadAcc, _ := tcpSink(t)
	latePort, lateAcc, _ := tcpSink(t)
	s := testSession()
	defer s.cancel()
	cands := []candidate{
		{name: "L", transport: transTCP, host: "127.0.0.1", port: int32(leadPort), remembered: true},
		{name: "D", transport: transTCP, host: "127.0.0.1", port: int32(latePort), delay: time.Second},
	}
	r, err := s.race(s.ctx, "127.0.0.1", cands, "пароль", nil, false, 3*time.Second)
	if err != nil || r.cand.name != "L" {
		t.Fatalf("ждал победу L: %v", err)
	}
	defer r.close()
	<-leadAcc
	select {
	case <-lateAcc:
		t.Fatal("отложенная ступень стартовала, хотя ведущая уже победила")
	case <-time.After(1500 * time.Millisecond):
	}
}

// Ведущий отказал сразу — отложенный стартует немедленно, не дожидаясь форы.
func TestRaceDelayedStartsWhenLeadFails(t *testing.T) {
	latePort, _, _ := tcpSink(t)
	s := testSession()
	defer s.cancel()
	cands := []candidate{
		{name: "L", transport: transTCP, host: "127.0.0.1", port: closedPort(t), remembered: true},
		{name: "D", transport: transTCP, host: "127.0.0.1", port: int32(latePort), delay: 5 * time.Second},
	}
	start := time.Now()
	r, err := s.race(s.ctx, "127.0.0.1", cands, "пароль", nil, false, 3*time.Second)
	if err != nil || r.cand.name != "D" {
		t.Fatalf("ждал победу D: %v", err)
	}
	defer r.close()
	if time.Since(start) > 2*time.Second {
		t.Fatalf("отложенная ждала форы, хотя ведущая отказала: %s", time.Since(start))
	}
}

// hold держит старт потоков после срока; resume отпускает.
func TestEarlyStreamsHoldResume(t *testing.T) {
	port, accepted, _ := tcpSink(t)
	s := testSession()
	defer s.cancel()
	streams := []candidate{{name: "T", transport: transTCP, host: "127.0.0.1", port: int32(port)}}
	e := s.startEarlyStreams("127.0.0.1", streams, []candidate{{name: "A"}}, "пароль", nil, false, budget25())
	defer e.discard()
	e.hold()
	select {
	case <-accepted:
		t.Fatal("поток стартовал во время hold")
	case <-time.After(streamEarlyStart + 500*time.Millisecond):
	}
	e.resume()
	select {
	case <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("resume не отпустил потоки")
	}
}

// readyC закрывается, когда поток поднят: по нему прерывается круг UDP.
func TestEarlyStreamsReadyC(t *testing.T) {
	port, _, _ := tcpSink(t)
	s := testSession()
	defer s.cancel()
	streams := []candidate{{name: "T", transport: transTCP, host: "127.0.0.1", port: int32(port)}}
	e := s.startEarlyStreams("127.0.0.1", streams, []candidate{{name: "A"}}, "пароль", nil, false, budget25())
	defer e.discard()
	e.now()
	select {
	case <-e.readyC():
	case <-time.After(3 * time.Second):
		t.Fatal("readyC не закрылся после подъёма потока")
	}
	if !e.ready() {
		t.Fatal("ready() ложно при поднятом потоке")
	}
}
