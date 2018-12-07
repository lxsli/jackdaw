(ns kafka.test.harness-test
  (:require
   [clojure.test :refer :all]
   [kafka.test.admin :as admin]
   [kafka.test.harness :as harness]
   [kafka.test.kafka :as kafka]
   [kafka.test.zk :as zk]
   [kafka.test.fs :as fs]
   [com.stuartsierra.component :as component]
   [kafka.test.admin :as admin]
   [manifold.stream :as s]
   [manifold.deferred :as d])
  (:import
   [org.I0Itec.zkclient ZkClient]
   [org.I0Itec.zkclient.exception ZkTimeoutException]))

(def broker-config
  {"zookeeper.connect"            "localhost:2181"
   "broker.id"                    "0"
   "advertised.host.name"         "localhost"
   "auto.create.topics.enable"    "true"
   "offsets.topic.num.partitions" "1"
   "log.dirs"                     (fs/tmp-dir "kafka-log")})

(def consumer-config
  {"bootstrap.servers"     "localhost:9092"
   "group.id"              "test"
   "key.deserializer"      "org.apache.kafka.common.serialization.StringDeserializer"
   "value.deserializer"    "org.apache.kafka.common.serialization.StringDeserializer"
   "metadata.max.age.ms"   "1000" ;; usually this is 5 minutes
   "auto.offset.reset"     "earliest"
   "enable.auto.commit"    "true"})

(def producer-config
  {"bootstrap.servers" "localhost:9092"
   "key.serializer"    "org.apache.kafka.common.serialization.StringSerializer"
   "value.serializer"  "org.apache.kafka.common.serialization.StringSerializer"})


(defn system-under-test [config]
  (harness/harness-system config))

(deftest harness-tests
  (testing "basic broker lifecycle"
    (let [sut (-> (system-under-test {:broker broker-config
                                      :producer producer-config
                                      :consumer consumer-config})
                  (atom))
          test-topics ["kafka-streams.harness-test"]]

      (testing "config"

        (is (= broker-config
               (get-in @sut [:harness :config :broker])))

        (is (= consumer-config
               (get-in @sut [:harness :config :consumer])))

        (is (= producer-config
               (get-in @sut [:harness :config :producer]))))


      (testing "start!"
        (swap! sut component/start-system)

        (are [property] (not (nil? (get-in @sut [:harness property])))
          :producer
          :zk-utils
          :zk-client))

      (testing "create!"
        (let [{:keys [zk-utils]} (:harness @sut)]
          (doseq [topic test-topics]
            (admin/create! zk-utils {:topic topic
                                     :replication-factor 1
                                     :partitions 3})
            (is (admin/exists? zk-utils topic)))))

      (testing "put!"
        (let [harness (:harness @sut)
              ack (d/deferred)]
          (harness/put! harness {:topic "kafka-streams.harness-test"
                                 :key "1"
                                 :value "bar"}
                        (kafka/callback (fn [record-meta e]
                                          (if e
                                            (d/error! ack e)
                                            (d/success! ack record-meta)))))
          (is @ack)))


      (testing "take!"
        (let [{:keys [harness]} @sut
              logs (harness/logs harness test-topics)]
          (is (= @(s/take! logs)
                 {:topic "kafka-streams.harness-test"
                  :key "1"
                  :value "bar"}))))

      (testing "multi-take!"
        ;; Note that this test creates two streams so that it can take
        ;; from each independently. This should allow us to write test
        ;; code that doesn't really care about the order of output
        ;; events by looking for events in filtered
        (let [{:keys [harness]} @sut
              log1 (harness/logs harness test-topics)
              log2 (harness/logs harness test-topics)]

          (harness/put! harness {:topic "kafka-streams.harness-test"
                                 :key "2"
                                 :value "foo"})

          (= (s/take! log1)
             {:topic "kafka-streams.harness-test"
              :key "2"
              :value "foo"})

          (= (s/take! log2)
             {:topic "kafka-streams.harness-test"
              :key "2"
              :value "foo"})))


      (testing "stop!"
        (swap! sut component/stop-system)
        (is (nil? (get-in @sut [:harness :producer])))
        (is (nil? (get-in @sut [:harness :zk-utils])))
        (is (nil? (get-in @sut [:harness :zk-client])))
        (is (every? s/closed? @(get-in @sut [:harness :log-streams])))))))
