(ns lcmm.http.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [lcmm.http.core :as http]))

(def id-pattern #"^[A-Za-z0-9._:-]+$")

(deftest wrap-correlation-context-test
  (testing "preserves valid incoming correlation id and adds request id"
    (let [handler (http/wrap-correlation-context
                   (fn [request]
                     {:status 200
                      :body {:correlation-id (:lcmm/correlation-id request)
                             :request-id (:lcmm/request-id request)}})
                   {:request-id-fn (constantly "generated-request-id")})
          response (handler {:headers {"x-correlation-id" "incoming-correlation-id"}})]
      (is (= 200 (:status response)))
      (is (= "incoming-correlation-id"
             (get-in response [:body :correlation-id])))
      (is (= "generated-request-id"
             (get-in response [:body :request-id])))
      (is (= "incoming-correlation-id"
             (get-in response [:headers "x-correlation-id"])))
      (is (= "generated-request-id"
             (get-in response [:headers "x-request-id"])))))

  (testing "replaces invalid incoming correlation id"
    (let [handler (http/wrap-correlation-context
                   (fn [request] {:status 200
                                  :body {:correlation-id (:lcmm/correlation-id request)}})
                   {:correlation-id-fn (constantly "safe-generated-cid")
                    :request-id-fn (constantly "safe-generated-rid")})
          response (handler {:headers {"x-correlation-id" "bad value with spaces"}})]
      (is (= "safe-generated-cid" (get-in response [:body :correlation-id])))
      (is (= "safe-generated-cid" (get-in response [:headers "x-correlation-id"])))))

  (testing "request id is always generated and safe"
    (let [handler (http/wrap-correlation-context
                   (fn [_] {:status 200})
                   {:request-id-fn (constantly "unsafe request id")
                    :correlation-id-fn (constantly "cid-1")})
          response (handler {:headers {}})
          rid (get-in response [:headers "x-request-id"])]
      (is (string? rid))
      (is (re-matches id-pattern rid))))

  (testing "can expose correlation headers for browser clients"
    (let [handler (http/wrap-correlation-context
                   (fn [_] {:status 200
                            :headers {"access-control-expose-headers" "x-existing"}})
                   {:correlation-id-fn (constantly "cid-2")
                    :request-id-fn (constantly "rid-2")
                    :expose-headers? true})
          response (handler {:headers {}})
          expose (get-in response [:headers "access-control-expose-headers"])]
      (is (string? expose))
      (is (.contains expose "x-existing"))
      (is (.contains expose "x-correlation-id"))
      (is (.contains expose "x-request-id")))))

(deftest wrap-error-contract-test
  (testing "returns unified error body with ids in recommended middleware order"
    (let [base-handler (fn [_] (throw (ex-info "boom" {:http/status 422
                                                       :error/code "validation_failed"
                                                       :error/message "Validation failed"
                                                       :error/details {:field :email}})))
          app (-> base-handler
                  (http/wrap-correlation-context
                   {:correlation-id-fn (constantly "cid-1")
                    :request-id-fn (constantly "rid-1")})
                  (http/wrap-error-contract {}))
          response (app {:headers {}})]
      (is (= 422 (:status response)))
      (is (= "application/json" (get-in response [:headers "content-type"])))
      (is (= "no-store" (get-in response [:headers "cache-control"])))
      (is (= "validation_failed" (get-in response [:body :code])))
      (is (= "Validation failed" (get-in response [:body :message])))
      (is (= {:field :email} (get-in response [:body :details])))
      (is (= "cid-1" (get-in response [:body :correlation-id])))
      (is (= "rid-1" (get-in response [:body :request-id])))))

  (testing "falls back to safe 500 when custom mapper crashes"
    (let [app (http/wrap-error-contract
               (fn [_] (throw (RuntimeException. "boom")))
               {:map-exception (fn [_]
                                 (throw (RuntimeException. "mapper-crash")))})
          response (app {:lcmm/correlation-id "cid-2"
                         :lcmm/request-id "rid-2"})]
      (is (= 500 (:status response)))
      (is (= "internal_error" (get-in response [:body :code])))
      (is (= "Internal server error" (get-in response [:body :message])))
      (is (= "cid-2" (get-in response [:body :correlation-id])))
      (is (= "rid-2" (get-in response [:body :request-id])))))

  (testing "falls back to safe 500 when custom mapper returns invalid contract"
    (let [app (http/wrap-error-contract
               (fn [_] (throw (RuntimeException. "boom")))
               {:map-exception (fn [_]
                                 {:status "500"
                                  :code :internal_error
                                  :message nil})})
          response (app {:lcmm/correlation-id "cid-3"
                         :lcmm/request-id "rid-3"})]
      (is (= 500 (:status response)))
      (is (= "internal_error" (get-in response [:body :code])))))

  (testing "sanitizes sensitive keys in details"
    (let [app (http/wrap-error-contract
               (fn [_]
                 (throw (ex-info "boom" {:http/status 422
                                         :error/code "validation_failed"
                                         :error/message "Validation failed"
                                         :error/details {:token "secret"
                                                         :nested {:password "qwerty"}
                                                         :safe "ok"}})))
               {})
          response (app {:lcmm/correlation-id "cid-4"
                         :lcmm/request-id "rid-4"})]
      (is (= "***" (get-in response [:body :details :token])))
      (is (= "***" (get-in response [:body :details :nested :password])))
      (is (= "ok" (get-in response [:body :details :safe])))))

  (testing "omits id fields from error body when they are absent"
    (let [app (http/wrap-error-contract
               (fn [_] (throw (RuntimeException. "boom")))
               {})
          response (app {})]
      (is (= 500 (:status response)))
      (is (not (contains? (:body response) :correlation-id)))
      (is (not (contains? (:body response) :request-id)))))

  (testing "adds retry-after only for 429/503 when value is safe"
    (let [app (http/wrap-error-contract
               (fn [_] (throw (RuntimeException. "boom")))
               {:map-exception (fn [_]
                                 {:status 429
                                  :code "rate_limited"
                                  :message "Too many requests"
                                  :retry-after 30})})
          response (app {:lcmm/correlation-id "cid-5"
                         :lcmm/request-id "rid-5"})]
      (is (= "30" (get-in response [:headers "retry-after"])))))

  (testing "ignores unsafe retry-after values"
    (let [app (http/wrap-error-contract
               (fn [_] (throw (RuntimeException. "boom")))
               {:map-exception (fn [_]
                                 {:status 503
                                  :code "dependency_unavailable"
                                  :message "Dependency unavailable"
                                  :retry-after "not-a-number"})})
          response (app {:lcmm/correlation-id "cid-6"
                         :lcmm/request-id "rid-6"})]
      (is (nil? (get-in response [:headers "retry-after"]))))))

(deftest bus-publish-opts-test
  (testing "builds publish opts with module and correlation id"
    (let [request {:lcmm/correlation-id "cid-10"}
          opts (http/->bus-publish-opts request {:module :users
                                                 :schema-version "2.0"})]
      (is (= :users (:module opts)))
      (is (= "2.0" (:schema-version opts)))
      (is (= "cid-10" (:correlation-id opts)))))

  (testing "supports fallback to request headers for correlation id"
    (let [opts (http/->bus-publish-opts
                {:headers {"x-correlation-id" "cid-11"}}
                {:module :users})]
      (is (= "cid-11" (:correlation-id opts)))))

  (testing "supports custom correlation header in adapter config"
    (let [opts (http/->bus-publish-opts
                {:headers {"x-cid" "cid-12"}}
                {:module :users}
                {:correlation-header "x-cid"})]
      (is (= "cid-12" (:correlation-id opts)))))

  (testing "rejects unsafe ids from request context and headers"
    (is (thrown? clojure.lang.ExceptionInfo
                 (http/->bus-publish-opts
                  {:lcmm/correlation-id "unsafe value with spaces"}
                  {:module :users}))))

  (testing "fails when module is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (http/->bus-publish-opts {:lcmm/correlation-id "cid"} {})))))

(deftest health-handler-test
  (testing "returns healthy response and includes ids when available"
    (let [handler (http/health-handler {})
          response (handler {:lcmm/correlation-id "cid-h"
                             :lcmm/request-id "rid-h"})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status])))
      (is (= "cid-h" (get-in response [:body :correlation-id])))
      (is (= "rid-h" (get-in response [:body :request-id]))))))

