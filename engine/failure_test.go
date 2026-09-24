package engine

import (
	"fmt"
	"testing"
)

func TestFailureKind(t *testing.T) {
	silent := fmt.Errorf("лестница пройдена без успеха, последняя ошибка: %w",
		fmt.Errorf("%w: тайм-аут", errAuthSilent))
	cases := []struct {
		name     string
		reached  bool
		hadRelay bool
		relayErr error
		err      error
		want     string
	}{
		{"смена сети", false, true, nil, errNetworkGone, FailNetwork},
		{"отказ шлюза", true, true, nil, errFatalAuth, FailRefused},
		{"сервер виден, молчит", true, true, nil, silent, FailSilent},
		{"сервер виден, иное", true, false, nil, fmt.Errorf("x"), FailUnknown},
		// Главный случай: ступень без DTLS промолчала, до сервера не дошли,
		// ссылок нет. Молчание без рукопожатия — не «сервер виден».
		{"белые списки без ссылок", false, true, errVKNoHashes, silent, FailBlockedNoLinks},
		{"креды TURN не заданы", false, true, errRelayNoCreds, silent, FailBlockedNoLinks},
		{"капча", false, true, fmt.Errorf("%w: итог", errVKCaptcha), silent, FailBlockedCaptcha},
		{"все мёртвые", false, true, fmt.Errorf("%w: итог", errVKDead), silent, FailBlockedLinksDead},
		{"VK не дал", false, true, fmt.Errorf("%w: итог", errVKAllFailed), silent, FailBlockedLinks},
		{"релей не встал", false, true, fmt.Errorf("%w: x", errRelayAlloc), silent, FailBlockedRelay},
		{"релей не пробовался", false, true, nil, silent, FailBlockedRelay},
		{"релея нет", false, false, nil, silent, FailBlocked},
		{"через релей сервер молчит", false, true, fmt.Errorf("%w: x", errAuthSilent), silent, FailSilent},
	}
	for _, c := range cases {
		s := &session{hadRelay: c.hadRelay, relayErr: c.relayErr}
		s.reached.Store(c.reached)
		if got := s.failureKind(c.err); got != c.want {
			t.Errorf("%s: получил %q, ждал %q", c.name, got, c.want)
		}
	}
}
