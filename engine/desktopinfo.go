//go:build !android && !ios

package engine

// AssignedInfo — адрес шлюза внутри туннеля и маска (хекс, как прислал шлюз)
// из ответа OK на AUTH последней поднявшейся сессии. Пусто, пока AUTH не
// проходил.
//
// ТОЛЬКО ДЛЯ НАСТОЛЬНЫХ СБОРОК. Файл исключён тегом сборки для android и ios,
// поэтому gomobile bind его не видит и экспортированная поверхность
// Android/iOS не меняется. Свой адрес и длину префикса отдаёт Connect
// ("10.77.77.5/16"), а шлюз и маска нужны настольному клиенту, чтобы
// настроить интерфейс TUN.
func AssignedInfo() (gateway, maskHex string) {
	g, _ := lastAuthGateway.Load().(string)
	m, _ := lastAuthMaskHex.Load().(string)
	return g, m
}
