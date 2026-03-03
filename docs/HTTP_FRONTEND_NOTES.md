# HTTP Frontend Notes (`lcmm-http`)

Этот документ — практические рекомендации для frontend-разработчиков, интегрирующихся с API, построенным на `lcmm-http`.

## 1. Базовая модель ошибок

Ожидаемый error-body:

```json
{
  "code": "validation_failed",
  "message": "Validation failed",
  "details": {},
  "correlation-id": "c1a2...",
  "request-id": "r9f0..."
}
```

Правила:
1. Для логики UI ориентируйтесь на `code`, не на `message`.
2. `message` используйте как fallback-текст для человека.
3. `details` обрабатывайте как machine-readable структуру.
4. Не завязывайтесь на точные тексты ошибок.

## 2. `details` для валидации

Рекомендуемый подход:
1. form-level ошибка: показывается как общая ошибка формы.
2. field-level ошибка: маппится к конкретному полю.

Пример обработки:

```js
function mapValidationError(errBody) {
  const details = errBody?.details ?? {};
  return {
    formError: details.form || null,
    fieldErrors: details.fields || {}
  };
}
```

## 3. Correlation и Request ID

`lcmm-http` использует два идентификатора:
1. `x-correlation-id` — идентификатор всей цепочки (request -> events -> logs).
2. `x-request-id` — идентификатор конкретного HTTP-запроса.

Кто формирует эти поля:
1. `x-request-id` формируется app-level middleware (`wrap-correlation-context`) на стороне сервера.
2. `x-correlation-id` сервер либо принимает из входящего запроса (если валидный), либо генерирует сам.
3. Фронтенд обычно **не должен** отправлять `x-request-id` в запросах.
4. `x-correlation-id` фронтенд может передавать только если это явно согласовано на app-level.

Зачем фронтенду:
1. добавлять их в bug report;
2. быстро связывать клиентскую ошибку с backend-логами.

Важно для браузера:
1. headers читаются из JS только если app-level CORS включает `Access-Control-Expose-Headers`.
2. Минимум: `x-correlation-id, x-request-id`.

## 4. `429` и `503`: retry политика

Если пришел `429` или `503`:
1. сначала проверьте `Retry-After`;
2. если есть — используйте как базовую задержку;
3. если нет — используйте fallback backoff (например, exponential + jitter).

Пример:

```js
function parseRetryAfterSeconds(headers) {
  const raw = headers.get("retry-after");
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function computeDelayMs(attempt, retryAfterSec) {
  if (retryAfterSec != null) return retryAfterSec * 1000;
  const base = Math.min(1000 * 2 ** attempt, 15000);
  const jitter = Math.floor(Math.random() * 250);
  return base + jitter;
}
```

## 5. `fetch` пример

```js
export async function apiFetch(url, init = {}) {
  const res = await fetch(url, init);
  const correlationId = res.headers.get("x-correlation-id");
  const requestId = res.headers.get("x-request-id");

  if (res.ok) return res;

  let body = null;
  try {
    body = await res.json();
  } catch (_) {}

  const err = {
    status: res.status,
    code: body?.code ?? "unknown_error",
    message: body?.message ?? "Request failed",
    details: body?.details ?? null,
    correlationId: body?.["correlation-id"] ?? correlationId,
    requestId: body?.["request-id"] ?? requestId,
    retryAfterSec: parseRetryAfterSeconds(res.headers)
  };

  throw err;
}
```

## 6. Axios interceptor пример

```js
import axios from "axios";

const api = axios.create({ baseURL: "/api" });

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const res = error.response;
    if (!res) throw error;

    const data = res.data || {};
    const normalized = {
      status: res.status,
      code: data.code || "unknown_error",
      message: data.message || "Request failed",
      details: data.details || null,
      correlationId: data["correlation-id"] || res.headers["x-correlation-id"] || null,
      requestId: data["request-id"] || res.headers["x-request-id"] || null,
      retryAfterSec: Number(res.headers["retry-after"]) || null
    };

    throw normalized;
  }
);
```

## 7. Bug Report Checklist

При отправке backend-команде прикладывайте:
1. endpoint + HTTP method;
2. `status` и `code`;
3. `correlation-id` и `request-id`;
4. timestamp и timezone;
5. минимальный шаг воспроизведения.

Не прикладывайте:
1. access token;
2. cookie/session id;
3. чувствительные пользовательские данные.

## 8. Что этот документ не покрывает

1. Реализацию middleware на backend.
2. Политику `lcmm-guard` (rate-limit/ban/detector).
3. Конкретные UI-решения вашего приложения.
