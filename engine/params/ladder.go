package params

import (
	"errors"
	"fmt"
	"runtime"
	"strings"

	"meridian/engine"
)

// Mode — режим транспорта, как Config.TransportMode у Android.
type Mode int

const (
	ModeAuto       Mode = iota // прямые ступени, затем релей (рабочий режим)
	ModeDirectOnly             // только прямые ступени, отката на релей нет
	ModeRelayOnly              // только релей
)

// LadderOptions — всё, что Android берёт для лестницы помимо /v1/params.
//
// Откуда что берётся на Android (MeridianVpnService.buildLadder):
//   - Known/SkipDirect/SkipStreams — память путей клиента (PathMemory:
//     какая ступень выиграла на этой сети и счётчики «прямой/поток
//     проиграли релею или онемели»). Состояние КЛИЕНТА, служба его не знает:
//     настольный клиент хранит своё и передаёт сюда.
//   - Hashes — ссылки VK на звонки. Их СОЗДАЁТ ЧЕЛОВЕК в приложении VK
//     (создать звонок, «поделиться ссылкой») и вставляет в клиент; хеш —
//     кусок ссылки после /call/join/ (ParseHashes ниже). Из них движок
//     на лету добывает креды TURN цепочкой из пяти шагов VK. Мёртвые
//     хеши в лестницу не передаются (engine.CheckHash проверяет один хеш
//     и возвращает "живой"/"капча"/"мёртвый"/"релея нет").
//   - TURNBackup/TURNRealm/RelayPort/RelayDTLS — константы Config.kt
//     (TURNBackup по умолчанию тот же, что у Android: DefaultTURNBackup).
type LadderOptions struct {
	Mode Mode

	// Known — id узла, выигравшего на этой сети в прошлый раз (пусто —
	// нет). Идёт первым и пробуется один, без гонки.
	Known string
	// SkipDirect — не строить UDP-ступени (на сети UDP «не держится»).
	// SkipStreams — то же для TCP/TLS-потока. Обе решает клиент по своей
	// памяти путей; при обоих флагах остаётся один релей.
	SkipDirect  bool
	SkipStreams bool

	Hashes []string // уже нормализованные хеши VK (ParseHashes)

	// TURNBackup — ЗАПАСНОЙ адрес релея "хост:порт". Пусто — умолчание
	// DefaultTURNBackup, то же значение, что TURN_ADDR_BACKUP у Android
	// (Config.kt), чтобы клиенты вели себя одинаково. Рабочие адреса
	// приходят от цепочки VK (шаг 5, «из ответа VK»); запасной нужен, только
	// когда цепочка адресов не вернула («ЗАПАСНОЙ из настроек» в логе).
	// NoTURNBackup отключает запасной совсем.
	TURNBackup   string
	NoTURNBackup bool
	TURNRealm    string // пусто допустимо: pion узнаёт realm из ответа сервера
	RelayPort    int32  // порт ШЛЮЗА на релейном плече; 0 — 56005
	RelayDTLS    bool   // DTLS на релейном плече; сегодня нет

	// Слоты (многослот, только релей). SlotsStart 0 — min(4, ядер),
	// SlotsMax 0 — 16; NoAutoSlots отключает автоподбор.
	SlotsStart  int
	SlotsMax    int
	NoAutoSlots bool
	NumCPU      int // 0 — runtime.NumCPU()
}

// DefaultTURNBackup — запасной адрес релея. Значение TURN_ADDR_BACKUP из
// Config.kt Android; менять здесь и там вместе.
const DefaultTURNBackup = "95.163.34.180:19302"

const (
	defaultRelayPort = 56005
	defaultSlotsMax  = 16
	relayName        = "R" // Paths.RELAY на Android
)

