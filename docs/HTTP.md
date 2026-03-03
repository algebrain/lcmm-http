# Документация по API: HTTP (`lcmm-http`)

Этот документ описывает публичный API `lcmm-http` и быстрый старт для двух аудиторий:
1. `module-level` разработчики;
2. `app-level` разработчики.

## 1. Назначение

`lcmm-http` закрывает базовый HTTP runtime layer:
1. единый error-contract;
2. correlation/request id контекст;
3. адаптер HTTP -> event-bus publish opts;
4. базовые `healthz/readyz` handlers.

## 2. Подключение

Репозиторий:
1. `https://github.com/algebrain/lcmm-http`

`deps.edn`:

```clojure
{:deps
 {algebrain/lcmm-http
  {:git/url "https://github.com/algebrain/lcmm-http"
   :git/sha "<PIN_COMMIT_SHA>"}}}
```

## 3. Публичный API

```clojure
(wrap-correlation-context handler opts)
(wrap-error-contract handler opts)
(->bus-publish-opts request opts)
(->bus-publish-opts request opts cfg)
(health-handler opts)
(ready-handler opts)
```

### 3.1 `wrap-correlation-context`

Зачем это нужно:
1. чтобы каждый запрос имел стабильный `correlation-id` для трассировки цепочки;
2. чтобы каждый HTTP-запрос имел отдельный `request-id` для диагностики конкретного обращения;
3. чтобы frontend мог получать эти id из headers (если включен `:expose-headers?`).

Когда использовать:
1. практически всегда в app-level middleware chain;
2. особенно важно, если у вас есть event-bus, централизованные логи или инцидент-репорты от клиентов.

Сигнатура:

```clojure
(wrap-correlation-context handler opts) => ring-handler
```

`opts`:
1. `:correlation-header` (string, default `"x-correlation-id"`)
2. `:request-header` (string, default `"x-request-id"`)
3. `:correlation-id-fn` (ifn, default UUID)
4. `:request-id-fn` (ifn, default UUID)
5. `:expose-headers?` (boolean, default `false`)
6. `:expose-headers-header` (string, default `"access-control-expose-headers"`)

Поведение:
1. входящий correlation-id валидируется и санитизируется (allow-list + max length);
2. невалидный входящий id заменяется новым безопасным id;
3. `request-id` генерируется для каждого запроса;
4. в request добавляются `:lcmm/correlation-id`, `:lcmm/request-id`, `:lcmm/causation-path`;
5. в response headers добавляются correlation/request headers;
6. при `:expose-headers? true` соответствующие headers добавляются в `Access-Control-Expose-Headers`.

Ошибки:
1. `ExceptionInfo` на невалидные аргументы/опции.

### 3.2 `wrap-error-contract`

Зачем это нужно:
1. чтобы все ошибки API имели единый формат (`code/message/details/...`);
2. чтобы клиентский код не зависел от случайных exception-форматов;
3. чтобы утечки чувствительных данных через `details` были под контролем.

Когда использовать:
1. как внешний (outermost) слой обработки ошибок на app-level;
2. всегда, когда API потребляется несколькими клиентами или фронтендом.

Сигнатура:

```clojure
(wrap-error-contract handler opts) => ring-handler
```

`opts`:
1. `:map-exception` (ifn, optional)
2. `:content-type` (string, default `"application/json"`)
3. `:correlation-header` (string, default `"x-correlation-id"`)
4. `:request-header` (string, default `"x-request-id"`)
5. `:sanitize-details-fn` (ifn, default built-in sanitizer+redaction)
6. `:cache-control` (string|nil, default `"no-store"`)

Поведeние:
1. перехватывает исключения и формирует единый error-body (при корректной работе переданных user hooks);
2. дефолтный mapper:
   - `ExceptionInfo` с `:http/status` -> указанный статус;
   - `IllegalArgumentException` -> `400`;
   - иначе `500`;
3. если custom `:map-exception` упал или вернул невалидный контракт, включается безопасный fallback `500/internal_error`;
4. `details` проходят sanitizer/redaction;
5. `retry-after` добавляется только для `429/503` и только в безопасном формате (`1..86400` сек);
6. `cache-control: no-store` ставится по умолчанию.

Error body:

```edn
{:code "..."
 :message "..."
 :details {...} ; optional
 :correlation-id "..." ; optional
 :request-id "..."} ; optional
```

### 3.3 `->bus-publish-opts`

Зачем это нужно:
1. чтобы не собирать вручную `publish`-opts для event-bus в каждом handler-е;
2. чтобы корректно и безопасно прокинуть `correlation-id` из HTTP-контекста в событие;
3. чтобы сохранить причинно-следственную связность HTTP -> event-bus.

Когда использовать:
1. в module-level handler-ах, которые публикуют события через `bus/publish`;
2. когда событие инициировано HTTP-запросом и должно быть связано с ним по `correlation-id`.

Сигнатура:

```clojure
(->bus-publish-opts request opts) => publish-opts
(->bus-publish-opts request opts cfg) => publish-opts
```

Аргументы:
1. `request` — Ring request map
2. `opts` — map, `:module` обязателен (keyword)
3. `cfg` — optional map:
   - `:correlation-header` (string, default `"x-correlation-id"`)

Поведение:
1. ищет безопасный `correlation-id` в `:lcmm/correlation-id` или в header;
2. возвращает `opts` с добавленным `:correlation-id`.

### 3.4 `health-handler`

