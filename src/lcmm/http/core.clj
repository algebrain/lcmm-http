(ns lcmm.http.core
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

(defn- raise!
  [message data]
  (throw (ex-info message data)))

(defn- ensure-map!
  [value arg-name]
  (when-not (map? value)
    (raise! (str arg-name " must be a map.") {:arg arg-name :value value})))

(defn- ensure-ifn!
  [value arg-name]
  (when-not (ifn? value)
    (raise! (str arg-name " must be invokable.") {:arg arg-name :value value})))

(defn- ensure-string!
  [value arg-name]
  (when-not (string? value)
    (raise! (str arg-name " must be a string.") {:arg arg-name :value value})))

(defn- ensure-non-empty-string!
  [value arg-name]
  (ensure-string! value arg-name)
  (when-not (seq value)
    (raise! (str arg-name " must be a non-empty string.")
            {:arg arg-name :value value})))

(defn- ensure-keyword!
  [value arg-name]
  (when-not (keyword? value)
    (raise! (str arg-name " must be a keyword.") {:arg arg-name :value value})))

(defn- ensure-positive-int!
  [value arg-name]
  (when-not (and (int? value) (pos? value))
    (raise! (str arg-name " must be a positive integer.")
            {:arg arg-name :value value})))

(defn- assoc-header
  [response header-name header-value]
  (assoc-in response [:headers header-name] header-value))

(def ^:private id-max-length 128)
(def ^:private id-pattern #"^[A-Za-z0-9._:-]+$")

(defn- safe-id?
  [value]
  (and (string? value)
       (<= 1 (count value) id-max-length)
       (boolean (re-matches id-pattern value))))

(defn- resolve-safe-id
  [candidate id-fn]
  (if (safe-id? candidate)
    candidate
    (let [generated (id-fn)]
      (if (safe-id? generated)
        generated
        (str (UUID/randomUUID))))))

(defn- parse-csv-values
  [value]
  (if (seq value)
    (->> (.split (str value) ",")
         (map str/trim)
         (remove str/blank?)
         vec)
    []))

(defn- merge-expose-headers
  [existing new-headers]
  (let [existing-values (parse-csv-values existing)
        existing-lc (set (map str/lower-case existing-values))
        extras (remove #(contains? existing-lc (str/lower-case %))
                       new-headers)]
    (when-let [all (seq (concat existing-values extras))]
      (str/join ", " all))))

(defn wrap-correlation-context
  [handler opts]
  (ensure-ifn! handler "handler")
  (ensure-map! opts "opts")
  (let [{:keys [correlation-header request-header correlation-id-fn request-id-fn
                expose-headers? expose-headers-header]
         :or {correlation-header "x-correlation-id"
              request-header "x-request-id"
              correlation-id-fn #(str (UUID/randomUUID))
              request-id-fn #(str (UUID/randomUUID))
              expose-headers? false
              expose-headers-header "access-control-expose-headers"}} opts]
    (ensure-string! correlation-header "correlation-header")
    (ensure-string! request-header "request-header")
    (ensure-ifn! correlation-id-fn "correlation-id-fn")
    (ensure-ifn! request-id-fn "request-id-fn")
    (when-not (boolean? expose-headers?)
      (raise! "expose-headers? must be boolean."
              {:arg "expose-headers?" :value expose-headers?}))
    (ensure-string! expose-headers-header "expose-headers-header")
    (fn [request]
      (let [incoming-correlation-id (get-in request [:headers correlation-header])
            correlation-id (resolve-safe-id incoming-correlation-id correlation-id-fn)
            request-id (resolve-safe-id nil request-id-fn)
            enriched-request (-> request
                                 (assoc :lcmm/correlation-id correlation-id)
                                 (assoc :lcmm/request-id request-id)
                                 (assoc :lcmm/causation-path []))]
        (try
          (let [response (-> (handler enriched-request)
                             (assoc-header correlation-header correlation-id)
                             (assoc-header request-header request-id))]
            (if expose-headers?
              (let [existing (get-in response [:headers expose-headers-header])
                    merged (merge-expose-headers existing [correlation-header request-header])]
                (if merged
                  (assoc-header response expose-headers-header merged)
                  response))
              response))
          (catch Throwable error
            (let [base-data (or (ex-data error) {})]
              (throw (ex-info (or (.getMessage error) "HTTP handler failed.")
                              (assoc base-data
                                     :lcmm/correlation-id correlation-id
                                     :lcmm/request-id request-id)
                              error)))))))))

(defn- default-exception->http
  [error]
  (let [data (ex-data error)]
    (cond
      (and (map? data) (:http/status data))
      {:status (:http/status data)
       :code (or (:error/code data) "request_failed")
       :message (or (:error/message data) "Request failed")
       :details (:error/details data)}

      (instance? IllegalArgumentException error)
      {:status 400
       :code "bad_request"
       :message "Bad request"}

      :else
      {:status 500
       :code "internal_error"
       :message "Internal server error"})))

(defn- validate-exception-mapping!
  [mapped]
  (ensure-map! mapped "map-exception result")
  (when-not (integer? (:status mapped))
    (raise! "map-exception result contract violation: :status must be an integer."
            {:error/contract-violation :status
             :result mapped}))
  (ensure-non-empty-string! (:code mapped) ":code")
  (ensure-non-empty-string! (:message mapped) ":message")
  mapped)

(def ^:private sensitive-detail-keys
  #{"password" "token" "secret" "api-key" "authorization" "cookie" "set-cookie"})

(defn- redact-key?
  [key]
  (contains? sensitive-detail-keys
             (-> key name str/lower-case)))

(defn- sanitize-detail-value
  [value]
  (cond
    (map? value)
    (->> value
         (map (fn [[k v]]
                [k (if (redact-key? k) "***" (sanitize-detail-value v))]))
         (into {}))

    (sequential? value)
    (mapv sanitize-detail-value value)

    (or (nil? value)
        (string? value)
        (number? value)
        (boolean? value)
        (keyword? value))
    value

    (instance? Throwable value)
    nil

    :else
    (str value)))

(defn- normalize-retry-after
  [value]
  (cond
    (and (int? value) (<= 1 value 86400))
    (str value)

    (string? value)
    (when-let [parsed (try
                        (Integer/parseInt value)
                        (catch Throwable _ nil))]
      (when (<= 1 parsed 86400)
        (str parsed)))

    :else
    nil))

(defn- fallback-error-mapping
  []
  {:status 500
   :code "internal_error"
   :message "Internal server error"})

(defn- safe-map-exception
  [mapper error]
  (try
    (validate-exception-mapping! (mapper error))
    (catch Throwable _
      (fallback-error-mapping))))

(defn wrap-error-contract
  [handler opts]
  (ensure-ifn! handler "handler")
  (ensure-map! opts "opts")
  (let [{:keys [map-exception content-type correlation-header request-header
                sanitize-details-fn cache-control]
         :or {content-type "application/json"
              correlation-header "x-correlation-id"
              request-header "x-request-id"
              sanitize-details-fn sanitize-detail-value
              cache-control "no-store"}} opts]
    (when map-exception
      (ensure-ifn! map-exception "map-exception"))
    (ensure-string! content-type "content-type")
    (ensure-string! correlation-header "correlation-header")
    (ensure-string! request-header "request-header")
    (ensure-ifn! sanitize-details-fn "sanitize-details-fn")
    (when cache-control
      (ensure-string! cache-control "cache-control"))
    (fn [request]
      (try
        (handler request)
        (catch Throwable error
          (let [mapped (safe-map-exception (or map-exception default-exception->http) error)
                status (:status mapped)
                code (:code mapped)
                message (:message mapped)
                details (sanitize-details-fn (:details mapped))
                data (or (ex-data error) {})
                correlation-id (or (:lcmm/correlation-id data)
                                   (:lcmm/correlation-id request))
                request-id (or (:lcmm/request-id data)
                               (:lcmm/request-id request))
                retry-after (when (#{429 503} status)
                              (normalize-retry-after (:retry-after mapped)))
                body (cond-> {:code code
                              :message message}
                       (some? correlation-id) (assoc :correlation-id correlation-id)
                       (some? request-id) (assoc :request-id request-id)
                       (some? details) (assoc :details details))
                response {:status status
                          :headers {"content-type" content-type}
                          :body body}
                response (if cache-control
                           (assoc-header response "cache-control" cache-control)
                           response)
                response (if correlation-id
                           (assoc-header response correlation-header correlation-id)
                           response)]
            (cond-> response
              request-id (assoc-header request-header request-id)
              retry-after (assoc-header "retry-after" retry-after))))))))

(defn ->bus-publish-opts
  ([request opts]
   (->bus-publish-opts request opts {}))
  ([request opts cfg]
   (ensure-map! request "request")
   (ensure-map! opts "opts")
   (ensure-map! cfg "cfg")
   (let [{:keys [correlation-header]
          :or {correlation-header "x-correlation-id"}} cfg
         _ (ensure-string! correlation-header "correlation-header")
         module (:module opts)
         _ (ensure-keyword! module "module")
         correlation-id (or (when (safe-id? (:lcmm/correlation-id request))
                              (:lcmm/correlation-id request))
                            (when (safe-id? (get-in request [:headers correlation-header]))
                              (get-in request [:headers correlation-header])))]
     (when-not (seq correlation-id)
       (raise! "Missing correlation id in request context."
               {:request-keys (keys request)
                :correlation-header correlation-header}))
     (assoc opts :correlation-id correlation-id))))

(defn health-handler
  [opts]
  (ensure-map! opts "opts")
  (fn [request]
    (cond-> {:status 200
             :body {:status "ok"}}
      (:lcmm/correlation-id request)
      (assoc-in [:body :correlation-id] (:lcmm/correlation-id request))
      (:lcmm/request-id request)
      (assoc-in [:body :request-id] (:lcmm/request-id request)))))

(defn- run-check
  [{:keys [name critical? check]} check-timeout-ms mode]
  (ensure-keyword! name "check name")
  (ensure-ifn! check "check fn")
  (let [base {:name name
              :critical? (true? critical?)}]
    (try
      (let [task (future (check))
            outcome (deref task check-timeout-ms ::timeout)]
        (cond
          (= outcome ::timeout)
          (do
            (future-cancel task)
            (assoc base :ok? false :reason :check-timeout))

          (true? (:ok? outcome))
          (assoc base :ok? true)

          :else
          (cond-> (assoc base :ok? false :reason :check-failed)
            (= mode :diagnostic)
            (assoc :diagnostic
                   {:result (sanitize-detail-value
                             (if (map? outcome)
                               (dissoc outcome :ok?)
                               {:value outcome}))}))))
      (catch Throwable _
        (assoc base :ok? false :reason :check-error)))))

(defn ready-handler
  [{:keys [checks check-timeout-ms mode cache-control] :as opts}]
  (ensure-map! opts "opts")
  (when-not (sequential? checks)
    (raise! "checks must be sequential." {:checks checks}))
  (let [check-timeout-ms (or check-timeout-ms 500)
        mode (or mode :public)
        cache-control (or cache-control "no-store")
        checks (vec checks)]
    (ensure-positive-int! check-timeout-ms "check-timeout-ms")
    (when-not (#{:public :diagnostic} mode)
      (raise! "mode must be :public or :diagnostic." {:mode mode}))
    (ensure-string! cache-control "cache-control")
    (fn [request]
      (let [results (mapv #(run-check % check-timeout-ms mode) checks)
            critical-failed? (some (fn [{:keys [critical? ok?]}]
                                     (and critical? (not ok?)))
                                   results)
            degraded? (and (not critical-failed?)
                           (some (comp not :ok?) results))
            status (cond
                     critical-failed? 503
                     :else 200)
            health-status (cond
                            critical-failed? "fail"
                            degraded? "degraded"
                            :else "ok")]
        (cond-> {:status status
                 :headers {"cache-control" cache-control}
                 :body {:status health-status
                        :checks results}}
          (:lcmm/correlation-id request)
          (assoc-in [:body :correlation-id] (:lcmm/correlation-id request))
          (:lcmm/request-id request)
          (assoc-in [:body :request-id] (:lcmm/request-id request)))))))
