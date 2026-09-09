package engine

import (
	"bytes"
	"io"
	"sync"
	"testing"
)

// fakeFlow — платформа понарошку. Отдаёт заранее заготовленные пачки,
// собирает записанное, считает закрытия.
type fakeFlow struct {
	mu      sync.Mutex
	batches [][]byte
	written [][]byte
	closes  int
	refuse  bool // WriteBatch отвечает false
}

func (f *fakeFlow) ReadBatch() []byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.batches) == 0 {
		return nil
	}
	b := f.batches[0]
	f.batches = f.batches[1:]
	return b
}

func (f *fakeFlow) WriteBatch(b []byte) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.refuse {
		return false
	}
	// КОПИРУЕМ, и это часть проверки: договор велит платформе копировать
	// до возврата, потому что срез — окно в память Go. Оставь мы здесь
	// сам срез, переиспользуемый wbuf испортил бы уже записанное, и тест
	// поймал бы это несовпадением ниже.
	f.written = append(f.written, append([]byte(nil), b...))
	return true
}

func (f *fakeFlow) Close() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closes++
}

func frame(payload ...byte) []byte {
	n := len(payload)
	return append([]byte{byte(n >> 8), byte(n)}, payload...)
}

func TestFlowReadsPacketsFromOneBatch(t *testing.T) {
	f := &fakeFlow{batches: [][]byte{
		append(frame(1, 2, 3), frame(4, 5)...),
	}}
	c := newFlowConn(f, nil)

	buf := make([]byte, flowFrameMax)
	n, err := c.Read(buf)
	if err != nil || n != 3 || !bytes.Equal(buf[:n], []byte{1, 2, 3}) {
		t.Fatalf("первый пакет: n=%d err=%v данные=%v", n, err, buf[:n])
	}
	n, err = c.Read(buf)
	if err != nil || n != 2 || !bytes.Equal(buf[:n], []byte{4, 5}) {
		t.Fatalf("второй пакет: n=%d err=%v данные=%v", n, err, buf[:n])
	}
	if _, err = c.Read(buf); err != io.EOF {
		t.Fatalf("после исчерпания ждали io.EOF, получили %v", err)
	}
}

func TestFlowRefuelsAcrossBatches(t *testing.T) {
	f := &fakeFlow{batches: [][]byte{frame(7), frame(8, 9)}}
	c := newFlowConn(f, nil)

	buf := make([]byte, flowFrameMax)
	for _, want := range [][]byte{{7}, {8, 9}} {
		n, err := c.Read(buf)
		if err != nil || !bytes.Equal(buf[:n], want) {
			t.Fatalf("ждали %v, получили %v (err=%v)", want, buf[:n], err)
		}
	}
}

// ПУСТАЯ ПАЧКА И NIL — ОДНО И ТО ЖЕ. Через границу gomobile они
// неразличимы, поэтому обе означают закрытие, а не «сейчас нечего».
func TestFlowEmptyBatchMeansClosed(t *testing.T) {
	for _, b := range [][]byte{nil, {}} {
		f := &fakeFlow{batches: [][]byte{b}}
		c := newFlowConn(f, nil)
		if _, err := c.Read(make([]byte, flowFrameMax)); err != io.EOF {
			t.Fatalf("пачка %v: ждали io.EOF, получили %v", b, err)
		}
	}
}

// Структурная ошибка рвёт поток и НЕ склеивает хвост со следующей
// пачкой: поехавшие длины дают мусор вместо потерь.
func TestFlowStructuralErrors(t *testing.T) {
	cases := []struct {
		name  string
		batch []byte
	}{
		{"нулевая длина", []byte{0, 0}},
		{"длина больше потолка", []byte{0xFF, 0xFF, 1, 2, 3}},
		{"хвост короче поля длины", []byte{0}},
		{"кадр не помещается", []byte{0, 8, 1, 2}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := &fakeFlow{batches: [][]byte{tc.batch}}
			c := newFlowConn(f, nil)
			_, err := c.Read(make([]byte, flowFrameMax))
			if err == nil || err == io.EOF {
				t.Fatalf("ждали структурную ошибку, получили %v", err)
			}
		})
	}
}

