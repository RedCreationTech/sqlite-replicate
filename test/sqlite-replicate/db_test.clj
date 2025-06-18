(ns sqlite-replicate.db-test
  (:require [clojure.test :refer :all]
            [sqlite-replicate.db :as sut] ; sut for "system under test"
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.java.io :as io]))

(def test-db-name "test-app-data.db")
(def test-db-spec {:dbtype "sqlite" :dbname test-db-name})
(def test-ds (jdbc/get-datasource test-db-spec)) ; Use a test-specific datasource

(defn clean-db-fixture [f]
  (try
    (println "Deleting existing test DB (if any):" test-db-name)
    (.delete (io/file test-db-name)) ; Ensure clean state before
    (f) ; Run the tests
    (finally
      (println "Deleting test DB:" test-db-name)
      (.delete (io/file test-db-name)))))

(use-fixtures :each clean-db-fixture)

(deftest init-db-test
  (testing "initialize-database! creates database and tables"
    (with-redefs [sut/ds test-ds] ; Redefine the datasource used by sut
      (sut/initialize-database!)
      (is (.exists (io/file test-db-name)) "Database file should be created.")
      ;; next.jdbc lowercases table and column names by default and namespaces them
      (let [tables (jdbc/execute! test-ds ["SELECT name FROM sqlite_master WHERE type='table';"])]
        (is (some #(= "users" (:sqlite_master/name %)) tables) "users table should exist.")
        (is (some #(= "events" (:sqlite_master/name %)) tables) "events table should exist.")))))

(deftest user-persistence-test
  (testing "add-user! and get-all-users"
    (with-redefs [sut/ds test-ds]
      (sut/initialize-database!) ; Ensure tables exist

      (let [user-data {:name "Test User" :email "test@example.com"}]
        (sut/add-user! user-data) ; add-user! uses sut/ds which is now test-ds

        ;; get-all-users also uses sut/ds
        (let [users (sut/get-all-users)
              ;; next.jdbc by default returns namespaced keywords, e.g., :users/name
              retrieved-user (first (filter #(= "Test User" (:users/name %)) users))]
          (is (some? retrieved-user) "User should be retrieved.")
          (is (= "Test User" (:users/name retrieved-user)) "User name should match.")
          (is (= "test@example.com" (:users/email retrieved-user)) "User email should match."))))))

(deftest event-persistence-test
  (testing "add-event! and query events"
    (with-redefs [sut/ds test-ds]
      (sut/initialize-database!) ; Ensure tables exist

      (let [current-time (System/currentTimeMillis)]
        (sut/add-event! current-time) ; add-event! uses sut/ds

        ;; Query directly using test-ds to check
        (let [events (sql/query test-ds ["SELECT ts FROM events ORDER BY ts DESC LIMIT 1"])
              retrieved-event (first events)]
          (is (some? retrieved-event) "Event should be retrieved.")
          ;; sql/query also returns namespaced keywords, e.g., :events/ts
          (is (= current-time (:events/ts retrieved-event)) "Event timestamp should match."))))))
