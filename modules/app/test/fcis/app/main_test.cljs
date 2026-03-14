(ns fcis.app.main-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [fcis.app.main :as app]
            [fcis.adapter.user-store :as store]))

;; --- Test fixtures ---

(def test-store-key "cljs-app-test-store")

(use-fixtures :each
  {:before #(store/clear-store! test-store-key)
   :after  #(store/clear-store! test-store-key)})

;; --- Smoke / end-to-end tests ---

(deftest register-user!-success-test
  (testing "successful registration returns user"
    (let [result (app/register-user! {:store-dir test-store-key
                                       :name "Alice"
                                       :email "alice@example.com"})]
      (is (true? (:success result)))
      (is (= "Alice" (get-in result [:user :name])))
      (is (= "alice@example.com" (get-in result [:user :email])))
      (is (string? (get-in result [:user :id]))))))

(deftest register-user!-validation-failure-test
  (testing "invalid input returns errors"
    (let [result (app/register-user! {:store-dir test-store-key
                                       :name ""
                                       :email "bad"})]
      (is (false? (:success result)))
      (is (= 2 (count (:errors result)))))))

(deftest register-and-retrieve-test
  (testing "registered user can be retrieved"
    (let [result (app/register-user! {:store-dir test-store-key
                                       :name "Bob"
                                       :email "bob@example.com"})
          user-id (get-in result [:user :id])
          loaded  (app/get-user {:store-dir test-store-key} user-id)]
      (is (= (:user result) loaded)))))

(deftest list-all-users-test
  (testing "lists all registered users"
    (app/register-user! {:store-dir test-store-key :name "A" :email "a@b.com"})
    (app/register-user! {:store-dir test-store-key :name "B" :email "b@c.com"})
    (let [users (app/list-all-users {:store-dir test-store-key})]
      (is (= 2 (count users))))))

(deftest get-user-not-found-test
  (testing "returns nil for non-existent user"
    (is (nil? (app/get-user {:store-dir test-store-key} "nonexistent")))))
