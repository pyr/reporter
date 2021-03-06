(ns spootnik.reporter.impl
  (:require [aleph.http                 :as http]
            [manifold.deferred         :as d]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as c]
            [raven.client               :as raven]
            [metrics.reporters.console  :as console]
            [metrics.reporters.jmx      :as jmx]
            [metrics.reporters.graphite :as graphite]
            [metrics.reporters.riemann  :as riemann]
            [spootnik.log-reporter      :as logs]
            [metrics.jvm.core           :as jvm]
            [metrics.core               :as m]
            [metrics.gauges             :as gge]
            [metrics.counters           :as cnt]
            [metrics.meters             :as mtr]
            [metrics.timers             :as tmr]
            [metrics.histograms         :as hst]
            [clojure.string             :as str]
            [clojure.tools.logging      :refer [info error]]
            [spootnik.uncaught          :refer [with-uncaught]])
  (:import com.aphyr.riemann.client.RiemannClient
           com.aphyr.riemann.client.RiemannBatchClient
           com.aphyr.riemann.client.TcpTransport
           com.aphyr.riemann.client.SSL
           com.aphyr.riemann.client.EventDSL
           com.aphyr.riemann.client.IPromise
           com.codahale.metrics.ScheduledReporter
           io.netty.handler.ssl.ClientAuth
           io.netty.handler.ssl.SslContextBuilder
           io.prometheus.client.CollectorRegistry
           io.prometheus.client.dropwizard.DropwizardExports
           io.prometheus.client.exporter.common.TextFormat
           io.prometheus.client.hotspot.DefaultExports
           java.io.StringWriter
           java.util.concurrent.TimeUnit
           java.util.List
           java.util.Map
           java.net.InetSocketAddress))

(defprotocol RiemannSink
  (send! [this e]))

(defprotocol SentrySink
  (capture! [this e] [this e tags]))

(defprotocol MetricHolder
  (instrument! [this prefix])
  (build! [this type alias] [this type alias f])
  (inc! [this alias] [this alias v])
  (dec! [this alias] [this alias v])
  (mark! [this alias] [this alias v])
  (update! [this alias v])
  (time-fn! [this alias f])
  (start! [this alias])
  (stop! [this ctx]))

(def config->protocol
  (comp keyword
        str/lower-case
        (fnil name "tcp")
        :protocol))

(defmulti build-client config->protocol)

(defmethod build-client :udp
  [{:keys [host port] :or {host "127.0.0.1" port 5555}}]
  (RiemannClient/udp host (int port)))

(defmethod build-client :tcp
  [{:keys [host port] :or {host "127.0.0.1" port 5555}}]
  (RiemannClient/tcp host (int port)))

(defmethod build-client :tls
  [{:keys [host port tls] :or {host "127.0.0.1" port 5554}}]
  (RiemannClient/wrap
   (doto (new TcpTransport host (int port))
     (-> .-sslContext
         (.set (SSL/sslContext
                (:pkey tls)
                (:cert tls)
                (:authority tls)))))))

(defmethod build-client :default
  [_]
  (throw (ex-info "Cannot build riemann client for invalid protocol" {})))

(defn- build-metrics-reporter-dispatch-fn [_ _ _ [k _]]
  (info "building" k "reporter!")
  k)

(defmulti build-metrics-reporter build-metrics-reporter-dispatch-fn)

(defmethod build-metrics-reporter :console [reg _ _ [_ {:keys [opts interval]}]]
  (let [r (console/reporter reg opts)]
    (reify
      c/Lifecycle
      (start [this] (console/start r interval) this)
      (stop [this]
        (info "scheduling a final console report")
        (.report r)
        (console/stop r)
        this))))

(defmethod build-metrics-reporter :logs [reg _ _ [_ {:keys [opts interval]}]]
  (let [r (logs/reporter reg opts)]
    (reify
      c/Lifecycle
      (start [this] (logs/start r interval) this)
      (stop [this]
        (info "scheduling a final console report")
        (.report r)
        (logs/stop r)
        this))))

(defmethod build-metrics-reporter :jmx [reg _ _ [_ {:keys [opts interval]}]]
  (let [r (jmx/reporter reg opts)]
    (reify
      c/Lifecycle
      (start [this] (jmx/start r) this)
      (stop [this]  (jmx/stop r) this))))

(defmethod build-metrics-reporter :graphite [reg _ _ [_ {:keys [opts interval]}]]
  (let [r (graphite/reporter reg opts)]
    (reify
      c/Lifecycle
      (start [this] (graphite/start r interval) this)
      (stop [this]  (graphite/stop r) this))))

(defmethod build-metrics-reporter :riemann [reg rclient _ [_ {:keys [opts interval]}]]
  (if-not rclient
    (throw (ex-info "need a valid riemann client to build reporter" {}))
    (let [r (riemann/reporter rclient reg opts)]
      (reify
        c/Lifecycle
        (start [this] (riemann/start r interval) this)
        (stop [this]
          (info "scheduling a final report of our metrics")
          (.report ^ScheduledReporter r)
          (riemann/stop r)
          this)))))