// Кадр, не влезающий в буфер вызывающего, — тоже структурная ошибка, а
// не молчаливая обрезка: обрезанный пакет хуже потерянного.
func TestFlowFrameLargerThanCallerBuffer(t *testing.T) {
	f := &fakeFlow{batches: [][]byte{frame(1, 2, 3, 4, 5)}}
	c := newFlowConn(f, nil)
	if _, err := c.Read(make([]byte, 3)); err == nil {
		t.Fatal("кадр больше буфера обязан быть ошибкой")
	}
}

func TestFlowWriteFramesOnePacket(t *testing.T) {
	f := &fakeFlow{}
	c := newFlowConn(f, nil)

	if n, err := c.Write([]byte{9, 8, 7}); err != nil || n != 3 {
		t.Fatalf("запись: n=%d err=%v", n, err)
	}
	if len(f.written) != 1 || !bytes.Equal(f.written[0], []byte{0, 3, 9, 8, 7}) {
		t.Fatalf("кадр на проводе к платформе: %v", f.written)
	}
}

func TestFlowWriteRejectsOutOfRange(t *testing.T) {
	c := newFlowConn(&fakeFlow{}, nil)
	if _, err := c.Write(nil); err == nil {
		t.Fatal("пустой пакет обязан быть отвергнут")
	}
	if _, err := c.Write(make([]byte, flowFrameMax+1)); err == nil {
		t.Fatal("пакет больше потолка обязан быть отвергнут")
	}
}

func TestFlowWriteAfterCloseFails(t *testing.T) {
	f := &fakeFlow{}
	c := newFlowConn(f, nil)
	_ = c.Close()
	if _, err := c.Write([]byte{1}); err != io.ErrClosedPipe {
		t.Fatalf("после закрытия ждали ErrClosedPipe, получили %v", err)
	}
}

func TestFlowWriteRefusedMeansClosed(t *testing.T) {
	c := newFlowConn(&fakeFlow{refuse: true}, nil)
	if _, err := c.Write([]byte{1}); err != io.ErrClosedPipe {
		t.Fatalf("false от платформы ждали как ErrClosedPipe, получили %v", err)
	}
}

// Close идемпотентен: stop() зовут откуда угодно и не по одному разу, а
// двойное закрытие на стороне платформы — записанный инвариант.
func TestFlowCloseIsIdempotent(t *testing.T) {
	f := &fakeFlow{}
	c := newFlowConn(f, nil)
	for i := 0; i < 3; i++ {
		if err := c.Close(); err != nil {
			t.Fatalf("закрытие %d: %v", i, err)
		}
	}
	if f.closes != 1 {
		t.Fatalf("платформу закрыли %d раз, ждали 1", f.closes)
	}
}

// Запись зовут до slotsMax горутин разом. Проверяем, что кадры не
// перемешиваются: каждый записанный кусок обязан быть целым кадром.
func TestFlowWriteIsSafeInParallel(t *testing.T) {
	f := &fakeFlow{}
	c := newFlowConn(f, nil)

	var wg sync.WaitGroup
	for i := 0; i < slotsMax; i++ {
		wg.Add(1)
		go func(v byte) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				if _, err := c.Write([]byte{v, v, v}); err != nil {
					t.Errorf("запись из потока %d: %v", v, err)
					return
				}
			}
		}(byte(i))
	}
	wg.Wait()

	if len(f.written) != slotsMax*50 {
		t.Fatalf("записей %d, ждали %d", len(f.written), slotsMax*50)
	}
	for _, w := range f.written {
		if len(w) != 5 || w[0] != 0 || w[1] != 3 || w[2] != w[3] || w[3] != w[4] {
			t.Fatalf("кадр испорчен параллельной записью: %v", w)
		}
	}
}
