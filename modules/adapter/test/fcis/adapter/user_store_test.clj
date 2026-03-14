(ns fcis.adapter.user-store-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [fcis.adapter.user-store :as store]
            [fcis.core.user :as user]
            [clojure.java.io :as io]))

;; --- Test fixtures ---

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Creates a temporary directory for each test, cleans up afterward."
  [f]
  (let [dir (str "/tmp/fcis-test-" (random-uuid))]
    (.mkdirs (io/file dir))
    (binding [*test-dir* dir]
      (try
        (f)
        (finally
          ;; Clean up test files
          (doseq [file (.listFiles (io/file dir))]
            (.delete file))
          (.delete (io/file dir)))))))

(use-fixtures :each temp-dir-fixture)

;; --- Integration tests ---

(deftest save-user!-test
  (testing "saves user and returns the user map"
    (let [u (user/create-user "Alice" "alice@example.com")
          result (store/save-user! *test-dir* u)]
      (is (= u result))
      (is (.exists (io/file *test-dir* (str (:id u) ".edn")))))))

(deftest load-user-test
  (testing "loads a previously saved user"
    (let [u (user/create-user "Bob" "bob@example.com")]
      (store/save-user! *test-dir* u)
      (is (= u (store/load-user *test-dir* (:id u))))))
  (testing "returns nil for non-existent user"
    (is (nil? (store/load-user *test-dir* "nonexistent-id")))))

(deftest list-users-test
  (testing "lists all saved users"
    (let [u1 (user/create-user "Alice" "alice@example.com")
          u2 (user/create-user "Bob" "bob@example.com")]
      (store/save-user! *test-dir* u1)
      (store/save-user! *test-dir* u2)
      (let [users (store/list-users *test-dir*)]
        (is (= 2 (count users)))
        (is (= #{(:id u1) (:id u2)}
               (set (map :id users)))))))
  (testing "returns empty vector for non-existent directory"
    (is (= [] (store/list-users "/tmp/fcis-nonexistent-dir")))))

(deftest delete-user!-test
  (testing "deletes a saved user"
    (let [u (user/create-user "Charlie" "charlie@example.com")]
      (store/save-user! *test-dir* u)
      (is (true? (store/delete-user! *test-dir* (:id u))))
      (is (nil? (store/load-user *test-dir* (:id u))))))
  (testing "returns false for non-existent user"
    (is (false? (store/delete-user! *test-dir* "nonexistent-id")))))

(deftest round-trip-test
  (testing "full round-trip: save, load, verify, delete, verify gone"
    (let [u (user/create-user "Dana" "dana@example.com")]
      (store/save-user! *test-dir* u)
      (is (= u (store/load-user *test-dir* (:id u))))
      (store/delete-user! *test-dir* (:id u))
      (is (nil? (store/load-user *test-dir* (:id u)))))))
