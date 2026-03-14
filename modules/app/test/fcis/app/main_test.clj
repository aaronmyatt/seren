(ns fcis.app.main-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [fcis.app.main :as app]
            [clojure.java.io :as io]))

;; --- Test fixtures ---

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str "/tmp/fcis-app-test-" (random-uuid))]
    (.mkdirs (io/file dir))
    (binding [*test-dir* dir]
      (try
        (f)
        (finally
          (doseq [file (.listFiles (io/file dir))]
            (.delete file))
          (.delete (io/file dir)))))))

(use-fixtures :each temp-dir-fixture)

;; --- Smoke / end-to-end tests ---

(deftest register-user!-success-test
  (testing "successful registration returns user"
    (let [result (app/register-user! {:store-dir *test-dir*
                                       :name "Alice"
                                       :email "alice@example.com"})]
      (is (true? (:success result)))
      (is (= "Alice" (get-in result [:user :name])))
      (is (= "alice@example.com" (get-in result [:user :email])))
      (is (string? (get-in result [:user :id]))))))

(deftest register-user!-validation-failure-test
  (testing "invalid input returns errors"
    (let [result (app/register-user! {:store-dir *test-dir*
                                       :name ""
                                       :email "bad"})]
      (is (false? (:success result)))
      (is (= 2 (count (:errors result)))))))

(deftest register-and-retrieve-test
  (testing "registered user can be retrieved"
    (let [result (app/register-user! {:store-dir *test-dir*
                                       :name "Bob"
                                       :email "bob@example.com"})
          user-id (get-in result [:user :id])
          loaded  (app/get-user {:store-dir *test-dir*} user-id)]
      (is (= (:user result) loaded)))))

(deftest list-all-users-test
  (testing "lists all registered users"
    (app/register-user! {:store-dir *test-dir* :name "A" :email "a@b.com"})
    (app/register-user! {:store-dir *test-dir* :name "B" :email "b@c.com"})
    (let [users (app/list-all-users {:store-dir *test-dir*})]
      (is (= 2 (count users))))))

(deftest get-user-not-found-test
  (testing "returns nil for non-existent user"
    (is (nil? (app/get-user {:store-dir *test-dir*} "nonexistent")))))