(defmethod build-metrics-reporter :prometheus [reg _ ^CollectorRegistry prometheus-registry _]
  (reify
    c/Lifecycle
    (start [this]
      (info "start prometheus reporter")
      (let [exporter (DropwizardExports. reg)]
        (.register prometheus-registry exporter)
        (DefaultExports/initialize)
        this))
    (stop [this])))

(defmethod build-metrics-reporter :default [_ _ _ [k _]]
  (throw (ex-info "Cannot build requested metrics reporter" {:reporter-key k})))

(defn ^RiemannClient riemann-client
  "To keep dependency conflicts, let's use RiemannClient directly."
  [{:keys [batch] :as opts}]
  (try
    (let [client ^RiemannClient (build-client opts)]
      (try
        (.connect client)
        (catch Exception e
          (error e "cannot connect to riemann")))
      (if (and batch (number? batch) (pos? batch))
        (RiemannBatchClient. client (int batch))
        client))
    (catch Exception e
      (error e "cannot create riemann client"))))

(defn ->event [^RiemannClient client defaults {:keys [host service time ^Double metric description] :as ev}]
  (let [tags  (some-> (concat (:tags defaults) (:tags ev)) set seq)
        attrs (merge (:attrs defaults) (:attrs ev))
        ttl   (if-let [t (or (:ttl ev) (:ttl defaults))] (float t))
        state (or (:state ev) (:state defaults))
        time  ^Long (or time (quot (System/currentTimeMillis) 1000))]
    (-> (.event client)
        (.host (or host (:host defaults) (raven/localhost)))
        (.service (or service (:service defaults) "<none>"))
        (.time (long time))
        (cond-> metric      (.metric metric)
                state       (.state ^String state)
                (seq attrs) (.attributes ^Map attrs)
                (seq tags)  (.tags ^List tags)
                ttl         (.ttl (float ttl))
                description (.description ^String description))
        ^EventDSL
        (.build))))

(defn riemann-send [^RiemannClient client events]
  (if (= (count events) 1)
    (.sendEvent client (first events))
    (.sendEvents client ^List events)))

