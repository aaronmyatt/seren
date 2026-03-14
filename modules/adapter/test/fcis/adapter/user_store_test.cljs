(ns fcis.adapter.user-store-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [fcis.adapter.user-store :as store]
            [fcis.core.user :as user]))

;; --- Test fixtures ---

(def test-store-key "cljs-test-store")

(use-fixtures :each
  {:before #(store/clear-store! test-store-key)
   :after  #(store/clear-store! test-store-key)})

;; --- Integration tests ---

(deftest save-user!-test
  (testing "saves user and returns the user map"
    (let [u (user/create-user "Alice" "alice@example.com")
          result (store/save-user! test-store-key u)]
      (is (= u result)))))

(deftest load-user-test
  (testing "loads a previously saved user"
    (let [u (user/create-user "Bob" "bob@example.com")]
      (store/save-user! test-store-key u)
      (is (= u (store/load-user test-store-key (:id u))))))
  (testing "returns nil for non-existent user"
    (is (nil? (store/load-user test-store-key "nonexistent-id")))))

(deftest list-users-test
  (testing "lists all saved users"
    (let [u1 (user/create-user "Alice" "alice@example.com")
          u2 (user/create-user "Bob" "bob@example.com")]
      (store/save-user! test-store-key u1)
      (store/save-user! test-store-key u2)
      (let [users (store/list-users test-store-key)]
        (is (= 2 (count users)))
        (is (= #{(:id u1) (:id u2)}
               (set (map :id users)))))))
  (testing "returns empty vector for non-existent store"
    (is (= [] (store/list-users "nonexistent-store")))))

(deftest delete-user!-test
  (testing "deletes a saved user"
    (let [u (user/create-user "Charlie" "charlie@example.com")]
      (store/save-user! test-store-key u)
      (is (true? (store/delete-user! test-store-key (:id u))))
      (is (nil? (store/load-user test-store-key (:id u))))))
  (testing "returns false for non-existent user"
    (is (false? (store/delete-user! test-store-key "nonexistent-id")))))

(deftest round-trip-test
  (testing "full round-trip: save, load, verify, delete, verify gone"
    (let [u (user/create-user "Dana" "dana@example.com")]
      (store/save-user! test-store-key u)
      (is (= u (store/load-user test-store-key (:id u))))
      (store/delete-user! test-store-key (:id u))
      (is (nil? (store/load-user test-store-key (:id u)))))))