(deftest ready-handler-test
  (testing "returns 200 and ok when all checks pass"
    (let [handler (http/ready-handler
                   {:checks [{:name :db
                              :critical? true
                              :check (fn [] {:ok? true})}
                             {:name :cache
                              :critical? false
                              :check (fn [] {:ok? true})}]})
          response (handler {})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status])))
      (is (= "no-store" (get-in response [:headers "cache-control"])))))

  (testing "returns degraded when only non-critical check fails"
    (let [handler (http/ready-handler
                   {:checks [{:name :db
                              :critical? true
                              :check (fn [] {:ok? true})}
                             {:name :cache
                              :critical? false
                              :check (fn [] {:ok? false})}]})
          response (handler {})]
      (is (= 200 (:status response)))
      (is (= "degraded" (get-in response [:body :status])))))

  (testing "returns 503 when critical check fails"
    (let [handler (http/ready-handler
                   {:checks [{:name :db
                              :critical? true
                              :check (fn [] {:ok? false})}]})
          response (handler {})]
      (is (= 503 (:status response)))
      (is (= "fail" (get-in response [:body :status])))))

  (testing "marks timeout with explicit reason"
    (let [handler (http/ready-handler
                   {:check-timeout-ms 10
                    :checks [{:name :db
                              :critical? true
                              :check (fn [] (Thread/sleep 50) {:ok? true})}]})
          response (handler {})]
      (is (= 503 (:status response)))
      (is (= :check-timeout (get-in response [:body :checks 0 :reason])))))

  (testing "diagnostic mode returns structured diagnostics for failed checks"
    (let [handler (http/ready-handler
                   {:mode :diagnostic
                    :checks [{:name :cache
                              :critical? false
                              :check (fn [] {:ok? false :code :cache-miss})}]})
          response (handler {})]
      (is (= 200 (:status response)))
      (is (= :check-failed (get-in response [:body :checks 0 :reason])))
      (is (= {:result {:code :cache-miss}}
             (get-in response [:body :checks 0 :diagnostic])))))

  (testing "accepts sequential checks, not only vectors"
    (let [handler (http/ready-handler
                   {:checks (list {:name :db
                                   :critical? true
                                   :check (fn [] {:ok? true})})})
          response (handler {})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status]))))))