Зачем это нужно:
1. дать простой liveness endpoint (`/healthz`) без дублирования boilerplate;
2. иметь единый минимальный формат “процесс жив”.

Когда использовать:
1. почти всегда как базовый endpoint для платформы/мониторинга.

Сигнатура:

```clojure
(health-handler opts) => ring-handler
```

Поведение:
1. `200` + `{:status "ok"}`;
2. при наличии добавляет id-поля в body.

### 3.5 `ready-handler`

Зачем это нужно:
1. централизованно проверять готовность зависимостей (`/readyz`);
2. не “виснуть” бесконечно на медленных checks (есть timeout);
3. безопасно регулировать детализацию ответа (`:public`/`:diagnostic`).

Когда использовать:
1. когда сервис зависит от БД/кэша/шины и нужен корректный readiness сигнал;
2. когда важно различать “degraded” и “fail” для оркестратора и операторов.

Сигнатура:

```clojure
(ready-handler opts) => ring-handler
```

`opts`:
1. `:checks` (required, sequential)
2. `:check-timeout-ms` (positive int, default `500`)
3. `:mode` (`:public`|`:diagnostic`, default `:public`)
4. `:cache-control` (string, default `"no-store"`)

Структура check:
1. `:name` (keyword, required)
2. `:critical?` (boolean, optional)
3. `:check` (ifn, required; expected `{:ok? true|false}`)

Статусы:
1. `200 + ok` — все checks successful
2. `200 + degraded` — только non-critical failures
3. `503 + fail` — есть critical failure

Reason codes:
1. `:check-timeout`
2. `:check-failed`
3. `:check-error`

Режимы ответа:
1. `:public` — возвращает безопасный минимум (`name/critical?/ok?/reason`), без диагностического payload;
2. `:diagnostic` — дополнительно может вернуть `:diagnostic` с безопасно санитизированными деталями check-результата.

## 4. Correlation vs Request ID

1. `correlation-id` — chain identifier (HTTP -> event-bus -> derived events).
2. `request-id` — идентификатор конкретного HTTP запроса.
3. `request-id` формируется серверным middleware `wrap-correlation-context` (клиент не обязан и обычно не должен его отправлять).

## 5. Quickstart (app-level)

```clojure
(ns my.app.main
  (:require [lcmm.http.core :as http]
            [lcmm.router :as router]))

(defn make-handler []
  (let [r (router/make-router)
        checks [{:name :db :critical? true :check (fn [] {:ok? true})}]]
    (router/add-route! r :get "/healthz" (http/health-handler {}))
    (router/add-route! r :get "/readyz" (http/ready-handler {:checks checks}))
    (-> (router/as-ring-handler r)
        (http/wrap-correlation-context {:expose-headers? true})
        (http/wrap-error-contract {}))))
```

## 6. Quickstart (module-level)

```clojure
(ns my.app.users
  (:require [event-bus :as bus]
            [lcmm.http.core :as http]))

(defn create-user-handler [bus-instance]
  (fn [request]
    (bus/publish bus-instance
                 :user/create-requested
                 {:email "a@b.com"}
                 (http/->bus-publish-opts request {:module :users}))
    {:status 202 :body {:ok true}}))
```

## 7. Recommended Middleware Order

Порядок ниже указан как порядок **вызова** (outer -> inner):
1. `wrap-error-contract` (outermost)
2. `wrap-correlation-context`
3. security headers middleware (app-level)
4. body/timeout/concurrency limits (app-level)
5. input validation middleware (app-level)
6. auth middleware (app-level)
7. `lcmm-guard` middleware (если используется)
8. business/router handler (innermost)

Минимальный шаблон:

```clojure
(-> (router/as-ring-handler r)
    (http/wrap-correlation-context {:expose-headers? true})
    (http/wrap-error-contract {}))
```

Если нарушить порядок:
1. correlation/request headers могут отсутствовать в error-response;
2. часть исключений может обойти единый error-contract;
3. усложняется трассировка инцидентов по `correlation-id`.

## 8. Frontend Integration Notes

1. ориентируйтесь на `code`, а не на `message`;
2. используйте `Retry-After` для backoff на `429/503`, если header присутствует;
3. для браузера откройте `x-correlation-id`/`x-request-id` через `Access-Control-Expose-Headers`;
4. `details` для validation ошибок должны обрабатываться как machine-readable contract.
5. подробные практические примеры для фронтенда: [HTTP_FRONTEND_NOTES](./HTTP_FRONTEND_NOTES.md)

## 9. Security Boundary (vs `lcmm-guard`)

`lcmm-http` не реализует:
1. IP rate limit/ban/detector;
2. trusted proxy policy;
3. fail-open/fail-closed security orchestration.

Это зона `lcmm-guard`.

## 10. Частые ошибки интеграции

1. Путать `correlation-id` и `request-id`.
2. Нарушать порядок middleware.
3. Публиковать события без `:module`.
4. Отдавать raw exception data в `details`.
5. Считать, что CORS expose headers включаются автоматически без app-level policy.

## 11. Production Defaults (SMB)

Для большинства малых/средних проектов можно стартовать с дефолтов:
1. `wrap-error-contract`: `content-type=application/json`, `cache-control=no-store`, встроенный sanitizer.
2. `wrap-correlation-context`: `expose-headers? true` только если это нужно браузерному клиенту.
3. `ready-handler`: `check-timeout-ms=500`, `mode=:public`, `cache-control=no-store`.
4. `->bus-publish-opts`: использовать с `:module` и стандартным `x-correlation-id` (или явно указать `cfg`).
