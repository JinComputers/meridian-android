// Планета Meridian для настольных клиентов (Wails v3, HTML/CSS/JS).
//
// ПЕРЕНОС 1:1 С ANDROID: app/src/main/java/org/meridianvpn/app/Planet.kt,
// функция Planet(). Числа, цвета и порядок отрисовки те же; меняя что-то
// здесь, меняйте и там, иначе клиенты разъедутся. Отличие одно: вместо
// Compose Canvas — HTML <canvas>.
//
// Подключение:
//   <canvas id="planet"></canvas>
//   <script src="planet.js"></script>
//   const p = new MeridianPlanet(document.getElementById('planet'), 220);
//   p.setState('off' | 'busy' | 'connected');
//   p.setEnabled(true | false);   // только курсор и доступность, рисунок тот же
//
// Нажатие обрабатывает приложение (click на canvas): off → подключить,
// busy → остановить подключение, connected → отключить.

(function () {
  'use strict';

  // Цвета — как Brand в Planet.kt.
  const BRAND = {
    space: '#000000',       // фон и «тело» сферы, пока связи нет
    sphere: '#171C26',      // заливка сферы при поднятом туннеле
    edge: '#6F98FF',        // контур и точки при туннеле, спутник всегда
    meridian: '#525D80',    // меридианы при туннеле
    edgeOff: '#3A4358',     // контур и точки без связи
    meridianOff: '#272D3A', // меридианы без связи
  };

  const REST_PHASE = 1.274;     // 73°: при этой фазе сфера выглядит как логотип
  const SIXTY_DEG = 1.0472;     // три плоскости меридианов через 60°
  const MERIDIAN_SPEED = 0.33;  // рад/с, оборот ~19 с
  const SAT_SPEED_UP = 0.78;    // рад/с, спутник при туннеле, оборот ~8 с
  const SAT_SPEED_BUSY = 2.62;  // рад/с, спутник при подключении, оборот ~2,4 с
  const FLASH_WINDOW = 0.30;    // окно вспышки у полюса, в единицах |cos| орбиты

  class MeridianPlanet {
    constructor(canvas, sizePx) {
      this.canvas = canvas;
      this.size = sizePx || 220; // CSS-пиксели; на Android 220 dp
      this.state = 'off';
      this.enabled = true;
      this.t = 0;          // секунды с начала движения, ноль в покое
      this.start = null;   // момент начала движения (performance.now)
      this.raf = 0;
      this._resize();
      window.addEventListener('resize', () => this._resize());
      this.draw();
    }

    setEnabled(v) {
      this.enabled = !!v;
      this.canvas.style.cursor = this.enabled ? 'pointer' : 'default';
      this._aria();
    }

    setState(s) {
      if (s !== 'off' && s !== 'busy' && s !== 'connected') return;
      const wasMoving = this._moving();
      this.state = s;
      const moving = this._moving();
      // Как LaunchedEffect(moving) в Planet.kt: время сбрасывается ТОЛЬКО
      // при остановке. Переход busy → connected движение не прерывает.
      if (moving && !wasMoving) {
        this.start = performance.now();
        this._loop();
      } else if (!moving && wasMoving) {
        cancelAnimationFrame(this.raf);
        this.raf = 0;
        this.t = 0;
        this.start = null;
        this.draw();
      } else {
        this.draw();
      }
      this._aria();
    }

    _moving() { return this.state === 'busy' || this.state === 'connected'; }

    _aria() {
      const state = !this.enabled ? 'недоступно'
        : this.state === 'busy' ? 'подключаюсь'
        : this.state === 'connected' ? 'подключено' : 'отключено';
      this.canvas.setAttribute('role', 'button');
      this.canvas.setAttribute('aria-label', 'Планета Meridian, кнопка подключения: ' + state);
      this.canvas.setAttribute('aria-disabled', String(!this.enabled));
    }

    _resize() {
      const dpr = window.devicePixelRatio || 1;
      this.canvas.style.width = this.size + 'px';
      this.canvas.style.height = this.size + 'px';
      this.canvas.width = Math.round(this.size * dpr);
      this.canvas.height = Math.round(this.size * dpr);
      this.ctx = this.canvas.getContext('2d');
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      this.draw();
    }

    // Кадры запрашиваются только пока планета движется. Неподвижная не
    // стоит ни одного кадра — как на Android.
    _loop() {
      const tick = (now) => {
        if (!this._moving()) return;
        this.t = (now - this.start) / 1000;
        this.draw();
        this.raf = requestAnimationFrame(tick);
      };
      cancelAnimationFrame(this.raf);
      this.raf = requestAnimationFrame(tick);
    }

    draw() {
      const g = this.ctx;
      if (!g) return;
      const W = this.size, H = this.size;
      g.clearRect(0, 0, W, H);

      const cx = W / 2, cy = H / 2;
      const r = Math.min(W, H) * 0.42; // остаток — спутнику на контуре
      const connected = this.state === 'connected';
      const moving = this._moving();

      const sphereColor = connected ? BRAND.sphere : BRAND.space;
      const edgeColor = connected ? BRAND.edge : BRAND.edgeOff;
      const meridianColor = connected ? BRAND.meridian : BRAND.meridianOff;

      const edgeWidth = r * 0.045;
      const meridianWidth = r * 0.022;
      const dot = r * 0.075;
      const phase = REST_PHASE + this.t * MERIDIAN_SPEED;

      // Тело сферы.
      circle(g, cx, cy, r, sphereColor);

      // Меридианы: эллипсы высотой 2r и шириной 2r·|cos(долгота)|.
      g.strokeStyle = meridianColor;
      g.lineWidth = meridianWidth;
      for (let i = 0; i < 3; i++) {
        const rx = r * Math.abs(Math.cos(phase + i * SIXTY_DEG));
        if (rx < meridianWidth) continue; // встал ребром
        g.beginPath();
        g.ellipse(cx, cy, rx, r, 0, 0, Math.PI * 2);
        g.stroke();
      }

      // Контур.
      g.strokeStyle = edgeColor;
      g.lineWidth = edgeWidth;
      g.beginPath();
      g.arc(cx, cy, r, 0, Math.PI * 2);
      g.stroke();

      // Точки на полюсах.
      circle(g, cx, cy - r, dot, edgeColor);
      circle(g, cx, cy + r, dot, edgeColor);

      // Спутник: бежит по контуру, всегда поверх. Быстро — подключение,
      // спокойно — туннель поднят.
      if (moving) {
        const speed = connected ? SAT_SPEED_UP : SAT_SPEED_BUSY;
        const orbit = this.t * speed;
        const co = Math.cos(orbit), so = Math.sin(orbit);
        const sx = cx + r * co, sy = cy + r * so;

        // Вспышка при встрече с полюсом.
        const toPole = Math.abs(co);
        if (toPole < FLASH_WINDOW) {
          const k = 1 - toPole / FLASH_WINDOW;
          const gk = k * k;
          const glow = dot * (2.4 + 2.2 * gk);
          const ay = so > 0 ? cy + r : cy - r; // южный полюс при sin > 0
          const grad = g.createRadialGradient(cx, ay, 0, cx, ay, glow);
          grad.addColorStop(0, 'rgba(255,255,255,' + (0.95 * gk) + ')');
          grad.addColorStop(0.5, 'rgba(255,255,255,' + (0.30 * gk) + ')');
          grad.addColorStop(1, 'rgba(255,255,255,0)');
          g.fillStyle = grad;
          g.beginPath();
          g.arc(cx, ay, glow, 0, Math.PI * 2);
          g.fill();
          circle(g, cx, ay, dot * 0.9, 'rgba(255,255,255,' + gk + ')');
        }

        circle(g, sx, sy, dot * 0.85, BRAND.edge);
      }
    }
  }

  function circle(g, x, y, rad, color) {
    g.fillStyle = color;
    g.beginPath();
    g.arc(x, y, rad, 0, Math.PI * 2);
    g.fill();
  }

  window.MeridianPlanet = MeridianPlanet;
})();
