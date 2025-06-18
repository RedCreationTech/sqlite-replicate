(ns sqlite-replicate.standby-test
  (:require [clojure.test :refer :all]
            [sqlite-replicate.standby :as sut]
            [sqlite-replicate.db :as mock-db] ; For mocking db functions
            [sqlite-replicate.service :as mock-service] ; For mocking service functions
            [clojure.java.shell :as mock-shell] ; For mocking shell commands
            [org.httpkit.client :as mock-client])) ; For mocking http client calls

(deftest check-primary-and-failover-test
  (let [restore-called (atom false)
        service-started (atom false)
        db-initialized (atom false)
        server-started (atom false)
        writer-started (atom false)
        primary-healthy-state (atom true)] ; Define upfront

    (with-redefs [;; Mock sqlite-replicate.standby specific functions
                  sut/restore-db (fn [] (reset! restore-called true) true) ; Assume restore succeeds
                  sut/start-service (fn [] (reset! service-started true)) ; Mock to prevent blocking

                  ;; Mock external calls made by functions in sqlite-replicate.standby
                  mock-client/get (fn [url params] ; Mock for sut/port-active?
                                    (if (= sut/health-url url)
                                      (do
                                        (println (str "Mocked GET request to " url " with params " params))
                                        (if @primary-healthy-state ; Control variable for test, now defined in outer let
                                          (delay {:status 200})
                                          (delay (throw (java.net.ConnectException. "Connection refused")))))
                                      (throw (IllegalArgumentException. (str "Unexpected URL for mock-client/get: " url)))))
                  mock-shell/sh (fn [& args] ; Mock for sut/restore-db (if not mocking restore-db directly)
                                  (if (= (first args) "litestream")
                                    (do (println (str "Mocked shell/sh call with args: " args))
                                        {:exit 0 :out "Mocked litestream restore success" :err ""})
                                    (throw (IllegalArgumentException. (str "Unexpected command for mock-shell/sh: " (first args))))))

                  ;; Mock functions called by the original start-service
                  mock-db/initialize-database! (fn [] (reset! db-initialized true))
                  mock-service/start-server (fn [] (reset! server-started true))
                  mock-service/start-writer (fn [] (reset! writer-started true))]

      (testing "Primary is healthy"
        (reset! restore-called false)
        (reset! service-started false)
        (reset! primary-healthy-state true) ; Set state for this test

        (with-redefs [sut/port-active? (fn []
                                         (println (str "Mocked port-active? returning: " @primary-healthy-state))
                                         @primary-healthy-state)]
            (let [failover-attempted? (sut/check-primary-and-failover!)]
              (is (not failover-attempted?) "Should not attempt failover if primary is healthy.")
              (is (not @restore-called) "restore-db should not be called.")
              (is (not @service-started) "start-service should not be called.")))
        )


      (testing "Primary is unhealthy, restore succeeds"
        (reset! restore-called false)
        (reset! service-started false)
        (reset! db-initialized false)
        (reset! server-started false)
        (reset! writer-started false)
        (reset! primary-healthy-state false) ; Set state for this test

        ;; Redefine start-service to check its components for this specific sub-test
        ;; Also redefine port-active? for this scope
        (with-redefs [sut/port-active? (fn []
                                         (println (str "Mocked port-active? returning: " @primary-healthy-state))
                                         @primary-healthy-state)
                      sut/start-service (fn []
                                          (mock-db/initialize-database!)
                                          (mock-service/start-server)
                                          (mock-service/start-writer)
                                          (reset! service-started true))]

            (let [failover-attempted? (sut/check-primary-and-failover!)]
                (is failover-attempted? "Should attempt failover if primary is unhealthy.")
                (is @restore-called "restore-db should be called.")
                (is @service-started "start-service logic should be invoked.")
                (is @db-initialized "db/initialize-database! should be called by start-service.")
                (is @server-started "service/start-server should be called by start-service.")
                (is @writer-started "service/start-writer should be called by start-service."))))
        )

      (testing "Primary is unhealthy, restore fails"
        (reset! restore-called false)
        (reset! service-started false)
        (reset! primary-healthy-state false) ; Set state for this test

        (with-redefs [sut/port-active? (fn []
                                         (println (str "Mocked port-active? returning: " @primary-healthy-state))
                                         @primary-healthy-state)
                      sut/restore-db (fn [] (reset! restore-called true) false)] ; Mock restore to fail
            (let [failover-attempted? (sut/check-primary-and-failover!)]
                (is (not failover-attempted?) "Failover should be marked as unsuccessful if restore fails.")
                (is @restore-called "restore-db should be called.")
                (is (not @service-started) "start-service should not be called if restore fails."))))
        ))
