(ns sqlite-replicate.service-test
  (:require [clojure.test :refer :all]
            [sqlite-replicate.service :as sut]
            [sqlite-replicate.db :as mock-db] ; For mocking
            [org.httpkit.client :as http-client]
            [org.httpkit.server :as http-server]
            [clojure.data.json :as json]))

(def test-server-port 3002)
(def test-server-url (str "http://localhost:" test-server-port))

(defn server-fixture [f]
  (let [original-server-atom sut/server
        test-server (atom nil)]
    (with-redefs [sut/server test-server ; Isolate server instance for test
                  sut/start-server (fn []
                                     (reset! test-server (http-server/run-server #'sut/health-handler {:port test-server-port}))
                                     (println (str "Test HTTP server started on port " test-server-port)))
                  sut/stop-server (fn []
                                    (when-let [stop-fn @test-server]
                                      (stop-fn :timeout 100) ; Ensure server stops quickly
                                      (reset! test-server nil)
                                      (println (str "Test HTTP server stopped from port " test-server-port))))]
      (try
        (sut/start-server)
        (f) ; Run tests
        (finally
          (sut/stop-server))))))

(use-fixtures :once server-fixture) ; :once because server start/stop is for all tests in this ns

(deftest health-endpoint-test
  (testing "/health endpoint returns OK"
    (let [response @(http-client/get (str test-server-url "/health"))]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))

(deftest periodic-event-writing-test
  (testing "record-timestamp-event! calls db/add-event!"
    (let [add-event-call-count (atom 0)]
      (with-redefs [mock-db/add-event! (fn [ts]
                                         (is (integer? ts) "Timestamp should be an integer.")
                                         (swap! add-event-call-count inc))]
        (sut/record-timestamp-event!) ; Call the refactored function
        (is (= 1 @add-event-call-count) "db/add-event! should be called once.")))))

(deftest start-stop-writer-test
  (testing "start-writer and stop-writer functionality"
    (let [add-event-call-count (atom 0)
          original-writer-atom sut/writer ;; We keep this to remember the original, though not strictly used in this version
          test-writer (atom nil)]      ;; This atom will hold the future for the test
      (with-redefs [sut/writer test-writer ; Redefine sut/writer to use our test-writer atom
                    mock-db/add-event! (fn [_] (swap! add-event-call-count inc))]
        (reset! test-writer nil) ; Ensure clean state for the test-writer atom
        (sut/start-writer)       ; This will place the new future into test-writer

        (let [captured-future @test-writer] ; Capture the actual future object
          (is (future? captured-future) "Writer should be a future.")
          (Thread/sleep 10) ; Give some time for the writer to potentially run

          (sut/stop-writer) ; This will cancel the future in test-writer and then set test-writer to nil

          (is (nil? @test-writer) "Writer atom should be nil after stopping.")
          (is (future-cancelled? captured-future) "Captured future should be cancelled."))

        (let [calls-before-stop @add-event-call-count]
          (Thread/sleep 50) ; Wait a bit more to ensure no more calls after stop
          (is (= calls-before-stop @add-event-call-count) "add-event! should not be called after writer is stopped."))))))