(defn riemann-events!
  [client defaults events]
  (let [deref' #(.deref ^IPromise % 100 TimeUnit/MILLISECONDS)]
    (->> events
         (map #(->event client defaults %))
         (riemann-send client)
         (deref'))))

(defn build-metrics-reporters
  [reg reporters rclient ^CollectorRegistry prometheus-registry]
  (info "building metrics reporters")
  (mapv (partial build-metrics-reporter reg rclient prometheus-registry) reporters))

(defn build-metrics
  [{:keys [reporters]} rclient ^CollectorRegistry prometheus-registry]
  (let [reg  (m/new-registry)
        reps (build-metrics-reporters reg reporters rclient prometheus-registry)]
    (doseq [r reps]
      (c/start r))
    [reg reps]))

(defn ->alias
  [v]
  (if (sequential? v)
    (map name v)
    (name v)))

(defn prometheus-str-metrics
  "Extracts and returns as string metrics from a Prometheus registry.
  Defaults to the defaultRegistry if no registry is provided."
  ([]
   (prometheus-str-metrics CollectorRegistry/defaultRegistry))
  ([^CollectorRegistry registry]
   (let [writer (StringWriter.)]
     (TextFormat/write004
      writer
      (.metricFamilySamples registry))
     (.toString writer))))

(defn prometheus-handler
  [prometheus ^CollectorRegistry prometheus-registry req]
  (if (and (:endpoint prometheus)
           (not= (:uri req) (:endpoint prometheus)))
    {:status 404}
    {:status 200
     :headers {"Content-Type" TextFormat/CONTENT_TYPE_004}
     :body (prometheus-str-metrics prometheus-registry)}))

(defn ssl-context
  [{:keys [pkey cert ca-cert]}]
  (-> (SslContextBuilder/forServer (io/file cert) (io/file pkey))
      (.trustManager (io/file ca-cert))
      (.clientAuth ClientAuth/REQUIRE)
      (.build)))

;; "sentry" is a sentry map like {:dsn "..."}
;; "raven-options" is the options map sent to raven http client
;; http://aleph.io/codox/aleph/aleph.http.html#var-request
(defrecord Reporter [rclient raven-options reporters registry sentry metrics riemann prevent-capture? prometheus
                     started?]
  c/Lifecycle
  (start [this]
    (if started?
      this
      (let [prometheus-registry (CollectorRegistry/defaultRegistry)
            rclient             (when riemann (riemann-client riemann))
            [reg reps]          (build-metrics metrics rclient prometheus-registry)
            options             (when sentry (or raven-options {}))
            prometheus-server   (when prometheus
                                  (let [tls (:tls prometheus)
                                        opts (cond->
                                                 {:port (:port prometheus)}
                                               (some? (:tls prometheus))
                                               (assoc :ssl-context (ssl-context tls))
                                               (:host prometheus)
                                               (assoc :socket-address
                                                      (InetSocketAddress.
                                                       (:host prometheus)
                                                       (:port prometheus))))]
                                    (http/start-server
                                     (partial prometheus-handler
                                              prometheus
                                              prometheus-registry)
                                     opts)))]
        (when-not prevent-capture?
          (with-uncaught e
            (capture! (assoc this :raven-options options) e)))
        (cond-> (assoc this
                       :registry      reg
                       :reporters     reps
                       :rclient       rclient
                       :raven-options options
                       :started? true)
          prometheus (assoc :prometheus {:server   prometheus-server
                                         :registry prometheus-registry})))))
  (stop [this]
    (when started?
      (when-not prevent-capture?
        (with-uncaught e
          (error e "uncaught exception.")))
      (doseq [r reporters]
        (c/stop r))
      (when registry
        (info "shutting down metric registry")
        (m/remove-all-metrics registry))
      (when rclient
        (try
          (.close ^RiemannClient rclient)
          (catch Exception _)))
      (when prometheus
        (.close ^java.io.Closeable (:server prometheus))))
    (assoc this
           :raven-options nil
           :reporters nil
           :registry nil
           :rclient nil
           :prometheus nil
           :started? false))
  MetricHolder
  (instrument! [this prefix]
    (when registry
      (doseq [[f title] [[jvm/register-jvm-attribute-gauge-set ["jvm" "attribute"]]
                         [jvm/register-memory-usage-gauge-set ["jvm" "memory"]]
                         [jvm/register-file-descriptor-ratio-gauge-set ["jvm" "file"]]
                         [jvm/register-garbage-collector-metric-set ["jvm" "gc"]]
                         [jvm/register-thread-state-gauge-set ["jvm" "thread"]]]]
        (f registry (mapv name (concat prefix title))))))
  (build! [this type alias f]
    (when registry
      (assert (fn? f))
      (case type
        :gauge (or (gge/gauge registry (->alias alias))
                   (gge/gauge-fn registry (->alias alias) f))
        (throw (ex-info "invalid metric type" {})))))
  (build! [this type alias]
    (when registry
      (case type
        :counter   (cnt/counter registry (->alias alias))
        :meter     (mtr/meter registry (->alias alias))
        :histogram (hst/histogram registry (->alias alias))
        :timer     (tmr/timer registry (->alias alias))
        (throw (ex-info "invalid metric type" {})))))
  (inc! [this alias]
    (when registry
      (cnt/inc! (cnt/counter registry (->alias alias)))))
  (inc! [this alias v]
    (when registry
      (cnt/inc! (cnt/counter registry (->alias alias)) v)))
  (dec! [this alias]
    (when registry
      (cnt/dec! (cnt/counter registry (->alias alias)))))
  (dec! [this alias v]
    (when registry
      (cnt/dec! (cnt/counter registry (->alias alias)) v)))
  (mark! [this alias]
    (when registry
      (mtr/mark! (mtr/meter registry (->alias alias)))))
  (mark! [this alias v]
    (when registry
      (mtr/mark! (mtr/meter registry (->alias alias)) v)))
  (update! [this alias v]
    (when registry
      (hst/update! (hst/histogram registry (->alias alias)) v)))
  (time-fn! [this alias f]
    (if registry
      (tmr/time-fn! (tmr/timer registry (->alias alias)) f)
      (f)))
  (start! [this alias]
    (when registry
      (tmr/start (tmr/timer registry (->alias alias)))))
  (stop! [this ctx]
    (when registry
      (tmr/stop ctx)))
  SentrySink
  (capture! [this e]
    (capture! this e {}))
  (capture! [this e tags]
    (if (:dsn sentry)
      (d/chain
       (raven/capture! raven-options (:dsn sentry) e tags)
       (fn [event-id]
         (error e (str "captured exception as sentry event: " event-id))))
      (error e)))
  RiemannSink
  (send! [this ev]
    (when rclient
      (let [to-seq #(if-not (sequential? %) [%] %)]
        (->> ev
             to-seq
             (riemann-events! rclient (:defaults riemann)))))))

(defmacro time!
  [reporter alias & body]
  `(time-fn! ~reporter ~alias (fn [] (do ~@body))))

(defmacro with-time!
  [[reporter alias] & body]
  `(time-fn! ~reporter ~alias (fn [] (do ~@body))))

(defn make-reporter
  ([]
   (map->Reporter nil))
  ([reporter]
   (map->Reporter reporter)))

(extend-type nil
  MetricHolder
  (instrument! [this prefix])
  (build! ([this type alias f]) ([this type alias]))
  (inc! ([this alias]) ([this alias v]))
  (dec! ([this alias]) ([this alias v]))
  (mark! ([this alias]) ([this alias v]))
  (update! [this alias v])
  (time-fn! [this alias f] (f))
  (start! [this alias])
  (stop! [this ctx])
  SentrySink
  (capture! ([this e]) ([this e tags]))
  RiemannSink
  (send! [this ev]))