// LadderInfo — что клиенту нужно знать о собранной лестнице.
type LadderInfo struct {
	// Letters — имя ступени для движка (буква) по id узла; IDs — обратное.
	// Winner() движка возвращает букву; для памяти путей нужен id узла:
	// буква зависит от места в списке, id устойчив.
	Letters map[string]string
	IDs     map[string]string
	// Streams — буквы потоковых ступеней (tcp/tls).
	Streams map[string]bool
	// Skipped — узлы, пропущенные вслух (незнакомый транспорт).
	Skipped []string
	// RelayTarget: "" — поля нет, "ok" — запасной адрес принят,
	// "invalid" — негоден (имя, порт, приватный адрес), идём только на
	// адрес из edge.
	RelayTarget string
	// NoHashes — хешей VK нет: релейная ступень останется в лестнице и
	// откажет с внятным текстом (молча пропасть из списка ей нельзя).
	NoHashes bool
	// Slots — сколько слотов запрошено на старте.
	Slots int
}

// ErrEmptyLadder — ни одной ступени: ни прямых, ни релея.
var ErrEmptyLadder = errors.New("лестница пуста: ни одной ступени")

// letter — имя прямой ступени по месту в списке (Paths.directName).
func letter(i int) string {
	if i < 26 {
		return string(rune('A' + i))
	}
	return fmt.Sprintf("A%d", i)
}

// BuildLadder собирает лестницу транспорта тем же порядком, что
// MeridianVpnService.buildLadder у Android.
//
// Узлы берутся ТОЛЬКО из ответа службы (зашитых запасных у настольного
// клиента нет). Порядок узлов — подсказка о предпочтении, список остаётся
// ПОЛНЫМ; узлы поднимаются параллельно. Буква — свойство узла: раздаётся ДО
// перестановки запомненного, по порядку списка.
//
// После Fetch вызывают BuildLadder(answer, opts) и передают результат в
// desktop.Config.Ladder. Отладочные пробы (STUN, глубокая диагностика,
// имитации) не включаются — как в релизной сборке Android.
func BuildLadder(a *Answer, opts LadderOptions) (*engine.Ladder, *LadderInfo, error) {
	if a == nil {
		return nil, nil, errors.New("нет ответа службы: собирать лестницу не из чего")
	}
	l := engine.NewLadder()
	l.SetSecondRoundMs(int32(a.Ladder.SecondRoundMs))
	// SetProbeStun("") и SetDeepProbe(false) — умолчания движка, в релизе
	// пусто: голый STUN с боевого сокета отличает нас от роутерного клиента.

	info := &LadderInfo{
		Letters: map[string]string{},
		IDs:     map[string]string{},
		Streams: map[string]bool{},
	}

	var all []Node
	if opts.Mode != ModeRelayOnly {
		all = a.Nodes
	}
	// Пропуск закрытого UDP убирает ТОЛЬКО UDP-ступени, поток оставляет;
	// пропуск резаного потока — зеркально. Включаются независимо.
	var directs []Node
	for _, n := range all {
		stream := n.Transport == "tcp" || n.Transport == "tls"
		if (stream && !opts.SkipStreams) || (!stream && !opts.SkipDirect) {
			directs = append(directs, n)
		}
	}

	for i, n := range directs {
		info.Letters[n.ID] = letter(i)
	}
	ordered := directs
	if opts.Known != "" {
		for i, n := range directs {
			if n.ID == opts.Known {
				ordered = append([]Node{n}, append(append([]Node{}, directs[:i]...), directs[i+1:]...)...)
				break
			}
		}
	}

	for _, n := range ordered {
		code := info.Letters[n.ID]
		info.IDs[code] = n.ID
		switch n.Transport {
		case "tcp":
			info.Streams[code] = true
			l.AddTCP(code, n.Host, int32(n.Port))
		case "tls":
			info.Streams[code] = true
			l.AddTLS(code, n.Host, int32(n.Port), n.SNI)
		case "udp":
			if n.ID == opts.Known {
				// Запомненную помечаем явно: движок пробует её одну и
				// без гонки. Гонка нужна незнакомой сети.
				l.AddDirectRemembered(code, n.Host, int32(n.Port), n.DTLS)
			} else {
				l.AddDirect(code, n.Host, int32(n.Port), n.DTLS)
			}
		default:
			// Незнакомый транспорт пропускаем ВСЛУХ: молчаливое «всё
			// непонятное считаем UDP» стоило бы мёртвой ступени без причины.
			info.Skipped = append(info.Skipped,
				fmt.Sprintf("узел %s: транспорт «%s» незнаком", code, n.Transport))
		}
	}

	// Релей отключается только прямым словом службы (Relay.Enabled по
	// умолчанию true: ответ без поля relay запасной путь не отнимает).
	if opts.Mode != ModeDirectOnly && a.Relay.Enabled {
		port := opts.RelayPort
		if port == 0 {
			port = defaultRelayPort
		}
		turn := opts.TURNBackup
		if turn == "" && !opts.NoTURNBackup {
			turn = DefaultTURNBackup
		}
		l.AddRelay(relayName, turn, opts.TURNRealm, port, opts.RelayDTLS)
		for _, h := range opts.Hashes {
			l.AddRelayHash(h)
		}
		info.NoHashes = len(opts.Hashes) == 0

		// relay.target — ЗАПАСНОЙ конечный адрес релея: сначала релей на
		// адрес из edge (как было), только если не поднялся — на этот
		// (Париж). ПОСЛЕ хешей: раздваивается готовая ступень вместе с ними.
		if a.Relay.Target != "" {
			if l.SetRelayTarget(a.Relay.Target) {
				info.RelayTarget = "ok"
			} else {
				info.RelayTarget = "invalid"
			}
		}
	}

	// Слоты: 0 — min(4, ядер), как у роутера. Движок обрежет по потолку и
	// применит только к релейной ступени.
	cores := opts.NumCPU
	if cores <= 0 {
		cores = runtime.NumCPU()
	}
	start := opts.SlotsStart
	if start <= 0 {
		start = cores
		if start > 4 {
			start = 4
		}
	}
	max := opts.SlotsMax
	if max <= 0 {
		max = defaultSlotsMax
	}
	l.SetSlots(int32(start), int32(max), !opts.NoAutoSlots)
	info.Slots = start

	if l.Len() == 0 {
		return nil, nil, ErrEmptyLadder
	}
	return l, info, nil
}

