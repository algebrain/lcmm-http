#!/usr/bin/env bb
(ns bench
  (:require [babashka.process :refer [process]]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def green "\u001b[1;32m")
(def reset "\u001b[0m")

(defn started-at []
  (let [t (java.time.LocalTime/now)
        s (.format t (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))]
    (println (str green "STARTED AT " s reset))))

(defn banner [text]
  (println (str green text reset)))

(defn- parse-long [s]
  (try
    (Long/parseLong s)
    (catch Throwable _ nil)))

(defn- opt-value
  [args prefix]
  (some (fn [arg]
          (when (str/starts-with? arg prefix)
            (subs arg (count prefix))))
        args))

(defn- parse-timeout-ms [args]
  (let [timeout-ms-arg (opt-value args "--timeout-ms=")
        timeout-min-arg (opt-value args "--timeout-min=")]
    (cond
      timeout-ms-arg (parse-long timeout-ms-arg)
      timeout-min-arg (some-> (parse-long timeout-min-arg) (* 60 1000))
      :else (* 10 60 1000))))

(def timeout-ms
  (or (parse-timeout-ms *command-line-args*)
      (* 10 60 1000)))

(defn- destroy-tree! [^Process p]
  (let [^java.lang.ProcessHandle ph (.toHandle p)
        consumer (reify java.util.function.Consumer
                   (accept [_ ^java.lang.ProcessHandle h]
                     (.destroyForcibly h)))]
    (.forEach (.descendants ph) consumer)
    (.destroyForcibly p)))

(defn run! [cmd]
  (let [proc (process {:inherit true} cmd)
        ^Process p (:proc proc)
        finished? (.waitFor p timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      (let [exit (.exitValue p)]
        (when (not= 0 exit)
          (System/exit exit)))
      (do
        (println (str green "TIMEOUT after " timeout-ms " ms: " cmd reset))
        (destroy-tree! p)
        (.waitFor p 5000 TimeUnit/MILLISECONDS)
        (when (.isAlive p)
          (println (str green "Process still alive after forced destroy" reset)))
        (System/exit 1)))))

(defn- bench-args
  [args]
  (->> args
       (remove #(or (str/starts-with? % "--timeout-ms=")
                    (str/starts-with? % "--timeout-min=")))
       (str/join " ")))

(started-at)
(banner "BENCH")
(run! (str "clj -J--enable-native-access=ALL-UNNAMED -M:bench " (bench-args *command-line-args*)))
