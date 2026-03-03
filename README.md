# lcmm-http

`lcmm-http` is a small Clojure library for building a consistent HTTP layer in LCMM-style applications.

It provides:
1. A unified error contract.
2. Correlation/request ID middleware.
3. HTTP-to-event-bus publish options adapter.
4. Lightweight `health` and `readiness` handlers.

## Installation

Until published to Clojars, use Git dependency in `deps.edn`:

```clojure
{:deps
 {algebrain/lcmm-http
  {:git/url "https://github.com/algebrain/lcmm-http"
   :git/sha "<PIN_COMMIT_SHA>"}}}
```

## Quick Start

```clojure
(ns my.app.main
  (:require [lcmm.http.core :as http]
            [lcmm.router :as router]))

(defn make-handler []
  (let [r (router/make-router)]
    (router/add-route! r :get "/healthz" (http/health-handler {}))
    (router/add-route! r :get "/readyz" (http/ready-handler {:checks []}))
    (-> (router/as-ring-handler r)
        (http/wrap-correlation-context {:expose-headers? true})
        (http/wrap-error-contract {}))))
```

## Development Commands

1. Full local checks (lint + tests + format):
   `bb test.bb`
2. Tests only:
   `clj -M:test`
3. Benchmarks (quick):
   `bb bench.bb --mode=quick`
4. Benchmarks (full):
   `bb bench.bb --mode=full`

## Documentation

This README is intentionally brief.

For full API contract, options, and integration details, see:
1. [HTTP guide](./docs/HTTP.md)
2. [Architecture context](./docs/ARCH.md)
3. [Pragmatism guidelines](./docs/PRAGMATISM.md)
4. [Frontend integration notes](./docs/HTTP_FRONTEND_NOTES.md)