// --- хеши VK ---------------------------------------------------------------

// NormalizeHash — одна ссылка или хеш → хеш. Правила normalizeVKJoinHash
// (спецификация-2, B.8, как HashStore.normalize у Android): вырезать хеш из
// /call/join/<хеш>; принять голый хеш, если строка не начинается с
// http(s); отсечь всё после ?, # и /. "" — не разобрано.
func NormalizeHash(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	low := strings.ToLower(s)
	if strings.HasPrefix(low, "http://") || strings.HasPrefix(low, "https://") {
		const marker = "/call/join/"
		at := strings.Index(low, marker)
		if at < 0 {
			return ""
		}
		s = s[at+len(marker):]
	}
	for _, stop := range []string{"?", "#", "/"} {
		if at := strings.Index(s, stop); at >= 0 {
			s = s[:at]
		}
	}
	return strings.TrimSpace(s)
}

// ParseHashes — несколько ссылок/хешей из одной строки. Разделители те же,
// что у роутерного клиента (спецификация-2 B.8, ParseHashes): запятая,
// точка с запятой, перевод строки, таб, пробел. Дубли отбрасываются молча.
func ParseHashes(input string) []string {
	seen := map[string]bool{}
	var out []string
	for _, part := range strings.FieldsFunc(input, func(r rune) bool {
		return r == ',' || r == ';' || r == '\n' || r == '\r' || r == '\t' || r == ' '
	}) {
		if h := NormalizeHash(part); h != "" && !seen[h] {
			seen[h] = true
			out = append(out, h)
		}
	}
	return out
}
