(ns fcis.core.user-test
  (:require #?(:clj  [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [fcis.core.user :as user]
            [fcis.core.schemas :as schemas]
            [fcis.schemas.common :as common]))

;; ============================================================
;; Unit tests
;; ============================================================

(deftest normalize-email-test
  (testing "lowercases email"
    (is (= "alice@example.com" (user/normalize-email "Alice@Example.COM"))))
  (testing "trims whitespace"
    (is (= "alice@example.com" (user/normalize-email "  alice@example.com  "))))
  (testing "handles already normalized"
    (is (= "alice@example.com" (user/normalize-email "alice@example.com")))))

(deftest validate-email-test
  (testing "valid emails"
    (is (true? (:valid? (user/validate-email "user@example.com"))))
    (is (true? (:valid? (user/validate-email "a@b.co"))))
    (is (true? (:valid? (user/validate-email "user+tag@domain.org")))))
  (testing "invalid emails"
    (is (false? (:valid? (user/validate-email "no-at-sign"))))
    (is (false? (:valid? (user/validate-email "@no-local.com"))))
    (is (false? (:valid? (user/validate-email "user@"))))
    (is (false? (:valid? (user/validate-email ""))))))

(deftest validate-name-test
  (testing "valid names"
    (is (true? (:valid? (user/validate-name "Alice"))))
    (is (true? (:valid? (user/validate-name "A")))))
  (testing "blank name"
    (is (false? (:valid? (user/validate-name ""))))
    (is (= "Name cannot be blank" (:reason (user/validate-name "")))))
  (testing "whitespace-only name"
    (is (false? (:valid? (user/validate-name "   ")))))
  (testing "name too long"
    (is (false? (:valid? (user/validate-name (apply str (repeat 101 "a"))))))))

(deftest validate-user-input-test
  (testing "valid input"
    (let [result (user/validate-user-input {:name "Alice" :email "alice@example.com"})]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  (testing "invalid name only"
    (let [result (user/validate-user-input {:name "" :email "alice@example.com"})]
      (is (false? (:valid? result)))
      (is (= 1 (count (:errors result))))))
  (testing "invalid email only"
    (let [result (user/validate-user-input {:name "Alice" :email "bad"})]
      (is (false? (:valid? result)))
      (is (= 1 (count (:errors result))))))
  (testing "both invalid"
    (let [result (user/validate-user-input {:name "" :email "bad"})]
      (is (false? (:valid? result)))
      (is (= 2 (count (:errors result)))))))

(deftest create-user-test
  (testing "creates user with all required fields"
    (let [u (user/create-user "Alice" "Alice@Example.COM")]
      (is (string? (:id u)))
      (is (= "Alice" (:name u)))
      (is (= "alice@example.com" (:email u)) "email should be normalized")
      (is (pos-int? (:created-at u)))))
  (testing "each call produces a unique ID"
    (let [u1 (user/create-user "A" "a@b.com")
          u2 (user/create-user "A" "a@b.com")]
      (is (not= (:id u1) (:id u2))))))

;; ============================================================
;; Property-based tests
;; ============================================================

(deftest validate-email-always-returns-valid-schema-test
  (testing "validate-email always returns a valid ValidationResult"
    (let [result (tc/quick-check
                   200
                   (prop/for-all [s gen/string-alphanumeric]
                     (m/validate common/ValidationResult
                                (user/validate-email s))))]
      (is (:pass? result)
          (str "Property failed: " (:shrunk result))))))

(deftest validate-name-always-returns-valid-schema-test
  (testing "validate-name always returns a valid ValidationResult"
    (let [result (tc/quick-check
                   200
                   (prop/for-all [s gen/string]
                     (m/validate common/ValidationResult
                                (user/validate-name s))))]
      (is (:pass? result)
          (str "Property failed: " (:shrunk result))))))

(deftest create-user-always-returns-valid-user-test
  (testing "create-user output always matches User schema"
    (let [result (tc/quick-check
                   100
                   (prop/for-all [name (gen/not-empty gen/string-alphanumeric)
                                  email (gen/fmap (fn [[local domain tld]]
                                                    (str local "@" domain "." tld))
                                                  (gen/tuple
                                                    (gen/not-empty gen/string-alphanumeric)
                                                    (gen/not-empty gen/string-alphanumeric)
                                                    (gen/not-empty gen/string-alphanumeric)))]
                     (m/validate schemas/User
                                (user/create-user name email))))]
      (is (:pass? result)
          (str "Property failed: " (:shrunk result))))))
