#!/usr/bin/env python3
"""
Список российских адресов IPv4 для переключателя «RU-адреса напрямую».

ИСТОЧНИК — официальный реестр RIPE NCC (delegated-ripencc-latest): все
блоки со страной RU и статусом allocated/assigned. Это страна
РЕГИСТРАЦИИ блока, не геолокация: чужой CDN с российским узлом сюда не
попадёт, а российский блок, анонсированный за границей, попадёт. Для
«банки, госуслуги, маркетплейсы — напрямую» этого достаточно.

ЗАПУСК (из корня репозитория):
    python tools/ru_ipv4.py              -> app/src/main/assets/ru_ipv4.bin
    python tools/ru_ipv4.py --txt FILE   -> текстом, CIDR построчно, ВСЕ
                                            блоки RU без отсечки по размеру
                                            (для настольных клиентов и
                                            роутеров, где предела Android нет)
Обновлять раз в несколько месяцев: блоки меняют страну редко.

ПОЧЕМУ ДВОИЧНЫЙ ФАЙЛ, а не текст:
  - в тексте было бы восемь тысяч адресов, и проверка публикации
    исходников (publish-src.sh) останавливалась бы на каждом;
  - файл меньше и читается без разбора строк.

ФОРМАТ ru_ipv4.bin:
  4 байта  "RU4\\x01"            — метка и версия формата
  8 байт   "ГГГГММДД" ASCII      — дата реестра
  4 байта  число записей, BE
  затем записи по 5 байт: 4 байта адрес сети (BE) + 1 байт длина префикса.
  Записи отсортированы по адресу, пересечений нет.

ЧТО ВХОДИТ: блоки /22 и крупнее — 98 % российских адресов при ~6000
записей. Мелкие /23-/24 (свыше 2500 записей, 2 % адресов) отброшены:
столько маршрутов-исключений Android может не принять одним вызовом
(предел размера передачи между процессами). Приложение само берёт отсюда
подмножество под свою версию Android — см. RuDirect.kt.
"""
import ipaddress
import struct
import urllib.request

SRC = "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest"
OUT = "app/src/main/assets/ru_ipv4.bin"
MAX_PREFIX = 22


def main():
    import sys
    txt = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--txt" else None
    raw = urllib.request.urlopen(SRC, timeout=120).read().decode("ascii", "replace")
    date = "00000000"
    nets = []
    for line in raw.splitlines():
        p = line.strip().split("|")
        if len(p) >= 6 and p[0] == "2":  # строка версии: 2|ripencc|serial|records|start|end|utc
            date = p[5]
        if len(p) >= 7 and p[1] == "RU" and p[2] == "ipv4" and p[6] in ("allocated", "assigned"):
            start = ipaddress.IPv4Address(p[3])
            end = ipaddress.IPv4Address(int(start) + int(p[4]) - 1)
            nets.extend(ipaddress.summarize_address_range(start, end))
    nets = list(ipaddress.collapse_addresses(nets))
    total = sum(n.num_addresses for n in nets)
    if txt:
        with open(txt, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# RU IPv4, RIPE {date}, {len(nets)}\n")
            for n in sorted(nets, key=lambda n: int(n.network_address)):
                f.write(f"{n}\n")
        print(f"реестр {date}: блоков RU {len(nets)} -> {txt}")
        return

    keep = sorted((n for n in nets if n.prefixlen <= MAX_PREFIX), key=lambda n: int(n.network_address))
    kept = sum(n.num_addresses for n in keep)

    with open(OUT, "wb") as f:
        f.write(b"RU4\x01")
        f.write(date.encode("ascii")[:8].ljust(8, b"0"))
        f.write(struct.pack(">I", len(keep)))
        for n in keep:
            f.write(struct.pack(">IB", int(n.network_address), n.prefixlen))

    print(f"реестр {date}: блоков RU {len(nets)}, записано /{MAX_PREFIX} и крупнее: "
          f"{len(keep)} ({kept * 100 / total:.1f} % адресов) -> {OUT}")


if __name__ == "__main__":
    main()
