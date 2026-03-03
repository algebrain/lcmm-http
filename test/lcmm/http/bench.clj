(ns lcmm.http.bench
  (:require [clojure.string :as str]
            [lcmm.http.core :as http])
  (:gen-class))

(defn- parse-long*
  [s]
  (try
    (Long/parseLong (str s))
    (catch Throwable _
      nil)))

(defn- parse-cli
  [args]
  (reduce (fn [acc arg]
            (if (str/starts-with? arg "--")
              (let [[k v] (str/split (subs arg 2) #"=" 2)]
                (assoc acc (keyword k) (or v "true")))
              acc))
          {}
          args))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- used-bytes []
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn- force-gc! []
  (System/gc)
  (Thread/sleep 20)
  (System/gc)
  (Thread/sleep 20))

(defn- run-timed
  [f warmup iters]
  (dotimes [_ warmup]
    (f))
  (let [start (System/nanoTime)]
    (dotimes [_ iters]
      (f))
    (let [total-ns (- (System/nanoTime) start)
          avg-ns (double (/ total-ns (max 1 iters)))
          ops-sec (double (/ 1.0 (/ avg-ns 1000000000.0)))]
      {:total-ns total-ns
       :avg-ns avg-ns
       :ops-sec ops-sec})))

(defn- run-memory
  [f iters]
  (force-gc!)
  (let [before (used-bytes)]
    (dotimes [_ iters]
      (f))
    (force-gc!)
    (let [after (used-bytes)
          delta (- after before)]
      {:before-bytes before
       :after-bytes after
       :delta-bytes delta
       :bytes-op (double (/ delta (max 1 iters)))})))

(defn- run-soak
  [f soak-sec]
  (let [deadline (+ (now-ms) (* 1000 soak-sec))]
    (force-gc!)
    (loop [ops 0
           min-b (used-bytes)
           max-b min-b]
      (if (>= (now-ms) deadline)
        {:ops ops
         :min-bytes min-b
         :max-bytes max-b
         :delta-bytes (- max-b min-b)}
        (do
          (f)
          (let [u (used-bytes)]
            (recur (inc ops) (min min-b u) (max max-b u))))))))

(defn- make-scenarios
  []
  (let [ok-handler (http/wrap-correlation-context
                    (fn [_] {:status 200})
                    {})
        invalid-handler (http/wrap-correlation-context
                         (fn [_] {:status 200})
                         {})
        error-default (http/wrap-error-contract
                       (fn [_] (throw (RuntimeException. "boom")))
                       {})
        error-custom (http/wrap-error-contract
                      (fn [_] (throw (RuntimeException. "boom")))
                      {:map-exception (fn [_]
                                        {:status 422
                                         :code "validation_failed"
                                         :message "Validation failed"
                                         :details {:field :email}})})
        error-fallback (http/wrap-error-contract
                        (fn [_] (throw (RuntimeException. "boom")))
                        {:map-exception (fn [_]
                                          (throw (RuntimeException. "mapper failed")))})
        ready-ok (http/ready-handler
                  {:checks [{:name :db :critical? true :check (fn [] {:ok? true})}]})
        ready-degraded (http/ready-handler
                        {:checks [{:name :db :critical? true :check (fn [] {:ok? true})}
                                  {:name :cache :critical? false :check (fn [] {:ok? false})}]})
        ready-timeout (http/ready-handler
                       {:check-timeout-ms 5
                        :checks [{:name :db :critical? true
                                  :check (fn [] (Thread/sleep 10) {:ok? true})}]})]
    [{:id "correlation-happy"
      :fn #(ok-handler {:headers {"x-correlation-id" "cid-1"}})}
     {:id "correlation-invalid-id"
      :fn #(invalid-handler {:headers {"x-correlation-id" "invalid id with spaces"}})}
     {:id "error-happy"
      :fn #((http/wrap-error-contract (fn [_] {:status 200}) {}) {})}
     {:id "error-default"
      :fn #(error-default {})}
     {:id "error-custom"
      :fn #(error-custom {})}
     {:id "error-fallback"
      :fn #(error-fallback {})}
     {:id "bus-opts-default-header"
      :fn #(http/->bus-publish-opts {:lcmm/correlation-id "cid-2"} {:module :users})}
     {:id "bus-opts-custom-header"
      :fn #(http/->bus-publish-opts {:headers {"x-cid" "cid-3"}}
                                    {:module :users}
                                    {:correlation-header "x-cid"})}
     {:id "ready-ok"
      :fn #(ready-ok {})}
     {:id "ready-degraded"
      :fn #(ready-degraded {})}
     {:id "ready-timeout"
      :fn #(ready-timeout {})}]))

(defn- mode-config
  [mode]
  (case mode
    "full" {:warmup 1000 :iters 10000 :soak-sec 15}
    {:warmup 100 :iters 1000 :soak-sec 1}))

(defn- coerce-config
  [cli]
  (let [mode (get cli :mode "quick")
        defaults (mode-config mode)
        warmup (or (parse-long* (get cli :warmup)) (:warmup defaults))
        iters (or (parse-long* (get cli :iters)) (:iters defaults))
        soak-sec (or (parse-long* (get cli :soak-sec)) (:soak-sec defaults))]
    {:mode mode
     :bench (get cli :bench "all")
     :report (get cli :report "stdout")
     :out (get cli :out)
     :warmup warmup
     :iters iters
     :soak-sec soak-sec}))

(defn- run-scenario
  [scenario {:keys [warmup iters soak-sec]}]
  (let [f (:fn scenario)
        timed (run-timed f warmup iters)
        mem (run-memory f iters)
        soak (run-soak f soak-sec)]
    {:id (:id scenario)
     :time timed
     :memory mem
     :soak soak}))

(defn- print-row
  [{:keys [id time memory soak]}]
  (println (format "%-28s avg-ns=%10.2f ops/s=%12.2f bytes/op=%10.2f soak-delta=%d"
                   id
                   (:avg-ns time)
                   (:ops-sec time)
                   (:bytes-op memory)
                   (:delta-bytes soak))))

(defn- write-json-report!
  [path data]
  (let [f (java.io.File. path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))
    (spit f (pr-str data))))

(defn -main
  [& args]
  (let [cli (parse-cli args)
        {:keys [bench report out] :as config} (coerce-config cli)
        scenarios (make-scenarios)
        chosen (if (= bench "all")
                 scenarios
                 (filter #(= bench (:id %)) scenarios))
        start (now-ms)
        results (mapv #(run-scenario % config) chosen)
        finished (now-ms)
        payload {:started-at start
                 :finished-at finished
                 :duration-ms (- finished start)
                 :config (dissoc config :out)
                 :results results}]
    (when (or (= report "stdout") (= report "both"))
      (println "BENCHMARK REPORT")
      (println (str "mode=" (:mode config)
                    " warmup=" (:warmup config)
                    " iters=" (:iters config)
                    " soak-sec=" (:soak-sec config)
                    " scenarios=" (count results)))
      (doseq [row results]
        (print-row row)))
    (when (or (= report "json") (= report "both"))
      (write-json-report! (or out ".local.bench/report.edn") payload)
      (println (str "Report written to " (or out ".local.bench/report.edn"))))
    (when (empty? results)
      (println (str "No benchmark matched --bench=" bench))
      (System/exit 2))))
