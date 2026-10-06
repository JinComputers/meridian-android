package engine

import (
	"errors"
	"sync/atomic"
)

// ПРИЧИНА ОТКАЗА ПОДКЛЮЧЕНИЯ — ОДНИМ КОДОМ, ДЛЯ ЧЕЛОВЕКА.
//
// Текст ошибки Connect говорит, КАК упала последняя ступень. Человеку нужно
// другое: ЧТО ему сделать. Самый частый случай — сеть с белыми списками:
// прямой путь до сервера закрыт, а запасной через VK-звонки не настроен.
// По тексту последней ошибки этого не видно (там «шлюз не ответил» от
// ступени без DTLS или «хеши VK не заданы»), а видно по уликам всей
// лестницы: дошла ли хоть одна прямая ступень до сервера и чем кончился
// релей.
//
// Код, а не текст: слова для человека — дело платформы (свой язык, свои
// названия экранов). Коды стабильны, новые только добавляются.
const (
	// FailNone — последний Connect удался или ещё не звался.
	FailNone = ""
	// FailNetwork — сеть сменилась во время подъёма.
	FailNetwork = "network"
	// FailRefused — сервер ответил отказом (FATAL_*): ключ, пул, отключён.
	// Текст ошибки Connect сам называет причину.
	FailRefused = "refused"
	// FailResolve — адрес сервера по имени узнать не удалось.
	FailResolve = "resolve"
	// FailSilent — сервер виден, но на AUTH промолчал: ключ ему незнаком
	// (свежий или удалённый).
	FailSilent = "silent"
	// FailBlocked — до сервера напрямую не дошли, релея в лестнице нет
	// (режим «только прямой» или релей выключен службой).
	FailBlocked = "blocked"
	// FailBlockedNoLinks — до сервера напрямую не дошли, а ссылок на
	// VK-звонки нет ни одной. Белые списки без запасного пути.
	FailBlockedNoLinks = "blocked-nolinks"
	// FailBlockedLinksDead — прямого нет, все ссылки на звонки мёртвые.
	FailBlockedLinksDead = "blocked-linksdead"
	// FailBlockedCaptcha — прямого нет, годные ссылки упёрлись в капчу VK.
	FailBlockedCaptcha = "blocked-captcha"
	// FailBlockedLinks — прямого нет, ссылки есть, но VK кредов не дал
	// (не ответил за срок, сбоил, релея не выдал).
	FailBlockedLinks = "blocked-links"
	// FailBlockedRelay — прямого нет, креды VK получены, но сам релей не
	// поднялся (аллокация, немой путь).
	FailBlockedRelay = "blocked-relay"
	// FailUnknown — ничего из перечисленного.
	FailUnknown = "unknown"
)

var lastFailure atomic.Value // string

// FailureKind — причина отказа ПОСЛЕДНЕГО Connect, один из кодов Fail*.
// Пусто после удачного Connect. Спрашивать сразу после того, как Connect
// вернул ошибку.
func FailureKind() string {
	v, _ := lastFailure.Load().(string)
	return v
}

func setFailure(kind string) { lastFailure.Store(kind) }

// failureKind разбирает улики сессии после неудачной лестницы.
//
// «ДОШЛИ ДО СЕРВЕРА» — только по рукопожатию, которое подделать нельзя
// (DTLS со сверкой отпечатка, пиновленный TLS). Ступень без DTLS «готова
// сразу» и на закрытой сети падает на молчании AUTH — ровно так же, как на
// незнакомом ключе, поэтому одно молчание сервер видимым не делает.
func (s *session) failureKind(err error) string {
	switch {
	case errors.Is(err, errNetworkGone):
		return FailNetwork
	case fatalForLadder(err):
		return FailRefused
	}

	// Через релей сервер промолчал на AUTH — путь до него был, дело в ключе.
	if s.relayErr != nil && errors.Is(s.relayErr, errAuthSilent) {
		return FailSilent
	}

	if s.reached.Load() {
		if errors.Is(err, errAuthSilent) {
			return FailSilent
		}
		return FailUnknown
	}

	if !s.hadRelay {
		return FailBlocked
	}
	re := s.relayErr
	switch {
	case re == nil:
		return FailBlockedRelay
	case errors.Is(re, errVKNoHashes), errors.Is(re, errRelayNoCreds):
		return FailBlockedNoLinks
	case errors.Is(re, errVKCaptcha):
		return FailBlockedCaptcha
	case errors.Is(re, errVKDead):
		return FailBlockedLinksDead
	case errors.Is(re, errVKAllFailed):
		return FailBlockedLinks
	}
	return FailBlockedRelay
}
